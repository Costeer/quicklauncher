# Current implementation status

Reviewed on 2026-09-14. Phases 0 through 4 remain complete. Phase 5 has a production implementation and passes the local JVM, Robolectric, lint, dependency, visual, API 35 AOSP, and available GrapheneOS reference-device gates. It remains open because the attached GrapheneOS reference phone has no managed-work profile, so mandatory real work-profile discovery, badging, launch, pause, resume, and callback invalidation evidence is unavailable. The shell-installed GrapheneOS build also did not expose the installer-dependent restricted-settings grant control. Phase 6 is not yet next.

The [visual current-state overview](current-state-overview.html) summarizes the architecture, capability status, verification results, device matrix, migration chain, and open gates.

## Working product

- `:app` qualifies for the Home role, restores Room and Proto DataStore state, and uses the generated production registry and composition engine.
- `MainActivity` is the Android composition root; Phase 5 profile sanitization, notification eligibility, shortcut ownership, folder filtering, and item-action policy live behind the typed `:host:runtime` Phase 5 facade.
- The host-owned safe layout, Map, Settings, onboarding, and recovery routes remain available when contributed rendering or platform policy fails.
- Widget allocation, binding, configuration, rendering, resizing, copying, dormant retention, restoration, invalidation, and recoverable cleanup are coordinated transactionally. Current and cardinal-neighbor surfaces use distinct widget IDs and host views, and every acquired surface is released when a host overlay replaces the composition.
- Editor destination, retained-layout, and subtree removal first completes recoverable framework widget cleanup. Generic store edits reject while any affected row still owns an ID, so they cannot bypass cleanup or leak IDs.
- Pinned and dynamic shortcut discovery is keyed by profile serial, package, and shortcut ID. Duplicate placements remain distinct while per-profile/package pinned sets are reconciled as complete sets.
- Profile policy classifies personal, managed-work, and private profiles before metadata access. Work pause/resume is asynchronous and callback-driven; unavailable work content is removed without affecting personal content.
- Private Space is a host-owned `FLAG_SECURE` overlay reachable from the permanent Map command. Its separate-container visibility preference is durable and recoverable from Map and Settings. Locked, locking, unavailable, unresolved, and ambiguous states expose no private metadata; lock intent cancels reads and purges cached results before waiting for the platform transition.
- Notification access starts only from Settings. `:host:platform` reduces notifications to eligible profile/package aggregates; modules receive only `none`, `dot`, or approximate count, hidden sibling activities never inherit a visible package's indicator, and no notification content is persisted.
- Folders preserve stable identity and explicit membership order. Folder and item-action surfaces are host overlays with keyboard/D-pad alternatives, confirmation paths, predictive-Back dismissal, repeated-Home cleanup, and stable folder-overlay restoration after Activity recreation.
- Hidden apps remain available through Settings recovery. The separate search-visibility preference affects only a non-empty matching query; collection-hidden results remain excluded from folders and notification eligibility.
- Uninstall and disable visibility is decided through a typed platform eligibility check and revalidated at execution against the current profile handle, exact package/activity, user restrictions, device policy, app kind, and resolvable system route. Unknown cross-profile policy fails closed without hiding safe actions.
- Prepared content contains immutable typed identity, semantic kind, profile/badge and availability state, sanitized indicator state, and an opaque widget surface token. Contribution modules receive neither Android objects nor unrestricted inventories.
- Room schema 6 and task-level `LauncherStore` edits cover widget pending/bound/cleanup states, shortcut placements, folder membership, favorites, visibility, and search visibility while retaining optimistic revision checks and atomic rejection.

The authoritative contribution rules are in [the contract index](../contracts/README.md). Architecture boundaries and sequencing are in [the architecture plan](../architecture/launcher-architecture-plan.md). Current Android behavior research is in [the Phase 5 platform note](../research/phase-5-android-platform-behavior.md).

## Phase 5 device-gate status

- The required full API 35 AOSP emulator matrix passed: 50 tests, 49 passed, 1 skipped, and 0 failed. Real widget bind denial/success and host-view recreation, notification Settings routes, Room, profile fallback, process recreation, overlays, Home reentry, and restoration passed. Shortcut pin success was skipped because the AOSP image did not grant shortcut-host access to the temporary Home holder.
- A supplemental Nothing A015 on Android 16/API 36 passed the final connected matrix: 45 tests, 43 passed, 2 skipped. It found and verified repairs to a duplicate-shortcut Room fixture and an Activity-recreation composition cleanup race. Widget bind success/host-view recreation and shortcut pinning were skipped because this OEM build could not grant the corresponding test-only host authorities; the remaining widget, profile fallback, notification, Room, overlay, actual Activity recreation, repeated-Home, and recovery tests passed.
- The GrapheneOS Pixel 10a on official Android 17 security-preview build `2026091001` passed 39 discovered connected tests with 38 passes, one unsupported shortcut-host-authority skip, and no failures. Real Private Space classification, locked suppression, secure overlay, system authentication, asynchronous unlock, sanitized launch, relock, cache purge, widgets, notification revocation, repeated Home, and restoration passed. API-37-incompatible Espresso surface suites remain covered by the required API 35 run.
- `ACCESS_HIDDEN_PROFILES` is declared now that the complete host-owned separate container, system hide/show settings route, lock/unlock/authentication flow, callback refresh, early metadata filtering, and immediate purge behavior are integrated and exercised on the reference device.
- The reference phone has personal, Private Space, and two full secondary users, but no managed-work profile. Full secondary users are not substituted for the missing work-profile evidence. Its restricted-settings path rejected listener operation and the typed recovery opened App Info, but the shell-installed build's menu did not offer the documented recovery grant; completion is not claimed.

The open evidence checklist is [Phase 5 device evidence](phase-5-device-evidence.md). The concise blocker and independent review prompt are in [Phase 5 blocker and review prompt](phase-5-blocker-and-review-prompt.md). Phase 5 must not be marked complete, and Phase 6 must not become next, until every item there passes.

## Last verified local gates

```bash
./gradlew build checkModuleBoundaries verifyNoGoogleDependencies buildHealth \
  --no-daemon --no-configuration-cache --console=plain
```

Passed in 7m47s after the reference-device and API-level fixes: 3,730 actionable tasks (539 executed, 30 from cache, 3,161 up-to-date). This includes debug and release unit tests, Android lint, license checks, registry/contract suites, module-boundary validation, Google dependency rejection, and dependency health. The generated XML reports contain 1,095 tests, 0 failures, 0 errors, and 0 skips.

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

Passed in 2m58s: 279 actionable tasks (3 executed, 3 from cache, 273 up-to-date). There are 121 inspected baselines, including 29 Phase 5 widget, folder, item-action, Private Space, and recovery images across normal, loading, locked, empty, light, dark, portrait, landscape, and large-text scenarios.

```bash
nix shell nixpkgs#actionlint -c actionlint .github/workflows/ci.yml
git diff --check
adb devices -l
```

`actionlint` and `git diff --check` passed after the final reference-device fixes. The final attached-device audit found the reference Pixel connected, Private Space stopped and quiet, Launcher3 holding Home, the original Launcher3 listener value restored exactly, and no Quicklauncher package installed.

Every `licensee` task named by CI also passed explicitly in 40 seconds: 36 actionable tasks, all up-to-date. The command derives the exact task set maintained by CI:

```bash
rg -o ':[A-Za-z0-9:_-]+:licensee' .github/workflows/ci.yml | sort -u | \
  xargs ./gradlew --no-daemon --no-configuration-cache --console=plain
```

The same connected command passed on the full API 35 AOSP phone image in 3m09s (384 actionable tasks: 5 executed, 379 up-to-date): 50 tests, 49 passed, 1 skipped, and 0 failed. The image was `Android/sdk_phone64_x86_64/emu64x:15/AE3A.240806.019/12368160:userdebug/test-keys`, build `AE3A.240806.019`. The only skip was shortcut pin success because the image did not grant shortcut-host access. The original `com.android.launcher3` Home holder, empty notification-listener value, package set, and empty Quicklauncher widget state were restored.

The connected command passed on the supplemental Android 16/API 36 device in 2m34s (384 actionable tasks: 5 executed, 379 up-to-date):

```bash
./gradlew \
  :host:data:connectedDebugAndroidTest \
  :host:platform:connectedDebugAndroidTest \
  :host:runtime:connectedDebugAndroidTest \
  :host:editor:connectedDebugAndroidTest \
  :app:connectedStableDebugAndroidTest \
  --no-daemon --no-configuration-cache --console=plain
```

It ran 45 tests: 43 passed, 2 unsupported authority-dependent paths skipped, and none failed. The module totals were Room/data 15, platform 7, runtime 5, editor 4, and production app 14. The app tests include real `MainActivity.recreate()`, folder restoration and Back dismissal, item-action and repeated-Home dismissal, and App Recovery semantics/actions. The original Home holder and locked/quiet Private Space state were restored, Quicklauncher notification access was absent, and Gradle removed all Quicklauncher test packages. This supplemental run does not replace the required GrapheneOS evidence above.

## Deliberate limits

- Provider-backed search and ranking, contacts/files/web providers, theming, icon packs, wallpaper editing, backup archives, scheduled backups, macrobenchmark thresholds, and publication remain later-phase work.
- Private Space hidden-profile access is limited to the complete host-owned secure container and typed system routes described above; private content remains suppressed on every unresolved or policy-ambiguous path.
- Destructive item actions remain fail-closed whenever the platform cannot prove current eligibility; supported uninstall and disable routes are exposed only after that proof and require confirmation.
- No APK was published, no release was created, and signing infrastructure was not changed.

## Next slice

Phase 5 completion: attach a compliant GrapheneOS reference device with a real managed-work profile and run discovery, mandatory badging, launch, pause, resume, and callback invalidation. Rerun the installer-dependent restricted-settings grant from the intended distribution path. Phase 6 follows only after those external gates pass.
