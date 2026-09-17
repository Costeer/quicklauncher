package org.quicklauncher.app

import android.app.role.RoleManager
import android.os.ParcelFileDescriptor
import android.os.SystemClock
import android.view.WindowInsets
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.runner.lifecycle.ActivityLifecycleMonitorRegistry
import androidx.test.runner.lifecycle.Stage
import androidx.test.uiautomator.By
import androidx.test.uiautomator.UiDevice
import androidx.test.uiautomator.Until
import java.io.FileInputStream
import java.util.concurrent.atomic.AtomicReference
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.quicklauncher.contracts.domain.ContributionId
import org.quicklauncher.contracts.domain.DestinationId
import org.quicklauncher.host.data.preferences.OnboardingState
import org.quicklauncher.host.data.store.LauncherStore
import org.quicklauncher.host.data.store.RegistryPlacementPolicy
import org.quicklauncher.host.data.store.registryConfigurationResolver
import org.quicklauncher.host.editor.OnboardingResult
import org.quicklauncher.host.editor.OnboardingSafeLayoutFactory
import org.quicklauncher.host.editor.SafeLayoutDraft
import org.quicklauncher.host.editor.TemplateOnboarding
import org.quicklauncher.host.platform.persistence.AndroidLauncherStoreFactory
import org.quicklauncher.host.runtime.LauncherRuntime
import org.quicklauncher.host.runtime.LauncherRuntimeStatus
import org.quicklauncher.host.runtime.SafeLayoutIds
import org.quicklauncher.host.runtime.composition.SearchPresentationSource
import org.quicklauncher.host.runtime.search.ComposeSearchPresentationSource
import org.quicklauncher.host.runtime.search.SearchPresentationController
import org.quicklauncher.host.runtime.phasefive.PhaseFiveHost
import org.quicklauncher.host.runtime.profile.ProfileAvailability
import org.quicklauncher.host.runtime.profile.ProfileKind
import org.quicklauncher.host.runtime.profile.WorkModeResult
import org.quicklauncher.registry.production.productionRegistry

/** Physical production-composition coverage for Phase 6 search session ownership. */
@RunWith(AndroidJUnit4::class)
class PhaseSixSearchLifecycleInstrumentedTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context = instrumentation.targetContext
    private val device = UiDevice.getInstance(instrumentation)
    private val roleManager = context.getSystemService(RoleManager::class.java)
    private val userId: Int by lazy { shell("am get-current-user").trim().toInt() }

    @Test
    fun searchSessionDismissesForBackRecreationAndRepeatedHome() {
        val packageName = context.packageName
        require(PACKAGE_NAME.matches(packageName)) { "Application ID is not shell-safe" }
        val originalHolders = roleHolders()
        var activity: MainActivity? = null

        try {
            installSyntheticModularPlan()
            runBlocking {
                requireNotNull(
                    (context.applicationContext as QuicklauncherApplication).launcherPreferences,
                ).setOnboardingState(OnboardingState.COMPLETED)
            }
            shell("cmd role add-role-holder --user $userId $HOME_ROLE $packageName")
            assertTrue("Quicklauncher did not become the Home-role holder", waitForRoleHeld())

            activity = startLauncher()
            waitForRuntimeReady(activity)
            val searchDestination = searchDestination(activity.launcherStore())
            assertTrue(runBlocking { activity.launcherRuntime().selectDestination(searchDestination) })
            val firstInput = waitForSearchInput()
            assertFalse(activity.searchPresentations().hasActiveQuery.value)

            firstInput.click()
            firstInput.text = QUERY
            assertTrue("Search did not publish an active presentation query", waitForSearchActive(activity, true))
            assertTrue(
                "The production Settings provider did not publish its public result",
                waitForSearchResult(activity, "Security settings"),
            )

            hideIme(activity)
            device.pressBack()
            assertTrue("Predictive Back did not dismiss search", waitForSearchActive(activity, false))
            assertEquals(packageName, device.currentPackageName)

            waitForSearchInput().apply {
                click()
                text = QUERY
            }
            assertTrue(waitForSearchActive(activity, true))
            val previous = activity
            instrumentation.runOnMainSync(previous::recreate)
            activity = waitForRecreatedActivity(previous)
            waitForRuntimeReady(activity)
            assertNotSame(previous, activity)
            assertFalse("Recreation retained the old search session", activity.searchPresentations().hasActiveQuery.value)
            assertEquals("", waitForSearchInput().text.orEmpty())

            waitForSearchInput().apply {
                click()
                text = QUERY
            }
            assertTrue(waitForSearchActive(activity, true))
            device.pressHome()
            assertTrue(
                "Repeated Home did not return to the existing launcher task",
                device.wait(Until.hasObject(By.pkg(packageName).depth(0)), TIMEOUT),
            )
            activity = requireNotNull(waitForResumedActivity())
            waitForRuntimeReady(activity)
            assertTrue("Repeated Home did not close the search query", waitForSearchActive(activity, false))
        } finally {
            activity?.let { current ->
                instrumentation.runOnMainSync {
                    if (!current.isFinishing) current.finishAndRemoveTask()
                }
            }
            restoreRoleHolders(packageName, originalHolders)
            context.deleteDatabase(MainActivity.DATABASE_NAME)
        }
    }

    @Test
    fun activeSearchRemovesAndRestoresManagedWorkResultsOnProfileCallbacks() {
        assumeTrue(
            "Managed-work search verification requires explicit physical-device orchestration",
            InstrumentationRegistry.getArguments().getString("phase6ManagedWork") == "true",
        )
        val packageName = context.packageName
        require(PACKAGE_NAME.matches(packageName)) { "Application ID is not shell-safe" }
        val originalHolders = roleHolders()
        var activity: MainActivity? = null
        var workSerial: org.quicklauncher.contracts.domain.ProfileSerial? = null

        try {
            installSyntheticModularPlan()
            runBlocking {
                requireNotNull(
                    (context.applicationContext as QuicklauncherApplication).launcherPreferences,
                ).setOnboardingState(OnboardingState.COMPLETED)
            }
            shell("cmd role add-role-holder --user $userId $HOME_ROLE $packageName")
            assertTrue("Quicklauncher did not become the Home-role holder", waitForRoleHeld())
            activity = startLauncher()
            waitForRuntimeReady(activity)
            val work = waitForWorkProfile(activity, ProfileAvailability.AVAILABLE)
            workSerial = work.serial
            assertTrue(
                runBlocking {
                    activity.launcherRuntime().selectDestination(searchDestination(activity.launcherStore()))
                },
            )
            waitForSearchInput().apply {
                click()
                text = MANAGED_FIXTURE_QUERY
            }
            assertTrue(waitForSearchActive(activity, true))
            assertTrue("The production search snapshot omitted mandatory work badging", waitForWorkResult(activity, true))

            assertEquals(
                WorkModeResult.Requested,
                runBlocking { activity.phaseFiveHost().setWorkMode(work.serial, enabled = false) },
            )
            waitForWorkProfile(activity, ProfileAvailability.QUIET)
            assertTrue(
                "A quiet work-profile result remained in the active search presentation",
                waitForWorkResult(activity, false),
            )

            assertEquals(
                WorkModeResult.Requested,
                runBlocking { activity.phaseFiveHost().setWorkMode(work.serial, enabled = true) },
            )
            waitForWorkProfile(activity, ProfileAvailability.AVAILABLE)
            assertTrue(
                "The eligible work result did not return after the availability callback",
                waitForWorkResult(activity, true),
            )
        } finally {
            val serial = workSerial
            val current = activity
            if (serial != null && current != null) {
                runCatching { runBlocking { current.phaseFiveHost().setWorkMode(serial, enabled = true) } }
            }
            current?.let {
                instrumentation.runOnMainSync {
                    if (!it.isFinishing) it.finishAndRemoveTask()
                }
            }
            restoreRoleHolders(packageName, originalHolders)
            context.deleteDatabase(MainActivity.DATABASE_NAME)
        }
    }

    private fun installSyntheticModularPlan() {
        context.deleteDatabase(MainActivity.DATABASE_NAME)
        val store = AndroidLauncherStoreFactory(context).open(
            MainActivity.DATABASE_NAME,
            registryConfigurationResolver(productionRegistry),
            RegistryPlacementPolicy(productionRegistry),
        )
        try {
            val onboarding = TemplateOnboarding(
                productionRegistry,
                store,
                OnboardingSafeLayoutFactory { destinationId ->
                    SafeLayoutIds.recordsFor(destinationId, selected = false).let { safe ->
                        SafeLayoutDraft(safe.layout, safe.instance, safe.configuration)
                    }
                },
            )
            val preview = onboarding.previewDefault(MODULAR_TEMPLATE) as OnboardingResult.Previewed
            assertEquals(OnboardingResult.Installed, runBlocking { onboarding.install(preview.preview) })
        } finally {
            (store as AutoCloseable).close()
        }
    }

    private fun searchDestination(store: LauncherStore): DestinationId = runBlocking {
        val snapshot = store.read()
        val searchInstance = snapshot.moduleInstances.single { it.contributionId == SEARCH_BLOCK }.id
        val layoutInstance = snapshot.placements.single { it.childInstanceId == searchInstance }.layoutInstanceId
        snapshot.destinationLayouts.single { it.layoutInstanceId == layoutInstance }.destinationId
    }

    private fun startLauncher(): MainActivity {
        device.pressHome()
        requireNotNull(device.wait(Until.findObject(By.pkg(context.packageName).depth(0)), TIMEOUT)) {
            "The Home role did not open Quicklauncher"
        }
        val deadline = SystemClock.uptimeMillis() + TIMEOUT
        do {
            waitForResumedActivity()?.let { return it }
            SystemClock.sleep(50L)
        } while (SystemClock.uptimeMillis() < deadline)
        error("Timed out waiting for the Home activity")
    }

    private fun waitForSearchInput(): androidx.test.uiautomator.UiObject2 {
        requireNotNull(device.wait(Until.findObject(By.desc("Search input")), TIMEOUT)) {
            "The production search presentation did not render"
        }
        return requireNotNull(device.wait(Until.findObject(By.clazz("android.widget.EditText")), TIMEOUT)) {
            "The production search presentation exposed no editable text node"
        }
    }

    private fun waitForSearchActive(activity: MainActivity, expected: Boolean): Boolean {
        val deadline = SystemClock.uptimeMillis() + TIMEOUT
        do {
            if (activity.searchPresentations().hasActiveQuery.value == expected) return true
            SystemClock.sleep(50L)
        } while (SystemClock.uptimeMillis() < deadline)
        return false
    }

    private fun waitForSearchResult(activity: MainActivity, title: String): Boolean {
        val deadline = SystemClock.uptimeMillis() + TIMEOUT
        do {
            if (activity.activeSearchController().state.value.results.any { it.title == title }) return true
            SystemClock.sleep(50L)
        } while (SystemClock.uptimeMillis() < deadline)
        return false
    }

    private fun hideIme(activity: MainActivity) {
        instrumentation.runOnMainSync {
            activity.window.insetsController?.hide(WindowInsets.Type.ime())
            activity.currentFocus?.clearFocus()
        }
        val deadline = SystemClock.uptimeMillis() + TIMEOUT
        do {
            val visible = AtomicReference(true)
            instrumentation.runOnMainSync {
                visible.set(
                    activity.window.decorView.rootWindowInsets
                        ?.isVisible(WindowInsets.Type.ime()) == true,
                )
            }
            if (!visible.get()) return
            SystemClock.sleep(50L)
        } while (SystemClock.uptimeMillis() < deadline)
        error("Timed out waiting for the software keyboard to close before testing Back")
    }

    private fun waitForWorkResult(activity: MainActivity, expected: Boolean): Boolean {
        val deadline = SystemClock.uptimeMillis() + TIMEOUT
        do {
            val present = activity.activeSearchController().state.value.results.any {
                it.title == MANAGED_FIXTURE_LABEL && it.workBadged
            }
            if (present == expected) return true
            SystemClock.sleep(50L)
        } while (SystemClock.uptimeMillis() < deadline)
        return false
    }

    private fun waitForRuntimeReady(activity: MainActivity) {
        val deadline = SystemClock.uptimeMillis() + TIMEOUT
        do {
            if (activity.launcherRuntime().state.value.status != LauncherRuntimeStatus.LOADING) return
            SystemClock.sleep(50L)
        } while (SystemClock.uptimeMillis() < deadline)
        error("Timed out waiting for the production launcher runtime")
    }

    private fun waitForWorkProfile(
        activity: MainActivity,
        expected: ProfileAvailability,
    ): org.quicklauncher.host.runtime.profile.ProfileState {
        val deadline = SystemClock.uptimeMillis() + TIMEOUT
        do {
            activity.phaseFiveHost().profileState.value.profiles.singleOrNull {
                it.kind == ProfileKind.WORK && it.availability == expected
            }?.let { return it }
            SystemClock.sleep(100L)
        } while (SystemClock.uptimeMillis() < deadline)
        error("Timed out waiting for managed-work availability $expected")
    }

    private fun waitForRecreatedActivity(previous: MainActivity): MainActivity {
        val deadline = SystemClock.uptimeMillis() + TIMEOUT
        do {
            waitForResumedActivity()?.takeIf { it !== previous }?.let { return it }
            SystemClock.sleep(50L)
        } while (SystemClock.uptimeMillis() < deadline)
        error("Timed out waiting for an actually recreated MainActivity")
    }

    private fun waitForResumedActivity(): MainActivity? {
        val reference = AtomicReference<MainActivity>()
        instrumentation.runOnMainSync {
            ActivityLifecycleMonitorRegistry.getInstance()
                .getActivitiesInStage(Stage.RESUMED)
                .filterIsInstance<MainActivity>()
                .singleOrNull()
                ?.let(reference::set)
        }
        return reference.get()
    }

    private fun MainActivity.launcherRuntime(): LauncherRuntime =
        MainActivity::class.java.getDeclaredField("runtime").apply { isAccessible = true }.get(this) as LauncherRuntime

    private fun MainActivity.launcherStore(): LauncherStore =
        MainActivity::class.java.getDeclaredField("launcherStore").apply { isAccessible = true }.get(this) as LauncherStore

    private fun MainActivity.searchPresentations(): SearchPresentationSource =
        MainActivity::class.java.getDeclaredField("searchPresentations")
            .apply { isAccessible = true }
            .get(this) as SearchPresentationSource

    private fun MainActivity.phaseFiveHost(): PhaseFiveHost =
        MainActivity::class.java.getDeclaredField("phaseFiveHost")
            .apply { isAccessible = true }
            .get(this) as PhaseFiveHost

    @Suppress("UNCHECKED_CAST")
    private fun MainActivity.activeSearchController(): SearchPresentationController {
        val source = searchPresentations() as ComposeSearchPresentationSource
        val field = ComposeSearchPresentationSource::class.java.getDeclaredField("controllers")
            .apply { isAccessible = true }
        val owned = field.get(source) as Collection<SearchPresentationController>
        val controllers = synchronized(owned) {
            owned.toList()
        }
        return controllers.single()
    }

    private fun restoreRoleHolders(packageName: String, originalHolders: Set<String>) {
        if (packageName !in originalHolders) {
            shell("cmd role remove-role-holder --user $userId $HOME_ROLE $packageName")
        }
        originalHolders.forEach { holder ->
            require(PACKAGE_NAME.matches(holder)) { "Original Home holder is not shell-safe" }
            shell("cmd role add-role-holder --user $userId $HOME_ROLE $holder")
        }
        assertEquals(originalHolders, waitForRoleHolders(originalHolders))
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
        var current = roleHolders()
        do {
            current = roleHolders()
            if (current == expected) return current
            SystemClock.sleep(50L)
        } while (SystemClock.uptimeMillis() < deadline)
        return current
    }

    private fun shell(command: String): String =
        instrumentation.uiAutomation.executeShellCommand(command).use { descriptor -> descriptor.readText() }

    private fun ParcelFileDescriptor.readText(): String =
        FileInputStream(fileDescriptor).bufferedReader().use { it.readText() }

    private companion object {
        const val HOME_ROLE = "android.app.role.HOME"
        const val TIMEOUT = 20_000L
        const val QUERY = "security"
        const val MANAGED_FIXTURE_QUERY = "phase five managed work fixture"
        const val MANAGED_FIXTURE_LABEL = "Phase five managed work fixture"
        val MODULAR_TEMPLATE = ContributionId.parse("org.quicklauncher.template/modular")
        val SEARCH_BLOCK = ContributionId.parse("org.quicklauncher.block/search")
        val PACKAGE_NAME = Regex("[A-Za-z][A-Za-z0-9_]*(?:\\.[A-Za-z][A-Za-z0-9_]*)+")
    }
}
