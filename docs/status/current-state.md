# Current implementation status

Reviewed on 2026-08-26 against the [architecture plan](../architecture/launcher-architecture-plan.md), accepted ADRs, and the current working tree.

## Summary

Phases 0 through 3 are complete. Phase 4 is in progress, starting with cross-module registry aggregation, valid template placement drafts, and the composition engine. No Phase 5 work has begun.

The production app now qualifies for the Home role, opens the Phase 2 stores through the platform boundary, provisions the host-owned safe layout, lists and launches profile-aware apps, returns repeated Home presses to the persisted start destination, and exposes Settings, map, quarantine, and typed app-recovery overlays. Ordinary app collections fail closed when visibility policy is unavailable; host Settings retains a typed launch-recovery route without exposing hidden apps through the safe layout's local filter. Optional contribution composition remains Phase 4 work.

## Phase 1 outcome

The authoritative specifications are indexed under [`docs/contracts`](../contracts/README.md). Shared identity, configuration, lifecycle, validation, accessibility, registration, and evolution rules have one definition; the layout, block, search-provider, launcher-command, and destination-template specifications add their type-specific behavior.

The implementation includes:

- `:contracts:domain`: validated namespaced contribution, category, capability, slot, configuration, instance, destination, content, search, and invocation identities; local stable keys; contract majors; schema versions; and ARGB values.
- `:contracts:contribution`: common and type-specific descriptors, exact major-1 compatibility, declarative settings and validation, typed configuration codecs, sequential pure migrations with original-document preservation, the five generic contribution contracts, typed results, and generic static registry records.
- `:contracts:ui`: immutable render snapshots and collections, typed action sinks, typed slot and prepared-content renderers, background contrast, placement and composition state, theme and orientation state, accessibility declarations and validation, preview scenarios, and performance-hook declarations.
- Layout and block sessions expose Compose `Render` entry points, accept host-prepared content, own cancellable work through an instance scope, and close when their composition leaves the tree.
- `:registry:annotations`: source-retained registration annotations and compile-visible codec, settings, and contract-suite manifests for all five first-release categories.
- `:registry:ksp`: a thin symbol adapter over a platform-neutral validator and deterministic source generator. Generated code preserves one configuration type across target, codec, descriptor, and category suite, uses direct references and stable ordering, and does not scan the runtime classpath or use reflection.
- `:testing:contracts`: reusable public-contract suites for the complete configuration failure matrix, immutable snapshots, rejected typed actions, slot and prepared-content rendering, query replacement and stale-result cancellation, owned-job disposal, accessibility and focus declarations, contrast, fixture coverage, screenshots, and category-specific performance hooks.
- `:testing:fakes`: deterministic success, loading, denial, locked-profile, error, cancellation, placement, composition, theme, and prepared-content behavior without Android services.
- `:testing:samples`: an Android test-fixture library containing skeletal contributions for all five categories. Each contribution registers through KSP, supplies a matching typed codec and sequential migrations, declares the mandatory suite metadata, emits typed behavior, and appears in the generated registry.

The generated registry rejects:

- duplicate contribution and persisted configuration type IDs;
- malformed contribution, configuration, capability, slot, and category IDs;
- unsupported contract majors;
- invalid common or type-specific descriptors and settings schemas;
- missing codecs or contract suites;
- target/codec, target/suite, and inherited generic mismatches;
- codec/descriptor configuration ID mismatches;
- contract-declaration/descriptor contribution ID mismatches;
- missing fixture scenarios or performance hooks;
- contract suites declared for the wrong category;
- targets that do not implement the declared contract or cannot be referenced directly;
- capability dependency cycles; and
- incompatible block/slot declarations and direct or indirect block-nesting cycles.

Generated-registry tests compare every generated descriptor field with the public sample declaration and assert target, codec, suite, scenario, and performance-hook identity. Code-contract and configuration-schema versions evolve independently. Persisted category IDs are explicit, tested string literals rather than enum names or ordinals; adding a later category requires explicit catalog, annotation, validation, and generation support and cannot renumber the existing five IDs.

## Deliberate Phase 1 limitations

The skeletal contributions are contract fixtures, not production launcher UI. The Android sample module exists to exercise real Compose rendering, Robolectric semantics, and Paparazzi screenshots; it does not use Android launcher services, persistence, Room, real widgets, production search sources, production themes, or backups.

Source-retained annotations are processed in the contribution's source module. A later multi-module application registry must aggregate generated fragments explicitly because source annotations are not visible through compiled dependency jars.

Performance hooks are deterministic contract-test probes. Device-specific numeric thresholds remain release-hardening work after production implementations and device baselines exist.

## Phase 2 outcome

Phase 2 was reopened after the 2026-08-23 correctness review. The remediation closed the persistence, copy, configuration-validation, schema-upgrade, and module-seam gaps, added focused regression tests, and passed the full repository, extended, and device gates recorded below.

[ADR 0030](../adr/0030-use-a-revisioned-transactional-launcher-store.md) records the persistence boundary. `:host:data` exposes a small task-level `LauncherStore`; Room entities, DAOs, row mapping, schema migrations, and transaction details stay internal. Callers submit immutable, revision-checked transactions and receive either one committed snapshot or a typed rejection with the unchanged current snapshot.

The implementation includes:

- a pure destination-map model with checked 64-bit coordinates, unique cells, one stable start destination, cardinal reachability, connected rigid group moves, and overflow-safe adjacency;
- atomic edits for bootstrap, destination installation and removal, start selection, group movement, retained and dormant layouts, block drops and moves, recursive copies, configuration replacement, and confirmed subtree removal;
- registry-backed validation of contribution category, configuration type, slot compatibility, capability requirements, scroll axes, capacity, placement reachability, order, and cycles;
- production configuration reconciliation through registered codecs and sequential migrations, with exact original-byte preservation on every failure, cancellation propagation, migration-error records, and configuration-owned quarantine that cannot clear renderer or restore quarantine;
- validated configuration replacement through the same production migration-and-decode path, with cancellation propagation and exact prior-state preservation on rejection;
- subtree moves and copies that carry explicit layout-owned placement documents for every node, while widget copies discard Android widget IDs and require fresh host binding;
- immutable, stable-ordered launcher snapshots for all twelve initial Room concepts: destinations, retained layouts, module instances, configuration documents, placements, content items, folder members, app overrides, widget placements, theme profiles, destination backgrounds, and crash markers;
- internal aggregate construction and Room implementation types behind a file-based `CloseableLauncherStore` factory, with Android `Context` confined to `:host:platform`;
- structural shortcut identity as profile, package, and shortcut ID;
- Room schema versions 1 through 5, including a v2-to-v3 migration that makes app activity identity structural and records quarantine origin, a v3-to-v4 migration for structural shortcut and crash-marker data, and a v4-to-v5 migration that enforces one durable crash marker per module instance; and
- Proto DataStore preferences for gesture mode, enabled search providers, search-history policy, theme selection, notification style, and onboarding, including corruption recovery, future-version rejection, cancellation, concurrent updates, and close/reopen behavior.

Randomized tests run 2,000 edits directly against the spatial model and another 250 revisioned transactions through `LauncherStore`. Every accepted state preserves unique coordinates, the stable start reference, and cardinal reachability. The same reducer powers the in-memory and Room stores, so database commits cannot bypass those invariants.

## Deliberate Phase 2 limitations

Phase 2 supplied persistence and deterministic adapters without app wiring or production launcher behavior. Phase 3 now opens those stores through the platform boundary and owns the safe launcher slice described below.

Clone UI and structural clone propagation remain post-1.0 work. The schema permits several instances of the same contribution to reference one configuration document, while copy creates independent instance, placement, and configuration identities.

Layout-owned placement data remains opaque, byte-preserving text with an explicit schema version. A later layout placement codec can evolve that data without exposing Room rows through the contract.

Room launcher state and Proto global preferences are separate transaction domains. Operations that eventually span both stores must define recovery rather than claim cross-store atomicity.

## Phase 3 implementation

The safe-launcher vertical slice is implemented across `:app`, `:host:runtime`, `:host:platform`, and `:host:settings`:

- `MainActivity` is an exported, `singleTask` Home activity with separate Home and launcher-icon intent filters, edge-to-edge composition, predictive Back for overlays, saved-destination restoration, and repeated-Home reset of destination, overlay, and local query;
- role onboarding is contextual rather than automatic, rechecks actual ownership after the Activity Result returns, and exposes the public Android Home-settings fallback when ownership is still absent;
- `AndroidAppPlatform` contains `LauncherApps`, `UserHandle`, component, icon, callback, and broadcast details behind Android-free runtime records; profile serials are resolved at use time and unknown secondary profiles fail closed before labels or icons are read;
- `DefaultAppCatalog` owns immutable catalog state, deterministic sorting, work-profile labeling, host overrides, stale-result preservation, conflated refresh, typed launch results, and lifecycle disposal;
- the reserved `org.quicklauncher.core/safe-layout` identity is host-owned, deterministically provisioned, and rejected by the contribution processor when optional modules try to claim its contribution or configuration identity;
- the safe surface provides local app filtering, typed launch actions, map, Settings, and recovery entry points, remains usable at large text and both orientations, and keeps Settings reachable if launcher-state loading fails; a narrow visibility-policy read preserves the ordinary app collection when safe, while total policy failure exposes apps only through the typed host-Settings recovery route;
- recovery uses durable startup markers, one-marker-per-instance Room enforcement, renderer quarantine, automatic safe-layout selection, original configuration preservation, bounded redacted diagnostics, and a real retry path when a contribution renderer is installed; and
- Room, preferences, catalog, diagnostics, role, and lifecycle failures degrade independently, propagate cancellation, and close owned resources without exposing Android or persistence implementation objects through runtime contracts.

Fifteen Paparazzi baselines cover the safe surface and all four host overlays in light portrait, dark landscape, and 2x text modes. The API 35 AOSP product suite covers merged Home qualification, real role grant and cancellation/fallback flows, repeated Home delivery to one activity, typed current-profile launch and Settings recovery, process persistence, renderer quarantine, durable crash-loop fallback, and injected selected-renderer startup recovery.

## Deliberate Phase 3 limitations

Production `MainActivity` leaves the optional selected-layout restorer absent until Phase 4 installs the real composition engine. Phase 3 proves crash recovery through the public runtime seam and device test rather than treating a no-op renderer as success. Renderer retry is offered only when that real renderer seam is present.

Quarantine inspection and automatic safe fallback belong to Phase 3. Contribution-backed configuration reset, replacement, and deletion move with the real editors to Phase 4; quarantined-state and redacted support-bundle export remain Phase 8 work. This sequencing is recorded in the architecture plan and does not weaken the public-build gate.

Both halves of [ADR 0029](../adr/0029-use-emulator-and-grapheneos-as-the-required-device-matrix.md) pass for the Phase 3 production slice. The current GrapheneOS reference Pixel has Private Space and separate full secondary users but no managed work profile. Phase 3 therefore proves stable current-profile identity and typed launch on-device; managed-work discovery, quiet-mode invalidation, work sections, and Private Space UI remain Phase 5 work. The Phase 3 app intentionally omits `ACCESS_HIDDEN_PROFILES`, and the platform adapter independently rejects private profiles before reading their labels, icons, or activities.

## Verification status

Java 17 and Android SDK 36 were supplied through Nix. The distinct Phase 1 JVM/debug suite contains 122 passing tests: 10 domain, 11 contribution, 6 UI, 33 registry processor/validator, 3 fake-host, and 59 generated-registry, lifecycle, black-box, Compose interaction, accessibility, and screenshot tests. The registry total contains 30 focused compile tests. All 59 sample tests also pass against the release variant, and 28 Paparazzi PNG baselines verify.

The required repository-wide command completed successfully after the final visibility-policy and query-projection fixes in 2 minutes 15 seconds with 2,917 Gradle tasks:

```bash
./gradlew build checkModuleBoundaries verifyNoGoogleDependencies buildHealth \
  --no-daemon --no-configuration-cache --console=plain
```

The extended local gate completed successfully on the same tree in 4 minutes 9 seconds with 2,662 Gradle tasks:

```bash
./gradlew \
  :app:assembleStableDebug \
  :app:assemblePreviewDebug \
  :app:lintStableDebug \
  :app:lintPreviewDebug \
  :app:testStableDebugUnitTest \
  :app:testPreviewDebugUnitTest \
  :prototypes:nested-scroll:assembleDebug \
  :prototypes:nested-scroll:lintDebug \
  :prototypes:nested-scroll:testDebugUnitTest \
  :prototypes:nested-scroll:licensee \
  :prototypes:platform-probe:assembleDebug \
  :prototypes:platform-probe:lintDebug \
  :prototypes:platform-probe:testDebugUnitTest \
  :prototypes:platform-probe:licensee \
  :prototypes:widget-neighbors:assembleDebug \
  :prototypes:widget-neighbors:lintDebug \
  :prototypes:widget-neighbors:testDebugUnitTest \
  :prototypes:widget-neighbors:licensee \
  :contracts:domain:test \
  :contracts:contribution:test \
  :contracts:ui:test \
  :registry:annotations:build \
  :registry:ksp:test \
  :testing:contracts:build \
  :testing:fakes:test \
  :testing:samples:assembleDebug \
  :testing:samples:lintDebug \
  :testing:samples:testDebugUnitTest \
  :testing:samples:testReleaseUnitTest \
  :testing:samples:verifyPaparazziDebug \
  :contracts:domain:licensee \
  :contracts:contribution:licensee \
  :contracts:ui:licensee \
  :registry:annotations:licensee \
  :registry:ksp:licensee \
  :testing:contracts:licensee \
  :testing:fakes:licensee \
  :testing:samples:licensee \
  :host:data:assembleDebug \
  :host:data:assembleRelease \
  :host:data:lintDebug \
  :host:data:testDebugUnitTest \
  :host:data:testReleaseUnitTest \
  :host:data:licensee \
  :host:platform:assembleDebug \
  :host:platform:assembleRelease \
  :host:platform:lintDebug \
  :host:platform:testDebugUnitTest \
  :host:platform:testReleaseUnitTest \
  :host:platform:licensee \
  :host:runtime:assembleDebug \
  :host:runtime:assembleRelease \
  :host:runtime:lintDebug \
  :host:runtime:testDebugUnitTest \
  :host:runtime:testReleaseUnitTest \
  :host:runtime:verifyPaparazziDebug \
  :host:runtime:licensee \
  :host:settings:assembleDebug \
  :host:settings:assembleRelease \
  :host:settings:lintDebug \
  :host:settings:testDebugUnitTest \
  :host:settings:testReleaseUnitTest \
  :host:settings:verifyPaparazziDebug \
  :host:settings:licensee \
  checkModuleBoundaries \
  verifyNoGoogleDependencies \
  buildHealth \
  :app:licensee \
  --no-daemon --no-configuration-cache --console=plain
```

The Phase 2/3 data suite has 82 tests. All 82 pass against both debug and release variants. The API 35 `aosp_atd` emulator ran nine Room instrumentation tests with zero failures in 1 minute 50 seconds:

```bash
./gradlew :host:data:connectedDebugAndroidTest \
  -Pkotlin.incremental=false \
  -Dkotlin.compiler.execution.strategy=in-process \
  --no-daemon --no-configuration-cache --console=plain
```

Those device tests prove a chained v1-to-v5 database migration, byte-exact unknown configuration preservation, structural app, shortcut, and crash-marker identity migration, legacy quarantine repair, the unique per-instance crash-marker constraint, all-record current-schema persistence through an unrelated rewrite and close/reopen, rollback after ordinary rejection, exception, cancellation, and malformed legacy migration, and single-winner revision concurrency across independent Room store instances. The five checked-in Room schema hashes remained unchanged across the final gates.

The Phase 3 runtime suite has 37 tests and passes against debug and release variants. Settings has seven tests per variant, platform has twelve per variant, and the stable app has two JVM policy tests. The extended gate includes the final runtime lint, license, debug/release test, and visual checks. The visual command passed for all fifteen Phase 3 PNG baselines:

```bash
./gradlew \
  :host:runtime:verifyPaparazziDebug \
  :host:settings:verifyPaparazziDebug \
  --no-daemon --no-configuration-cache --console=plain
```

The complete stable product suite passed 11 of 11 tests on the API 35 AOSP ATD image (`Android/sdk_slim_x86_64/emu64x:15/AE3A.240806.019/12368160:userdebug/test-keys`) in 1 minute 23 seconds:

```bash
./gradlew :app:connectedStableDebugAndroidTest \
  --no-daemon --no-configuration-cache --console=plain
```

The suite uses the real RoleManager dialog for both grant and cancellation, verifies the resolved public Home-settings fallback, restores prior Home holders, delivers repeated Home through `onNewIntent`, and exercises typed launch, the host-owned typed app-recovery list, Room reopen, quarantine, crash-loop fallback, and injected startup restoration.

The same complete 11-test stable product suite passed on the current GrapheneOS reference Pixel 10a in 1 minute 32 seconds:

```text
Android 17 / API 37
Build 2026081301
Fingerprint google/stallion/stallion:17/CP2A.260805.005/2026081301:user/release-keys
Security patch 2026-08-05
11 tests, 0 failures, 0 errors, 0 skipped
```

This fresh run covers the production Home-role grant and public-settings fallback, repeated Home delivery and state reset, current-profile typed launch with a stable profile serial, typed Settings app recovery, Room reopen, renderer quarantine, durable crash-loop fallback, and selected-renderer startup recovery. It restored `com.android.launcher3` as the sole Home holder after the suite. The merged Phase 3 manifest contains no `ACCESS_HIDDEN_PROFILES`; combined with the host's fail-closed private-profile filter and the unchanged Phase 0 raw-platform result on this exact GrapheneOS build, private labels and icons cannot enter the safe list, local filter, Settings recovery, diagnostics, or module state.

Dependency health produced an empty report. Module-boundary and Google Play Services/Firebase rejection passed. All applicable license and Android lint tasks passed. `nix shell nixpkgs#actionlint -c actionlint .github/workflows/ci.yml` accepted the CI workflow, and `git diff --check` reported no whitespace errors.

The recorded Phase 0 GrapheneOS probe supplies the raw Private Space and platform-behavior evidence for this same OS build; the fresh Phase 3 run above verifies the production slice built on those assumptions. No APK was published, no release was created, and signing infrastructure was not changed.

## Phase status

| Phase | Status | Remaining exit work |
| --- | --- | --- |
| 0. Project and risk spikes | Complete | None. The executable proofs and required device results are recorded. |
| 1. Contracts and generated registry | Complete | None. Major-1 contracts, compile-time registry validation, reusable suites, fakes, samples, screenshots, and repository gates pass. |
| 2. Persistence and spatial state | Complete | None. The corrected store invariants, copy semantics, configuration validation, schema evolution, deep persistence seam, and repository/device gates pass. |
| 3. Safe launcher vertical slice | Complete | None. Home role and fallback, safe layout, typed app launch and recovery, persistence, crash recovery, visual baselines, repository gates, and both required device targets pass. |
| 4. Composition, navigation, and editors | In progress | Complete registry aggregation and template placement, then build composition, typed slots, capability checks, neighbor loading, gestures, accessible editors, onboarding templates, and the reference modules. |
| 5. Widgets, shortcuts, profiles, and indicators | Not started | Implement widget and shortcut lifecycles, work-profile behavior, Private Space isolation, notification indicators, item actions, and folder overlays. |
| 6. Search | Not started | Build provider-independent retrieval and ranking, typed actions, privacy filtering, provider permissions, timeouts, and both search presentations. |
| 7. Themes and backgrounds | Not started | Add Material You and Expressive palettes, theme profiles, manual tokens, fonts, icon packs, per-app icons, backgrounds, wallpaper confirmation, validation, and visual tests. |
| 8. Backup and migration | Not started | Define the archive; add user-selected backup folders, manual and automatic galleries, encryption, staged restore, widget rebinding, profile review, and support bundles. |
| 9. Release hardening | Not started | Finish accessibility and performance gates, security audits, signing and provenance, Obtainium release metadata, key-recovery drills, and user documentation. |

## Recommended next build slice

Complete Phase 4 through the composition engine, instance scopes, typed slots, capability validation, current-plus-neighbor loading, accessible editors, and ADR 0028 onboarding templates.

## Next checkpoint

The next review should expect the Phase 4 composition seam to render the first real layout and blocks through the generated registry, preserve the host-owned safe route, and add accessible editor and navigation behavior without widening the major-1 contracts.
