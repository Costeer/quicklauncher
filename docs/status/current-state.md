# Current implementation status

Reviewed on 2026-09-17. Phases 0 through 7 are complete after independent Phase 7 repairs. Phase 8 is in progress with the published deterministic archive envelope implemented in `:host:backup`. The Phase 7 theme implementation has passed its local, visual, contract, dependency, privacy, Robolectric, and required API 35 AOSP gates. The GrapheneOS, real managed-profile, and physical-device restoration results below remain owner-supplied evidence reviewed, not independently rerun.

The [visual current-state overview](current-state-overview.html) summarizes the architecture, capability status, verification results, device matrix, migration chain, and completed gates.

## Working product

- `:app` qualifies for the Home role, restores Room and Proto DataStore state, and uses the generated production registry and composition engine.
- `MainActivity` is the Android composition root; profile sanitization, notification eligibility, shortcut ownership, folder filtering, item-action policy, search candidate preparation, provider availability, and typed search execution live behind host-owned runtime and platform seams.
- The host-owned safe layout, Map, Settings, onboarding, and recovery routes remain available when contributed rendering or platform policy fails.
- Widget allocation, binding, configuration, rendering, resizing, copying, dormant retention, restoration, invalidation, and recoverable cleanup are coordinated transactionally. Current and cardinal-neighbor surfaces use distinct widget IDs and host views, and every acquired surface is released when a host overlay replaces the composition.
- Editor destination, retained-layout, and subtree removal first completes recoverable framework widget cleanup. Generic store edits reject while any affected row still owns an ID, so they cannot bypass cleanup or leak IDs.
- Pinned and dynamic shortcut discovery is keyed by profile serial, package, and shortcut ID. Duplicate placements remain distinct while per-profile/package pinned sets are reconciled as complete sets.
- Profile policy classifies personal, managed-work, and private profiles before metadata access. Work pause/resume is asynchronous and callback-driven; unavailable work content is removed without affecting personal content.
- Private Space is a host-owned `FLAG_SECURE` overlay reachable from the permanent Map command. Its separate-container visibility preference is durable and recoverable from Map and Settings. Locked, locking, unavailable, unresolved, and ambiguous states expose no private metadata; lock intent cancels reads and purges cached results before waiting for the platform transition.
- Notification access starts only from Settings. `:host:platform` reduces notifications to eligible profile/package aggregates; modules receive only `none`, `dot`, or approximate count, hidden sibling activities never inherit a visible package's indicator, and no notification content is persisted.
- Folders preserve stable identity and explicit membership order. Folder and item-action surfaces are host overlays with keyboard/D-pad alternatives, confirmation paths, predictive-Back dismissal, repeated-Home cleanup, and stable folder-overlay restoration after Activity recreation.
- Hidden apps remain available through Settings recovery. The separate search-visibility preference affects only a non-empty matching query; collection-hidden results remain excluded from folders and notification eligibility.
- Each visible search presentation owns one bounded host session. Validated query generations replace prior work, enabled providers run concurrently with provider-local first-result timeouts and limits, healthy partial results publish immediately, and close or composition removal cancels provider sessions and owned coroutines. Stale, malformed, post-close, and failed-provider emissions cannot cross into immutable presentation snapshots.
- Nine generated-registry providers cover profile-aware apps and shortcuts, registered commands, contacts, user-authorized files, public Android Settings, a manually enabled versioned GrapheneOS Settings catalog, safe HTTPS web actions, and recovery information. The search block is presentation-only and emits typed actions.
- Ranking is deterministic across normalized provider relevance, exact/prefix/token/fuzzy match quality, bounded decaying app/shortcut launch history, duplicate-target merging, and stable identity tie-breaking. History cannot encode queries, contacts, files, web targets, or Private Space metadata.
- Contacts and files fail closed and mutate only their own enabled-provider preference after denial or revocation. Live profile callbacks remove ineligible results and ephemeral targets immediately; work results are badged before crossing the host boundary, and Private Space candidates are rejected before protected metadata reads.
- Uninstall and disable visibility is decided through a typed platform eligibility check and revalidated at execution against the current profile handle, exact package/activity, user restrictions, device policy, app kind, and resolvable system route. Unknown cross-profile policy fails closed without hiding safe actions.
- Prepared content contains immutable typed identity, semantic kind, profile/badge and availability state, sanitized indicator state, and an opaque widget surface token. Contribution modules receive neither Android objects nor unrestricted inventories.
- Room schema 7 and task-level `LauncherStore` edits cover widget pending/bound/cleanup states, shortcut placements, folder membership, favorites, visibility, search visibility, and at most 256 app/shortcut ranking-history rows while retaining optimistic revision checks and atomic rejection.
- Phase 7 resolves system appearance and accessibility inputs once in `:host:runtime`. Named built-in/custom profiles, Material You/Expressive/manual palettes, capability-bounded overrides, fonts, icons, launcher/destination backgrounds, image derivation, and wallpaper commands all converge on the same immutable resolved theme used by the root Material surface, host UI, production composition, cold restore, and previews.
- Static/variable font and image imports begin only from explicit Settings actions, pass bounded host/platform validation, and retain opaque private copies. Nova/ADW mappings are declarative and bounded. Contributions receive only resolved fonts, prepared icon bytes, background identity, and contrast—not Android objects, paths, URIs, source images, or inventories.
- Wallpaper preview and execution are separate typed operations. The user acknowledges the displacement warning and selects Home, Lock, or Both; ownership, crop preview generation, current platform state, and support are revalidated immediately before the only `WallpaperManager` call.
- The Phase 8 archive codec remains pure and cannot mutate live state. Format v1 has immutable inputs/results, canonical section order, strict logical names, fixed count/size ceilings, per-section SHA-256 validation, one required launcher-map section, and typed rejection of unsupported, malformed, truncated, corrupt, or trailing input. Optional empty categories are omitted. Passphrase protection is a separate bounded AES-256-GCM wrapper with authenticated PBKDF2 parameters; archive feature flags remain zero.
- The Phase 8 release candidate uses persisted SAF authorization and bounded provider reads. Manual and automatic inventory pages are separate. Plain inventory is classified by decoded kind and creation time; protected wrapper magic keeps encrypted manual archives discoverable until preview authenticates them. Retention removes only decoded automatic archives after the newest seven.
- Automatic backup is one persisted charging-only job for the next local 03:00 boundary and may export only before 06:00. Completion, failure, interruption, or a late delivery schedules the next night. The stored enabled preference remains true only while the expected job exists or rescheduling succeeds.
- Restore review validates current theme and destination asset references against canonical imported sections and an exact optional SHA-256 manifest. Themes and assets are one selection; destination-map and web-adapter data remain separately selectable. Opaque launcher extensions and web-adapter entries persist across restore, process recreation, and later export without execution. Rejected or cancelled work rolls back auxiliary data, and theme reconciliation removes an interrupted private staging directory.
- Post-restore Settings actions open document authorization, app permission Settings, widget rebinding, quarantine review, and profile review. Recovery counts survive Activity recreation through saved instance state. A pending restore preview expires on recreation and must be created again. Support diagnostics use a bounded typed private log and clear only the exported prefix after a successful export.

The authoritative contribution rules are in [the contract index](../contracts/README.md). Architecture boundaries and sequencing are in [the architecture plan](../architecture/launcher-architecture-plan.md). The accepted theme delivery boundary is in [the Phase 7 theme scope](phase-7-theme-scope.md). Current Android behavior research is in the [Phase 5 platform note](../research/phase-5-android-platform-behavior.md) and [Phase 6 search platform note](../research/phase-6-search-platform-behavior.md).

## Phase 5 device-gate status

- The required full API 35 AOSP emulator matrix passed: 50 tests, 49 passed, 1 skipped, and 0 failed. Real widget bind denial/success and host-view recreation, notification Settings routes, Room, profile fallback, process recreation, overlays, Home reentry, and restoration passed. Shortcut pin success was skipped because the AOSP image did not grant shortcut-host access to the temporary Home holder.
- A supplemental Nothing A015 on Android 16/API 36 passed the final connected matrix: 45 tests, 43 passed, 2 skipped. It found and verified repairs to a duplicate-shortcut Room fixture and an Activity-recreation composition cleanup race. Widget bind success/host-view recreation and shortcut pinning were skipped because this OEM build could not grant the corresponding test-only host authorities; the remaining widget, profile fallback, notification, Room, overlay, actual Activity recreation, repeated-Home, and recovery tests passed.
- The earlier GrapheneOS Pixel 10a baseline on official Android 17 security-preview build `2026091001` passed 39 discovered connected tests with 38 passes, one unsupported shortcut-host-authority skip, and no failures. Real Private Space classification, locked suppression, secure overlay, system authentication, asynchronous unlock, sanitized launch, relock, cache purge, widgets, notification revocation, repeated Home, and restoration passed. API-37-incompatible Espresso surface suites remain covered by the required API 35 run.
- `ACCESS_HIDDEN_PROFILES` is declared now that the complete host-owned separate container, system hide/show settings route, lock/unlock/authentication flow, callback refresh, early metadata filtering, and immediate purge behavior are integrated and exercised on the reference device.
- A disposable profile created through Android ManagedProvisioning was reported as a real managed profile and passed discovery, mandatory badging, app and dynamic-shortcut launch, pause, immediate removal, personal continuity, resume, and callback restoration. The final focused test passed in 7.73 seconds.
- Official Obtainium 1.6.17 installed the disposable app through its supported Direct APK Link path, and Android recorded Obtainium as installer. Explicit denial, App Info recovery, later grant, listener-process restart, visible revocation, immediate cleanup, and exact listener restoration passed without inspecting notification content.
- The owner-supplied final Phase 5 GrapheneOS matrix passed in 3m08s: 384 actionable tasks, 39 tests, 37 passes, 2 explicit authority/orchestration skips, and no failures. The separately enabled managed-profile test passed. Final restoration removed the disposable profile and packages, kept Private Space locked/quiet, and restored Launcher3 Home and listener state exactly.

The completed evidence record is [Phase 5 device evidence](phase-5-device-evidence.md). The independent review prompt is in [Phase 5 completion and review prompt](phase-5-blocker-and-review-prompt.md).

## Phase 6 gate status

- The complete implementation and evidence ledger is [Phase 6 search evidence](phase-6-search-evidence.md). It records engine, provider, executor, presentation, persistence, privacy, and restoration behavior without raw queries or protected personal data.
- The final current-worktree API 35 full-phone AOSP matrix passed all five modules in 5m 9s: 390 actionable tasks, 62 tests, 59 passed, 3 explicit authority-dependent skips, and 0 failures or errors. Search permission, grant, revocation, route validation, predictive Back, recreation, repeated Home, and the Phase 7 root-theme wiring ran against the current code.
- Before independent repairs, the owner-supplied API 35 full-phone AOSP matrix passed all five modules: 58 tests, 55 passed, 3 explicit authority-dependent skips, and 0 failures. Search adapter permission/grant/revocation, URI/Settings validation, lifecycle, recreation, Back, and repeated-Home paths were included.
- Before independent repairs, the owner-supplied GrapheneOS Pixel 10a matrix passed in 221 seconds: 390 actionable tasks, 47 tests, 44 passes, 3 explicit skips, and no failures. The skips were shortcut-host authority and the two separately gated managed-profile cases. This evidence was reviewed, not independently rerun.
- Android's visible provisioning flow created an enabled `RUNNING_UNLOCKED` managed profile owned by the synthetic debug DPC. The separately enabled Phase 5 app/shortcut test passed in 9.332 seconds, and the Phase 6 active-search transition test passed in 6.525 seconds. Both ran one test without a skip or failure.
- Final restoration reproduced the exact pre-run user-record hash, retained users `0,10,11,12`, quiet Private Space and both secondary users, restored Launcher3 Home and the exact original listener value, and left no managed profile, Quicklauncher package, widget, URI grant, synthetic contact row, or temporary device dump.

## Phase 7 gate status

- The implementation and complete command ledger are in [Phase 7 theme evidence](phase-7-theme-evidence.md); an independent rerun is specified by the [Phase 7 validation prompt](phase-7-validation-prompt.md).
- Room remains at schema 7 because its existing versioned theme/background records and the existing Proto selected-profile field meet the durable contract. Built-ins create no rows. A destination background selected from a built-in atomically materializes a derived custom profile before selection. Existing 1→7 migration, rollback, malformed-row isolation, conflict, reopen, and opaque-byte checks remain green.
- The independent exact local rerun passed all 3,807 actionable tasks. The six-module visual gate passed all 285 actionable tasks, and all nine changed Phase 7 profile, manual-palette, and wallpaper settings baselines were inspected at normal/large text and portrait/landscape light/dark states.
- The final pinned API 35 AOSP matrix passed 69 tests: 66 passed, 3 explicit authority-dependent skips, 0 failures, and 0 errors. Phase 7 synthetic font/image, revoked access, icon-pack, displayed preview, stale-command, and cleanup cases passed. Exact emulator restoration was confirmed before shutdown.
- GrapheneOS was excluded as directed. Its existing results are owner-supplied evidence reviewed, not independently rerun; the exclusion is neither a failure nor an independent pass.

## Last verified local gates

```bash
./gradlew build checkModuleBoundaries verifyNoGoogleDependencies buildHealth \
  --no-daemon --no-configuration-cache --console=plain
```

The final Phase 7 code passed the independent exact rerun in 5m25s: 3,807 actionable tasks (711 executed, 40 from cache, and 3,056 up-to-date). This includes debug and release unit tests, Android lint, license checks, registry/contract suites, module-boundary validation, Google dependency rejection, and dependency health. The subsequently added Phase 8 archive slice has its own focused unit/lint and dependency-health results in [the Phase 8 scope](phase-8-backup-scope.md).

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

Passed on the current code in 1m 56s: 285 actionable tasks (6 executed and 279 up-to-date), covering 299 passing tests. The 130 inspected baselines include nine new theme-profile, manual-palette, and wallpaper states at normal and large text plus the earlier search, widget, folder, item-action, Private Space, and recovery scenarios.

```bash
nix shell nixpkgs#actionlint -c actionlint .github/workflows/ci.yml
git diff --check
adb devices -l
```

The final `actionlint` rerun passed in 1.42s and `git diff --check` passed in under 0.1s on the current worktree. The later API 35 run was pinned to `emulator-5554`; the excluded physical device was not queried or changed.

Every `licensee` task named by CI also passed explicitly in 12 seconds: 23 declared tasks expanded to 38 actionable tasks, all up-to-date. The command derives the exact task set maintained by CI:

```bash
rg -o ':[A-Za-z0-9:_-]+:licensee' .github/workflows/ci.yml | sort -u | \
  xargs ./gradlew --no-daemon --no-configuration-cache --console=plain
```

The independent current-worktree connected command passed on the full API 35 AOSP phone image in 3m26s (390 actionable tasks: 5 executed, 385 up-to-date): 69 tests, 66 passed, 3 skipped, and 0 failed or errored. The image was `Android/sdk_phone64_x86_64/emu64x:15/AE3A.240806.019/12368160:userdebug/test-keys`, build `AE3A.240806.019`. The skips were shortcut-host success and the two explicitly gated managed-profile cases. The original `com.android.launcher3` Home holder, Settings values, wallpaper names, and pre-existing empty notification-listener key/value were restored; only the Owner user remained, there was no device owner, and no Quicklauncher package, grant, widget, provider reference, or synthetic test file remained before the emulator stopped with snapshots disabled.

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

- Search web behavior intentionally opens validated external HTTPS destinations; it does not retrieve remote result data. Restored portable web-adapter records stay inert. Phase 8 now has typed payloads and fixtures, SAF folder access, manual backups, one-shot 03:00 through 06:00 local charging-only automatic backups, encryption, staged category restore, asset-reference and optional-manifest validation, recovery actions, support bundles, and inspected large-text restore actions. It is still in progress. Final gates, API 35 evidence, macrobenchmark thresholds, and publication remain open. Phase 7 supports the documented Nova/ADW declarative subset only; other icon-pack dialects remain out of scope.
- Private Space hidden-profile access is limited to the complete host-owned secure container and typed system routes described above; private content remains suppressed on every unresolved or policy-ambiguous path.
- Destructive item actions remain fail-closed whenever the platform cannot prove current eligibility; supported uninstall and disable routes are exposed only after that proof and require confirmation.
- No APK was published, no release was created, and signing infrastructure was not changed.

## Next slice

Continue Phase 8 with the final local, release, lint, visual, license, workflow, diff, and pinned API 35 AOSP gates. Capture the final fingerprint first, restore the emulator exactly, and stop it without saving a snapshot. Do not treat the focused passes as Phase 8 completion evidence.
