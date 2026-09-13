package org.quicklauncher.host.platform.apps

import android.content.ActivityNotFoundException
import android.content.Context
import android.os.UserManager
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.withContext
import org.quicklauncher.contracts.domain.AppActivityIdentity
import org.quicklauncher.contracts.domain.ProfileSerial
import org.quicklauncher.host.runtime.catalog.AppIcon
import org.quicklauncher.host.runtime.catalog.AppLaunchResult
import org.quicklauncher.host.runtime.catalog.AppPlatform
import org.quicklauncher.host.runtime.catalog.AppPlatformActivity
import org.quicklauncher.host.runtime.catalog.AppPlatformInvalidation
import org.quicklauncher.host.runtime.catalog.AppPlatformProfile
import org.quicklauncher.host.runtime.catalog.AppPlatformSnapshot
import org.quicklauncher.host.runtime.catalog.AppProfileKind

/** Android launcher-app adapter. Android framework objects remain behind [AppPlatform]. */
class AndroidAppPlatform internal constructor(
    private val backend: LauncherAppsBackend,
    private val dispatcher: CoroutineDispatcher,
) : AppPlatform {
    private val closed = AtomicBoolean(false)
    private val mutableInvalidations = MutableSharedFlow<AppPlatformInvalidation>(
        replay = 0,
        extraBufferCapacity = 1,
        onBufferOverflow = BufferOverflow.DROP_OLDEST,
    )

    override val invalidations: Flow<AppPlatformInvalidation> = mutableInvalidations.asSharedFlow()

    constructor(
        context: Context,
        dispatcher: CoroutineDispatcher = Dispatchers.IO,
    ) : this(FrameworkLauncherAppsBackend(context.applicationContext), dispatcher)

    init {
        backend.start {
            if (!closed.get()) {
                mutableInvalidations.tryEmit(AppPlatformInvalidation.Changed)
            }
        }
    }

    override suspend fun snapshot(): AppPlatformSnapshot = withContext(dispatcher) {
        check(!closed.get()) { "Android app platform is closed" }
        val acceptedProfiles = backend.profiles()
            .mapNotNull { profile ->
                val kind = acceptedAppProfileKind(profile.userType) ?: return@mapNotNull null
                AppPlatformProfile(
                    serial = ProfileSerial.of(profile.serial),
                    kind = kind,
                    quiet = profile.quiet,
                    unlocked = profile.unlocked,
                )
            }
        val activities = acceptedProfiles.flatMap { profile ->
            if (profile.quiet || !profile.unlocked) {
                emptyList()
            } else {
                backend.activities(profile.serial.value).map { activity ->
                    AppPlatformActivity(
                        identity = activity.identity,
                        label = activity.label,
                        icon = AppIcon.of(activity.iconBytes()),
                    )
                }
            }
        }
        AppPlatformSnapshot(acceptedProfiles, activities)
    }

    override suspend fun launch(identity: AppActivityIdentity): AppLaunchResult =
        withContext(dispatcher) {
            if (closed.get()) return@withContext AppLaunchResult.Closed
            try {
                val profile = backend.profile(identity.profile.value)
                    ?: return@withContext AppLaunchResult.MissingProfile
                if (acceptedAppProfileKind(profile.userType) == null) {
                    return@withContext AppLaunchResult.SecurityDenied
                }
                if (profile.quiet || !profile.unlocked) {
                    return@withContext AppLaunchResult.ProfileLocked
                }
                if (!backend.isActivityEnabled(identity)) {
                    return@withContext AppLaunchResult.ActivityUnavailable
                }
                backend.startMainActivity(identity)
                AppLaunchResult.Launched
            } catch (failure: AndroidLaunchFailure) {
                failure.result
            } catch (_: ActivityNotFoundException) {
                AppLaunchResult.ActivityUnavailable
            } catch (_: SecurityException) {
                AppLaunchResult.SecurityDenied
            } catch (_: IllegalStateException) {
                AppLaunchResult.ProfileLocked
            } catch (_: RuntimeException) {
                AppLaunchResult.Failed(LAUNCH_FAILED)
            }
        }

    override fun close() {
        if (!closed.compareAndSet(false, true)) return
        backend.close()
    }

    private companion object {
        const val LAUNCH_FAILED = "android_launch_failed"
    }
}

internal fun acceptedAppProfileKind(userType: String): AppProfileKind? = when {
    userType == UserManager.USER_TYPE_PROFILE_MANAGED -> AppProfileKind.WORK
    userType.startsWith(FULL_USER_TYPE_PREFIX) -> AppProfileKind.PERSONAL
    else -> null
}

private const val FULL_USER_TYPE_PREFIX = "android.os.usertype.full."

internal data class AndroidProfileRecord(
    val serial: Long,
    val userType: String,
    val quiet: Boolean,
    val unlocked: Boolean,
) {
    init {
        require(serial >= 0L) { "Android profile serial must not be negative" }
        require(userType.isNotBlank()) { "Android profile type must not be blank" }
    }
}

internal class AndroidActivityRecord(
    val identity: AppActivityIdentity,
    val label: String,
    iconBytes: ByteArray,
) {
    private val icon = iconBytes.copyOf()

    init {
        require(label.isNotBlank()) { "Android activity label must not be blank" }
        require(icon.isNotEmpty()) { "Android activity icon bytes must not be empty" }
    }

    fun iconBytes(): ByteArray = icon.copyOf()
}

internal sealed class AndroidLaunchFailure(
    val result: AppLaunchResult,
) : RuntimeException() {
    data object SECURITY : AndroidLaunchFailure(AppLaunchResult.SecurityDenied)
    data object PROFILE_LOCKED : AndroidLaunchFailure(AppLaunchResult.ProfileLocked)
    data object ACTIVITY_MISSING : AndroidLaunchFailure(AppLaunchResult.ActivityUnavailable)
    data object OTHER : AndroidLaunchFailure(AppLaunchResult.Failed("android_launch_failed"))
}

internal interface LauncherAppsBackend : AutoCloseable {
    fun start(onInvalidated: () -> Unit)

    fun profiles(): List<AndroidProfileRecord>

    fun activities(profileSerial: Long): List<AndroidActivityRecord>

    fun profile(profileSerial: Long): AndroidProfileRecord?

    fun isActivityEnabled(identity: AppActivityIdentity): Boolean

    fun startMainActivity(identity: AppActivityIdentity)
}
