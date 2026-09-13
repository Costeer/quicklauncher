package org.quicklauncher.host.platform.notifications

import android.app.Instrumentation
import android.content.ComponentName
import android.content.IntentFilter
import android.os.Build
import android.os.ParcelFileDescriptor
import android.provider.Settings
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.io.FileInputStream
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.quicklauncher.host.runtime.notifications.NotificationListenerStatus
import org.quicklauncher.host.runtime.notifications.NotificationPlatformActionResult

@RunWith(AndroidJUnit4::class)
class AndroidNotificationPlatformInstrumentedTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context = instrumentation.targetContext
    private val listener = ComponentName(context, QuicklauncherNotificationListenerService::class.java)
    private var originalListenersSetting: String? = null

    @Before
    fun revokeListenerAccess() {
        assertTrue("Notification listener behavior requires API 35 or newer", Build.VERSION.SDK_INT >= 35)
        originalListenersSetting = shell("settings get secure enabled_notification_listeners")
            .trim()
            .takeUnless { it == "null" }
        shell("cmd notification disallow_listener ${listener.flattenToString()}")
    }

    @After
    fun restoreListenerAccess() {
        shell("cmd notification disallow_listener ${listener.flattenToString()}")
        val original = originalListenersSetting
        if (original == null) {
            shell("settings delete secure enabled_notification_listeners")
        } else {
            shell("settings put secure enabled_notification_listeners ${original.shellQuoted()}")
        }
    }

    @Test
    fun explicitOnboardingAndRestrictedSettingsRecoveryOpenFrameworkRoutesOnlyOnRequest() = runBlocking {
        val detailMonitor = Instrumentation.ActivityMonitor(
            IntentFilter(Settings.ACTION_NOTIFICATION_LISTENER_DETAIL_SETTINGS),
            null,
            true,
        )
        instrumentation.addMonitor(detailMonitor)
        val fallbackMonitor = Instrumentation.ActivityMonitor(
            IntentFilter(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS),
            null,
            true,
        )
        instrumentation.addMonitor(fallbackMonitor)
        val detailsFilter = IntentFilter(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
            addDataScheme("package")
        }
        val detailsMonitor = Instrumentation.ActivityMonitor(
            detailsFilter,
            null,
            true,
        )
        instrumentation.addMonitor(detailsMonitor)
        val platform = AndroidNotificationPlatform(context, Dispatchers.IO)
        try {
            assertEquals(NotificationListenerStatus.NO_ACCESS, platform.status())
            assertEquals(0, detailMonitor.hits + fallbackMonitor.hits)
            assertEquals(NotificationPlatformActionResult.OPENED, platform.requestAccess())
            assertEquals(1, detailMonitor.hits + fallbackMonitor.hits)
            assertEquals(NotificationListenerStatus.NO_ACCESS, platform.status())

            assertEquals(
                NotificationPlatformActionResult.OPENED,
                platform.openRestrictedSettingsRecovery(),
            )
            assertEquals(1, detailsMonitor.hits)
        } finally {
            platform.close()
            instrumentation.removeMonitor(detailMonitor)
            instrumentation.removeMonitor(fallbackMonitor)
            instrumentation.removeMonitor(detailsMonitor)
        }
    }

    @Test
    fun denialRevocationAndListenerDisconnectNeverExposeStaleCounts() = runBlocking {
        val platform = AndroidNotificationPlatform(context, Dispatchers.IO)
        try {
            assertEquals(NotificationListenerStatus.NO_ACCESS, platform.status())
            assertTrue(platform.counts().counts.isEmpty())

            shell("cmd notification allow_listener ${listener.flattenToString()}")
            awaitStatus(platform) { it != NotificationListenerStatus.NO_ACCESS }

            shell("cmd notification disallow_listener ${listener.flattenToString()}")
            awaitStatus(platform) { it == NotificationListenerStatus.NO_ACCESS }
            assertTrue(platform.counts().counts.isEmpty())
        } finally {
            platform.close()
        }
    }

    private suspend fun awaitStatus(
        platform: AndroidNotificationPlatform,
        predicate: (NotificationListenerStatus) -> Boolean,
    ): NotificationListenerStatus {
        repeat(50) {
            val value = platform.status()
            if (predicate(value)) return value
            kotlinx.coroutines.delay(100)
        }
        return platform.status().also { assertTrue("Timed out waiting for listener state, got $it", predicate(it)) }
    }

    private fun shell(command: String): String =
        instrumentation.uiAutomation.executeShellCommand(command).use { descriptor -> descriptor.readText() }

    private fun ParcelFileDescriptor.readText(): String =
        FileInputStream(fileDescriptor).bufferedReader().use { it.readText() }

    private fun String.shellQuoted(): String = "'${replace("'", "'\\''")}'"
}
