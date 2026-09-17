package org.quicklauncher.host.platform.apps

import android.annotation.TargetApi
import android.content.BroadcastReceiver
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.LauncherApps
import android.content.pm.LauncherUserInfo
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.drawable.Drawable
import android.os.Handler
import android.os.Looper
import android.os.Process
import android.os.UserHandle
import android.os.UserManager
import java.io.ByteArrayOutputStream
import java.util.concurrent.atomic.AtomicBoolean
import org.quicklauncher.contracts.domain.ActivityName
import org.quicklauncher.contracts.domain.AppActivityIdentity
import org.quicklauncher.contracts.domain.PackageName
import org.quicklauncher.contracts.domain.ProfileSerial

internal class FrameworkLauncherAppsBackend(
    context: Context,
) : LauncherAppsBackend {
    private val applicationContext = context.applicationContext
    private val launcherApps = applicationContext.getSystemService(LauncherApps::class.java)
    private val userManager = applicationContext.getSystemService(UserManager::class.java)
    private val mainHandler = Handler(Looper.getMainLooper())
    private val started = AtomicBoolean(false)
    private val closed = AtomicBoolean(false)
    private var invalidated: (() -> Unit)? = null
    private var receiverRegistered = false

    private val launcherCallback = object : LauncherApps.Callback() {
        override fun onPackageAdded(packageName: String, user: UserHandle) = invalidate()

        override fun onPackageRemoved(packageName: String, user: UserHandle) = invalidate()

        override fun onPackageChanged(packageName: String, user: UserHandle) = invalidate()

        override fun onPackagesAvailable(
            packageNames: Array<out String>,
            user: UserHandle,
            replacing: Boolean,
        ) = invalidate()

        override fun onPackagesUnavailable(
            packageNames: Array<out String>,
            user: UserHandle,
            replacing: Boolean,
        ) = invalidate()

        override fun onPackagesSuspended(packageNames: Array<out String>, user: UserHandle) =
            invalidate()

        override fun onPackagesUnsuspended(packageNames: Array<out String>, user: UserHandle) =
            invalidate()

        override fun onPackageLoadingProgressChanged(
            packageName: String,
            user: UserHandle,
            progress: Float,
        ) = invalidate()

        @TargetApi(36)
        override fun onUserConfigChanged(launcherUserInfo: LauncherUserInfo) = invalidate()
    }

    private val profileReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            if (intent.action in PROFILE_ACTIONS) invalidate()
        }
    }

    override fun start(onInvalidated: () -> Unit) {
        check(!closed.get()) { "Launcher apps backend is closed" }
        check(started.compareAndSet(false, true)) { "Launcher apps backend is already started" }
        invalidated = onInvalidated
        launcherApps.registerCallback(launcherCallback, mainHandler)
        try {
            applicationContext.registerReceiver(
                profileReceiver,
                IntentFilter().apply {
                    PROFILE_ACTIONS.forEach { action -> addAction(action) }
                },
                Context.RECEIVER_EXPORTED,
            )
            receiverRegistered = true
        } catch (error: RuntimeException) {
            try {
                launcherApps.unregisterCallback(launcherCallback)
            } catch (_: RuntimeException) {
                // Preserve the registration failure while resetting local lifecycle state.
            } finally {
                invalidated = null
                started.set(false)
            }
            throw error
        }
    }

    override fun profiles(): List<AndroidProfileRecord> = launcherApps.profiles.mapNotNull(::profile)

    override fun activities(profileSerial: Long): List<AndroidActivityRecord> {
        val user = userManager.getUserForSerialNumber(profileSerial) ?: return emptyList()
        val density = applicationContext.resources.displayMetrics.densityDpi
        return launcherApps.getActivityList(null, user).map { activity ->
            val component = activity.componentName
            val packageName = PackageName.parse(component.packageName)
            val label = activity.label.toString().takeIf(String::isNotBlank) ?: packageName.value
            AndroidActivityRecord(
                identity = AppActivityIdentity(
                    profile = ProfileSerial.of(profileSerial),
                    packageName = packageName,
                    activityName = ActivityName.parse(component.className),
                ),
                label = label,
                iconBytes = encodeIcon(activity.getBadgedIcon(density)),
            )
        }
    }

    override fun profile(profileSerial: Long): AndroidProfileRecord? =
        userManager.getUserForSerialNumber(profileSerial)?.let(::profile)

    override fun isActivityEnabled(identity: AppActivityIdentity): Boolean {
        val user = userManager.getUserForSerialNumber(identity.profile.value) ?: return false
        return launcherApps.isActivityEnabled(identity.componentName(), user)
    }

    override fun startMainActivity(identity: AppActivityIdentity) {
        val user = userManager.getUserForSerialNumber(identity.profile.value)
            ?: throw AndroidLaunchFailure.ACTIVITY_MISSING
        launcherApps.startMainActivity(identity.componentName(), user, null, null)
    }

    override fun close() {
        if (!closed.compareAndSet(false, true)) return
        invalidated = null
        if (started.get()) {
            try {
                launcherApps.unregisterCallback(launcherCallback)
            } catch (_: RuntimeException) {
                // The receiver must still be unregistered if framework callback cleanup fails.
            } finally {
                if (receiverRegistered) {
                    try {
                        applicationContext.unregisterReceiver(profileReceiver)
                    } catch (_: RuntimeException) {
                        // Close is idempotent and best effort during host teardown.
                    } finally {
                        receiverRegistered = false
                    }
                }
            }
        }
    }

    private fun profile(user: UserHandle): AndroidProfileRecord? {
        val serial = userManager.getSerialNumberForUser(user)
        if (serial < 0L) return null
        val userType = resolveLauncherUserType(
            advertisedType = launcherApps.getLauncherUserInfo(user)?.userType,
            isCurrentUser = user == Process.myUserHandle(),
        ) ?: return null
        val isProfile = userType.startsWith(PROFILE_TYPE_PREFIX)
        return AndroidProfileRecord(
            serial = serial,
            userType = userType,
            quiet = isProfile && userManager.isQuietModeEnabled(user),
            unlocked = userManager.isUserUnlocked(user),
        )
    }

    private fun invalidate() {
        invalidated?.invoke()
    }

    private fun AppActivityIdentity.componentName(): ComponentName =
        ComponentName(packageName.value, activityName.value)

    private fun encodeIcon(drawable: Drawable): ByteArray {
        val width = drawable.intrinsicWidth.coerceIn(1, MAX_ICON_SIZE_PX)
        val height = drawable.intrinsicHeight.coerceIn(1, MAX_ICON_SIZE_PX)
        val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        val oldBounds = drawable.copyBounds()
        try {
            drawable.setBounds(0, 0, width, height)
            drawable.draw(Canvas(bitmap))
            return ByteArrayOutputStream().use { output ->
                check(bitmap.compress(Bitmap.CompressFormat.PNG, 100, output)) {
                    "Android launcher icon could not be encoded"
                }
                output.toByteArray()
            }
        } finally {
            drawable.bounds = oldBounds
            bitmap.recycle()
        }
    }

    private companion object {
        const val PROFILE_TYPE_PREFIX = "android.os.usertype.profile."
        const val MAX_ICON_SIZE_PX = 256

        val PROFILE_ACTIONS = setOf(
            Intent.ACTION_PROFILE_AVAILABLE,
            Intent.ACTION_PROFILE_UNAVAILABLE,
            Intent.ACTION_PROFILE_ACCESSIBLE,
            Intent.ACTION_PROFILE_INACCESSIBLE,
            Intent.ACTION_PROFILE_ADDED,
            Intent.ACTION_PROFILE_REMOVED,
            Intent.ACTION_MANAGED_PROFILE_AVAILABLE,
            Intent.ACTION_MANAGED_PROFILE_UNAVAILABLE,
            Intent.ACTION_MANAGED_PROFILE_UNLOCKED,
            Intent.ACTION_MANAGED_PROFILE_ADDED,
            Intent.ACTION_MANAGED_PROFILE_REMOVED,
        )
    }
}

internal fun resolveLauncherUserType(
    advertisedType: String?,
    isCurrentUser: Boolean,
): String? = advertisedType ?: if (isCurrentUser) FULL_SYSTEM_USER_TYPE else null

private const val FULL_SYSTEM_USER_TYPE = "android.os.usertype.full.SYSTEM"
