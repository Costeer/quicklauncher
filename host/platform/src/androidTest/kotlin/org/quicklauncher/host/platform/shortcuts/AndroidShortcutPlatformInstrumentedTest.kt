package org.quicklauncher.host.platform.shortcuts

import android.app.role.RoleManager
import android.content.ComponentName
import android.content.Intent
import android.content.pm.LauncherApps
import android.content.pm.ShortcutInfo
import android.content.pm.ShortcutManager
import android.os.Build
import android.os.ParcelFileDescriptor
import android.os.Process
import android.os.UserManager
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.io.FileInputStream
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.quicklauncher.contracts.domain.PackageName
import org.quicklauncher.contracts.domain.ProfileSerial
import org.quicklauncher.contracts.domain.ShortcutId
import org.quicklauncher.host.runtime.shortcuts.ShortcutOwner
import org.quicklauncher.host.runtime.shortcuts.ShortcutPinRequest
import org.quicklauncher.host.runtime.shortcuts.ShortcutPinResult
import org.quicklauncher.host.runtime.shortcuts.ShortcutQueryResult

@RunWith(AndroidJUnit4::class)
class AndroidShortcutPlatformInstrumentedTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context = instrumentation.context
    private var originalHome: String? = null

    @Before
    fun installFixtureAndRequestTemporaryHomeRole() {
        assertTrue("Shortcut behavior requires API 35 or newer", Build.VERSION.SDK_INT >= 35)
        originalHome = shell("cmd role get-role-holders ${RoleManager.ROLE_HOME} --user current")
            .lineSequence()
            .map(String::trim)
            .firstOrNull(String::isNotEmpty)
        val shortcutManager = context.getSystemService(ShortcutManager::class.java)
        shortcutManager.removeAllDynamicShortcuts()
        val target = ComponentName(context, PhaseFiveFixtureShortcutActivity::class.java)
        assertTrue(
            shortcutManager.setDynamicShortcuts(
                listOf(
                    ShortcutInfo.Builder(context, FIXTURE_ID)
                        .setShortLabel("Phase five fixture")
                        .setActivity(target)
                        .setIntent(Intent(Intent.ACTION_VIEW).setComponent(target))
                        .build(),
                ),
            ),
        )
        shell("cmd role add-role-holder ${RoleManager.ROLE_HOME} ${context.packageName} --user current")
    }

    @After
    fun removeFixtureAndRestoreHome() {
        context.getSystemService(ShortcutManager::class.java).removeAllDynamicShortcuts()
        originalHome?.let { shell("cmd role add-role-holder ${RoleManager.ROLE_HOME} $it --user current") }
    }

    @Test
    fun realDynamicQueryAndPinnedSetUseTypedResultsForTheCurrentProfile() = runBlocking {
        val serial = context.getSystemService(UserManager::class.java)
            .getSerialNumberForUser(Process.myUserHandle())
        require(serial >= 0L)
        val owner = ShortcutOwner(ProfileSerial.of(serial), PackageName.parse(context.packageName))
        val platform = AndroidShortcutPlatform(context, Dispatchers.IO)
        try {
            val hostPermission = awaitShortcutHostPermission()
            assumeTrue(
                "The OEM did not grant shortcut-host access to the temporary HOME role holder",
                hostPermission,
            )

            val discovered = platform.query(owner) as ShortcutQueryResult.Available
            val fixture = discovered.shortcuts.single { it.target.shortcutId.value == FIXTURE_ID }
            assertTrue(fixture.dynamic)

            assertEquals(
                ShortcutPinResult.Applied,
                platform.pin(ShortcutPinRequest(owner, setOf(ShortcutId.parse(FIXTURE_ID)))),
            )
            val pinned = platform.query(owner) as ShortcutQueryResult.Available
            assertTrue(pinned.shortcuts.single { it.target.shortcutId.value == FIXTURE_ID }.pinned)

            assertEquals(
                ShortcutPinResult.Applied,
                platform.pin(ShortcutPinRequest(owner, emptySet())),
            )
            val unpinned = platform.query(owner) as ShortcutQueryResult.Available
            assertTrue(!unpinned.shortcuts.single { it.target.shortcutId.value == FIXTURE_ID }.pinned)
        } finally {
            platform.close()
        }
    }

    private fun shell(command: String): String =
        instrumentation.uiAutomation.executeShellCommand(command).use { descriptor ->
            descriptor.readText().also { output ->
                check("Error:" !in output && "Exception" !in output) {
                    "Shell command failed: $command\n$output"
                }
            }
        }

    private fun ParcelFileDescriptor.readText(): String =
        FileInputStream(fileDescriptor).bufferedReader().use { it.readText() }

    private fun awaitShortcutHostPermission(): Boolean {
        val launcherApps = context.getSystemService(LauncherApps::class.java)
        repeat(50) {
            if (launcherApps.hasShortcutHostPermission()) return true
            Thread.sleep(100)
        }
        return launcherApps.hasShortcutHostPermission()
    }

    private companion object {
        const val FIXTURE_ID = "phase-five-fixture"
    }
}
