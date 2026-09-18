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
import androidx.test.runner.lifecycle.ActivityLifecycleMonitorRegistry
import androidx.test.runner.lifecycle.Stage
import androidx.test.uiautomator.By
import androidx.test.uiautomator.StaleObjectException
import androidx.test.uiautomator.UiDevice
import androidx.test.uiautomator.Until
import java.io.FileInputStream
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.quicklauncher.host.platform.profile.AndroidProfilePlatform
import org.quicklauncher.host.runtime.profile.PrivateSpaceEntryPoint
import org.quicklauncher.host.runtime.profile.ProfileAvailability
import org.quicklauncher.host.runtime.profile.ProfileKind

@RunWith(AndroidJUnit4::class)
class HomeManifestInstrumentedTest {
    @Test
    fun completeLauncherDeclaresBroadPackageVisibilityForTheAppCatalog() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val requestedPermissions = context.packageManager.getPackageInfo(
            context.packageName,
            PackageManager.PackageInfoFlags.of(PackageManager.GET_PERMISSIONS.toLong()),
        ).requestedPermissions?.toSet().orEmpty()

        assertTrue(
            "The complete launcher app catalog requires explicit broad package visibility",
            android.Manifest.permission.QUERY_ALL_PACKAGES in requestedPermissions,
        )
        assertEquals(
            PackageManager.PERMISSION_GRANTED,
            context.packageManager.checkPermission(
                android.Manifest.permission.QUERY_ALL_PACKAGES,
                context.packageName,
            ),
        )
    }

    @Test
    fun completePrivateSpaceLauncherDeclaresHiddenProfileAccess() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        assertEquals(
            PackageManager.PERMISSION_GRANTED,
            context.packageManager.checkPermission(
                "android.permission.ACCESS_HIDDEN_PROFILES",
                context.packageName,
            ),
        )
    }

    @Test
    fun homeRoleExposesARealLockedPrivateProfileWithoutReadingItsMetadata() = runBlocking {
        val deviceHasPrivateProfile = shell("cmd user list -v")
            .lineSequence()
            .any { it.contains("type=profile.PRIVATE") }
        if (!deviceHasPrivateProfile) return@runBlocking
        val originalHolders = roleHolders()
        try {
            shell(
                "cmd role add-role-holder --user $userId android.app.role.HOME " +
                    context.packageName,
            )
            assertTrue(roleHolders().contains(context.packageName))
            val platform = AndroidProfilePlatform(context, Dispatchers.IO)
            try {
                val privateProfile = platform.profiles().single { it.kind == ProfileKind.PRIVATE }
                assertEquals(ProfileAvailability.LOCKED, privateProfile.availability)
                assertTrue(
                    privateProfile.privateSpaceEntryPoint == PrivateSpaceEntryPoint.SYSTEM_DEFAULT ||
                        privateProfile.privateSpaceEntryPoint == PrivateSpaceEntryPoint.HIDDEN,
                )
            } finally {
                platform.close()
            }
        } finally {
            restoreRoleHolders(originalHolders)
        }
    }

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
            HomeEntryRecorder.resetForTests()
            removeOwnRole()
            finishQuicklauncherActivities()
            assertTrue(
                "Quicklauncher activity did not stop before onboarding setup",
                waitForNoQuicklauncherActivity(),
            )
            setOnboardingState(org.quicklauncher.host.data.preferences.OnboardingState.IN_PROGRESS)
            launchFromAppIcon()
            val appIconActivity = HomeEntryRecorder.snapshot().activityInstanceId
            requireNotNull(
                waitForEnabled(
                    By.text(context.getString(org.quicklauncher.host.runtime.R.string.use_as_home)),
                    TIMEOUT,
                ),
            ) { onboardingDiagnostics() }.click()
            assertTrue(
                "The production Home-role flow did not acquire ownership",
                completeHomeRoleGrant(roleManager),
            )
            assertTrue(
                "The granted Home role was not durably acknowledged before leaving onboarding",
                device.wait(
                    Until.gone(By.text("Set up Quicklauncher")),
                    TIMEOUT,
                ),
            )
            assertTrue(
                "The granted role did not enter Quicklauncher through Android's Home mapping",
                waitForHomeEntryAfter(appIconActivity),
            )
            assertTrue(
                "The completed role request did not settle on exactly one live launcher activity",
                waitForOneStableQuicklauncherActivity(),
            )
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
            finishQuicklauncherActivities()
            assertTrue(
                "Quicklauncher activity did not stop before onboarding setup",
                waitForNoQuicklauncherActivity(),
            )
            setOnboardingState(org.quicklauncher.host.data.preferences.OnboardingState.IN_PROGRESS)
            launchFromAppIcon()
            requireNotNull(
                waitForEnabled(
                    By.text(context.getString(org.quicklauncher.host.runtime.R.string.use_as_home)),
                    TIMEOUT,
                ),
            ) { onboardingDiagnostics() }.click()
            assertTrue(
                "The platform Home-role request did not open",
                device.wait(
                    Until.gone(
                        By.text(context.getString(org.quicklauncher.host.runtime.R.string.use_as_home)),
                    ),
                    TIMEOUT,
                ),
            )
            device.pressBack()
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
        setOnboardingState(org.quicklauncher.host.data.preferences.OnboardingState.COMPLETED)
        HomeEntryRecorder.resetForTests()
        launchFromAppIcon()
        val settings = waitForEnabled(
            By.desc(context.getString(org.quicklauncher.host.runtime.R.string.launcher_settings)),
            TIMEOUT,
        )
        requireNotNull(settings) {
            "Launcher settings were absent from ${device.currentPackageName}: ${visibleSemantics()}"
        }.click()
        requireNotNull(
            waitForEnabled(
                By.text(context.getString(org.quicklauncher.host.settings.R.string.open_app_recovery)),
                TIMEOUT,
            ),
        ).click()
        requireNotNull(
            device.wait(
                Until.findObject(
                    By.text(context.getString(org.quicklauncher.host.settings.R.string.app_recovery_title)),
                ),
                TIMEOUT,
            ),
        ) {
            "App recovery did not open after activating its launcher Settings action"
        }
        requireNotNull(
            findByScrolling(
                By.text(context.getString(R.string.app_name)),
                attempts = 24,
            )?.takeIf { it.isEnabled },
        ) {
            "Quicklauncher was absent from the virtualized app recovery catalog"
        }.click()
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
            waitForResumedQuicklauncherActivity(),
        )
        completeOnboardingIfNeeded()
    }

    private fun waitForResumedQuicklauncherActivity(): Boolean {
        val deadline = SystemClock.uptimeMillis() + TIMEOUT
        do {
            var resumed = false
            instrumentation.runOnMainSync {
                resumed = ActivityLifecycleMonitorRegistry.getInstance()
                    .getActivitiesInStage(Stage.RESUMED)
                    .any { it is MainActivity }
            }
            if (resumed) return true
            SystemClock.sleep(50L)
        } while (SystemClock.uptimeMillis() < deadline)
        return false
    }

    private fun completeOnboardingIfNeeded() {
        val application = context.applicationContext as QuicklauncherApplication
        val onboardingState = runBlocking {
            requireNotNull(application.launcherPreferences).read().onboardingState
        }
        if (onboardingState == org.quicklauncher.host.data.preferences.OnboardingState.IN_PROGRESS) {
            requireNotNull(waitForEnabled(By.text("Use Quicklauncher as Home"), TIMEOUT)) {
                onboardingDiagnostics()
            }
            return
        }
        if (waitForEnabled(By.text("Use Quicklauncher as Home"), 250L) != null) return
        val preview = findTextByScrolling("Preview configured Modular", attempts = 6) ?: return
        preview.click()
        requireNotNull(
            findByScrolling(By.descStartsWith("Noninteractive preview of"), attempts = 6),
        ) {
            "Onboarding did not render the validated plan through the production composition preview"
        }
        requireNotNull(
            device.wait(Until.findObject(By.desc("Favorites")), TIMEOUT),
        ) {
            "The production registry did not render recognizable modular block content"
        }
        val install = requireNotNull(findTextByScrolling("Install this plan", attempts = 6)) {
            "A configured onboarding plan did not expose its explicit install action"
        }
        install.click()
        requireNotNull(waitForEnabled(By.text("Use Quicklauncher as Home"), TIMEOUT)) {
            "Onboarding did not install the selected plan: ${onboardingDiagnostics()}"
        }
    }

    private fun onboardingDiagnostics(): String {
        val application = context.applicationContext as QuicklauncherApplication
        val onboardingState = runBlocking {
            requireNotNull(application.launcherPreferences).read().onboardingState
        }
        return "Home action unavailable: onboarding=$onboardingState, " +
            "holders=${roleHolders()}, foreground=${device.currentPackageName}, " +
            "runtime=${HomeEntryRecorder.snapshot()}, visible=${visibleSemantics()}"
    }

    private fun setOnboardingState(state: org.quicklauncher.host.data.preferences.OnboardingState) {
        val application = context.applicationContext as QuicklauncherApplication
        runBlocking { requireNotNull(application.launcherPreferences).setOnboardingState(state) }
    }

    private fun waitForEnabled(
        selector: androidx.test.uiautomator.BySelector,
        timeout: Long,
    ): androidx.test.uiautomator.UiObject2? {
        val deadline = SystemClock.uptimeMillis() + timeout
        do {
            device.findObject(selector)?.clickableAncestor()?.takeIf { it.isEnabled }?.let { return it }
            SystemClock.sleep(50L)
        } while (SystemClock.uptimeMillis() < deadline)
        return device.findObject(selector)?.clickableAncestor()?.takeIf { it.isEnabled }
    }

    private fun androidx.test.uiautomator.UiObject2.clickableAncestor(): androidx.test.uiautomator.UiObject2? {
        var candidate: androidx.test.uiautomator.UiObject2? = this
        while (candidate != null && !candidate.isClickable) candidate = candidate.parent
        return candidate
    }

    private fun findTextByScrolling(text: String, attempts: Int): androidx.test.uiautomator.UiObject2? {
        return findByScrolling(By.text(text), attempts)
    }

    private fun findByScrolling(
        selector: androidx.test.uiautomator.BySelector,
        attempts: Int,
    ): androidx.test.uiautomator.UiObject2? {
        repeat(attempts) {
            device.findObject(selector)?.let { return it }
            device.swipe(
                device.displayWidth / 2,
                device.displayHeight * 3 / 4,
                device.displayWidth / 2,
                device.displayHeight / 4,
                20,
            )
            instrumentation.waitForIdleSync()
        }
        return device.findObject(selector)
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

    private fun completeHomeRoleGrant(roleManager: RoleManager): Boolean {
        val deadline = SystemClock.uptimeMillis() + TIMEOUT
        while (SystemClock.uptimeMillis() < deadline) {
            if (roleManager.isRoleHeld(RoleManager.ROLE_HOME)) return true
            clickIfEnabled(By.text(context.getString(R.string.app_name)))
            clickIfEnabled(By.res("android", "button1"))
            SystemClock.sleep(50L)
        }
        return roleManager.isRoleHeld(RoleManager.ROLE_HOME)
    }

    private fun clickIfEnabled(selector: androidx.test.uiautomator.BySelector) {
        try {
            device.findObject(selector)?.takeIf { it.isEnabled }?.click()
        } catch (_: StaleObjectException) {
            // The role dialog can replace its accessibility tree between lookup and click.
        }
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
        assertTrue("The device must have an original Home holder", original.isNotEmpty())
        finishQuicklauncherActivities()
        device.pressHome()
        assertTrue(
            "The restored Home activity did not settle before the next test",
            waitForForegroundPackage(original),
        )
    }

    private fun finishQuicklauncherActivities() {
        instrumentation.runOnMainSync {
            val monitor = ActivityLifecycleMonitorRegistry.getInstance()
            Stage.entries
                .flatMap { stage -> monitor.getActivitiesInStage(stage) }
                .filterIsInstance<MainActivity>()
                .toSet()
                .forEach(MainActivity::finishAndRemoveTask)
        }
        instrumentation.waitForIdleSync()
    }

    private fun waitForNoQuicklauncherActivity(): Boolean {
        val deadline = SystemClock.uptimeMillis() + TIMEOUT
        do {
            var count = Int.MAX_VALUE
            instrumentation.runOnMainSync {
                val monitor = ActivityLifecycleMonitorRegistry.getInstance()
                count = Stage.entries
                    .filterNot { stage -> stage == Stage.DESTROYED }
                    .flatMap { stage -> monitor.getActivitiesInStage(stage) }
                    .filterIsInstance<MainActivity>()
                    .toSet()
                    .size
            }
            if (count == 0) return true
            SystemClock.sleep(50L)
        } while (SystemClock.uptimeMillis() < deadline)
        return false
    }

    private fun waitForHomeEntryAfter(appIconActivity: Int): Boolean {
        val deadline = SystemClock.uptimeMillis() + TIMEOUT
        do {
            val entry = HomeEntryRecorder.snapshot()
            if (
                entry.homeEntries > 0 &&
                (entry.homeNewIntents > 0 || entry.activityInstanceId != appIconActivity)
            ) {
                return true
            }
            SystemClock.sleep(50L)
        } while (SystemClock.uptimeMillis() < deadline)
        return false
    }

    private fun waitForOneStableQuicklauncherActivity(): Boolean {
        val deadline = SystemClock.uptimeMillis() + TIMEOUT
        var stableSince: Long? = null
        do {
            var count = Int.MAX_VALUE
            instrumentation.runOnMainSync {
                val monitor = ActivityLifecycleMonitorRegistry.getInstance()
                count = Stage.entries
                    .filterNot { stage -> stage == Stage.DESTROYED }
                    .flatMap { stage -> monitor.getActivitiesInStage(stage) }
                    .filterIsInstance<MainActivity>()
                    .toSet()
                    .size
            }
            stableSince = when {
                count != 1 || device.currentPackageName != context.packageName -> null
                stableSince == null -> SystemClock.uptimeMillis()
                else -> stableSince
            }
            if (stableSince != null && SystemClock.uptimeMillis() - stableSince >= SETTLE_TIME) return true
            SystemClock.sleep(50L)
        } while (SystemClock.uptimeMillis() < deadline)
        return false
    }

    private fun visibleSemantics(): String = device.findObjects(By.depth(0)).joinToString(" | ") { root ->
        root.flattenSemantics().joinToString(", ")
    }

    private fun androidx.test.uiautomator.UiObject2.flattenSemantics(): List<String> {
        val own = listOfNotNull(
            text?.takeIf(String::isNotEmpty)?.let { "text=$it" },
            contentDescription?.takeIf(String::isNotEmpty)?.let { "desc=$it" },
        )
        return own + children.flatMap { child -> child.flattenSemantics() }
    }

    private fun waitForForegroundPackage(expected: String): Boolean =
        waitForForegroundPackage(setOf(expected))

    private fun waitForForegroundPackage(expected: Set<String>): Boolean {
        val deadline = SystemClock.uptimeMillis() + TIMEOUT
        var stableSince: Long? = null
        do {
            val foreground = device.currentPackageName in expected
            stableSince = when {
                !foreground -> null
                stableSince == null -> SystemClock.uptimeMillis()
                else -> stableSince
            }
            if (stableSince != null && SystemClock.uptimeMillis() - stableSince >= SETTLE_TIME) {
                return true
            }
            SystemClock.sleep(50L)
        } while (SystemClock.uptimeMillis() < deadline)
        return false
    }

    private fun shell(command: String): String =
        instrumentation.uiAutomation.executeShellCommand(command).use { descriptor ->
            descriptor.readText()
        }

    private fun ParcelFileDescriptor.readText(): String =
        FileInputStream(fileDescriptor).bufferedReader().use { it.readText() }

    private companion object {
        const val TIMEOUT = 10_000L
        const val SETTLE_TIME = 500L
        val PACKAGE_NAME = Regex("[A-Za-z0-9_.]+")
    }
}
