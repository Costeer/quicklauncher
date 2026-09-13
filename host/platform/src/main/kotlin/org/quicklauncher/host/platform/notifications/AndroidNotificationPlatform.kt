package org.quicklauncher.host.platform.notifications

import android.app.Notification
import android.app.NotificationManager
import android.content.BroadcastReceiver
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.LauncherApps
import android.net.Uri
import android.os.Process
import android.os.UserHandle
import android.os.UserManager
import android.provider.Settings
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.withContext
import org.quicklauncher.contracts.domain.PackageName
import org.quicklauncher.contracts.domain.ProfilePackageIdentity
import org.quicklauncher.contracts.domain.ProfileSerial
import org.quicklauncher.host.runtime.notifications.NotificationCount
import org.quicklauncher.host.runtime.notifications.NotificationCountSnapshot
import org.quicklauncher.host.runtime.notifications.NotificationListenerStatus
import org.quicklauncher.host.runtime.notifications.NotificationPlatform
import org.quicklauncher.host.runtime.notifications.NotificationPlatformActionResult

class AndroidNotificationPlatform internal constructor(
    private val backend: NotificationBackend,
    private val dispatcher: CoroutineDispatcher,
) : NotificationPlatform {
    private val closed = AtomicBoolean(false)
    override val invalidations: Flow<Unit> = backend.invalidations

    constructor(
        context: Context,
        dispatcher: CoroutineDispatcher = Dispatchers.IO,
    ) : this(FrameworkNotificationBackend(context.applicationContext), dispatcher)

    override suspend fun status(): NotificationListenerStatus = withContext(dispatcher) {
        if (closed.get()) NotificationListenerStatus.UNAVAILABLE else backend.status()
    }

    override suspend fun counts(): NotificationCountSnapshot = withContext(dispatcher) {
        if (closed.get()) return@withContext NotificationCountSnapshot.Empty
        val values = backend.counts().mapNotNull { value ->
            if (!value.available) return@mapNotNull null
            if (value.userType != FULL_SYSTEM_USER_TYPE && value.userType != MANAGED_USER_TYPE) {
                return@mapNotNull null
            }
            NotificationCount(
                ProfilePackageIdentity(
                    ProfileSerial.of(value.profileSerial),
                    PackageName.parse(value.packageName),
                ),
                value.count,
            )
        }
        NotificationCountSnapshot(values)
    }

    override suspend fun requestAccess(): NotificationPlatformActionResult = withContext(dispatcher) {
        actionResult { backend.openAccessSettings() }
    }

    override suspend fun openRestrictedSettingsRecovery(): NotificationPlatformActionResult =
        withContext(dispatcher) { actionResult { backend.openRestrictedSettingsRecovery() } }

    override fun close() {
        if (!closed.compareAndSet(false, true)) return
        backend.close()
    }

    private fun actionResult(action: () -> Boolean): NotificationPlatformActionResult =
        if (closed.get()) {
            NotificationPlatformActionResult.UNAVAILABLE
        } else {
            try {
                if (action()) {
                    NotificationPlatformActionResult.OPENED
                } else {
                    NotificationPlatformActionResult.UNAVAILABLE
                }
            } catch (_: SecurityException) {
                NotificationPlatformActionResult.DENIED
            } catch (_: RuntimeException) {
                NotificationPlatformActionResult.UNAVAILABLE
            }
        }
}

internal data class PlatformNotificationCount(
    val profileSerial: Long,
    val userType: String,
    val available: Boolean,
    val packageName: String,
    val count: Int,
)

internal interface NotificationBackend : AutoCloseable {
    val invalidations: Flow<Unit>
    fun status(): NotificationListenerStatus
    fun counts(): List<PlatformNotificationCount>
    fun openAccessSettings(): Boolean
    fun openRestrictedSettingsRecovery(): Boolean
}

private class FrameworkNotificationBackend(context: Context) : NotificationBackend {
    private val applicationContext = context.applicationContext
    private val notificationManager = applicationContext.getSystemService(NotificationManager::class.java)
    private val component = ComponentName(
        applicationContext,
        QuicklauncherNotificationListenerService::class.java,
    )
    private val closed = AtomicBoolean(false)
    private val mutableInvalidations = MutableSharedFlow<Unit>(
        extraBufferCapacity = 1,
        onBufferOverflow = BufferOverflow.DROP_OLDEST,
    )
    private val listener: () -> Unit = { mutableInvalidations.tryEmit(Unit) }
    override val invalidations: Flow<Unit> = mutableInvalidations.asSharedFlow()

    private val profileReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            mutableInvalidations.tryEmit(Unit)
        }
    }

    init {
        NotificationListenerBridge.add(listener)
        try {
            applicationContext.registerReceiver(
                profileReceiver,
                IntentFilter().apply {
                    addAction(Intent.ACTION_PROFILE_AVAILABLE)
                    addAction(Intent.ACTION_PROFILE_UNAVAILABLE)
                    addAction(Intent.ACTION_PROFILE_ADDED)
                    addAction(Intent.ACTION_PROFILE_REMOVED)
                },
                Context.RECEIVER_EXPORTED,
            )
        } catch (error: RuntimeException) {
            NotificationListenerBridge.remove(listener)
            throw error
        }
    }

    override fun status(): NotificationListenerStatus {
        if (!notificationManager.isNotificationListenerAccessGranted(component)) {
            return NotificationListenerStatus.NO_ACCESS
        }
        return if (NotificationListenerBridge.connected()) {
            NotificationListenerStatus.CONNECTED
        } else {
            NotificationListenerStatus.DISCONNECTED
        }
    }

    override fun counts(): List<PlatformNotificationCount> {
        if (!notificationManager.isNotificationListenerAccessGranted(component)) return emptyList()
        return NotificationListenerBridge.counts() ?: emptyList()
    }

    override fun openAccessSettings(): Boolean {
        val detail = Intent(Settings.ACTION_NOTIFICATION_LISTENER_DETAIL_SETTINGS).putExtra(
            Settings.EXTRA_NOTIFICATION_LISTENER_COMPONENT_NAME,
            component.flattenToString(),
        )
        return open(detail) || open(Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS))
    }

    override fun openRestrictedSettingsRecovery(): Boolean = open(
        Intent(
            Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
            Uri.fromParts("package", applicationContext.packageName, null),
        ),
    )

    override fun close() {
        if (!closed.compareAndSet(false, true)) return
        NotificationListenerBridge.remove(listener)
        try {
            applicationContext.unregisterReceiver(profileReceiver)
        } catch (_: RuntimeException) {
            // Android may already have removed the receiver during process teardown.
        }
    }

    private fun open(intent: Intent): Boolean {
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        if (applicationContext.packageManager.resolveActivity(intent, 0) == null) return false
        applicationContext.startActivity(intent)
        return true
    }
}

/** Framework entry point. Raw notification objects are reduced before crossing this class. */
class QuicklauncherNotificationListenerService : NotificationListenerService() {
    override fun onListenerConnected() {
        super.onListenerConnected()
        NotificationListenerBridge.connect(this)
    }

    override fun onListenerDisconnected() {
        NotificationListenerBridge.disconnect(this)
        requestRebind(ComponentName(this, QuicklauncherNotificationListenerService::class.java))
        super.onListenerDisconnected()
    }

    override fun onNotificationPosted(sbn: StatusBarNotification?) {
        NotificationListenerBridge.invalidate()
    }

    override fun onNotificationRemoved(sbn: StatusBarNotification?) {
        NotificationListenerBridge.invalidate()
    }

    override fun onDestroy() {
        NotificationListenerBridge.disconnect(this)
        super.onDestroy()
    }

    internal fun sanitizedCounts(): List<PlatformNotificationCount> {
        val launcherApps = getSystemService(LauncherApps::class.java)
        val userManager = getSystemService(UserManager::class.java)
        val grouped = LinkedHashMap<SanitizedNotificationKey, Int>()
        activeNotifications.orEmpty().forEach { notification ->
            val user = notification.user
            val profile = classify(user, launcherApps, userManager) ?: return@forEach
            if (!profile.available) return@forEach
            if (notification.notification.flags and Notification.FLAG_GROUP_SUMMARY != 0) return@forEach
            val packageName = notification.packageName
            val key = SanitizedNotificationKey(profile.serial, profile.userType, packageName)
            grouped[key] = (grouped[key] ?: 0) + 1
        }
        return grouped.map { (key, count) ->
            PlatformNotificationCount(
                key.serial,
                key.userType,
                available = true,
                key.packageName,
                count,
            )
        }
    }

    private fun classify(
        user: UserHandle,
        launcherApps: LauncherApps,
        userManager: UserManager,
    ): SanitizedProfile? {
        val serial = userManager.getSerialNumberForUser(user)
        if (serial < 0L) return null
        val userType = launcherApps.getLauncherUserInfo(user)?.userType
            ?: if (user == Process.myUserHandle()) FULL_SYSTEM_USER_TYPE else return null
        if (userType != FULL_SYSTEM_USER_TYPE && userType != MANAGED_USER_TYPE) return null
        val quiet = userType == MANAGED_USER_TYPE && userManager.isQuietModeEnabled(user)
        return SanitizedProfile(serial, userType, !quiet && userManager.isUserUnlocked(user))
    }
}

private object NotificationListenerBridge {
    private val listeners = LinkedHashSet<() -> Unit>()
    @Volatile private var service: QuicklauncherNotificationListenerService? = null

    @Synchronized fun add(listener: () -> Unit) {
        listeners += listener
    }

    @Synchronized fun remove(listener: () -> Unit) {
        listeners -= listener
    }

    @Synchronized fun connect(value: QuicklauncherNotificationListenerService) {
        service = value
        notifyListeners()
    }

    @Synchronized fun disconnect(value: QuicklauncherNotificationListenerService) {
        if (service === value) service = null
        notifyListeners()
    }

    @Synchronized fun connected(): Boolean = service != null

    /**
     * Serializes reads with disconnect and treats framework-side revocation as detachment.
     *
     * Newer Android releases may invalidate the listener token before delivering
     * [NotificationListenerService.onListenerDisconnected]. In that interval even a previously
     * connected service throws [SecurityException] from `activeNotifications`.
     */
    @Synchronized fun counts(): List<PlatformNotificationCount>? {
        val connectedService = service ?: return null
        return try {
            connectedService.sanitizedCounts()
        } catch (_: SecurityException) {
            if (service === connectedService) service = null
            notifyListeners()
            null
        }
    }

    @Synchronized fun invalidate() {
        notifyListeners()
    }

    private fun notifyListeners() {
        listeners.toList().forEach { it.invoke() }
    }
}

private data class SanitizedProfile(val serial: Long, val userType: String, val available: Boolean)
private data class SanitizedNotificationKey(
    val serial: Long,
    val userType: String,
    val packageName: String,
)

private const val FULL_SYSTEM_USER_TYPE = "android.os.usertype.full.SYSTEM"
private const val MANAGED_USER_TYPE = "android.os.usertype.profile.MANAGED"
