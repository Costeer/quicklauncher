package org.quicklauncher.app

import android.app.role.RoleManager
import android.content.ComponentName
import android.content.Intent
import android.content.pm.ActivityInfo
import android.content.pm.PackageManager
import android.os.ParcelFileDescriptor
import android.os.SystemClock
import android.provider.Settings
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.By
import androidx.test.uiautomator.UiDevice
import androidx.test.uiautomator.Until
import java.io.FileInputStream
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class HomeManifestInstrumentedTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context = instrumentation.targetContext
    private val packageManager = context.packageManager
    private val activity = ComponentName(context, MainActivity::class.java)
    private val device = UiDevice.getInstance(instrumentation)
    private val userId: Int by lazy { shell("am get-current-user").trim().toInt() }

    @Test
    fun mergedManifestExportsOneSingleTaskHomeActivity() {
        val info = packageManager.getActivityInfo(
            activity,
            PackageManager.ComponentInfoFlags.of(0),
        )

        assertTrue("MainActivity must be exported to Android's Home resolver", info.exported)
        assertEquals(
            "Repeated Home must be delivered to the existing launcher task",
            ActivityInfo.LAUNCH_SINGLE_TASK,
            info.launchMode,
        )

        val matches = packageManager.queryIntentActivities(
            homeIntent(),
            PackageManager.ResolveInfoFlags.of(PackageManager.MATCH_DEFAULT_ONLY.toLong()),
        )
        assertEquals(
            "The merged manifest must expose MainActivity exactly once for MAIN/HOME/DEFAULT",
            1,
            matches.count { match ->
                match.activityInfo.packageName == context.packageName &&
                    match.activityInfo.name == MainActivity::class.java.name
            },
        )
    }

    @Test
    fun platformHomeRoleIsAvailableAndItsRequestIntentResolves() {
        val roleManager = context.getSystemService(RoleManager::class.java)

        assertTrue(
            "API 35 launcher devices must expose RoleManager.ROLE_HOME",
            roleManager.isRoleAvailable(RoleManager.ROLE_HOME),
        )
        val request = roleManager.createRequestRoleIntent(RoleManager.ROLE_HOME)
        assertNotNull(
            "The platform Home-role request must resolve before onboarding offers it",
            packageManager.resolveActivity(
                request,
                PackageManager.ResolveInfoFlags.of(PackageManager.MATCH_DEFAULT_ONLY.toLong()),
            ),
        )
    }

    @Test
    fun contextualHomeRequestCanGrantOwnershipThroughTheRealPlatformDialog() {
        val roleManager = context.getSystemService(RoleManager::class.java)
        val originalHolders = roleHolders()
        try {
            removeOwnRole()
            launchFromAppIcon()
            requireNotNull(
                device.wait(
                    Until.findObject(By.text(context.getString(org.quicklauncher.host.runtime.R.string.use_as_home))),
                    TIMEOUT,
                ),
            ).click()
            val candidate = requireNotNull(
                device.wait(Until.findObject(By.text(context.getString(R.string.app_name))), TIMEOUT),
            )
            candidate.click()
            assertTrue(
                "The platform did not expose a stable Home-role confirmation action",
                clickFresh(By.res("android", "button1")),
            )

            assertTrue("The production Home-role flow did not acquire ownership", waitForRoleHeld(roleManager))
        } finally {
            restoreRoleHolders(originalHolders)
        }
    }

    @Test
    fun cancelledHomeRequestExposesAndOpensThePublicSettingsFallback() {
        val originalHolders = roleHolders()
        val settingsPackage = requireNotNull(
            packageManager.resolveActivity(
                Intent(Settings.ACTION_HOME_SETTINGS),
                PackageManager.ResolveInfoFlags.of(PackageManager.MATCH_DEFAULT_ONLY.toLong()),
            ),
        ).activityInfo.packageName
        try {
            removeOwnRole()
            launchFromAppIcon()
            requireNotNull(
                device.wait(
                    Until.findObject(By.text(context.getString(org.quicklauncher.host.runtime.R.string.use_as_home))),
                    TIMEOUT,
                ),
            ).click()
            requireNotNull(
                device.wait(Until.findObject(By.res("android", "button2")), TIMEOUT),
            ).click()
            requireNotNull(
                device.wait(
                    Until.findObject(
                        By.text(context.getString(org.quicklauncher.host.settings.R.string.open_home_settings)),
                    ),
                    TIMEOUT,
                ),
            ).click()

            assertTrue(
                "The public Home settings fallback did not open its resolved system activity",
                device.wait(Until.hasObject(By.pkg(settingsPackage).depth(0)), TIMEOUT),
            )
        } finally {
            restoreRoleHolders(originalHolders)
        }
    }

    @Test
    fun launcherSettingsExposeTheTypedSystemAppRecoveryRoute() {
        HomeEntryRecorder.resetForTests()
        launchFromAppIcon()
        requireNotNull(
            device.wait(
                Until.findObject(
                    By.desc(context.getString(org.quicklauncher.host.runtime.R.string.launcher_settings)),
                ),
                TIMEOUT,
            ),
        ).click()
        requireNotNull(
            device.wait(
                Until.findObject(
                    By.text(context.getString(org.quicklauncher.host.settings.R.string.open_app_recovery)),
                ),
                TIMEOUT,
            ),
        ).click()
        requireNotNull(
            device.wait(Until.findObject(By.text(context.getString(R.string.app_name))), TIMEOUT),
        ).click()
        instrumentation.waitForIdleSync()
        assertTrue(HomeEntryRecorder.snapshot().appIconEntries > 0)
    }

    private fun homeIntent(): Intent = Intent(Intent.ACTION_MAIN).apply {
        addCategory(Intent.CATEGORY_HOME)
        addCategory(Intent.CATEGORY_DEFAULT)
    }

    private fun launchFromAppIcon() {
        val launch = requireNotNull(packageManager.getLaunchIntentForPackage(context.packageName)).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
        }
        context.startActivity(launch)
        assertTrue(
            "Quicklauncher did not open for the contextual role action",
            device.wait(Until.hasObject(By.pkg(context.packageName).depth(0)), TIMEOUT),
        )
    }

    private fun roleHolders(): Set<String> =
        shell("cmd role get-role-holders --user $userId ${RoleManager.ROLE_HOME}")
            .lineSequence()
            .map(String::trim)
            .filter(PACKAGE_NAME::matches)
            .toSet()

    private fun removeOwnRole() {
        shell(
            "cmd role remove-role-holder --user $userId ${RoleManager.ROLE_HOME} " +
                context.packageName,
        )
        val deadline = SystemClock.uptimeMillis() + TIMEOUT
        while (context.packageName in roleHolders() && SystemClock.uptimeMillis() < deadline) {
            SystemClock.sleep(50L)
        }
        assertTrue("Quicklauncher remained the Home-role holder", context.packageName !in roleHolders())
    }

    private fun waitForRoleHeld(roleManager: RoleManager): Boolean {
        val deadline = SystemClock.uptimeMillis() + TIMEOUT
        do {
            if (roleManager.isRoleHeld(RoleManager.ROLE_HOME)) return true
            SystemClock.sleep(50L)
        } while (SystemClock.uptimeMillis() < deadline)
        return false
    }

    private fun clickFresh(selector: androidx.test.uiautomator.BySelector): Boolean {
        val deadline = SystemClock.uptimeMillis() + TIMEOUT
        while (SystemClock.uptimeMillis() < deadline) {
            val clicked = runCatching {
                val candidate = device.findObject(selector) ?: return@runCatching false
                if (!candidate.isEnabled) return@runCatching false
                candidate.click()
                true
            }.getOrDefault(false)
            if (clicked) return true
            SystemClock.sleep(50L)
        }
        return false
    }

    private fun restoreRoleHolders(original: Set<String>) {
        removeOwnRole()
        original.forEach { holder ->
            require(PACKAGE_NAME.matches(holder)) { "Original Home holder is not shell-safe" }
            shell("cmd role add-role-holder --user $userId ${RoleManager.ROLE_HOME} $holder")
        }
        val deadline = SystemClock.uptimeMillis() + TIMEOUT
        while (roleHolders() != original && SystemClock.uptimeMillis() < deadline) {
            SystemClock.sleep(50L)
        }
        assertEquals("The test must restore every prior Home-role holder", original, roleHolders())
    }

    private fun shell(command: String): String =
        instrumentation.uiAutomation.executeShellCommand(command).use { descriptor ->
            descriptor.readText()
        }

    private fun ParcelFileDescriptor.readText(): String =
        FileInputStream(fileDescriptor).bufferedReader().use { it.readText() }

    private companion object {
        const val TIMEOUT = 10_000L
        val PACKAGE_NAME = Regex("[A-Za-z0-9_.]+")
    }
}
