package org.quicklauncher.app

import android.app.role.RoleManager
import android.os.ParcelFileDescriptor
import android.os.SystemClock
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.By
import androidx.test.uiautomator.UiDevice
import androidx.test.uiautomator.Until
import java.io.FileInputStream
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.quicklauncher.host.runtime.LauncherSurface

@RunWith(AndroidJUnit4::class)
class HomeReentryInstrumentedTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context = instrumentation.targetContext
    private val device = UiDevice.getInstance(instrumentation)
    private val roleManager = context.getSystemService(RoleManager::class.java)
    private val userId: Int by lazy {
        shell("am get-current-user").trim().toInt().also { require(it >= 0) }
    }

    @Test
    fun repeatedHomeKeepsTheSingleLauncherTaskInFrontAndRestoresPriorHolder() {
        val packageName = context.packageName
        require(PACKAGE_NAME.matches(packageName)) { "Application ID is not shell-safe" }
        val originalHolders = roleHolders()

        try {
            HomeEntryRecorder.resetForTests()
            shell("cmd role add-role-holder --user $userId $HOME_ROLE $packageName")
            assertTrue(
                "Quicklauncher did not become the Home-role holder",
                waitForRoleHeld(),
            )

            device.pressHome()
            assertCurrentPackage(packageName, "First Home press did not open Quicklauncher")
            val mapButton = device.wait(
                Until.findObject(
                    By.desc(context.getString(org.quicklauncher.host.runtime.R.string.map_overview)),
                ),
                TIMEOUT,
            )
            requireNotNull(mapButton) { "Safe layout did not expose its host-owned map entry" }
            val firstEntry = waitForRecorder { it.destinationId != null }
            mapButton.click()
            assertTrue(
                "The map overlay did not open before the repeated-Home assertion",
                device.wait(
                    Until.hasObject(
                        By.text(context.getString(org.quicklauncher.host.settings.R.string.map_title)),
                    ),
                    TIMEOUT,
                ),
            )
            device.pressHome()
            assertCurrentPackage(packageName, "Repeated Home created or selected another task")
            assertTrue(
                "Repeated Home did not close the transient overlay and restore the safe surface",
                device.wait(
                    Until.hasObject(
                        By.desc(
                            context.getString(org.quicklauncher.host.runtime.R.string.local_app_filter),
                        ),
                    ),
                    TIMEOUT,
                ),
            )
            val filter = device.findObject(By.clazz("android.widget.EditText"))
            requireNotNull(filter) { "Safe layout did not expose its editable local filter" }
            filter.click()
            filter.text = QUERY_THAT_MUST_RESET
            assertEquals(
                "The local query could not be entered before the reset assertion",
                QUERY_THAT_MUST_RESET,
                waitForRecorder { it.localQuery == QUERY_THAT_MUST_RESET }.localQuery,
            )
            device.pressHome()
            assertCurrentPackage(packageName, "Third Home press did not preserve the launcher task")
            val repeatedEntry = waitForRecorder { it.localQuery.isEmpty() }

            assertTrue("The first Home press was not recorded as a Home entry", firstEntry.homeEntries >= 1)
            assertEquals(
                "singleTask must deliver repeated Home to the same MainActivity instance",
                firstEntry.activityInstanceId,
                repeatedEntry.activityInstanceId,
            )
            assertTrue(
                "Every press must reach MainActivity's Home-entry policy",
                repeatedEntry.homeEntries >= firstEntry.homeEntries + 2,
            )
            assertTrue(
                "Repeated Home must be delivered through onNewIntent",
                repeatedEntry.homeNewIntents >= firstEntry.homeNewIntents + 2,
            )
            assertEquals(
                "Repeated Home must return to the persisted start destination",
                requireNotNull(firstEntry.destinationId),
                repeatedEntry.destinationId,
            )
            assertEquals(
                "Repeated Home must close every transient host overlay",
                LauncherSurface.SAFE_LAYOUT.name,
                repeatedEntry.surface,
            )
            assertEquals(
                "Repeated Home must clear the transient local query",
                "",
                repeatedEntry.localQuery,
            )
        } finally {
            restoreRoleHolders(packageName, originalHolders)
        }
    }

    private fun assertCurrentPackage(expected: String, failure: String) {
        assertTrue(failure, device.wait(Until.hasObject(By.pkg(expected).depth(0)), TIMEOUT))
        assertEquals(failure, expected, device.currentPackageName)
    }

    private fun waitForRecorder(predicate: (HomeEntrySnapshot) -> Boolean): HomeEntrySnapshot {
        val deadline = SystemClock.uptimeMillis() + TIMEOUT
        do {
            val snapshot = HomeEntryRecorder.snapshot()
            if (predicate(snapshot)) return snapshot
            SystemClock.sleep(50L)
        } while (SystemClock.uptimeMillis() < deadline)
        error("Timed out waiting for launcher runtime state")
    }

    private fun restoreRoleHolders(
        packageName: String,
        originalHolders: Set<String>,
    ) {
        if (packageName !in originalHolders) {
            shell("cmd role remove-role-holder --user $userId $HOME_ROLE $packageName")
        }
        originalHolders.forEach { holder ->
            require(PACKAGE_NAME.matches(holder)) { "Original Home holder is not shell-safe" }
            shell("cmd role add-role-holder --user $userId $HOME_ROLE $holder")
        }
        val restored = waitForRoleHolders(originalHolders)
        assertEquals("The test must restore every prior Home-role holder", originalHolders, restored)
    }

    private fun roleHolders(): Set<String> =
        shell("cmd role get-role-holders --user $userId $HOME_ROLE")
            .lineSequence()
            .map(String::trim)
            .filter(PACKAGE_NAME::matches)
            .toSet()

    private fun waitForRoleHeld(): Boolean {
        val deadline = SystemClock.uptimeMillis() + TIMEOUT
        do {
            if (roleManager.isRoleHeld(RoleManager.ROLE_HOME)) return true
            SystemClock.sleep(50L)
        } while (SystemClock.uptimeMillis() < deadline)
        return false
    }

    private fun waitForRoleHolders(expected: Set<String>): Set<String> {
        val deadline = SystemClock.uptimeMillis() + TIMEOUT
        var current: Set<String>
        do {
            current = roleHolders()
            if (current == expected) return current
            SystemClock.sleep(50L)
        } while (SystemClock.uptimeMillis() < deadline)
        return current
    }

    private fun shell(command: String): String =
        instrumentation.uiAutomation.executeShellCommand(command).use { descriptor ->
            descriptor.readText()
        }

    private fun ParcelFileDescriptor.readText(): String =
        FileInputStream(fileDescriptor).bufferedReader().use { it.readText() }

    private companion object {
        const val HOME_ROLE = "android.app.role.HOME"
        const val TIMEOUT = 10_000L
        const val QUERY_THAT_MUST_RESET = "phase-three-transient-query"
        val PACKAGE_NAME = Regex("[A-Za-z][A-Za-z0-9_]*(?:\\.[A-Za-z][A-Za-z0-9_]*)+")
    }
}
