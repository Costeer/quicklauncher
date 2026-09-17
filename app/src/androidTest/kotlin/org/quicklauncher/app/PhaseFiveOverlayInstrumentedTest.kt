package org.quicklauncher.app

import org.quicklauncher.host.runtime.contentItemId
import org.quicklauncher.host.runtime.phasefive.PhaseFiveHost
import org.quicklauncher.host.runtime.phasefive.PhaseFiveOverlay

import android.app.role.RoleManager
import android.content.Intent
import android.content.pm.LauncherApps
import android.os.ParcelFileDescriptor
import android.os.SystemClock
import android.os.UserHandle
import android.os.UserManager
import android.view.WindowManager
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
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.quicklauncher.contracts.domain.PackageName
import org.quicklauncher.contracts.domain.ShortcutId
import org.quicklauncher.host.data.preferences.OnboardingState
import org.quicklauncher.host.data.store.ContentItemKind
import org.quicklauncher.host.data.store.LauncherStore
import org.quicklauncher.host.runtime.LauncherRuntime
import org.quicklauncher.host.runtime.LauncherRuntimeStatus
import org.quicklauncher.host.runtime.catalog.AppLaunchResult
import org.quicklauncher.host.runtime.actions.ItemActionResult
import org.quicklauncher.host.runtime.folders.FolderOperationResult
import org.quicklauncher.host.runtime.profile.PrivateSpaceEntryPoint
import org.quicklauncher.host.runtime.profile.PrivateLaunchResult
import org.quicklauncher.host.runtime.profile.ProfileAvailability
import org.quicklauncher.host.runtime.profile.ProfileKind
import org.quicklauncher.host.runtime.profile.WorkModeResult
import org.quicklauncher.host.runtime.shortcuts.ResolvedShortcutPlacement
import org.quicklauncher.host.runtime.shortcuts.ShortcutLaunchResult
import org.quicklauncher.host.runtime.shortcuts.ShortcutPlacementCreation
import org.quicklauncher.host.runtime.shortcuts.ShortcutTargetIdentity

/** Production-activity coverage for Phase 5 host-owned transient overlays. */
@RunWith(AndroidJUnit4::class)
class PhaseFiveOverlayInstrumentedTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context = instrumentation.targetContext
    private val device = UiDevice.getInstance(instrumentation)
    private val roleManager = context.getSystemService(RoleManager::class.java)
    private val userId: Int by lazy {
        shell("am get-current-user").trim().toInt().also { require(it >= 0) }
    }

    @Test
    fun managedWorkProfileDiscoveryBadgingLaunchPauseResumeAndCallbackRestoration() {
        assumeTrue(
            "Managed-work verification requires explicit physical-device orchestration",
            InstrumentationRegistry.getArguments().getString("phase5ManagedWork") == "true",
        )
        val packageName = context.packageName
        val fixturePackageName = packageName
        val originalHolders = roleHolders()
        var activity: MainActivity? = null
        var workSerial: org.quicklauncher.contracts.domain.ProfileSerial? = null
        var placementId: org.quicklauncher.contracts.domain.ContentItemId? = null
        try {
            runBlocking {
                requireNotNull(
                    (context.applicationContext as QuicklauncherApplication).launcherPreferences,
                ).setOnboardingState(OnboardingState.COMPLETED)
            }
            shell("cmd role add-role-holder --user $userId $HOME_ROLE $packageName")
            assertTrue("Quicklauncher did not become the Home-role holder", waitForRoleHeld())
            activity = instrumentation.startActivitySync(
                Intent(context, MainActivity::class.java)
                    .setAction(Intent.ACTION_MAIN)
                    .addCategory(Intent.CATEGORY_LAUNCHER)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            ) as MainActivity
            var launched = activity
            waitForRuntimeReady(launched)

            val work = waitForWorkProfile(launched, ProfileAvailability.AVAILABLE)
            workSerial = work.serial
            val workUser = requireNotNull(
                context.getSystemService(UserManager::class.java)
                    .getUserForSerialNumber(work.serial.value),
            )
            assertTrue(
                "Android LauncherApps did not expose the managed-work fixture to the active Home app",
                context.getSystemService(LauncherApps::class.java)
                    .getActivityList(fixturePackageName, workUser)
                    .any { it.componentName.className == ManagedWorkFixtureActivity::class.java.name },
            )
            val personalBefore = launched.launcherRuntime().state.value.settingsApps
                .filterNot { it.workProfile }
                .mapTo(linkedSetOf()) { it.identity }
            assertTrue("The personal profile exposed no app for continuity coverage", personalBefore.isNotEmpty())
            val workApp = waitForSyntheticWorkApp(launched, work.serial, fixturePackageName)
            assertTrue("The production catalog omitted mandatory work-profile badging", workApp.workProfile)
            assertTrue(
                "The production safe layout omitted the accessible work badge",
                waitForText("${workApp.label}, Work"),
            )

            assertEquals(
                AppLaunchResult.Launched,
                runBlocking { launched.launcherRuntime().launch(workApp.identity) },
            )
            assertTrue(
                "The typed app launch did not foreground the managed-profile target",
                waitForTopActivityUser(userIdentifier(workUser), fixturePackageName),
            )
            device.pressHome()
            launched = requireNotNull(waitForResumedActivity()) {
                "Quicklauncher did not resume after the managed-profile app launch"
            }
            activity = launched
            waitForRuntimeReady(launched)

            val shortcutTarget = ShortcutTargetIdentity(
                work.serial,
                PackageName.parse(fixturePackageName),
                ShortcutId.parse(MANAGED_WORK_SHORTCUT_ID),
            )
            waitForWorkShortcut(launched, shortcutTarget)
            val created = runBlocking { launched.phaseFiveHost().createShortcutPlacement(shortcutTarget) }
            assertTrue(
                "The production host did not create the managed-profile shortcut placement",
                created is ShortcutPlacementCreation.Created,
            )
            placementId = (created as ShortcutPlacementCreation.Created).placement.id
            assertEquals(
                ShortcutLaunchResult.Launched,
                runBlocking { launched.phaseFiveHost().launchShortcut(placementId) },
            )
            assertTrue(
                "The typed shortcut launch did not foreground the managed-profile target",
                waitForTopActivityUser(userIdentifier(workUser), fixturePackageName),
            )
            device.pressHome()
            launched = requireNotNull(waitForResumedActivity()) {
                "Quicklauncher did not resume after the managed-profile shortcut launch"
            }
            activity = launched
            waitForRuntimeReady(launched)

            assertEquals(
                WorkModeResult.Requested,
                runBlocking { launched.phaseFiveHost().setWorkMode(work.serial, enabled = false) },
            )
            waitForWorkProfile(launched, ProfileAvailability.QUIET)
            waitForWorkMetadataRemoval(launched, work.serial)
            assertTrue(
                "Pausing work changed the personal-profile app collection",
                launched.launcherRuntime().state.value.settingsApps.any { it.identity in personalBefore },
            )

            assertEquals(
                WorkModeResult.Requested,
                runBlocking { launched.phaseFiveHost().setWorkMode(work.serial, enabled = true) },
            )
            waitForWorkProfile(launched, ProfileAvailability.AVAILABLE)
            waitForSyntheticWorkApp(launched, work.serial, fixturePackageName)
            val restoredShortcut = waitForWorkShortcut(launched, shortcutTarget)
            assertTrue(
                "The managed shortcut placement did not return after the availability callback",
                launched.phaseFiveHost().shortcutState.value.placements.any {
                    it.id == placementId && it is ResolvedShortcutPlacement.Available
                },
            )
            assertEquals(shortcutTarget, restoredShortcut.target)

            assertEquals(
                ItemActionResult.Applied,
                runBlocking { launched.phaseFiveHost().openItemActions(requireNotNull(placementId)) },
            )
            assertEquals(
                ItemActionResult.Applied,
                runBlocking {
                    launched.phaseFiveHost().executeItemAction(
                        org.quicklauncher.host.runtime.actions.ItemActionCommand.RemoveShortcut(
                            confirmed = true,
                        ),
                    )
                },
            )
            placementId = null
        } finally {
            if (activity != null) device.pressHome()
            val current = waitForResumedActivity() ?: activity
            if (current != null && workSerial != null) {
                runCatching {
                    val state = current.phaseFiveHost().profileState.value.profile(requireNotNull(workSerial))
                    if (state?.availability == ProfileAvailability.QUIET) {
                        runBlocking {
                            current.phaseFiveHost().setWorkMode(requireNotNull(workSerial), enabled = true)
                        }
                        waitForWorkProfile(current, ProfileAvailability.AVAILABLE)
                    }
                }
            }
            if (current != null && placementId != null) {
                runCatching {
                    if (runBlocking {
                            current.phaseFiveHost().openItemActions(requireNotNull(placementId))
                        } == ItemActionResult.Applied
                    ) {
                        runBlocking {
                            current.phaseFiveHost().executeItemAction(
                                org.quicklauncher.host.runtime.actions.ItemActionCommand.RemoveShortcut(
                                    confirmed = true,
                                ),
                            )
                        }
                    }
                }
            }
            current?.let {
                instrumentation.runOnMainSync {
                    if (!it.isFinishing) it.finishAndRemoveTask()
                }
            }
            restoreRoleHolders(packageName, originalHolders)
        }
    }

    @Test
    fun authenticatedPrivateSpaceUnlockLaunchAndRelockOnReferenceDevice() {
        if (InstrumentationRegistry.getArguments().getString("phase5PrivateAuth") != "true") return
        if (!deviceHasPrivateProfile()) return
        val packageName = context.packageName
        val originalHolders = roleHolders()
        var activity: MainActivity? = null
        try {
            runBlocking {
                requireNotNull(
                    (context.applicationContext as QuicklauncherApplication).launcherPreferences,
                ).setOnboardingState(OnboardingState.COMPLETED)
            }
            shell("cmd role add-role-holder --user $userId $HOME_ROLE $packageName")
            assertTrue("Quicklauncher did not become the Home-role holder", waitForRoleHeld())
            activity = instrumentation.startActivitySync(
                Intent(context, MainActivity::class.java)
                    .setAction(Intent.ACTION_MAIN)
                    .addCategory(Intent.CATEGORY_LAUNCHER)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            ) as MainActivity
            var launched = activity
            waitForRuntimeReady(launched)
            if (waitForPrivateProfile(launched).availability == ProfileAvailability.AVAILABLE) {
                runBlocking { launched.phaseFiveHost().setPrivateSpaceLocked(locked = true) }
                waitForPrivateAvailability(launched, ProfileAvailability.LOCKED, TIMEOUT)
            }
            assertEquals(ProfileAvailability.LOCKED, waitForPrivateProfile(launched).availability)
            launched.phaseFiveHost().openPrivateSpace()
            requireNotNull(
                device.wait(Until.findObject(By.text("Unlock Private Space")), TIMEOUT),
            ).click()

            val (resumedAfterAuthentication, available) = waitForPrivateAvailabilityFromResumed(
                ProfileAvailability.AVAILABLE,
                PRIVATE_AUTH_TIMEOUT,
            )
            launched = resumedAfterAuthentication
            activity = launched
            val privateItem = launched.phaseFiveHost().profileState.value.privateSpace.items.firstOrNull()
            if (privateItem != null) {
                assertEquals(
                    PrivateLaunchResult.LAUNCHED,
                    runBlocking { launched.phaseFiveHost().launchPrivate(privateItem.identity) },
                )
                device.pressHome()
                assertCurrentPackage(packageName, "Home did not return from a private activity")
                launched = requireNotNull(waitForResumedActivity()) {
                    "Quicklauncher did not resume after returning from a private activity"
                }
                activity = launched
                waitForRuntimeReady(launched)
            }
            assertEquals(available.serial, launched.phaseFiveHost().profileState.value.privateSpace.profile)

            runBlocking { launched.phaseFiveHost().setPrivateSpaceLocked(locked = true) }
            waitForPrivateAvailability(launched, ProfileAvailability.LOCKED, TIMEOUT)
            assertTrue(launched.phaseFiveHost().profileState.value.privateSpace.items.isEmpty())
            assertTrue(
                launched.launcherRuntime().state.value.settingsApps.none {
                    it.identity.profile == available.serial
                },
            )
        } finally {
            (waitForResumedActivity() ?: activity)?.let { current ->
                if (current.phaseFiveHost().profileState.value.privateSpace.availability ==
                    ProfileAvailability.AVAILABLE
                ) {
                    runCatching {
                        runBlocking { current.phaseFiveHost().setPrivateSpaceLocked(locked = true) }
                    }
                    runCatching {
                        waitForPrivateAvailability(current, ProfileAvailability.LOCKED, TIMEOUT)
                    }
                }
                instrumentation.runOnMainSync {
                    if (!current.isFinishing) current.finishAndRemoveTask()
                }
            }
            restoreRoleHolders(packageName, originalHolders)
        }
    }

    @Test
    fun lockedPrivateSpaceIsSanitizedAndUsesTheSecureProductionOverlay() {
        if (!deviceHasPrivateProfile()) return
        val packageName = context.packageName
        val originalHolders = roleHolders()
        var activity: MainActivity? = null
        try {
            runBlocking {
                requireNotNull(
                    (context.applicationContext as QuicklauncherApplication).launcherPreferences,
                ).setOnboardingState(OnboardingState.COMPLETED)
            }
            shell("cmd role add-role-holder --user $userId $HOME_ROLE $packageName")
            assertTrue("Quicklauncher did not become the Home-role holder", waitForRoleHeld())
            activity = instrumentation.startActivitySync(
                Intent(context, MainActivity::class.java)
                    .setAction(Intent.ACTION_MAIN)
                    .addCategory(Intent.CATEGORY_LAUNCHER)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            ) as MainActivity
            val launched = activity
            waitForRuntimeReady(launched)
            val privateProfile = waitForPrivateProfile(launched)
            assertEquals(ProfileAvailability.LOCKED, privateProfile.availability)
            assertTrue(launched.phaseFiveHost().profileState.value.privateSpace.items.isEmpty())
            assertTrue(
                launched.launcherRuntime().state.value.settingsApps.none {
                    it.identity.profile == privateProfile.serial
                },
            )

            launched.phaseFiveHost().openPrivateSpace()
            if (privateProfile.privateSpaceEntryPoint == PrivateSpaceEntryPoint.HIDDEN) {
                assertEquals(PhaseFiveOverlay.NONE, launched.phaseFiveHost().overlay.value)
            } else {
                assertEquals(
                    PhaseFiveOverlay.PRIVATE_SPACE,
                    waitForOverlay(launched, PhaseFiveOverlay.PRIVATE_SPACE),
                )
                assertTrue("Locked Private Space explanation was absent", waitForText("Private Space is locked"))
                assertTrue(
                    "Private Space overlay did not enable screenshot protection",
                    launched.window.attributes.flags and WindowManager.LayoutParams.FLAG_SECURE != 0,
                )
                device.pressHome()
                assertEquals(
                    PhaseFiveOverlay.NONE,
                    waitForOverlay(launched, PhaseFiveOverlay.NONE),
                )
                assertCurrentPackage(packageName, "Repeated Home left the secure launcher overlay")
            }
        } finally {
            activity?.let { current ->
                instrumentation.runOnMainSync {
                    if (!current.isFinishing) current.finishAndRemoveTask()
                }
            }
            restoreRoleHolders(packageName, originalHolders)
        }
    }

    @Test
    fun folderAndItemActionOverlaysDismissThroughBackHomeAndActivityRecreation() {
        val packageName = context.packageName
        require(PACKAGE_NAME.matches(packageName)) { "Application ID is not shell-safe" }
        val originalHolders = roleHolders()
        val folderLabel = "Device overlay ${System.currentTimeMillis()}"
        var createdFolderId: org.quicklauncher.contracts.domain.ContentItemId? = null
        var activity: MainActivity? = null

        try {
            runBlocking {
                requireNotNull(
                    (context.applicationContext as QuicklauncherApplication).launcherPreferences,
                ).setOnboardingState(OnboardingState.COMPLETED)
            }
            shell("cmd role add-role-holder --user $userId $HOME_ROLE $packageName")
            assertTrue("Quicklauncher did not become the Home-role holder", waitForRoleHeld())
            HomeEntryRecorder.resetForTests()

            activity = instrumentation.startActivitySync(
                Intent(context, MainActivity::class.java)
                    .setAction(Intent.ACTION_MAIN)
                    .addCategory(Intent.CATEGORY_LAUNCHER)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            ) as MainActivity
            val firstActivity = activity
            val firstHost = firstActivity.phaseFiveHost()
            val firstStore = firstActivity.launcherStore()
            waitForRuntimeReady(firstActivity)

            val beforeIds = runBlocking { firstStore.read() }.contentItems.mapTo(mutableSetOf()) { it.id }
            assertEquals(FolderOperationResult.Applied, runBlocking { firstHost.createFolder(folderLabel) })
            createdFolderId = runBlocking { firstStore.read() }.contentItems.single { item ->
                item.id !in beforeIds && item.kind == ContentItemKind.FOLDER && item.encoded == folderLabel
            }.id

            assertEquals(FolderOperationResult.Applied, runBlocking { firstHost.openFolder(createdFolderId) })
            assertTrue("The production folder overlay did not open", waitForText("Delete folder"))
            device.pressBack()
            assertTrue(
                "Back dispatch did not close the host-owned folder overlay",
                device.wait(Until.gone(By.text("Delete folder")), TIMEOUT),
            )
            assertCurrentPackage(packageName, "Back left the launcher root instead of closing its overlay")

            assertEquals(FolderOperationResult.Applied, runBlocking { firstHost.openFolder(createdFolderId) })
            assertTrue("The folder overlay did not reopen before recreation", waitForText("Delete folder"))
            instrumentation.runOnMainSync(firstActivity::recreate)
            val recreatedActivity = waitForRecreatedActivity(firstActivity)
            activity = recreatedActivity
            waitForRuntimeReady(recreatedActivity)
            assertEquals(
                "Activity recreation did not restore the open durable folder overlay",
                PhaseFiveOverlay.FOLDER,
                waitForOverlay(recreatedActivity, PhaseFiveOverlay.FOLDER),
            )
            assertTrue("The restored folder overlay was not rendered", waitForText("Delete folder"))
            device.pressBack()
            assertEquals(
                "Back did not dismiss the folder restored after recreation",
                PhaseFiveOverlay.NONE,
                waitForOverlay(recreatedActivity, PhaseFiveOverlay.NONE),
            )
            assertTrue(
                "The restored folder surface remained after Back",
                device.wait(Until.gone(By.text("Delete folder")), TIMEOUT),
            )

            val firstHomeBaseline = HomeEntryRecorder.snapshot().homeEntries
            device.pressHome()
            assertCurrentPackage(packageName, "The first Home entry did not foreground Quicklauncher")
            waitForRecorder { it.homeEntries > firstHomeBaseline }
            val homeActivity = requireNotNull(waitForResumedActivity()) {
                "The production Home activity did not reach RESUMED"
            }
            activity = homeActivity
            waitForRuntimeReady(homeActivity)
            val runtime = homeActivity.launcherRuntime()
            val actionableApp = runtime.state.value.settingsApps.firstOrNull()
                ?: error("The attached device exposed no sanitized app for item-action coverage")
            val actionTitle = "Actions for ${actionableApp.label}"
            assertEquals(
                ItemActionResult.Applied,
                runBlocking { homeActivity.phaseFiveHost().openItemActions(contentItemId(actionableApp)) },
            )
            assertTrue("The production item-action overlay did not open", waitForText(actionTitle))
            val homeEntryBefore = HomeEntryRecorder.snapshot()
            device.pressHome()
            assertCurrentPackage(packageName, "Repeated Home did not return to Quicklauncher")
            assertTrue(
                "Repeated Home did not close the host-owned item-action overlay",
                device.wait(Until.gone(By.text(actionTitle)), TIMEOUT),
            )
            waitForRecorder {
                it.homeNewIntents > homeEntryBefore.homeNewIntents
            }
            assertTrue(
                "Repeated Home replaced MainActivity instead of dismissing the overlay in place",
                resumedActivity() === homeActivity,
            )
        } finally {
            val folderId = createdFolderId
            if (folderId != null) {
                runCatching {
                    activity?.phaseFiveHost()?.let { host ->
                        runBlocking { host.deleteFolder(folderId) }
                    }
                }
            }
            activity?.let { current ->
                instrumentation.runOnMainSync {
                    if (!current.isFinishing) current.finishAndRemoveTask()
                }
            }
            restoreRoleHolders(packageName, originalHolders)
        }
    }

    private fun waitForRecreatedActivity(previous: MainActivity): MainActivity {
        val deadline = SystemClock.uptimeMillis() + TIMEOUT
        do {
            val current = resumedActivity()
            if (current != null && current !== previous) return current
            SystemClock.sleep(50L)
        } while (SystemClock.uptimeMillis() < deadline)
        error("Timed out waiting for an actually recreated MainActivity")
    }

    private fun resumedActivity(): MainActivity? {
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

    private fun waitForResumedActivity(): MainActivity? {
        val deadline = SystemClock.uptimeMillis() + TIMEOUT
        do {
            resumedActivity()?.let { return it }
            SystemClock.sleep(50L)
        } while (SystemClock.uptimeMillis() < deadline)
        return null
    }

    private fun waitForOverlay(activity: MainActivity, expected: PhaseFiveOverlay): PhaseFiveOverlay {
        val deadline = SystemClock.uptimeMillis() + TIMEOUT
        var current: PhaseFiveOverlay
        do {
            current = activity.phaseFiveHost().overlay.value
            if (current == expected) return current
            SystemClock.sleep(50L)
        } while (SystemClock.uptimeMillis() < deadline)
        return current
    }

    private fun MainActivity.phaseFiveHost(): PhaseFiveHost =
        MainActivity::class.java.getDeclaredField("phaseFiveHost")
            .apply { isAccessible = true }
            .get(this) as PhaseFiveHost

    private fun MainActivity.launcherStore(): LauncherStore =
        MainActivity::class.java.getDeclaredField("launcherStore")
            .apply { isAccessible = true }
            .get(this) as LauncherStore

    private fun MainActivity.launcherRuntime(): LauncherRuntime =
        MainActivity::class.java.getDeclaredField("runtime")
            .apply { isAccessible = true }
            .get(this) as LauncherRuntime

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
        availability: ProfileAvailability,
    ): org.quicklauncher.host.runtime.profile.ProfileState {
        val deadline = SystemClock.uptimeMillis() + TIMEOUT
        do {
            activity.phaseFiveHost().profileState.value.profiles
                .singleOrNull { it.kind == ProfileKind.WORK && it.availability == availability }
                ?.let { return it }
            SystemClock.sleep(100L)
        } while (SystemClock.uptimeMillis() < deadline)
        error("Timed out waiting for managed-work availability $availability")
    }

    private fun waitForSyntheticWorkApp(
        activity: MainActivity,
        profile: org.quicklauncher.contracts.domain.ProfileSerial,
        packageName: String,
    ): org.quicklauncher.host.runtime.LauncherApp {
        val deadline = SystemClock.uptimeMillis() + TIMEOUT
        do {
            activity.launcherRuntime().state.value.settingsApps.singleOrNull {
                    it.identity.profile == profile &&
                    it.identity.packageName.value == packageName &&
                    it.identity.activityName.value == ManagedWorkFixtureActivity::class.java.name
            }?.let { return it }
            SystemClock.sleep(100L)
        } while (SystemClock.uptimeMillis() < deadline)
        error("Timed out waiting for the synthetic managed-work app")
    }

    private fun waitForWorkShortcut(
        activity: MainActivity,
        target: ShortcutTargetIdentity,
    ): org.quicklauncher.host.runtime.shortcuts.DiscoveredShortcut {
        val deadline = SystemClock.uptimeMillis() + TIMEOUT
        do {
            activity.phaseFiveHost().shortcutState.value.availableShortcuts
                .singleOrNull { it.target == target }
                ?.let { return it }
            SystemClock.sleep(100L)
        } while (SystemClock.uptimeMillis() < deadline)
        error("Timed out waiting for the synthetic managed-work shortcut")
    }

    private fun waitForWorkMetadataRemoval(
        activity: MainActivity,
        profile: org.quicklauncher.contracts.domain.ProfileSerial,
    ) {
        val deadline = SystemClock.uptimeMillis() + TIMEOUT
        do {
            val runtimeRemoved = activity.launcherRuntime().state.value.settingsApps.none {
                it.identity.profile == profile
            }
            val shortcutsRemoved = activity.phaseFiveHost().shortcutState.value.let { snapshot ->
                snapshot.availableShortcuts.none { it.target.profile == profile } &&
                    snapshot.placements.none {
                        it.target.profile == profile && it is ResolvedShortcutPlacement.Available
                    }
            }
            if (runtimeRemoved && shortcutsRemoved) return
            SystemClock.sleep(100L)
        } while (SystemClock.uptimeMillis() < deadline)
        error("Managed-work metadata remained after the availability callback")
    }

    private fun waitForTopActivityUser(expectedUserId: Int, expectedPackageName: String): Boolean {
        val deadline = SystemClock.uptimeMillis() + TIMEOUT
        do {
            val matched = shell("dumpsys activity activities").lineSequence().any { line ->
                "ResumedActivity" in line &&
                    " u$expectedUserId " in line &&
                    expectedPackageName in line
            }
            if (matched) return true
            SystemClock.sleep(100L)
        } while (SystemClock.uptimeMillis() < deadline)
        return false
    }

    private fun userIdentifier(user: UserHandle): Int {
        val encoded = user.toString()
        require(encoded.startsWith(USER_HANDLE_PREFIX) && encoded.endsWith(USER_HANDLE_SUFFIX)) {
            "Android returned an unrecognized user handle"
        }
        return requireNotNull(
            encoded.substring(USER_HANDLE_PREFIX.length, encoded.length - USER_HANDLE_SUFFIX.length)
                .toIntOrNull(),
        ) { "Android returned an unrecognized user handle" }
    }

    private fun waitForPrivateProfile(activity: MainActivity): org.quicklauncher.host.runtime.profile.ProfileState {
        val deadline = SystemClock.uptimeMillis() + TIMEOUT
        do {
            activity.phaseFiveHost().profileState.value.profiles
                .singleOrNull { it.kind == ProfileKind.PRIVATE }
                ?.let { return it }
            SystemClock.sleep(50L)
        } while (SystemClock.uptimeMillis() < deadline)
        error("Timed out waiting for a classified private profile")
    }

    private fun deviceHasPrivateProfile(): Boolean = shell("cmd user list -v")
        .lineSequence()
        .any { it.contains("type=profile.PRIVATE") }

    private fun waitForPrivateAvailability(
        activity: MainActivity,
        expected: ProfileAvailability,
        timeout: Long,
    ): org.quicklauncher.host.runtime.profile.ProfileState {
        val deadline = SystemClock.uptimeMillis() + timeout
        do {
            activity.phaseFiveHost().profileState.value.profiles
                .singleOrNull { it.kind == ProfileKind.PRIVATE && it.availability == expected }
                ?.let { return it }
            SystemClock.sleep(100L)
        } while (SystemClock.uptimeMillis() < deadline)
        error("Timed out waiting for Private Space availability $expected")
    }

    private fun waitForPrivateAvailabilityFromResumed(
        expected: ProfileAvailability,
        timeout: Long,
    ): Pair<MainActivity, org.quicklauncher.host.runtime.profile.ProfileState> {
        val deadline = SystemClock.uptimeMillis() + timeout
        do {
            val activity = resumedActivity()
            val profile = activity?.phaseFiveHost()?.profileState?.value?.profiles
                ?.singleOrNull { it.kind == ProfileKind.PRIVATE && it.availability == expected }
            if (activity != null && profile != null) return activity to profile
            SystemClock.sleep(100L)
        } while (SystemClock.uptimeMillis() < deadline)
        error("Timed out waiting for resumed Private Space availability $expected")
    }

    private fun waitForText(text: String): Boolean =
        device.wait(Until.hasObject(By.text(text)), TIMEOUT)

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
        error("Timed out waiting for the production Home-entry callback")
    }

    private fun restoreRoleHolders(packageName: String, originalHolders: Set<String>) {
        if (packageName !in originalHolders) {
            shell("cmd role remove-role-holder --user $userId $HOME_ROLE $packageName")
        }
        originalHolders.forEach { holder ->
            require(PACKAGE_NAME.matches(holder)) { "Original Home holder is not shell-safe" }
            shell("cmd role add-role-holder --user $userId $HOME_ROLE $holder")
        }
        assertEquals(
            "The test must restore every prior Home-role holder",
            originalHolders,
            waitForRoleHolders(originalHolders),
        )
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
        const val TIMEOUT = 15_000L
        const val PRIVATE_AUTH_TIMEOUT = 120_000L
        const val USER_HANDLE_PREFIX = "UserHandle{"
        const val USER_HANDLE_SUFFIX = "}"
        val PACKAGE_NAME = Regex("[A-Za-z][A-Za-z0-9_]*(?:\\.[A-Za-z][A-Za-z0-9_]*)+")
    }
}
