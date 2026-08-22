# Current implementation status

Reviewed on 2026-08-22 against the [architecture plan](../architecture/launcher-architecture-plan.md), accepted ADRs, and the current working tree.

## Summary

Phases 0 and 1 are complete. Phase 2, persistence and spatial state, is the next build stage and has not begun.

The production app is still not a usable launcher. It renders an empty Compose activity and does not yet register as Home, persist launcher state, show apps, or compose contributions. Those capabilities begin in later phases.

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

## Verification status

Java 17 and Android SDK 36 were supplied through Nix. The distinct Phase 1 JVM/debug suite contains 122 passing tests: 10 domain, 11 contribution, 6 UI, 33 registry processor/validator, 3 fake-host, and 59 generated-registry, lifecycle, black-box, Compose interaction, accessibility, and screenshot tests. The registry total contains 30 focused compile tests. All 59 sample tests also pass against the release variant, and 28 Paparazzi PNG baselines verify.

The required repository-wide command completed successfully in 1 minute 42 seconds with 2,752 Gradle tasks:

```bash
./gradlew build checkModuleBoundaries verifyNoGoogleDependencies buildHealth \
  --no-daemon --no-configuration-cache --console=plain
```

The extended local gate completed successfully in 1 minute 28 seconds with 2,377 Gradle tasks:

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
  checkModuleBoundaries \
  verifyNoGoogleDependencies \
  buildHealth \
  :app:licensee \
  --no-daemon --no-configuration-cache --console=plain
```

Dependency health produced no advice or warnings. Module-boundary and Google Play Services/Firebase rejection passed. All applicable license and Android lint tasks passed. `nix-shell -p actionlint --run 'actionlint .github/workflows/ci.yml'` accepted the CI workflow, and `git diff --check` reported no whitespace errors.

The recorded Phase 0 emulator and GrapheneOS evidence remains unchanged. No APK was published, no release was created, and signing infrastructure was not changed.

## Phase status

| Phase | Status | Remaining exit work |
| --- | --- | --- |
| 0. Project and risk spikes | Complete | None. The executable proofs and required device results are recorded. |
| 1. Contracts and generated registry | Complete | None. Major-1 contracts, compile-time registry validation, reusable suites, fakes, samples, screenshots, and repository gates pass. |
| 2. Persistence and spatial state | Next | Implement the versioned Room model, typed configuration storage, geometry, transactional editing, migrations, and deterministic persistence tests. |
| 3. Safe launcher vertical slice | Not started | Add Home role onboarding, package and profile adapters, app launching, a safe layout, settings, permission flows, crash markers, quarantine, and diagnostics. |
| 4. Composition, navigation, and editors | Not started | Build composition, typed slots, capability checks, neighbor loading, gestures, accessible editors, and the first reference modules. |
| 5. Widgets, shortcuts, profiles, and indicators | Not started | Implement widget and shortcut lifecycles, work-profile behavior, Private Space isolation, notification indicators, item actions, and folder overlays. |
| 6. Search | Not started | Build provider-independent retrieval and ranking, typed actions, privacy filtering, provider permissions, timeouts, and both search presentations. |
| 7. Themes and backgrounds | Not started | Add Material You and Expressive palettes, theme profiles, manual tokens, fonts, icon packs, per-app icons, backgrounds, wallpaper confirmation, validation, and visual tests. |
| 8. Backup and migration | Not started | Define the archive; add user-selected backup folders, manual and automatic galleries, encryption, staged restore, widget rebinding, profile review, and support bundles. |
| 9. Release hardening | Not started | Finish accessibility and performance gates, security audits, signing and provenance, Obtainium release metadata, key-recovery drills, and user documentation. |

## Recommended next build slice

Begin Phase 2 with the versioned persistence model and spatial-state invariants. ADR 0026's contract-design prerequisite is satisfied; this work did not publish an APK or create a release.

## Next checkpoint

The next review should expect a Phase 2 persistence schema and migration plan grounded in the completed major-1 identities, descriptors, configuration documents, and codecs.
