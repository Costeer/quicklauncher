package org.quicklauncher.host.platform.shortcuts

import android.annotation.TargetApi
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.LauncherApps
import android.content.pm.LauncherUserInfo
import android.content.pm.ShortcutInfo
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.drawable.Drawable
import android.os.Handler
import android.os.Looper
import android.os.UserHandle
import android.os.UserManager
import java.io.ByteArrayOutputStream
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.withContext
import org.quicklauncher.contracts.domain.PackageName
import org.quicklauncher.contracts.domain.ProfileSerial
import org.quicklauncher.contracts.domain.ShortcutId
import org.quicklauncher.host.runtime.shortcuts.DiscoveredShortcut
import org.quicklauncher.host.runtime.shortcuts.ShortcutIcon
import org.quicklauncher.host.runtime.shortcuts.ShortcutInvalidation
import org.quicklauncher.host.runtime.shortcuts.ShortcutLaunchResult
import org.quicklauncher.host.runtime.shortcuts.ShortcutOwner
import org.quicklauncher.host.runtime.shortcuts.ShortcutPinRequest
import org.quicklauncher.host.runtime.shortcuts.ShortcutPinResult
import org.quicklauncher.host.runtime.shortcuts.ShortcutPlatform
import org.quicklauncher.host.runtime.shortcuts.ShortcutQueryResult
import org.quicklauncher.host.runtime.shortcuts.ShortcutTargetIdentity
import org.quicklauncher.host.runtime.shortcuts.ShortcutUnavailableReason

/** Production LauncherApps adapter. No framework shortcut or profile object crosses this seam. */
class AndroidShortcutPlatform internal constructor(
    private val backend: AndroidShortcutBackend,
    private val dispatcher: CoroutineDispatcher,
) : ShortcutPlatform {
    private val closed = AtomicBoolean(false)
    private val mutableInvalidations = MutableSharedFlow<ShortcutInvalidation>(
        replay = 0,
        extraBufferCapacity = 1,
        onBufferOverflow = BufferOverflow.DROP_OLDEST,
    )

    override val invalidations: Flow<ShortcutInvalidation> = mutableInvalidations.asSharedFlow()

    constructor(
        context: Context,
        dispatcher: CoroutineDispatcher = Dispatchers.IO,
    ) : this(FrameworkAndroidShortcutBackend(context.applicationContext), dispatcher)

    init {
        backend.start {
            if (!closed.get()) mutableInvalidations.tryEmit(ShortcutInvalidation.Changed)
        }
    }

    override suspend fun query(owner: ShortcutOwner): ShortcutQueryResult = withContext(dispatcher) {
        check(!closed.get()) { "Android shortcut platform is closed" }
        when (val access = backend.access(owner.profile.value)) {
            AndroidShortcutProfileAccess.AVAILABLE -> queryAvailable(owner)
            AndroidShortcutProfileAccess.MISSING -> unavailable(ShortcutUnavailableReason.PROFILE_UNAVAILABLE)
            AndroidShortcutProfileAccess.LOCKED -> unavailable(ShortcutUnavailableReason.PROFILE_LOCKED)
            AndroidShortcutProfileAccess.PRIVATE -> unavailable(ShortcutUnavailableReason.PRIVATE_PROFILE)
            AndroidShortcutProfileAccess.DENIED -> unavailable(ShortcutUnavailableReason.SECURITY_DENIED)
        }
    }

    override suspend fun pin(request: ShortcutPinRequest): ShortcutPinResult = withContext(dispatcher) {
        if (closed.get()) return@withContext ShortcutPinResult.Unavailable(ShortcutUnavailableReason.PLATFORM_FAILURE)
        when (val access = backend.access(request.owner.profile.value)) {
            AndroidShortcutProfileAccess.AVAILABLE -> try {
                backend.pin(request.owner, request.shortcutIds)
                ShortcutPinResult.Applied
            } catch (_: SecurityException) {
                ShortcutPinResult.Unavailable(ShortcutUnavailableReason.SECURITY_DENIED)
            } catch (_: IllegalStateException) {
                ShortcutPinResult.Unavailable(ShortcutUnavailableReason.PROFILE_LOCKED)
            } catch (_: RuntimeException) {
                ShortcutPinResult.Unavailable(ShortcutUnavailableReason.PLATFORM_FAILURE)
            }
            AndroidShortcutProfileAccess.MISSING -> pinUnavailable(ShortcutUnavailableReason.PROFILE_UNAVAILABLE)
            AndroidShortcutProfileAccess.LOCKED -> pinUnavailable(ShortcutUnavailableReason.PROFILE_LOCKED)
            AndroidShortcutProfileAccess.PRIVATE -> pinUnavailable(ShortcutUnavailableReason.PRIVATE_PROFILE)
            AndroidShortcutProfileAccess.DENIED -> pinUnavailable(ShortcutUnavailableReason.SECURITY_DENIED)
        }
    }

    override suspend fun launch(target: ShortcutTargetIdentity): ShortcutLaunchResult = withContext(dispatcher) {
        if (closed.get()) return@withContext ShortcutLaunchResult.Closed
        when (val access = backend.access(target.profile.value)) {
            AndroidShortcutProfileAccess.AVAILABLE -> Unit
            AndroidShortcutProfileAccess.MISSING ->
                return@withContext launchUnavailable(ShortcutUnavailableReason.PROFILE_UNAVAILABLE)
            AndroidShortcutProfileAccess.LOCKED ->
                return@withContext launchUnavailable(ShortcutUnavailableReason.PROFILE_LOCKED)
            AndroidShortcutProfileAccess.PRIVATE ->
                return@withContext launchUnavailable(ShortcutUnavailableReason.PRIVATE_PROFILE)
            AndroidShortcutProfileAccess.DENIED ->
                return@withContext launchUnavailable(ShortcutUnavailableReason.SECURITY_DENIED)
        }
        try {
            val current = backend.shortcuts(target.owner()).singleOrNull { it.id == target.shortcutId }
                ?: return@withContext launchUnavailable(ShortcutUnavailableReason.MISSING)
            if (!current.enabled) {
                return@withContext launchUnavailable(ShortcutUnavailableReason.DISABLED)
            }
            backend.launch(target)
            ShortcutLaunchResult.Launched
        } catch (_: SecurityException) {
            launchUnavailable(ShortcutUnavailableReason.SECURITY_DENIED)
        } catch (_: IllegalStateException) {
            launchUnavailable(ShortcutUnavailableReason.PROFILE_LOCKED)
        } catch (_: RuntimeException) {
            launchUnavailable(ShortcutUnavailableReason.PLATFORM_FAILURE)
        }
    }

    override fun close() {
        if (!closed.compareAndSet(false, true)) return
        backend.close()
    }

    private fun queryAvailable(owner: ShortcutOwner): ShortcutQueryResult = try {
        ShortcutQueryResult.Available(
            backend.shortcuts(owner).map { shortcut ->
                DiscoveredShortcut(
                    target = ShortcutTargetIdentity(owner.profile, owner.packageName, shortcut.id),
                    label = shortcut.label,
                    icon = shortcut.iconBytes()?.let(ShortcutIcon::of),
                    dynamic = shortcut.dynamic,
                    pinned = shortcut.pinned,
                    enabled = shortcut.enabled,
                )
            },
        )
    } catch (_: SecurityException) {
        unavailable(ShortcutUnavailableReason.SECURITY_DENIED)
    } catch (_: IllegalStateException) {
        unavailable(ShortcutUnavailableReason.PROFILE_LOCKED)
    } catch (_: RuntimeException) {
        unavailable(ShortcutUnavailableReason.PLATFORM_FAILURE)
    }

    private fun ShortcutTargetIdentity.owner() = ShortcutOwner(profile, packageName)

    private fun unavailable(reason: ShortcutUnavailableReason) = ShortcutQueryResult.Unavailable(reason)

    private fun pinUnavailable(reason: ShortcutUnavailableReason) = ShortcutPinResult.Unavailable(reason)

    private fun launchUnavailable(reason: ShortcutUnavailableReason) = ShortcutLaunchResult.Unavailable(reason)
}

internal enum class AndroidShortcutProfileAccess {
    AVAILABLE,
    MISSING,
    LOCKED,
    PRIVATE,
    DENIED,
}

internal class AndroidShortcutRecord(
    val id: ShortcutId,
    val label: String,
    val dynamic: Boolean,
    val pinned: Boolean,
    val enabled: Boolean,
    iconBytes: ByteArray?,
) {
    private val icon = iconBytes?.copyOf()

    init {
        require(label.isNotBlank()) { "Android shortcut label must not be blank" }
        require(dynamic || pinned) { "Android shortcut must be dynamic or pinned" }
    }

    fun iconBytes(): ByteArray? = icon?.copyOf()
}

internal interface AndroidShortcutBackend : AutoCloseable {
    fun start(onInvalidated: () -> Unit)

    fun access(profileSerial: Long): AndroidShortcutProfileAccess

    fun shortcuts(owner: ShortcutOwner): List<AndroidShortcutRecord>

    fun pin(owner: ShortcutOwner, shortcutIds: Set<ShortcutId>)

    fun launch(target: ShortcutTargetIdentity)
}

private class FrameworkAndroidShortcutBackend(context: Context) : AndroidShortcutBackend {
    private val applicationContext = context.applicationContext
    private val launcherApps = applicationContext.getSystemService(LauncherApps::class.java)
    private val userManager = applicationContext.getSystemService(UserManager::class.java)
    private val handler = Handler(Looper.getMainLooper())
    private val started = AtomicBoolean(false)
    private val closed = AtomicBoolean(false)
    private var invalidated: (() -> Unit)? = null
    private var receiverRegistered = false

    private val callback = object : LauncherApps.Callback() {
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
        override fun onPackagesSuspended(packageNames: Array<out String>, user: UserHandle) = invalidate()
        override fun onPackagesUnsuspended(packageNames: Array<out String>, user: UserHandle) = invalidate()
        override fun onShortcutsChanged(
            packageName: String,
            shortcuts: MutableList<ShortcutInfo>,
            user: UserHandle,
        ) = invalidate()
        @TargetApi(36)
        override fun onUserConfigChanged(launcherUserInfo: LauncherUserInfo) = invalidate()
    }

    private val receiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) = invalidate()
    }

    override fun start(onInvalidated: () -> Unit) {
        check(!closed.get()) { "Shortcut backend is closed" }
        check(started.compareAndSet(false, true)) { "Shortcut backend is already started" }
        invalidated = onInvalidated
        launcherApps.registerCallback(callback, handler)
        try {
            applicationContext.registerReceiver(
                receiver,
                IntentFilter().apply { PROFILE_ACTIONS.forEach(::addAction) },
                Context.RECEIVER_EXPORTED,
            )
            receiverRegistered = true
        } catch (failure: RuntimeException) {
            try {
                launcherApps.unregisterCallback(callback)
            } catch (_: RuntimeException) {
                // Preserve the registration failure.
            }
            invalidated = null
            started.set(false)
            throw failure
        }
    }

    override fun access(profileSerial: Long): AndroidShortcutProfileAccess {
        val user = userManager.getUserForSerialNumber(profileSerial)
            ?: return AndroidShortcutProfileAccess.MISSING
        val type = try {
            launcherApps.getLauncherUserInfo(user)?.userType
                ?: return AndroidShortcutProfileAccess.DENIED
        } catch (_: SecurityException) {
            return AndroidShortcutProfileAccess.DENIED
        }
        return shortcutProfileAccess(
            userType = type,
            unlocked = userManager.isUserUnlocked(user),
            quiet = userManager.isQuietModeEnabled(user),
            hasHostPermission = launcherApps.hasShortcutHostPermission(),
        )
    }

    override fun shortcuts(owner: ShortcutOwner): List<AndroidShortcutRecord> {
        val user = requireUser(owner.profile)
        val query = LauncherApps.ShortcutQuery()
            .setPackage(owner.packageName.value)
            .setQueryFlags(
                LauncherApps.ShortcutQuery.FLAG_MATCH_DYNAMIC or
                    LauncherApps.ShortcutQuery.FLAG_MATCH_PINNED,
            )
        return launcherApps.getShortcuts(query, user).orEmpty().map { shortcut ->
            AndroidShortcutRecord(
                id = ShortcutId.parse(shortcut.id),
                label = shortcut.shortLabel?.toString()?.takeIf(String::isNotBlank) ?: shortcut.id,
                dynamic = shortcut.isDynamic,
                pinned = shortcut.isPinned,
                enabled = shortcut.isEnabled,
                iconBytes = launcherApps.getShortcutBadgedIconDrawable(shortcut, iconDensity())
                    ?.let(::encodeIcon),
            )
        }
    }

    override fun pin(owner: ShortcutOwner, shortcutIds: Set<ShortcutId>) {
        launcherApps.pinShortcuts(
            owner.packageName.value,
            shortcutIds.map(ShortcutId::value),
            requireUser(owner.profile),
        )
    }

    override fun launch(target: ShortcutTargetIdentity) {
        launcherApps.startShortcut(
            target.packageName.value,
            target.shortcutId.value,
            null,
            null,
            requireUser(target.profile),
        )
    }

    override fun close() {
        if (!closed.compareAndSet(false, true)) return
        invalidated = null
        if (!started.get()) return
        try {
            launcherApps.unregisterCallback(callback)
        } catch (_: RuntimeException) {
            // Receiver cleanup must still run.
        } finally {
            if (receiverRegistered) {
                try {
                    applicationContext.unregisterReceiver(receiver)
                } catch (_: RuntimeException) {
                    // Close is idempotent and best effort.
                } finally {
                    receiverRegistered = false
                }
            }
        }
    }

    private fun requireUser(profile: ProfileSerial): UserHandle =
        userManager.getUserForSerialNumber(profile.value)
            ?: throw IllegalStateException("Shortcut profile is unavailable")

    private fun invalidate() {
        invalidated?.invoke()
    }

    private fun iconDensity(): Int = applicationContext.resources.displayMetrics.densityDpi

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
        val PROFILE_ACTIONS = setOf(
            Intent.ACTION_PROFILE_AVAILABLE,
            Intent.ACTION_PROFILE_UNAVAILABLE,
            Intent.ACTION_PROFILE_ADDED,
            Intent.ACTION_PROFILE_REMOVED,
        )
    }
}

internal fun shortcutProfileAccess(
    userType: String,
    unlocked: Boolean,
    quiet: Boolean,
    hasHostPermission: Boolean,
): AndroidShortcutProfileAccess = when {
    userType == UserManager.USER_TYPE_PROFILE_PRIVATE -> AndroidShortcutProfileAccess.PRIVATE
    userType != UserManager.USER_TYPE_PROFILE_MANAGED &&
        !userType.startsWith(FULL_USER_TYPE_PREFIX) -> AndroidShortcutProfileAccess.DENIED
    !unlocked || (userType == UserManager.USER_TYPE_PROFILE_MANAGED && quiet) ->
        AndroidShortcutProfileAccess.LOCKED
    !hasHostPermission -> AndroidShortcutProfileAccess.DENIED
    else -> AndroidShortcutProfileAccess.AVAILABLE
}

private const val FULL_USER_TYPE_PREFIX = "android.os.usertype.full."
