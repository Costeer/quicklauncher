package org.quicklauncher.host.platform.profile

import android.content.BroadcastReceiver
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.IntentSender
import android.content.pm.LauncherApps
import android.content.pm.LauncherUserInfo
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.drawable.Drawable
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.Process
import android.os.UserHandle
import android.os.UserManager
import java.io.ByteArrayOutputStream
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.withContext
import org.quicklauncher.contracts.domain.ActivityName
import org.quicklauncher.contracts.domain.AppActivityIdentity
import org.quicklauncher.contracts.domain.PackageName
import org.quicklauncher.contracts.domain.ProfileSerial
import org.quicklauncher.host.runtime.catalog.AppIcon
import org.quicklauncher.host.runtime.profile.PrivateItemsResult
import org.quicklauncher.host.runtime.profile.PrivateLaunchResult
import org.quicklauncher.host.runtime.profile.PrivateProfileItem
import org.quicklauncher.host.runtime.profile.PrivateSpaceEntryPoint
import org.quicklauncher.host.runtime.profile.PrivateSpaceSettingsResult
import org.quicklauncher.host.runtime.profile.ProfileAvailability
import org.quicklauncher.host.runtime.profile.ProfileInvalidation
import org.quicklauncher.host.runtime.profile.ProfileKind
import org.quicklauncher.host.runtime.profile.ProfilePlatform
import org.quicklauncher.host.runtime.profile.ProfileRecord
import org.quicklauncher.host.runtime.profile.WorkModeRequestResult

/** Android profile adapter. UserHandle and launcher metadata stay in this module. */
class AndroidProfilePlatform internal constructor(
    private val backend: ProfileBackend,
    private val dispatcher: CoroutineDispatcher,
) : ProfilePlatform {
    private val closed = AtomicBoolean(false)

    override val invalidations: Flow<ProfileInvalidation> = backend.invalidations

    constructor(
        context: Context,
        dispatcher: CoroutineDispatcher = Dispatchers.IO,
    ) : this(FrameworkProfileBackend(context.applicationContext), dispatcher)

    override suspend fun profiles(): List<ProfileRecord> = withContext(dispatcher) {
        check(!closed.get()) { "Android profile platform is closed" }
        backend.profiles().mapNotNull(::classify)
    }

    override suspend fun privateItems(profile: ProfileSerial): PrivateItemsResult =
        withContext(dispatcher) {
            if (closed.get()) return@withContext PrivateItemsResult.Unavailable
            val current = backend.profile(profile.value) ?: return@withContext PrivateItemsResult.MissingProfile
            if (current.userType != UserManager.USER_TYPE_PROFILE_PRIVATE) {
                return@withContext PrivateItemsResult.SecurityDenied
            }
            if (current.quiet || !current.unlocked) return@withContext PrivateItemsResult.Locked
            try {
                PrivateItemsResult.Available(
                    backend.privateActivities(profile.value).map { activity ->
                        PrivateProfileItem(
                            activity.identity,
                            activity.label,
                            AppIcon.of(activity.iconBytes()),
                        )
                    },
                )
            } catch (_: SecurityException) {
                PrivateItemsResult.SecurityDenied
            } catch (_: IllegalStateException) {
                PrivateItemsResult.Locked
            } catch (_: RuntimeException) {
                PrivateItemsResult.Unavailable
            }
        }

    override suspend fun setQuietMode(
        profile: ProfileSerial,
        quiet: Boolean,
    ): WorkModeRequestResult = withContext(dispatcher) {
        if (closed.get()) return@withContext WorkModeRequestResult.REJECTED
        val current = backend.profile(profile.value)
            ?: return@withContext WorkModeRequestResult.MISSING_PROFILE
        if (
            current.userType != UserManager.USER_TYPE_PROFILE_MANAGED &&
            current.userType != UserManager.USER_TYPE_PROFILE_PRIVATE
        ) {
            return@withContext WorkModeRequestResult.WRONG_PROFILE_KIND
        }
        try {
            if (backend.requestQuietMode(profile.value, quiet)) {
                WorkModeRequestResult.REQUESTED
            } else if (
                current.userType == UserManager.USER_TYPE_PROFILE_PRIVATE &&
                !quiet &&
                current.quiet
            ) {
                // A false return can mean Android started credential confirmation. Availability
                // callbacks and a state refresh determine the eventual result.
                WorkModeRequestResult.REQUESTED
            } else {
                WorkModeRequestResult.REJECTED
            }
        } catch (_: SecurityException) {
            WorkModeRequestResult.DENIED
        } catch (_: RuntimeException) {
            WorkModeRequestResult.REJECTED
        }
    }

    override suspend fun launchPrivate(identity: AppActivityIdentity): PrivateLaunchResult =
        withContext(dispatcher) {
            if (closed.get()) return@withContext PrivateLaunchResult.CLOSED
            val current = backend.profile(identity.profile.value)
                ?: return@withContext PrivateLaunchResult.MISSING_PROFILE
            if (current.userType != UserManager.USER_TYPE_PROFILE_PRIVATE) {
                return@withContext PrivateLaunchResult.WRONG_PROFILE_KIND
            }
            if (current.quiet || !current.unlocked) return@withContext PrivateLaunchResult.LOCKED
            val available = try {
                backend.privateActivities(identity.profile.value).any { it.identity == identity }
            } catch (_: SecurityException) {
                return@withContext PrivateLaunchResult.DENIED
            } catch (_: RuntimeException) {
                return@withContext PrivateLaunchResult.REJECTED
            }
            if (!available) return@withContext PrivateLaunchResult.ACTIVITY_UNAVAILABLE
            try {
                if (backend.launchPrivate(identity)) {
                    PrivateLaunchResult.LAUNCHED
                } else {
                    PrivateLaunchResult.ACTIVITY_UNAVAILABLE
                }
            } catch (_: SecurityException) {
                PrivateLaunchResult.DENIED
            } catch (_: RuntimeException) {
                PrivateLaunchResult.REJECTED
            }
        }

    override suspend fun openPrivateSpaceSettings(): PrivateSpaceSettingsResult =
        withContext(dispatcher) {
            if (closed.get()) return@withContext PrivateSpaceSettingsResult.Closed
            try {
                when (backend.openPrivateSpaceSettings()) {
                    PlatformPrivateSpaceSettingsResult.LAUNCHED ->
                        PrivateSpaceSettingsResult.Launched
                    PlatformPrivateSpaceSettingsResult.UNAVAILABLE ->
                        PrivateSpaceSettingsResult.Unavailable
                    PlatformPrivateSpaceSettingsResult.REJECTED ->
                        PrivateSpaceSettingsResult.Rejected
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: SecurityException) {
                PrivateSpaceSettingsResult.Denied
            } catch (_: RuntimeException) {
                PrivateSpaceSettingsResult.Rejected
            }
        }

    override fun close() {
        if (!closed.compareAndSet(false, true)) return
        backend.close()
    }

    private fun classify(profile: PlatformProfileRecord): ProfileRecord? {
        val kind = when (profile.userType) {
            FULL_SYSTEM_USER_TYPE -> ProfileKind.PERSONAL
            UserManager.USER_TYPE_PROFILE_MANAGED -> ProfileKind.WORK
            UserManager.USER_TYPE_PROFILE_PRIVATE -> ProfileKind.PRIVATE
            else -> return null
        }
        val availability = when {
            kind == ProfileKind.PRIVATE && (profile.quiet || !profile.unlocked) ->
                ProfileAvailability.LOCKED
            kind == ProfileKind.WORK && profile.quiet -> ProfileAvailability.QUIET
            !profile.unlocked -> ProfileAvailability.LOCKED
            else -> ProfileAvailability.AVAILABLE
        }
        return ProfileRecord(
            ProfileSerial.of(profile.serial),
            kind,
            availability,
            profile.privateSpaceEntryPoint,
        )
    }
}

internal data class PlatformProfileRecord(
    val serial: Long,
    val userType: String,
    val quiet: Boolean,
    val unlocked: Boolean,
    val privateSpaceEntryPoint: PrivateSpaceEntryPoint = if (
        userType == UserManager.USER_TYPE_PROFILE_PRIVATE
    ) {
        PrivateSpaceEntryPoint.SYSTEM_DEFAULT
    } else {
        PrivateSpaceEntryPoint.NOT_PRIVATE
    },
)

internal class PlatformPrivateActivity(
    val identity: AppActivityIdentity,
    val label: String,
    iconBytes: ByteArray,
) {
    private val icon = iconBytes.copyOf()

    init {
        require(label.isNotBlank())
        require(icon.isNotEmpty())
    }

    fun iconBytes(): ByteArray = icon.copyOf()
}

internal interface ProfileBackend : AutoCloseable {
    val invalidations: Flow<ProfileInvalidation>
    fun profiles(): List<PlatformProfileRecord>
    fun profile(serial: Long): PlatformProfileRecord?
    fun privateActivities(serial: Long): List<PlatformPrivateActivity>
    fun requestQuietMode(serial: Long, quiet: Boolean): Boolean
    fun launchPrivate(identity: AppActivityIdentity): Boolean
    fun openPrivateSpaceSettings(): PlatformPrivateSpaceSettingsResult =
        PlatformPrivateSpaceSettingsResult.UNAVAILABLE
}

internal enum class PlatformPrivateSpaceSettingsResult { LAUNCHED, UNAVAILABLE, REJECTED }

private class FrameworkProfileBackend(context: Context) : ProfileBackend {
    private val applicationContext = context.applicationContext
    private val launcherApps = applicationContext.getSystemService(LauncherApps::class.java)
    private val userManager = applicationContext.getSystemService(UserManager::class.java)
    private val handler = Handler(Looper.getMainLooper())
    private val closed = AtomicBoolean(false)
    private val mutableInvalidations = MutableSharedFlow<ProfileInvalidation>(
        extraBufferCapacity = 1,
        onBufferOverflow = BufferOverflow.DROP_OLDEST,
    )
    override val invalidations: Flow<ProfileInvalidation> = mutableInvalidations.asSharedFlow()

    private val callback = object : LauncherApps.Callback() {
        override fun onPackageAdded(packageName: String, user: UserHandle) = changed()
        override fun onPackageRemoved(packageName: String, user: UserHandle) = changed()
        override fun onPackageChanged(packageName: String, user: UserHandle) = changed()
        override fun onPackagesAvailable(
            packageNames: Array<out String>,
            user: UserHandle,
            replacing: Boolean,
        ) = changed()
        override fun onPackagesUnavailable(
            packageNames: Array<out String>,
            user: UserHandle,
            replacing: Boolean,
        ) = changed()
    }

    private val receiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            val user = intent.getParcelableExtra(Intent.EXTRA_USER, UserHandle::class.java)
            val serial = user?.let(userManager::getSerialNumberForUser)?.takeIf { it >= 0L }
            if (serial == null) {
                changed()
            } else {
                mutableInvalidations.tryEmit(
                    ProfileInvalidation.AvailabilityChanged(ProfileSerial.of(serial)),
                )
            }
        }
    }

    init {
        launcherApps.registerCallback(callback, handler)
        try {
            applicationContext.registerReceiver(
                receiver,
                IntentFilter().apply {
                    addAction(Intent.ACTION_PROFILE_AVAILABLE)
                    addAction(Intent.ACTION_PROFILE_UNAVAILABLE)
                    addAction(Intent.ACTION_PROFILE_ADDED)
                    addAction(Intent.ACTION_PROFILE_REMOVED)
                },
                Context.RECEIVER_EXPORTED,
            )
        } catch (error: RuntimeException) {
            launcherApps.unregisterCallback(callback)
            throw error
        }
    }

    override fun profiles(): List<PlatformProfileRecord> = launcherApps.profiles.mapNotNull(::record)

    override fun profile(serial: Long): PlatformProfileRecord? =
        userManager.getUserForSerialNumber(serial)?.let(::record)

    override fun privateActivities(serial: Long): List<PlatformPrivateActivity> {
        val user = userManager.getUserForSerialNumber(serial) ?: return emptyList()
        val density = applicationContext.resources.displayMetrics.densityDpi
        return launcherApps.getActivityList(null, user).map { activity ->
            val component = activity.componentName
            PlatformPrivateActivity(
                AppActivityIdentity(
                    ProfileSerial.of(serial),
                    PackageName.parse(component.packageName),
                    ActivityName.parse(component.className),
                ),
                activity.label.toString().takeIf(String::isNotBlank) ?: component.packageName,
                encodeIcon(activity.getBadgedIcon(density)),
            )
        }
    }

    override fun requestQuietMode(serial: Long, quiet: Boolean): Boolean {
        val user = userManager.getUserForSerialNumber(serial) ?: return false
        return userManager.requestQuietModeEnabled(quiet, user)
    }

    override fun launchPrivate(identity: AppActivityIdentity): Boolean {
        val user = userManager.getUserForSerialNumber(identity.profile.value) ?: return false
        val activity = launcherApps.getActivityList(identity.packageName.value, user)
            .firstOrNull {
                it.componentName.packageName == identity.packageName.value &&
                    it.componentName.className == identity.activityName.value
            } ?: return false
        launcherApps.startMainActivity(activity.componentName, user, null, null)
        return true
    }

    override fun openPrivateSpaceSettings(): PlatformPrivateSpaceSettingsResult {
        if (Build.VERSION.SDK_INT < 36) {
            return PlatformPrivateSpaceSettingsResult.UNAVAILABLE
        }
        val sender = launcherApps.privateSpaceSettingsIntent
            ?: return PlatformPrivateSpaceSettingsResult.UNAVAILABLE
        return try {
            applicationContext.startIntentSender(
                sender,
                Intent().addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                0,
                0,
                0,
            )
            PlatformPrivateSpaceSettingsResult.LAUNCHED
        } catch (_: IntentSender.SendIntentException) {
            PlatformPrivateSpaceSettingsResult.REJECTED
        }
    }

    override fun close() {
        if (!closed.compareAndSet(false, true)) return
        try {
            launcherApps.unregisterCallback(callback)
        } finally {
            try {
                applicationContext.unregisterReceiver(receiver)
            } catch (_: RuntimeException) {
                // Idempotent close remains safe if Android already removed the receiver.
            }
        }
    }

    private fun record(user: UserHandle): PlatformProfileRecord? {
        val serial = userManager.getSerialNumberForUser(user)
        if (serial < 0L) return null
        val launcherUserInfo = launcherApps.getLauncherUserInfo(user)
        val type = launcherUserInfo?.userType
            ?: if (user == Process.myUserHandle()) FULL_SYSTEM_USER_TYPE else return null
        val isProfile = type == UserManager.USER_TYPE_PROFILE_MANAGED ||
            type == UserManager.USER_TYPE_PROFILE_PRIVATE
        return PlatformProfileRecord(
            serial,
            type,
            quiet = isProfile && userManager.isQuietModeEnabled(user),
            unlocked = userManager.isUserUnlocked(user),
            privateSpaceEntryPoint = privateSpaceEntryPoint(type, launcherUserInfo),
        )
    }

    private fun privateSpaceEntryPoint(
        userType: String,
        launcherUserInfo: LauncherUserInfo?,
    ): PrivateSpaceEntryPoint {
        if (userType != UserManager.USER_TYPE_PROFILE_PRIVATE) {
            return PrivateSpaceEntryPoint.NOT_PRIVATE
        }
        if (Build.VERSION.SDK_INT < 36 || launcherUserInfo == null) {
            return PrivateSpaceEntryPoint.SYSTEM_DEFAULT
        }
        return if (
            launcherUserInfo.userConfig.getBoolean(
                LauncherUserInfo.PRIVATE_SPACE_ENTRYPOINT_HIDDEN,
                false,
            )
        ) {
            PrivateSpaceEntryPoint.HIDDEN
        } else {
            PrivateSpaceEntryPoint.SYSTEM_DEFAULT
        }
    }

    private fun changed() {
        mutableInvalidations.tryEmit(ProfileInvalidation.Changed)
    }

    private fun encodeIcon(drawable: Drawable): ByteArray {
        val width = drawable.intrinsicWidth.coerceIn(1, MAX_ICON_SIZE_PX)
        val height = drawable.intrinsicHeight.coerceIn(1, MAX_ICON_SIZE_PX)
        val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        val oldBounds = drawable.copyBounds()
        try {
            drawable.setBounds(0, 0, width, height)
            drawable.draw(Canvas(bitmap))
            return ByteArrayOutputStream().use { output ->
                check(bitmap.compress(Bitmap.CompressFormat.PNG, 100, output))
                output.toByteArray()
            }
        } finally {
            drawable.bounds = oldBounds
            bitmap.recycle()
        }
    }

    private companion object {
        const val MAX_ICON_SIZE_PX = 256
    }
}

private const val FULL_SYSTEM_USER_TYPE = "android.os.usertype.full.SYSTEM"
