# Current implementation status

Reviewed on 2026-09-18. Phases 0 through 8 are complete. Phase 9 release machinery is implemented, and its final repository gates, separate two-axis reviews, owner-authorized current GrapheneOS compatibility matrix, and pinned API 35 accessibility/performance matrix pass. `P9-RELEASE` still needs protected real-draft verification. Phase 8 closed at source fingerprint `65820aea82c057acf53678db1dbc5a5946eeba962f7df7d5ca653ae55d086656`. Earlier GrapheneOS and real managed-profile results below remain labeled as owner-supplied historical evidence; the new Phase 9 runs are recorded separately.

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
- Android SAF, persisted-grant, and job-scheduler adapters live in `:host:platform`; `:host:backup` retains only the pure nightly wall-clock policy. Composite auxiliary staging cleans up already acquired stages after a later acquisition failure or post-stage cancellation, and asset reconciliation keys cleanup by exact kind plus stable ID.
- Phase 9 adds an isolated release-mode macrobenchmark boundary, fail-closed thresholds and result metadata checks, exact security/privacy audits, separate protected stable/preview draft workflows, independent checksum/certificate/provenance verification, Obtainium channel metadata, disposable-key recovery, and user-facing installation and recovery guidance. The pinned API 35 benchmark now supplies real measurements; it does not claim a production-signed artifact.

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

## Phase 8 gate status

- The complete implementation and command ledger are in [Phase 8 backup evidence](phase-8-backup-evidence.md). The final full local gate passed 3,831 actionable tasks and 713 tests with no failures, errors, or skips.
- The final six-module no-record Paparazzi gate passed 334 tests. The four backup-review baselines were inspected for hierarchy, contrast, wrapping, clipping, and visibility of all review categories plus restore/cancel actions at normal and 2x text.
- All 23 CI-declared license entry tasks expanded to 38 passing actionable tasks; `actionlint`, whitespace, repository/privacy/release, boundary, and backup-rule audits passed.
- The final pinned API 35 AOSP matrix passed 78 tests: 75 passed, 3 explicit capability skips, and 0 failures/errors. The exact baseline state was restored, the normalized semantic diff was empty, and the emulator stopped with snapshots disabled.
- Final spec review found no issues. Final standards review found no documented-standard violations; two medium divergent-responsibility smells and one low internal restore-port surface smell remain non-blocking follow-up.
- No GrapheneOS device was queried or used for Phase 8. Existing GrapheneOS results remain owner-supplied only.

## Phase 9 gate status

- The authoritative implementation, command, failure, repair, and open-prerequisite ledger is [Phase 9 release evidence](phase-9-release-evidence.md); bounded packet status is in [Phase 9 packets](phase-9-packets.md).
- The complete post-API 35 gate passes `build`, module boundaries, no-Google policy, dependency health, and `checkPhase9Performance` in 68s with 4,038 actionable tasks on repository-gate fingerprint `f8a76ee4c87da5749f52f9fc93cb262cb9434358e81c8e97d9433e71840fa97f`. The final six-module no-record Paparazzi rerun passes 365 tests in 21s with zero mismatches, and all 146 repository PNG hashes remain unchanged. Earlier fully executed candidates `33e764da0583059d07ca21906d824964a0b988aa9a39f4193e0015cbcf8d5eac` and `24c17342eba1277a137c91c63f1aea322cffb05fea6e21b1abe2cdbdbc5be679` remain historical evidence.
- Exact security/privacy tooling passes 20 tests and canonical stable/preview manifest audits. Release tooling passes 38 tests, stable/preview dummy artifact drills, workflow/channel validation, three-workflow `actionlint`, and disposable two-backup/v3-lineage recovery.
- The restore auxiliary interface was deepened to two explicit immutable request methods, and `ProductionSelectedLayoutShell` was extracted as the exact bounded production seam for shell visual and compiled focus/key tests. `MainActivity`, `AndroidThemeAssetStore`, and duplicated stable/preview workflow structure remain justified follow-up smells because splitting them now would not create a deeper release-safety boundary.
- On final API 35 source fingerprint `7912ef41bc5ae2a6fffb9150ca9075aedc16b53540bb59c7b724612758ae8287`, the connected accessibility matrix passed 80 of 83 tests with three expected skips and no failures, and all five benchmark scenarios plus all six thresholds passed. The current GrapheneOS compatibility portion is also closed by the authorized Phase 9 run.
- Accessibility repairs include a debug-only Compose test activity excluded from release variants, explicit shell focus order, deterministic keyboard input mode, and cold-start onboarding-state synchronization. Benchmark/release repairs include signing and emulator acknowledgement, permission and selector corrections, and R8 plus resource shrinking from 34.7MB to 5.78MB with narrow KSP/protobuf rules.
- Dependency health identified the Compose test manifest as an implementation dependency; moving it from `debugImplementation` to `debugRuntimeOnly` preserved the debug-only test activity and restored the intended dependency graph. The full affected aggregate then passed.
- No tag, release, production signature, publication, production-key access, or real downloaded-draft verification was performed. The authorized protected-draft run remains blocked by the dirty uncommitted candidate being absent remotely, zero remote tags, unauthenticated `gh`, and uninspectable protected environment/secrets/certificate/reviewer configuration.
- The final specification and documented-standards reviews pass on documentation-inclusive fingerprint `6b1337cd066ca29925563bc02b571a85afb8062eb426a9ed4767e0510577bd88`: zero specification findings and zero documented-standard violations. The standards report retains three medium advisory smells. Reports: `/tmp/quicklauncher-phase9-final-review-api35/spec.md` and `standards.md`.

## Last verified local gates

```bash
GRADLE_USER_HOME=/tmp/quicklauncher-phase9-gradle-home \
  tools/gradle --summary \
  --project-cache-dir /tmp/quicklauncher-phase9-project-cache \
  build checkModuleBoundaries verifyNoGoogleDependencies buildHealth checkPhase9Performance \
  --max-workers=1 --no-daemon --no-configuration-cache --console=plain
```

On this host the successful Phase 9 runs used isolated user/project caches because a preserved failed JVM remains recorded in old Gradle locks. The final post-API 35 aggregate passed in 68s with 4,038 actionable tasks at repository-gate fingerprint `f8a76ee4c87da5749f52f9fc93cb262cb9434358e81c8e97d9433e71840fa97f`. Earlier diagnosed JVM, lint, compilation, and dependency failures remain preserved rather than overwritten. Full final evidence: `/tmp/quicklauncher-phase9-final-gates-api35/`.

```bash
GRADLE_USER_HOME=/tmp/quicklauncher-phase9-gradle-home \
tools/gradle --summary \
  --project-cache-dir /tmp/quicklauncher-phase9-project-cache \
  :app:verifyPaparazziStableDebug \
  :modules:block:core:verifyPaparazziDebug \
  :modules:layout:core:verifyPaparazziDebug \
  :host:editor:verifyPaparazziDebug \
  :host:runtime:verifyPaparazziDebug \
  :host:settings:verifyPaparazziDebug \
  --max-workers=1 --no-daemon --no-configuration-cache --console=plain
```

Passed on repository-gate fingerprint `f8a76ee4c87da5749f52f9fc93cb262cb9434358e81c8e97d9433e71840fa97f` in 21s, covering 365 tests without record mode or image mismatch. All 146 repository PNGs retained identical SHA-256 hashes. Full evidence: `/tmp/quicklauncher-phase9-final-gates-api35/`.

```bash
nix shell nixpkgs#actionlint -c actionlint .github/workflows/ci.yml \
  .github/workflows/release-stable.yml .github/workflows/release-preview.yml
git diff --check
```

The final post-API 35 non-Gradle suite passed on repository-gate fingerprint `f8a76ee4c87da5749f52f9fc93cb262cb9434358e81c8e97d9433e71840fa97f` in about 9s: 20 security tests, 38 release tests, 32 performance-verifier tests, canonical audits, channel/workflow validators, three-workflow `actionlint`, disposable-key recovery, stable/preview generated-key dummy drills, documentation coherence, and `git diff --check`. Full evidence: `/tmp/quicklauncher-phase9-final-gates-api35/`. That run did not query or change a device.

Every `licensee` task named by CI plus the benchmark exact-graph audit passed explicitly in 13 seconds, and all 51 exact dependencies had approved SPDX licenses. The command derives the maintained task set and adds the benchmark module's compatible audit; complete evidence is retained in `/tmp/quicklauncher-phase9-final-gates-api35/`:

```bash
(rg -o ':[A-Za-z0-9:_-]+:licensee' .github/workflows/ci.yml; \
  printf '%s\n' ':benchmark:macrobenchmark:checkBenchmarkDependencyLicenses') | \
  sort -u | GRADLE_USER_HOME=/tmp/quicklauncher-phase9-gradle-home \
  xargs tools/gradle --summary \
    --project-cache-dir /tmp/quicklauncher-phase9-project-cache \
    --max-workers=1 --no-daemon --no-configuration-cache --console=plain
```

The latest connected evidence is the owner-authorized Phase 9 GrapheneOS Pixel 10a (`stallion`) run on Android 17/API 37, build `2026091001`, patch `2026-09-01`. On candidate `9d013c4b8b79b227243f67aa38efb50dcdff90b6783decc9714247b3dd28289d`, the focused repaired app-recovery route passed 1/1 in 40s, then the exact five-module matrix passed in 213s (Gradle 3m31s, 410 actionable tasks): 68 tests, 65 passed, three expected skips, zero failures/errors. Data passed 17/17; platform passed 19 with one shortcut-authority skip; runtime/editor were SDK-suppressed on API 37; app passed 29 with two managed-work skips. The initial failure and repeated focused reruns diagnosed an instrumentation targeting defect around a non-clickable Compose child and virtualized list; the final harness scrolls to the exact visible Quicklauncher label, activates it through normal UI Automator hit testing, and retains the production entry assertion. Baseline/postflight comparison found no release-relevant semantic change and no Quicklauncher package, widget, grant, or job residue; volatile whole-service dump hashes remain advisory. Evidence: `/tmp/quicklauncher-phase9-grapheneos-physical/`.

The latest connected API 35 evidence is the owner-authorized Phase 9 AOSP run on source fingerprint `7912ef41bc5ae2a6fffb9150ca9075aedc16b53540bb59c7b724612758ae8287`. The matrix passed in 129s: 83 tests, 80 passed, three expected skips, and zero failures/errors. Evidence: `/tmp/quicklauncher-phase9-api35-authorized/connected-final3/`. The five-scenario benchmark passed in 329s at thermal status `NONE` and `60.000004Hz`; all six thresholds passed: Home startup `830.310ms`, spatial-navigation CPU `22.196ms`, frame overrun `5.845ms`, catalog browse `84.749ms`, search first result `251.307ms`, and memory `253.062MiB`. It measured the exact installed `org.quicklauncher` version `0.1.0`/code `1` APK with SHA-256 `ab1634d54b5f258d61a6b8a94f805d053910d63a02105c31bdf705f1744f5f49` and certificate SHA-256 `9be4fbc5c7311b023c58a1d0ffa105b30503805471948122b901796f71c15940`. Evidence: `/tmp/quicklauncher-phase9-api35-authorized/benchmark/run7/`. Restoration was an exact semantic match; the emulator was killed without snapshot save and confirmed offline. Evidence: `/tmp/quicklauncher-phase9-api35-authorized/restoration/` and `/tmp/quicklauncher-phase9-api35-authorized/shutdown/`.

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

- Search web behavior intentionally opens validated external HTTPS destinations; it does not retrieve remote result data. Restored portable web-adapter records stay inert. Phase 8 has no macrobenchmark task or result. Phase 9's benchmark result is only the authorized pinned API 35 evidence recorded above. Phase 7 supports the documented Nova/ADW declarative subset only; other icon-pack dialects remain out of scope.
- Private Space hidden-profile access is limited to the complete host-owned secure container and typed system routes described above; private content remains suppressed on every unresolved or policy-ambiguous path.
- Destructive item actions remain fail-closed whenever the platform cannot prove current eligibility; supported uninstall and disable routes are exposed only after that proof and require confirmation.
- Release workflows, verification tooling, and channel metadata were added, but no APK was published, no release was created, no production APK was signed, and no production signing material was accessed or reconstructed.

## Next slice

The pinned API 35 accessibility and benchmark matrix, final post-API 35 repository gates, and separate final two-axis reviews are complete. The protected matching-tag draft workflow is authorized but concretely blocked: the dirty uncommitted candidate is absent from the remote, the remote has zero tags, `gh` is unauthenticated, and protected environment/secrets/public-certificate/reviewer configuration is uninspectable. No external or source-control mutation occurred; see `/tmp/quicklauncher-phase9-protected-draft-preflight/report.md`. Once those prerequisites exist, verify the downloaded artifact's tag/commit, channel application ID, version, minimum SDK, v3 signature, trusted certificate, checksum, and attested provenance.
