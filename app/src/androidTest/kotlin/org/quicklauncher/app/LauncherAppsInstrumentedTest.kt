package org.quicklauncher.app

import android.os.Process
import android.os.SystemClock
import android.os.UserManager
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.By
import androidx.test.uiautomator.UiDevice
import androidx.test.uiautomator.Until
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.quicklauncher.host.platform.apps.AndroidAppPlatform
import org.quicklauncher.host.runtime.catalog.AppLaunchResult

@RunWith(AndroidJUnit4::class)
class LauncherAppsInstrumentedTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context = instrumentation.targetContext

    @Test
    fun productionAdapterEnumeratesCurrentProfileAndPerformsTypedLaunch() = runBlocking {
        val userManager = context.getSystemService(UserManager::class.java)
        val currentSerial = userManager.getSerialNumberForUser(Process.myUserHandle())
        assertTrue("The current Android profile must have a stable serial", currentSerial >= 0L)
        val platform = AndroidAppPlatform(context)

        try {
            val snapshot = platform.snapshot()
            assertTrue(
                "LauncherApps must expose the current profile through its stable serial",
                snapshot.profiles.any { it.serial.value == currentSerial },
            )
            val ownActivity = snapshot.activities.singleOrNull { activity ->
                activity.identity.profile.value == currentSerial &&
                    activity.identity.packageName.value == context.packageName &&
                    activity.identity.activityName.value == MainActivity::class.java.name
            }
            requireNotNull(ownActivity) {
                "The production catalog did not enumerate Quicklauncher's launchable activity"
            }

            HomeEntryRecorder.resetForTests()
            assertEquals(AppLaunchResult.Launched, platform.launch(ownActivity.identity))
            assertTrue(
                "The typed LauncherApps launch did not foreground its exact package",
                UiDevice.getInstance(instrumentation).wait(
                    Until.hasObject(By.pkg(context.packageName).depth(0)),
                    TIMEOUT,
                ),
            )
            instrumentation.waitForIdleSync()
            assertTrue(
                "The typed launch result was returned without dispatching the app-icon entry",
                waitForAppIconEntry(),
            )
        } finally {
            platform.close()
        }
    }

    private fun waitForAppIconEntry(): Boolean {
        val deadline = SystemClock.uptimeMillis() + TIMEOUT
        do {
            if (HomeEntryRecorder.snapshot().appIconEntries >= 1) return true
            SystemClock.sleep(50L)
        } while (SystemClock.uptimeMillis() < deadline)
        return false
    }

    private companion object {
        const val TIMEOUT = 10_000L
    }
}
