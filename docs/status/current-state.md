# Current implementation status

Reviewed on 2026-08-20 against the [architecture plan](../architecture/launcher-architecture-plan.md) and the current working tree.

## Summary

Quicklauncher has a detailed architecture and an Android project skeleton. It is not yet a usable launcher.

Phase 0 is complete and closed. The repository has the Gradle foundation, release channels, dependency checks, CI workflow, identity types, and three isolated executable risk probes. The API 35 emulator and GrapheneOS Pixel 10a provide the required device evidence under ADR 0029.

The production app still renders an empty Compose activity. It cannot register as the device Home app, show apps, navigate destinations, or persist launcher state.

Phases 1 through 9 have not started beyond placeholder Gradle modules.

## What exists now

### Product and architecture documents

- [`CONTEXT.md`](../../CONTEXT.md) defines the product language and system boundaries.
- The [`docs/adr`](../adr/) directory contains 29 accepted architecture decisions.
- The [consolidated architecture plan](../architecture/launcher-architecture-plan.md) defines the module model, Android platform boundaries, release phases, and 1.0 acceptance checklist.
- [Release-signing research](../research/launcher-release-signing.md) records the signing and Obtainium findings.

The product decisions are specific enough to begin implementation. More general planning is not the current blocker.

### Android project foundation

- The Gradle 8.13 wrapper and Android Gradle Plugin 8.13.2 are configured.
- The project compiles against Android 36, targets Android 35, and has a minimum SDK of 35.
- Kotlin 2.2.21, Java 17, and Compose are configured.
- Stable and preview product flavors use separate application IDs.
- The root build checks module dependency direction and rejects Google Play Services and Firebase dependencies.
- The app applies an Apache 2.0 license allowlist.
- GitHub Actions defines stable and preview build, lint, unit-test, dependency, and license jobs.
- Placeholder modules exist for contracts, registry generation, host services, and testing support.

Most of these modules currently contain build files only. `:contracts:domain` now contains validated package and profile identity value types with unit coverage; the rest of the production contracts and host implementations start in Phase 1.

### Gesture prototype

The [`prototypes/nested-scroll`](../../prototypes/nested-scroll/) app contains a state machine, a Compose demonstration, seven unit tests, and eight instrumentation tests. The [recorded prototype result](../prototypes/nested-scroll.md) says the API 35 emulator suite passed.

The GrapheneOS Pixel 10a manual state checks and all eight connected Compose tests pass. The physical run exposed an old Espresso input path; pinning Espresso 3.7.0 fixed it on API 37. CI builds, lints, license-checks, and unit-tests all three probes, and an API 35 emulator job runs the nested-scroll instrumentation suite. Boundary handoff is accepted as the default, while edge-only navigation remains a user option.

### Widget and platform probes

The [widget-neighbor proof](../prototypes/widget-neighbors.md) hosts separate live widget IDs in the current destination and neighbor preview. Its build, lint, license check, five unit tests, and API 35 emulator procedure pass, including cancellation cleanup.

The [platform behavior probe](../prototypes/platform-probe.md) exercises Home-role state, repeated Home delivery, predictive Back, profile visibility, and safe Settings routing. Its build, lint, license check, and eight unit tests pass. Predictive Back cancellation and commit pass on the API 35 emulator and GrapheneOS Pixel 10a. On the physical device, repeated Home, profile transitions, widget hosting, and route guards were also exercised. Two findings now shape production work: locked Private Space queries can still return activities, and the role request can return without displaying a chooser or granting the role.

### Production app

The production app contains an empty `MainActivity`. Its manifest registers a normal launcher icon with `MAIN` and `LAUNCHER`, but it does not declare `HOME` and `DEFAULT`. Android therefore cannot select it as the default launcher.

No production implementation exists yet for:

- destination and block contracts;
- generated contribution registration;
- spatial navigation or editing;
- app discovery and launching;
- persistence or migrations;
- widgets, shortcuts, profiles, or Private Space;
- search;
- themes, fonts, icons, and backgrounds;
- backups and restore;
- launcher settings, diagnostics, or recovery.

## Verification status

Java 17 is supplied reproducibly through Nix in this environment. Android builds use SDK 36; device checks use the local API 35 emulator. The domain tests, probe builds, probe JVM tests, lint checks, license checks, and the eight-test nested-scroll instrumentation suite were independently reproduced on 2026-08-20.

The final combined gate completed successfully in 42 seconds: 2,377 Gradle tasks covered stable and preview app assembly, lint and unit tests; all three probe assemblies, lint checks, JVM tests, and license checks; domain tests; module-boundary validation; Google dependency rejection; and repository-wide dependency health. `actionlint` 1.7.12 also accepted the updated GitHub Actions workflow, and `git diff --check` reported no whitespace errors.

The repository also has no commits and no configured Git remote. Most project files are staged or untracked. There is no immutable baseline yet, and the GitHub Actions workflow cannot have run from this local repository configuration.

The local verification command is:

```bash
./gradlew \
  :app:assembleStableDebug \
  :app:assemblePreviewDebug \
  :app:lintStableDebug \
  :app:lintPreviewDebug \
  :app:testStableDebugUnitTest \
  :app:testPreviewDebugUnitTest \
  :prototypes:nested-scroll:testDebugUnitTest \
  :prototypes:platform-probe:testDebugUnitTest \
  :prototypes:widget-neighbors:testDebugUnitTest \
  :contracts:domain:test \
  checkModuleBoundaries \
  verifyNoGoogleDependencies \
  buildHealth \
  :app:licensee
```

Run the prototype instrumentation suite separately on an API 35 emulator. The recorded GrapheneOS Pixel result supplies the required physical-device evidence.

## Phase status

| Phase | Status | Remaining exit work |
| --- | --- | --- |
| 0. Project and risk spikes | Complete | None. The executable proofs and required device results are recorded. |
| 1. Contracts and generated registry | Not started | Write five contract specifications; implement IDs, descriptors, settings, actions, codecs, and contract versions; build the KSP registry; add compile tests, a contribution test kit, fakes, and skeletal contributions. |
| 2. Persistence and spatial state | Not started | Add Room and Proto DataStore; implement `LauncherStore`, migrations, configuration documents, map operations, drop commits, dormant layout state, copy, and clone-ready references. |
| 3. Safe launcher vertical slice | Not started | Add Home role onboarding, package and profile adapters, app launching, a safe layout, settings, permission flows, crash markers, quarantine, and diagnostics. |
| 4. Composition, navigation, and editors | Not started | Build composition, typed slots, capability checks, neighbor loading, gestures, accessible editors, and the first reference modules. |
| 5. Widgets, shortcuts, profiles, and indicators | Not started | Implement widget and shortcut lifecycles, work-profile behavior, Private Space isolation, notification indicators, item actions, and folder overlays. |
| 6. Search | Not started | Build provider-independent retrieval and ranking, typed actions, privacy filtering, provider permissions, timeouts, and both search presentations. |
| 7. Themes and backgrounds | Not started | Add Material You and Expressive palettes, theme profiles, manual tokens, fonts, icon packs, per-app icons, backgrounds, wallpaper confirmation, validation, and visual tests. |
| 8. Backup and migration | Not started | Define the archive; add user-selected backup folders, manual and automatic galleries, encryption, staged restore, widget rebinding, profile review, and support bundles. |
| 9. Release hardening | Not started | Finish accessibility and performance gates, security audits, signing and provenance, Obtainium release metadata, key-recovery drills, and user documentation. |

## Recommended next build slice

Start Phase 1 with the five contract documents and generated-registry foundation. ADR 0026 still blocks a public APK until that contract gate passes.

## Next checkpoint

The next review should expect:

- the five contribution contract specifications;
- the first generated-registry implementation and compile tests;
- a first commit and a configured release remote.

The Phase 0 checkpoint is closed. New product work begins with the Phase 1 contracts rather than the empty activity.
