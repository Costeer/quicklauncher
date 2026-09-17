# Phase 7 theme evidence

Recorded on 2026-09-16 against the preserved mixed worktree. Phase 7 is complete. No required local, visual, license, workflow, diff, privacy, module-boundary, persistence, Robolectric, or API 35 AOSP check remains failing or unrun.

## Findings resolved during validation

1. Compiled Android XML icon-pack resources cannot be treated as textual streams. The host icon adapter now parses packaged `appfilter.xml` through bounded `XmlPullParser`, while plain assets retain hardened SAX parsing. The synthetic Nova/ADW connected regression passes mapping, deterministic duplicate handling, fallback, and package-change invalidation.
2. Built-in theme profiles must not create Room rows during empty-database startup. The controller now exposes immutable code-owned built-ins without seeding storage, preserving the database invariant that an empty launcher has no unrelated durable rows. A regression covers first start.
3. The Android wallpaper adapter requires the normal `SET_WALLPAPER` permission. It is declared in `:host:platform`; lint passes without suppressions.
4. Dependency analysis required direct app geometry and runtime serialization-core declarations and public Material 3 exposure from the runtime theme surface. Those declarations are explicit, and `buildHealth` passes without ignored advice.
5. The requested wallpaper matrix lacked an explicitly pinned Robolectric case. API 35 Robolectric now proves a missing private image fails as a typed recoverable result without applying wallpaper.
6. Revoked or unreadable SAF sources could escape font/image import as checked I/O failures. The asset adapter now fails closed with typed denial or invalid-input results, preserves coroutine cancellation, and covers denial, revocation, corrupt private copies, deterministic fallback, and cleanup.
7. Theme asset inventory and icon-pack parsing had per-item limits but incomplete aggregate traversal and memory limits. Private storage is now traversed lazily through bounded directory streams; icon components are length-limited; package discovery fails closed on timeout; and the prepared-icon cache is bounded by both 256 entries and 16 MiB.
8. Settings exposed generated profiles but not all required manual token and registered override controls. It now validates four `#AARRGGBB` manual fields and admits at most 32 module accents only for registry entries that declare `THEME_OVERRIDE`.
9. Wallpaper confirmation described the crop but did not display it. The host now renders the bounded preview selected by opaque identity while keeping all decoding and Android bitmap ownership in `:app` and `:host:platform`.
10. A destination background selected while a code-owned built-in profile was active violated the Room foreign key because built-ins intentionally have no rows. The controller now commits a derived custom profile and destination background atomically, then selects it only after the Room transaction succeeds.
11. Malformed theme/background rows could abort an otherwise recoverable store read, and deleting the active profile could change Proto selection before a rejected Room commit. Row mapping now skips malformed bounded records and dangling backgrounds; active deletion changes selection only after storage succeeds.
12. The connected lifecycle regression used an exact UiAutomator descendant text as its oracle, which can be absent from the accessibility tree while the lazy result is valid. The test now waits on the immutable public presentation state after driving the production UI, retaining its Back, recreation, and repeated-Home coverage.

No unresolved defect was found after these repairs.

## Independent validation repair addendum

An independent validation on 2026-09-17 did not trust the completion statement above and found seven additional gaps through the public seams:

1. The resolved theme carried colors and typography but not the complete immutable non-color token set. `LauncherTheme` now includes bounded shapes, corner radii, spacing, icon rendering, and accessibility values; the root Material adapter maps the resolved shape roles rather than recreating them.
2. A destination without its own override could inherit the currently visible neighbor's resolved theme. The composition environment now retains the launcher fallback separately, and every current or neighbor destination is wrapped in its own resolved Material theme, imported typography, and background.
3. Room profile mutations followed by a failed Proto selection write could leave the two stores divergent. Save, active delete, and built-in materialization now compensate the Room transaction, including caller cancellation in a non-cancellable cleanup section, and return typed recovery-required state when compensation itself cannot be proved.
4. Platform wallpaper cancellation was not distinguishable from caller cancellation, confirmation had no explicit full/center crop selection, and confirmation could overtake crop-preview regeneration. Platform cancellation is now typed, caller cancellation still propagates, and one mutex serializes crop regeneration, cancellation, and exactly-once confirmation.
5. Pending font/image picker intent was not restored after process recreation. The pending import kind and font role now round-trip through saved instance state with malformed-value fallback.
6. Theme file and package work used timeouts around blocking calls without making those calls interruptible. Imports, inventory, cleanup, validation, and icon-pack discovery/lookup now use bounded interruptible I/O; a regression proves timeout interrupts a deliberately blocked worker.
7. The settings visual test duplicated production content instead of crossing the production composable seam. The profile, manual-palette, and wallpaper-confirmation content functions are now the public visual/test seam used by `ThemeSettingsContent`; all nine changed PNGs were inspected at normal and large text before their intentional baselines were recorded.

Focused regressions first failed for missing recovery and crop behavior, then passed. The exact Phase 7 focused command passed in 1m55s with 255 actionable tasks (12 executed, 243 up-to-date). The exact six-module visual gate passed in 2m19s with 285 actionable tasks (18 executed, 267 up-to-date).

The first exact full local gate was invalidated by a Gradle test-worker startup timeout after 7h24m; no product assertion failed. Its only unfinished task, `:host:editor:testReleaseUnitTest`, passed unchanged in isolation. The complete exact gate was then rerun and passed in 5m25s with 3,807 actionable tasks (711 executed, 40 from cache, 3,056 up-to-date). The CI-derived license command passed in 28s with all 38 tasks up-to-date; `actionlint` and `git diff --check` also passed.

The first pinned API 35 rerun hit the already documented Home-role dialog accessibility timing failure at `HomeManifestInstrumentedTest.kt:138`. That exact flow passed unchanged as one focused test in 59s, and the exact five-module command then passed in 3m26s with 390 actionable tasks (5 executed, 385 up-to-date). XML totals were data 17/0 skipped, platform 20/1, runtime 5/0, editor 4/0, and app 23/2: 69 tests, 66 passed, 3 authority-dependent skips, 0 failures, and 0 errors.

The rerun used only `quicklauncher_aosp_full35_phase5` at pinned serial `emulator-5554`, fingerprint `Android/sdk_phone64_x86_64/emu64x:15/AE3A.240806.019/12368160:userdebug/test-keys`, API 35. Before/after Home, Settings, notification-listener value and key presence, users/profiles, device owners, packages and permissions, wallpaper names, URI grants, widgets/providers, and synthetic test files matched byte-for-byte. The baseline listener key was present with an empty value and remained so. The emulator reported that snapshots were disabled when it stopped. The attached GrapheneOS phone was not queried or changed.

The preserved worktree was already a mixed staged/unstaged/untracked Phase 6 and Phase 7 state. The Phase 6 manifest and the repository's recorded slice-1 list allow file-level comparison, but exact temporal authorship of overlapping files cannot be reconstructed independently from the worktree; this addendum does not present that provenance as newly observed fact.

## Slice results

### 1. One resolved host theme

The existing host-owned resolver was retained and extended; no second theme path was introduced. One `ResolvedLauncherTheme` supplies the root Material scheme, typography roles, host surfaces, production and neighbor composition, onboarding preview, layouts, and blocks. Contribution-facing records are immutable and platform-neutral.

### 2. Profiles and palettes

- Stable namespaced profile identity and explicit record version are validated by the profile codec.
- Built-in light, dark, and system-following profiles are code-owned complete defaults.
- Material You and Material Expressive use `com.google.android.material:material:1.14.0` color utilities; manual tokens use the same opacity, completeness, text-contrast, and non-text-contrast checks.
- Overrides are limited to 32 entries, require the contribution's registered `THEME_OVERRIDE` capability, and cannot replace typography, assets, system state, or inventories.
- Room schema 7 stores custom encoded `ThemeProfile` and `DestinationBackground` rows; Proto DataStore stores the active profile identity. Selection is one preference update, preview remains transient, and state publication advances one generation.
- Assigning a destination background while a built-in is active atomically materializes a derived custom profile plus its foreign-keyed background before changing Proto selection; a rejected commit leaves both the active profile and published generation unchanged.
- A selected record that is absent, malformed, incomplete, or incompatible resolves to the entire system-following built-in. Stored and fallback tokens are never mixed.

### 3. Fonts

- Import begins only from a Settings activity-result action and accepts content URIs through the host/platform adapter.
- Reads time out at 5 seconds and stop at 8 MiB. SFNT/TTC structure, table ranges, signatures, one to four families, one to eight faces, up to 16 supported variable axes, weight, and italic metadata are checked before a private atomic copy is retained.
- Android must also construct the copied typeface; malformed, truncated, misleading, missing, revoked, or corrupt assets resolve to the deterministic system role.
- Checked I/O and revoked grants fail closed as typed import results. Cancellation remains cancellation, and no source URI or private path enters an error payload.
- The inventory admits at most 64 private font assets. Profile replacement/deletion calls bounded reference cleanup, and filenames, paths, URIs, family strings, and bytes are not logged or placed in evidence.

### 4. Nova and ADW icon packs

- Only declarative `<item component= drawable=>` mappings from the documented Nova/ADW package intents are read. No package code or contribution class is loaded.
- Queries are limited to 64 packages, component strings to 512 characters, mappings to 20,000 entries/2 MiB, icons to 512×512 and 4 MiB decoded memory/1 MiB encoded output, the cache to 256 entries and 16 MiB, and adapter operations to 5 seconds.
- First valid duplicate wins deterministically. Every lookup rechecks the selected pack and installed version; unavailable, stale, malformed, missing, or unsupported mappings return the prepared application icon.
- Package replacement/removal, profile change, and configuration invalidation clear affected cache state. Contracts contain encoded prepared icons only, never `Drawable`, `Resources`, or package inventories.

### 5. Backgrounds and image-derived themes

- Profiles own a launcher solid, two-to-four-stop gradient, or private image background with a bounded scrim. Destination records either inherit or replace it deterministically.
- Resolution verifies opacity, gradient stop order/count, available private image identity, text contrast of at least 4.5:1, and non-text contrast of at least 3:1 before publishing one background/foreground pair.
- Explicit SAF selection permits PNG, JPEG, or WebP up to 16 MiB, 8,192 pixels per dimension, 24 million pixels, and 96 MiB decoded memory. Android `ImageDecoder` applies orientation while the adapter forces software/low-memory decode. Retained previews are at most 1,024 pixels.
- An image-derived profile keeps the seed, generated palette, and bounded preview. Source bytes are kept only for a configured background or pending wallpaper command. Replacement/deletion removes unreferenced private assets.

### 6. Wallpaper and Settings

- The user selects an embedded private image, sees the bounded crop preview resolved from an opaque host identity, acknowledges the non-restorable-wallpaper warning, and explicitly selects Home, Lock, or Both.
- The command contains only opaque preview/image identities, crop generation, target, and acknowledgement. Immediately before `WallpaperManager.setBitmap`, the coordinator rechecks current support/state, image ownership, preview generation, and crop.
- Consumed generations make execution exactly once. Cancellation, Back, recreation loss, stale or corrupt assets, denial, unavailable support, timeout, and recoverable platform failure return typed results and do not claim restoration.
- Settings exposes profile selection/preview/deletion, Material You/Expressive/manual creation, font roles, icon packs, launcher/destination solid-gradient-image backgrounds, image derivation, and wallpaper confirmation through host-owned controls with radio roles, selected state, headings, and error state descriptions.
- Compose tests and inspected visual baselines cover click/keyboard/D-pad-accessible controls, normal and large text, light portrait and dark landscape, cancellation, and the safe recovery route. Existing production overlay instrumentation covers predictive Back, repeated Home, Activity recreation, and process restoration without giving theme/import failures control of the safe layout.

## Persistence and migration

The durable shape did not change. Room schema 7 already contains versioned opaque theme-profile and destination-background records, and Proto DataStore already has the selected-profile field. Therefore Phase 7 adds no Room or Proto migration and no new exported schema.

The authoritative build reruns the existing schema 1→7 adjacent and oldest-path tests, close/reopen checks, injected cancellation/failure rollback tests, malformed-row reads, and unrelated opaque-byte preservation. Theme-specific tests additionally prove empty-store startup creates no rows, custom selection survives controller recreation, preview does not, malformed theme/background rows are isolated, an active-profile delete conflict preserves selection, a built-in destination background is materialized atomically, and a corrupt selected profile publishes one complete built-in fallback.

## Test and command ledger

All test counts below come from Gradle summaries or generated XML. Failures found during implementation are retained here rather than rewritten as passes.

### Focused implementation

- `./gradlew :host:data:testDebugUnitTest :host:runtime:testDebugUnitTest :host:platform:testDebugUnitTest :host:settings:testDebugUnitTest :app:testStableDebugUnitTest :host:platform:compileDebugAndroidTestKotlin --no-daemon --no-configuration-cache --console=plain` — passed in 2m13s; 255 actionable (12 executed, 1 from cache, 242 up-to-date); no test failure, error, or skip.
- `./gradlew :host:platform:assembleDebugAndroidTest :host:platform:testDebugUnitTest --no-daemon --no-configuration-cache --console=plain` — passed in 24s; 108 actionable (9 executed, 99 up-to-date); no test failure, error, or skip.
- Theme-controller regression — passed in 29s; 63 actionable (3 executed, 60 up-to-date); 1 selected test, no failure, error, or skip.
- `./gradlew :host:platform:testDebugUnitTest --tests '*AndroidThemeAssetStoreRobolectricTest*' --no-daemon --no-configuration-cache --console=plain` — passed in 26s; 51 actionable (3 executed, 48 up-to-date); 1 selected API 35 Robolectric test, no failure, error, or skip.
- An attempted combined Robolectric/dependency-health command named nonexistent `:host:platform:buildHealth` and stopped during task selection in 10s with no actionable test. The corrected focused test above and root `buildHealth` below both pass.
- The first corrected Robolectric compile exposed missing test-scope AndroidX/Robolectric declarations: failed in 15s; 49 actionable (1 executed, 48 up-to-date); no test executed. Direct declarations were added.
- A final icon-package revalidation audit first exposed an `ApplicationInfo` version-code compile error: the focused platform command failed in 15s with 92 actionable tasks (1 executed, 91 up-to-date), before tests ran. The implementation switched to current `PackageInfo.longVersionCode`; the same command then passed in 31s with 108 actionable tasks (11 executed, 97 up-to-date) and no test failure, error, or skip.

### Required local gate

```bash
./gradlew build checkModuleBoundaries verifyNoGoogleDependencies buildHealth \
  --no-daemon --no-configuration-cache --console=plain
```

The initial comprehensive attempt failed after 14m04s because lint found the missing `SET_WALLPAPER` permission: 3,439 actionable tasks (2,279 executed, 831 from cache, 329 up-to-date). No unit-test failure or error occurred. After that fix, a 2m17s run reached `buildHealth` and reported the three dependency declarations described above: 3,807 actionable (327 executed, 120 from cache, 3,360 up-to-date). Focused `buildHealth` then passed in 1m08s with 2,928 actionable (224 executed, 14 from cache, 2,690 up-to-date).

Adding the required Robolectric coverage caused one focused `buildHealth` failure in 31s because `androidx.test:monitor` was transitive in the test source set: 2,928 actionable (37 executed, 6 from cache, 2,885 up-to-date). The dependency was declared directly; `buildHealth` passed in 31s with 2,928 actionable (28 executed, 1 from cache, 2,899 up-to-date).

After the final connected-test oracle repair, the exact gate passed again in 57s: 3,807 actionable tasks (32 executed, 6 from cache, 3,769 up-to-date). Generated XML contains 1,259 tests, 0 failures, 0 errors, and 0 skips.

### Visual gate and inspection

```bash
./gradlew \
  :app:verifyPaparazziStableDebug \
  :modules:block:core:verifyPaparazziDebug \
  :modules:layout:core:verifyPaparazziDebug \
  :host:editor:verifyPaparazziDebug \
  :host:runtime:verifyPaparazziDebug \
  :host:settings:verifyPaparazziDebug \
  --no-daemon --no-configuration-cache --console=plain
```

The final run passed in 1m56s: 285 actionable tasks (6 executed, 279 up-to-date). The selected XML reports contain 299 tests, 0 failures, 0 errors, and 0 skips. The repository contains 130 baselines. The nine new Phase 7 settings images were inspected individually: profile, manual-palette, and wallpaper-confirmation states at normal/large light portrait and dark landscape are legible, preserve focusable controls and warnings, scroll in landscape, and show no unintended clipping. The displayed wallpaper preview is visible in every confirmation state. No baseline was regenerated merely to silence a comparison.

### License, workflow, diff, privacy, and boundaries

```bash
rg -o ':[A-Za-z0-9:_-]+:licensee' .github/workflows/ci.yml | sort -u | \
  xargs ./gradlew --no-daemon --no-configuration-cache --console=plain
```

Final pass: 12s; 38 actionable tasks, all up-to-date; no failure. Material Color Utilities and the test-only dependencies use already allowed repository licenses.

- `nix shell nixpkgs#actionlint -c actionlint .github/workflows/ci.yml` — passed initially in 1.91s and on the final documentation rerun in 1.42s; no Gradle tasks or diagnostics.
- `git diff --check` — passed in under 0.1s before evidence editing and again after final documentation; no whitespace error.
- `checkModuleBoundaries`, `verifyNoGoogleDependencies`, and `buildHealth` are included in the final exact local gate and passed without suppression.
- Source scans found no `android.*` import in contracts, contribution modules, `:host:data`, `:host:runtime`, or `:host:settings`; no theme-path logger, analytics call, emitted content/file URI, or absolute-path diagnostic; and no network theme loader, Google Play Services, Firebase, or runtime plugin path. The only URL-like strings in theme code disable XML external entities.

### Pinned API 35 AOSP connected matrix

The serial was resolved and pinned as `emulator-5554` before Gradle. The full-phone AVD fingerprint was `Android/sdk_phone64_x86_64/emu64x:15/AE3A.240806.019/12368160:userdebug/test-keys`, API 35.

```bash
ANDROID_SERIAL=emulator-5554 ./gradlew \
  :host:data:connectedDebugAndroidTest \
  :host:platform:connectedDebugAndroidTest \
  :host:runtime:connectedDebugAndroidTest \
  :host:editor:connectedDebugAndroidTest \
  :app:connectedStableDebugAndroidTest \
  --no-daemon --no-configuration-cache --console=plain
```

The first attempt failed in 2m54s with 387 actionable tasks (156 executed, 31 from cache, 200 up-to-date): three new platform tests exposed virtual-test-time versus real-I/O scheduling and binary-XML parsing defects. After those fixes, a 1m39s attempt with 390 actionable tasks (9 executed, 381 up-to-date) exposed the empty-database built-in seeding defect. The next 2m48s attempt (390 actionable: 18 executed, 372 up-to-date) hit the existing transient Home-role dialog timeout. Its exact Home flow passed unchanged in 38s (261 actionable: 1 executed, 260 up-to-date; 1 test, no failure/error/skip), demonstrating a platform-dialog timing failure rather than a product repair. Partial connected XML was superseded by later runs, so reliable aggregate test counts are recorded only for the final run.

After the package/version revalidation repair, an earlier complete matrix passed in 2m49s. The expanded final matrix first failed after 3m18s with 390 actionable tasks (13 executed, 377 up-to-date) because the Phase 6 lifecycle regression polled for exact descendant text that UiAutomator omitted under matrix load. The production search state was correct, and the same product flow passed unchanged in a focused 49s run with 261 actionable tasks (1 executed, 260 up-to-date). The regression oracle was moved to the immutable public presentation state rather than weakening the product assertion.

The exact complete matrix then passed in 2m39s: 390 actionable tasks (9 executed, 381 up-to-date). Module XML totals were `:host:data` 17/0 skipped, `:host:platform` 20/1 skipped, `:host:runtime` 5/0, `:host:editor` 4/0, and `:app` 23/2. Total: 69 tests, 66 passed, 3 explicit authority-dependent skips, 0 failures, and 0 errors.

The three skips remain the shortcut-host success path and two separately gated managed-profile paths. They are not Phase 7 failures. Synthetic images, declarative mappings, package/activity identities, and provider content were used; no real installed-app label/icon or personal content entered fixtures or output.

## Emulator restoration

Before the run the AVD used Launcher3 Home, font scale 1.0, accelerometer rotation enabled, user rotation 0, default night mode, one Owner user, no device owner, no Quicklauncher packages or notification access, and blank wallpaper names. After the final matrix those values matched exactly. The test cleanup left an empty notification-listener setting key where the baseline key was absent; that empty key was deleted and the baseline absence was confirmed. No Quicklauncher/test/platform package, URI grant, widget/provider reference, profile, or selected test file remained. The emulator was launched with `-no-snapshot-save`, stopped, and confirmed not to save a snapshot.

The attached GrapheneOS phone was not queried, connected to Gradle, or changed. Its prior results are **owner-supplied evidence reviewed, not independently rerun**. This exclusion is neither a failure nor an independent pass.

## Changed-file separation

The repository already contained the complete staged/unstaged/untracked Phase 6 work listed in [the Phase 6 changed-file manifest](phase-6-search-evidence.md#changed-file-manifest). Those changes were preserved. Phase 7 also began with slice 1 already present in the shared resolver/composition files; it was retained and extended rather than replaced.

Phase 7-only files not present in the Phase 6 manifest are:

```text
docs/status/phase-7-theme-evidence.md
docs/status/phase-7-theme-scope.md
docs/status/phase-7-validation-prompt.md
gradle/libs.versions.toml
host/data/src/main/kotlin/org/quicklauncher/host/data/store/InMemoryLauncherStore.kt
host/data/src/main/kotlin/org/quicklauncher/host/data/store/StoreEdits.kt
host/data/src/main/kotlin/org/quicklauncher/host/data/store/StoreModels.kt
host/data/src/main/kotlin/org/quicklauncher/host/data/room/LauncherRowMapper.kt
host/data/src/test/kotlin/org/quicklauncher/host/data/room/LauncherRowMapperTest.kt
host/data/src/test/kotlin/org/quicklauncher/host/data/store/InMemoryLauncherStoreTest.kt
host/platform/src/androidTest/kotlin/org/quicklauncher/host/platform/theme/AndroidIconPackResolverInstrumentedTest.kt
host/platform/src/androidTest/kotlin/org/quicklauncher/host/platform/theme/AndroidThemeAssetStoreInstrumentedTest.kt
host/platform/src/androidTest/kotlin/org/quicklauncher/host/platform/theme/PhaseSevenFixtureDocumentsProvider.kt
host/platform/src/androidTest/kotlin/org/quicklauncher/host/platform/theme/PhaseSevenFixtureIconPackActivity.kt
host/platform/src/androidTest/res/drawable/phase_seven_fixture_icon.xml
host/platform/src/androidTest/res/xml/appfilter.xml
host/platform/src/main/kotlin/org/quicklauncher/host/platform/theme/AndroidIconPackResolver.kt
host/platform/src/main/kotlin/org/quicklauncher/host/platform/theme/AndroidThemeAssetStore.kt
host/platform/src/main/kotlin/org/quicklauncher/host/platform/theme/DeclarativeIconPackParser.kt
host/platform/src/main/kotlin/org/quicklauncher/host/platform/theme/SfntFontValidator.kt
host/platform/src/test/kotlin/org/quicklauncher/host/platform/theme/AndroidThemeAssetStoreRobolectricTest.kt
host/platform/src/test/kotlin/org/quicklauncher/host/platform/theme/DeclarativeIconPackParserTest.kt
host/platform/src/test/kotlin/org/quicklauncher/host/platform/theme/SfntFontValidatorTest.kt
host/runtime/build.gradle.kts
host/runtime/src/main/kotlin/org/quicklauncher/host/runtime/LauncherRuntime.kt
host/runtime/src/main/kotlin/org/quicklauncher/host/runtime/theme/LauncherThemeResolver.kt
host/runtime/src/main/kotlin/org/quicklauncher/host/runtime/theme/ThemeAssetContracts.kt
host/runtime/src/main/kotlin/org/quicklauncher/host/runtime/theme/ThemeController.kt
host/runtime/src/main/kotlin/org/quicklauncher/host/runtime/theme/ThemeProfiles.kt
host/runtime/src/test/kotlin/org/quicklauncher/host/runtime/theme/LauncherMaterialThemeTest.kt
host/runtime/src/test/kotlin/org/quicklauncher/host/runtime/theme/LauncherThemeResolverTest.kt
host/runtime/src/test/kotlin/org/quicklauncher/host/runtime/theme/ThemeControllerTest.kt
host/runtime/src/test/kotlin/org/quicklauncher/host/runtime/theme/WallpaperCoordinatorTest.kt
host/settings/src/test/kotlin/org/quicklauncher/host/settings/HostOverlaysTest.kt
host/settings/src/test/kotlin/org/quicklauncher/host/settings/visual/HostOverlayVisualTest.kt
host/settings/src/test/snapshots/images/org.quicklauncher.host.settings.visual_HostOverlayVisualTest_theme_profile_and_wallpaper_confirmation_remain_accessible_across_visual_modes_manual-palette-dark-landscape.png
host/settings/src/test/snapshots/images/org.quicklauncher.host.settings.visual_HostOverlayVisualTest_theme_profile_and_wallpaper_confirmation_remain_accessible_across_visual_modes_manual-palette-large-text-light-portrait.png
host/settings/src/test/snapshots/images/org.quicklauncher.host.settings.visual_HostOverlayVisualTest_theme_profile_and_wallpaper_confirmation_remain_accessible_across_visual_modes_manual-palette-light-portrait.png
host/settings/src/test/snapshots/images/org.quicklauncher.host.settings.visual_HostOverlayVisualTest_theme_profile_and_wallpaper_confirmation_remain_accessible_across_visual_modes_theme-profiles-dark-landscape.png
host/settings/src/test/snapshots/images/org.quicklauncher.host.settings.visual_HostOverlayVisualTest_theme_profile_and_wallpaper_confirmation_remain_accessible_across_visual_modes_theme-profiles-large-text-light-portrait.png
host/settings/src/test/snapshots/images/org.quicklauncher.host.settings.visual_HostOverlayVisualTest_theme_profile_and_wallpaper_confirmation_remain_accessible_across_visual_modes_theme-profiles-light-portrait.png
host/settings/src/test/snapshots/images/org.quicklauncher.host.settings.visual_HostOverlayVisualTest_theme_profile_and_wallpaper_confirmation_remain_accessible_across_visual_modes_wallpaper-confirmation-dark-landscape.png
host/settings/src/test/snapshots/images/org.quicklauncher.host.settings.visual_HostOverlayVisualTest_theme_profile_and_wallpaper_confirmation_remain_accessible_across_visual_modes_wallpaper-confirmation-large-text-light-portrait.png
host/settings/src/test/snapshots/images/org.quicklauncher.host.settings.visual_HostOverlayVisualTest_theme_profile_and_wallpaper_confirmation_remain_accessible_across_visual_modes_wallpaper-confirmation-light-portrait.png
modules/layout/core/src/main/kotlin/org/quicklauncher/modules/layout/core/CoreLayouts.kt
```

Shared files contain both preserved Phase 6 work and Phase 7 integration and must not be attributed wholly to either phase:

```text
app/build.gradle.kts
app/src/main/java/org/quicklauncher/app/MainActivity.kt
app/src/main/java/org/quicklauncher/app/ProductionCompositionSurface.kt
contracts/ui/src/main/kotlin/org/quicklauncher/contracts/ui/ImmutableRenderModels.kt
docs/architecture/launcher-architecture-plan.md
docs/status/current-state-overview.html
docs/status/current-state.md
host/data/src/main/kotlin/org/quicklauncher/host/data/preferences/FileLauncherPreferencesStore.kt
host/data/src/main/kotlin/org/quicklauncher/host/data/preferences/LauncherPreferences.kt
host/data/src/main/kotlin/org/quicklauncher/host/data/preferences/LauncherPreferencesProtoCodec.kt
host/data/src/main/kotlin/org/quicklauncher/host/data/room/LauncherDao.kt
host/data/src/main/kotlin/org/quicklauncher/host/data/room/LauncherDatabase.kt
host/data/src/main/kotlin/org/quicklauncher/host/data/room/LauncherEntities.kt
host/data/src/main/kotlin/org/quicklauncher/host/data/room/RoomLauncherStore.kt
host/data/src/main/kotlin/org/quicklauncher/host/data/store/LauncherStore.kt
host/data/src/main/proto/launcher_preferences.proto
host/platform/build.gradle.kts
host/platform/src/androidTest/AndroidManifest.xml
host/platform/src/main/AndroidManifest.xml
host/runtime/src/main/kotlin/org/quicklauncher/host/runtime/composition/CompositionModels.kt
host/runtime/src/main/kotlin/org/quicklauncher/host/runtime/composition/DefaultCompositionEngine.kt
host/runtime/src/test/kotlin/org/quicklauncher/host/runtime/composition/CompositionEngineTest.kt
host/settings/src/main/kotlin/org/quicklauncher/host/settings/HostOverlays.kt
```

No pre-existing file was reset, cleaned, stashed, discarded, staged, committed, pushed, or replaced.
