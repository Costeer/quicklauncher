# Phase 6 search evidence

Checked by the repository owner on 2026-09-15. The owner-supplied record below documents the implementation and device runs that preceded independent validation. Independent validation later found defects and repaired them; the post-repair addendum supersedes this record wherever it describes the current code or current local totals. The owner-supplied physical-device results remain part of the record and were reviewed, not independently rerun.

## Implemented architecture

- `:host:runtime` owns one bounded search session per visible presentation, query validation and debounce, generation replacement, concurrent provider fan-out, per-provider first-result timeouts, cancellation, stale-output rejection, deterministic immutable snapshots, provider-local state, ranking, history policy, and typed activation.
- Provider flows remain collected after their first result so same-generation refinements are accepted. A later flow failure removes only that provider. Query replacement, provider denial/failure/timeout, profile invalidation, and presentation close release the affected ephemeral target mappings.
- `:host:platform` prepares candidates from the app catalog, shortcut coordinator, profile coordinator, registered commands, contacts, persisted Storage Access Framework grants, public Settings routes, the versioned optional GrapheneOS catalog, safe HTTPS web actions, and recovery information. `:app` wires these host services to generated contributions. Profile-sensitive app data requires the app catalog and live profile coordinator to agree before it can cross the host boundary.
- `:modules:search:core` contributes nine generated-registry providers. `:modules:command:core` contributes typed launcher commands. Neither module depends on host or Android code, and providers and commands do not call one another.
- The search block consumes only immutable host-prepared presentation state and emits typed query, activation, dismissal, and focus actions. Session ownership stays in the composition host.
- Room schema 7 stores at most 256 local app or shortcut launch targets. It cannot represent a query, contact, file, web, or Private Space entry. Proto DataStore owns the typed enabled-provider set and its initialization marker.
- Contacts require an explicit runtime-permission action. Files use only persisted picker grants. Both fail closed, disable only themselves after access loss, and purge visible and ephemeral current results. The GrapheneOS provider is manual and build-versioned. Web actions construct exact HTTPS destinations with encoded query parameters and never retrieve or execute remote code.

The platform conclusions that constrain these adapters are in [Phase 6 search platform behavior](../research/phase-6-search-platform-behavior.md).

## Automated coverage

Focused JVM, Robolectric, and contract tests cover:

- query bounds, redacted string forms, debounce, replacement, cancellation, stale and post-close emissions;
- healthy partial results, slow-provider isolation, candidate-preparation timeout, crash, malformed output, same-generation refinement, and post-result flow failure;
- immutable snapshots and inputs, deterministic exact/prefix/token/fuzzy ranking, tie-breaking, duplicate merging, time decay, backward clocks, malformed history, and bounded app/shortcut-only retention;
- provider denial, grant, revocation, recovery, provider-local preference mutation, profile changes, mandatory work badging, and stale-target purge;
- typed target and registered-command revalidation, capability/context/result-kind checks, and no side effect after missing or stale targets;
- generated registry aggregation and contribution contracts for all nine providers and two commands;
- search presentation creation/removal, Enter, Escape, D-pad traversal, screen-reader semantics, work badging, normal and large-text rendering;
- Room 1-to-7 migration, adjacent migration, reopen, injected rollback, malformed-row handling, and preservation of unrelated opaque bytes.

The owner's pre-repair authoritative local command passed in 123 seconds: 3,807 actionable tasks, with 94 executed, 5 from cache, and 3,708 up-to-date. The generated XML reports contain 1,190 tests, 0 failures, 0 errors, and 0 skips. This command includes full debug and release builds, unit and Robolectric tests, Android lint, contribution contracts, generated registries, `checkModuleBoundaries`, `verifyNoGoogleDependencies`, and `buildHealth`.

All 23 distinct `licensee` tasks named by CI, including the two Phase 6 contribution modules, passed explicitly in 23 seconds; their aggregate tasks expanded to 38 actionable tasks, all up-to-date. The six Paparazzi verification tasks passed in 37 seconds with 285 actionable tasks (1 executed, 5 from cache, 279 up-to-date), including the inspected search normal and large-text baselines. `actionlint` and `git diff --check` passed in 1 second after the final code changes.

## Device evidence

### API 35 AOSP

The full-phone AOSP image is:

```text
AVD: quicklauncher_aosp_full35_phase5
Fingerprint: Android/sdk_phone64_x86_64/emu64x:15/AE3A.240806.019/12368160:userdebug/test-keys
Android/API: 15/35
Original Home: com.android.launcher3
```

The owner-supplied pre-repair five-module matrix passed in 3m53s: 390 actionable tasks, with 5 executed and 385 up-to-date. Its XML reports contain 58 tests, 55 passed, 3 skipped, and 0 failed:

- Room/data: 17 passed, 0 skipped;
- platform: 10 passed, 1 shortcut-host-authority skip;
- runtime: 5 passed, 0 skipped;
- editor: 4 passed, 0 skipped;
- production app: 19 passed, 2 explicitly gated managed-profile skips.

The matrix includes real contact grant and synthetic contact cleanup, persisted file grant and release, public and GrapheneOS Settings-route resolution, web URI rejection, production search Back/recreation/repeated-Home behavior, and all earlier persistence, profile, notification, widget, shortcut, editor, and recovery tests. A Home-role dialog test failed once during the first sequence, then passed alone and the unchanged complete matrix passed; the passing complete rerun is the recorded gate.

### GrapheneOS reference device

The required reference device was the Pixel 10a recorded in [Phase 5 device evidence](phase-5-device-evidence.md), on fingerprint `google/stallion/stallion:17/CP2A.260805.005/2026091001:user/release-keys`, Android 17/API 37, security patch `2026-09-01`.

The owner-supplied pre-repair five-module matrix was pinned to serial `64221JEA319616` and passed in 221 seconds: 390 actionable tasks, with 5 executed and 385 up-to-date. Its XML reports contain 47 tests, 44 passed, 3 skipped, and 0 failed:

- Room/data: 17 passed, 0 skipped;
- platform: 10 passed, 1 shortcut-host-authority skip;
- runtime and editor: 0 tests, as their released Espresso surface suites are SDK-suppressed above API 36 and pass on the required API 35 matrix;
- production app: 17 passed, 2 explicitly gated managed-profile skips.

The exact matrix command was:

```bash
ANDROID_SERIAL=64221JEA319616 ./gradlew \
  :host:data:connectedDebugAndroidTest \
  :host:platform:connectedDebugAndroidTest \
  :host:runtime:connectedDebugAndroidTest \
  :host:editor:connectedDebugAndroidTest \
  :app:connectedStableDebugAndroidTest \
  --no-daemon --no-configuration-cache --console=plain
```

The managed-profile cases were then enabled explicitly against one disposable profile created through Android's visible `ACTION_PROVISION_MANAGED_PROFILE` flow. Android reported the profile as `profile.MANAGED`, `RUNNING_UNLOCKED`, enabled, visible, and owned by the debug-only Quicklauncher DPC. The Phase 5 app/shortcut discovery, work badging, typed launch, pause, removal, resume, and restoration test passed in 9.332 seconds. The Phase 6 active-search work badging, quiet-mode removal, callback-driven restoration, and personal continuity test passed in 6.525 seconds. Both commands ran one test with zero failures or skips; shell elapsed times were 12 and 9 seconds respectively.

```bash
adb shell am instrument --user 0 -w \
  -e class org.quicklauncher.app.PhaseFiveOverlayInstrumentedTest#managedWorkProfileDiscoveryBadgingLaunchPauseResumeAndCallbackRestoration \
  -e phase5ManagedWork true \
  org.quicklauncher.test/androidx.test.runner.AndroidJUnitRunner

adb shell am instrument --user 0 -w \
  -e class org.quicklauncher.app.PhaseSixSearchLifecycleInstrumentedTest#activeSearchRemovesAndRestoresManagedWorkResultsOnProfileCallbacks \
  -e phase6ManagedWork true \
  org.quicklauncher.test/androidx.test.runner.AndroidJUnitRunner
```

One initial managed-work test invocation failed after 17.448 seconds because orchestration started it after accepting consent but before pressing Android's second visible `Next` button. The profile existed but was correctly classified unavailable because it remained disabled. Three incomplete disposable attempts used while isolating that state were type-checked and removed. The final flow completed both platform screens and passed. Diagnosis also aligned the debug DPC fixture with Android's current TestDPC pattern: the protected policy-compliance activity stays separate from an unprotected `ACTION_PROVISIONING_SUCCESSFUL` activity, and both converge on idempotent synthetic fixture initialization. This changes only the physical-test fixture, not production profile policy.

## Restoration and data handling

The API 35 run restored Launcher3 as Home and the original empty notification-listener value. The final audit found no managed or private profile, Quicklauncher package, or Quicklauncher widget reference before the emulator was stopped.

The GrapheneOS baseline and final verbose user-record SHA-256 were both `7ff6a88eb7c15658baedd868a5e28618fe969699574d8b1fb47fd54b8101d71c`. The active user IDs returned to `0,10,11,12`; there was no managed profile; the one Private Space profile retained `QUIET_MODE`; and the two full secondary users remained present. Home was exactly `com.android.launcher3`, and the enabled notification-listener value was exactly `com.android.launcher3/com.android.launcher3.notification.NotificationListener`. Quicklauncher and its test package, widgets, persisted URI grants, synthetic contact rows, disposable profiles, and temporary device-side UI dumps were absent. The ADB reverse list remained at its original empty value.

Only synthetic or disposable labels and values were used. This record contains no personal contact name, file name, app label, shortcut identity, user name, web query, notification content, or Private Space content.

## Privacy and boundary audit

- Search queries and protected result contents have redacted diagnostic string forms. No search path calls a logger, analytics API, standard-output printer, or diagnostic store.
- Search history types can encode only app and shortcut targets and are bounded in memory and Room. Raw queries and contact, file, web, and Private Space data have no durable representation.
- Contact cursors, file cursors, `Intent`, `Uri`, `ComponentName`, `UserHandle`, Android cancellation signals, and Android callbacks remain in `:host:platform` or entry/instrumentation adapters.
- Provider candidates, results, sessions, list sizes, cursor reads, directory traversal, target maps, flow lifetimes, timeouts, and web URI length are bounded or presentation-owned.
- Profile classification precedes candidate preparation. A live profile transition purges stale target mappings; an old work target cannot revive when the profile becomes available again without a new host preparation.
- The module-boundary, no-Google-dependency, dependency-health, and license gates pass without exclusions or weakened checks.

## Post-repair independent verification

Independent validation repaired the session-visibility lifecycle, host-side matching, history profile encoding, protected-candidate cache race, Settings fallback execution, presentation ownership, typed command-result handling, contact and file access handling, file-provider search, contribution ownership, and Open Search focus behavior. Public-seam regression tests cover each repaired path.

The final post-repair local gate passed in 2m 28s: 3,807 actionable tasks, with 187 executed, 17 from cache, and 3,603 up-to-date. Its XML reports contain 1,195 tests, 0 failures, 0 errors, and 0 skips. `checkModuleBoundaries`, `verifyNoGoogleDependencies`, and `buildHealth` all passed without suppressions or weakened checks.

The post-repair Paparazzi gate passed in 1m 34s: 285 actionable tasks, with 6 executed and 279 up-to-date. The six selected modules contain 275 passing tests. Independent visual inspection found the normal search render intact and the large-text render reflowing without horizontal clipping or loss of the work badge. All 38 expanded CI license tasks passed in 11s. `actionlint` passed in 0.36s, and `git diff --check` passed in 0.02s.

The final current-worktree API 35 matrix was pinned to `emulator-5554` and passed in 5m 9s: 390 actionable tasks, with 5 executed and 385 up-to-date. Its XML reports contain 62 tests, 59 passed, 3 skipped, 0 failed, and 0 errors:

- Room/data: 17 passed, 0 skipped;
- platform: 12 passed, 1 shortcut-host-authority skip;
- runtime: 5 passed, 0 skipped;
- editor: 4 passed, 0 skipped;
- production app: 21 passed, 2 explicitly gated managed-profile skips.

The exact command was:

```bash
ANDROID_SERIAL=emulator-5554 ./gradlew \
  :host:data:connectedDebugAndroidTest \
  :host:platform:connectedDebugAndroidTest \
  :host:runtime:connectedDebugAndroidTest \
  :host:editor:connectedDebugAndroidTest \
  :app:connectedStableDebugAndroidTest \
  --no-daemon --no-configuration-cache --console=plain
```

The first independent matrix exposed one AVD-dependent lifecycle-test defect: its Back press could dismiss the software keyboard instead of reaching the predictive-Back callback. A focused reproduction failed in 1m 56s. The test now closes the IME without consuming Back and enters through the actual Home role before checking Back, recreation, and repeated Home. The repaired focused test passed in 1m 51s: 261 actionable tasks, with 1 executed and 260 up-to-date; 1 test passed with no failure, error, or skip.

After the first Phase 7 theme-contract slice, a complete matrix attempt hit the previously documented transient Home-role dialog failure: the platform button did not appear within the test timeout. That attempt failed in 5m 15s with 390 actionable tasks; its 62 tests contained 1 failure, 3 skips, and no errors. The exact Home-role flow then passed unchanged as 1 test in 1m 27s with 261 actionable tasks, and the unchanged complete matrix passed as recorded above. The final restoration audit found Launcher3 holding Home, an empty notification-listener value, only the Owner user, no device owner, and no Quicklauncher package, widget, or provider reference before the emulator was stopped.

At the repository owner's direction, the GrapheneOS matrix, managed-work-profile flows, Private Space flows, intended-installer notification recovery, and restoration audit were not rerun. Their recorded results above are owner-supplied evidence reviewed, not independently rerun. The exclusion is not recorded as a failure or as an independent pass.

## Remaining required evidence

No required non-GrapheneOS Phase 6 evidence remains outstanding. No local, visual, license, workflow, diff, privacy, module-boundary, or API 35 defect remains known. Authority-dependent shortcut-host behavior and the two managed-profile cases remain truthfully recorded as skips in the independent API 35 result; the separately run owner-supplied managed-profile evidence remains recorded above.

The [independent validation prompt](phase-6-validation-prompt.md) reruns the code, local, visual, dependency, and API 35 checks. At the repository owner's direction, it reviews the recorded GrapheneOS evidence without repeating device-specific work.

## Changed-file manifest

The Phase 5 gate repair, Phase 6 implementation, tests, and evidence changed exactly these repository files:

```text
.github/workflows/ci.yml
app/build.gradle.kts
app/src/androidTest/kotlin/org/quicklauncher/app/PhaseFiveOverlayInstrumentedTest.kt
app/src/androidTest/kotlin/org/quicklauncher/app/FileAuthorizationContractInstrumentedTest.kt
app/src/androidTest/kotlin/org/quicklauncher/app/PhaseSixSearchLifecycleInstrumentedTest.kt
app/src/debug/AndroidManifest.xml
app/src/debug/java/org/quicklauncher/app/ManagedWorkFixtureAdminReceiver.kt
app/src/debug/res/xml/phase_five_managed_work_admin.xml
app/src/main/java/org/quicklauncher/app/MainActivity.kt
app/src/main/java/org/quicklauncher/app/ProductionCompositionSurface.kt
build.gradle.kts
contracts/contribution/src/main/kotlin/org/quicklauncher/contracts/contribution/ContributionInterfaces.kt
contracts/contribution/src/main/kotlin/org/quicklauncher/contracts/contribution/Descriptors.kt
contracts/contribution/src/test/kotlin/org/quicklauncher/contracts/contribution/SearchContractTest.kt
contracts/ui/src/main/kotlin/org/quicklauncher/contracts/ui/ImmutableRenderModels.kt
docs/architecture/launcher-architecture-plan.md
docs/contracts/search-provider.md
docs/research/phase-6-search-platform-behavior.md
docs/status/current-state-overview.html
docs/status/current-state.md
docs/status/phase-5-blocker-and-review-prompt.md
docs/status/phase-5-device-evidence.md
docs/status/phase-6-search-evidence.md
docs/status/phase-6-validation-prompt.md
host/data/schemas/org.quicklauncher.host.data.room.LauncherDatabase/7.json
host/data/src/androidTest/kotlin/org/quicklauncher/host/data/room/RoomLauncherStoreInstrumentedTest.kt
host/data/src/main/kotlin/org/quicklauncher/host/data/preferences/FileLauncherPreferencesStore.kt
host/data/src/main/kotlin/org/quicklauncher/host/data/preferences/LauncherPreferences.kt
host/data/src/main/kotlin/org/quicklauncher/host/data/preferences/LauncherPreferencesProtoCodec.kt
host/data/src/main/kotlin/org/quicklauncher/host/data/room/LauncherDao.kt
host/data/src/main/kotlin/org/quicklauncher/host/data/room/LauncherDatabase.kt
host/data/src/main/kotlin/org/quicklauncher/host/data/room/LauncherEntities.kt
host/data/src/main/kotlin/org/quicklauncher/host/data/room/LauncherMigrations.kt
host/data/src/main/kotlin/org/quicklauncher/host/data/room/RoomLauncherStore.kt
host/data/src/main/kotlin/org/quicklauncher/host/data/store/LauncherStore.kt
host/data/src/main/kotlin/org/quicklauncher/host/data/store/SearchLaunchHistoryStore.kt
host/data/src/main/proto/launcher_preferences.proto
host/data/src/test/kotlin/org/quicklauncher/host/data/preferences/FileLauncherPreferencesStoreTest.kt
host/platform/build.gradle.kts
host/platform/src/androidTest/AndroidManifest.xml
host/platform/src/androidTest/kotlin/org/quicklauncher/host/platform/search/PhaseSixFixtureDocumentsProvider.kt
host/platform/src/androidTest/kotlin/org/quicklauncher/host/platform/search/SearchPlatformInstrumentedTest.kt
host/platform/src/main/AndroidManifest.xml
host/platform/src/main/kotlin/org/quicklauncher/host/platform/apps/FrameworkLauncherAppsBackend.kt
host/platform/src/main/kotlin/org/quicklauncher/host/platform/notifications/AndroidNotificationPlatform.kt
host/platform/src/main/kotlin/org/quicklauncher/host/platform/profile/AndroidProfilePlatform.kt
host/platform/src/main/kotlin/org/quicklauncher/host/platform/search/AndroidContactSearchAdapter.kt
host/platform/src/main/kotlin/org/quicklauncher/host/platform/search/AndroidFileSearchAdapter.kt
host/platform/src/main/kotlin/org/quicklauncher/host/platform/search/AndroidSearchTargetPlatform.kt
host/platform/src/main/kotlin/org/quicklauncher/host/platform/search/ProductionSearchCatalog.kt
host/platform/src/main/kotlin/org/quicklauncher/host/platform/search/SearchProviderAvailabilityCoordinator.kt
host/platform/src/main/kotlin/org/quicklauncher/host/platform/shortcuts/AndroidShortcutPlatform.kt
host/platform/src/test/kotlin/org/quicklauncher/host/platform/search/ProductionSearchCatalogTest.kt
host/platform/src/test/kotlin/org/quicklauncher/host/platform/search/SearchProviderAvailabilityCoordinatorTest.kt
host/runtime/src/main/kotlin/org/quicklauncher/host/runtime/composition/CompositionModels.kt
host/runtime/src/main/kotlin/org/quicklauncher/host/runtime/composition/DefaultCompositionEngine.kt
host/runtime/src/main/kotlin/org/quicklauncher/host/runtime/search/ComposeSearchPresentationSource.kt
host/runtime/src/main/kotlin/org/quicklauncher/host/runtime/search/DefaultSearchEngine.kt
host/runtime/src/main/kotlin/org/quicklauncher/host/runtime/search/RegisteredSearchProviderBinding.kt
host/runtime/src/main/kotlin/org/quicklauncher/host/runtime/search/SearchActionExecution.kt
host/runtime/src/main/kotlin/org/quicklauncher/host/runtime/search/SearchModels.kt
host/runtime/src/main/kotlin/org/quicklauncher/host/runtime/search/SearchPresentationController.kt
host/runtime/src/main/kotlin/org/quicklauncher/host/runtime/search/SearchRanking.kt
host/runtime/src/main/kotlin/org/quicklauncher/host/runtime/shortcuts/ShortcutCoordinator.kt
host/runtime/src/test/kotlin/org/quicklauncher/host/runtime/search/ComposeSearchPresentationSourceTest.kt
host/runtime/src/test/kotlin/org/quicklauncher/host/runtime/composition/CompositionEngineTest.kt
host/runtime/src/test/kotlin/org/quicklauncher/host/runtime/search/DefaultSearchEngineTest.kt
host/runtime/src/test/kotlin/org/quicklauncher/host/runtime/search/SearchActionExecutionTest.kt
host/runtime/src/test/kotlin/org/quicklauncher/host/runtime/search/SearchPresentationControllerTest.kt
host/runtime/src/test/kotlin/org/quicklauncher/host/runtime/search/SearchRankingTest.kt
host/settings/src/main/kotlin/org/quicklauncher/host/settings/HostOverlays.kt
modules/block/core/src/main/kotlin/org/quicklauncher/modules/block/core/CoreBlocks.kt
modules/block/core/src/test/kotlin/org/quicklauncher/modules/block/core/CoreBlocksComposeTest.kt
modules/block/core/src/test/kotlin/org/quicklauncher/modules/block/core/CoreBlocksVisualTest.kt
modules/block/core/src/test/snapshots/images/org.quicklauncher.modules.block.core_CoreBlocksVisualTest_core_block_renderers_cover_every_required_preview_scenario_search-large_text.png
modules/block/core/src/test/snapshots/images/org.quicklauncher.modules.block.core_CoreBlocksVisualTest_core_block_renderers_cover_every_required_preview_scenario_search-normal.png
modules/command/core/build.gradle.kts
modules/command/core/src/main/kotlin/org/quicklauncher/modules/command/core/CoreLauncherCommands.kt
modules/search/core/build.gradle.kts
modules/search/core/src/main/kotlin/org/quicklauncher/modules/search/core/CoreSearchProviders.kt
modules/search/core/src/test/kotlin/org/quicklauncher/modules/search/core/CoreSearchProvidersTest.kt
registry/annotations/src/main/kotlin/org/quicklauncher/registry/annotations/RegistrationAnnotations.kt
registry/ksp/src/main/kotlin/org/quicklauncher/registry/ksp/ContributionRegistryProcessor.kt
registry/ksp/src/main/kotlin/org/quicklauncher/registry/ksp/RegistryModel.kt
registry/ksp/src/main/kotlin/org/quicklauncher/registry/ksp/RegistrySourceGenerator.kt
registry/production/build.gradle.kts
registry/production/src/main/kotlin/org/quicklauncher/registry/production/ProductionRegistry.kt
registry/production/src/test/kotlin/org/quicklauncher/registry/production/ProductionRegistryTest.kt
settings.gradle.kts
testing/contracts/src/main/kotlin/org/quicklauncher/testing/contracts/ContributionContractSuites.kt
testing/samples/src/main/kotlin/org/quicklauncher/testing/samples/SampleSearchProvider.kt
```
