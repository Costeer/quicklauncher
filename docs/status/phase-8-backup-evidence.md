# Phase 8 backup evidence

Phase 8 is complete at final relevant source fingerprint `65820aea82c057acf53678db1dbc5a5946eeba962f7df7d5ca653ae55d086656`. P8-ENVELOPE through P8-GATES and the exit condition are satisfied. The focused history is retained below, followed by the authoritative release ledger.

## Focused verification ledger

| Acceptance IDs | Source fingerprint | Environment | Command summary | Duration | Result | Tests | Full log |
| --- | --- | --- | --- | ---: | --- | ---: | --- |
| P8-SCHEMA, P8-CRYPTO, P8-RESTORE, P8-REAUTH, P8-SUPPORT | not captured | repository JDK 17, local SDK, no device | handed-off backup/data/settings/stable-app command | 46s | pass | 40 | `/tmp/quicklauncher-agent-logs/gradle-20260917T103621Z-787674.log` |
| P8-RESTORE, P8-REAUTH | intermediate source fingerprint before documentation | repository JDK 17, local SDK, no device | focused backup-library and platform theme-asset tests | 21s | pass | 8 | `/tmp/quicklauncher-agent-logs/gradle-20260917T104147Z-794837.log` |
| P8-RESTORE, P8-REAUTH | intermediate source fingerprint before documentation | repository JDK 17, local SDK, no device | Home entry policy tests and stable app compilation | 66s | pass | 4 | `/tmp/quicklauncher-agent-logs/gradle-20260917T104342Z-797365.log` |
| P8-SAF, P8-RETENTION | intermediate source fingerprint before documentation | repository JDK 17, local SDK, no device | backup library tests plus stable app and Android-test compilation | 50s | pass | 9 | `/tmp/quicklauncher-agent-logs/gradle-20260917T104621Z-800848.log` |
| P8-SCHEMA, P8-RESTORE | `e0bf5a0816e16016ffb3b7c4a4fde7e7fe2634b026a8ed3f0ce2b2097d26c7cc` | repository JDK 17, local SDK, no device | backup library tests and stable app compilation | 39s | pass | 10 | `/tmp/quicklauncher-agent-logs/gradle-20260917T104814Z-803504.log` |
| P8-SCHEMA, P8-CRYPTO, P8-RESTORE, P8-REAUTH, P8-SUPPORT | `acea67be2e3ed2d7c7c5c4d7c5afbe1b191b170e84237e5aea532c02970c0327` | repository JDK 17, local SDK, no device | exact handed-off backup/data/settings/stable-app command after implementation and specification edits | 13s | pass | 30 | `/tmp/quicklauncher-agent-logs/gradle-20260917T105033Z-806465.log` |
| P8-GATES visual subset | `6b9261c57849f1c3e7e473e336ab5b4ebebdea74b6e44a623bc296d73c5c26e5` | repository JDK 17, local SDK, no device | `:host:settings:verifyPaparazziDebug` without recording | 93s | pass | 30 | `/tmp/quicklauncher-agent-logs/gradle-20260917T105112Z-807541.log` |
| P8-RETENTION | intermediate source fingerprint before the instrumentation contract test | repository JDK 17, local SDK, no device | nightly schedule unit tests and stable app compilation | 40s | fail: missing service import | 4 passed before compile failure | `/tmp/quicklauncher-agent-logs/gradle-20260917T111324Z-836351.log` |
| P8-RETENTION | `5e6a70f5cf07d7bb639a23b768fc7ce5faa4a327b8ef89d2dc58daba4783e66c` | repository JDK 17, local SDK, no device | nightly schedule unit tests plus stable app and Android-test compilation | 15s | pass | 6 | `/tmp/quicklauncher-agent-logs/gradle-20260917T111738Z-842967.log` |
| P8-SCHEMA, P8-RESTORE | intermediate source fingerprint | repository JDK 17, local SDK, no device | asset consistency library/app tests and Android-test compilation | 63s | fail: non-local return in app collector | 17 passed before compile failure | `/tmp/quicklauncher-agent-logs/gradle-20260917T112301Z-849657.log` |
| P8-SCHEMA, P8-RESTORE | intermediate source fingerprint | repository JDK 17, local SDK, no device | asset consistency library/app tests and Android-test compilation | 44s | fail: four invalid destination test fixtures | 4 failed | `/tmp/quicklauncher-agent-logs/gradle-20260917T112422Z-851687.log` |
| P8-SCHEMA, P8-RESTORE | `0517ba4fc7a88a21e431fe0c9cd3b63b4af83c1e8a6b6141fdd492fb1781a0fb` | repository JDK 17, local SDK, no device | forced asset consistency library/app tests and Android-test compilation | 138s | pass | 21 | `/tmp/quicklauncher-agent-logs/gradle-20260917T112724Z-856073.log` |
| P8-GATES visual subset | intermediate fingerprint before the new baseline | repository JDK 17, local SDK, no device | focused settings Paparazzi verification | 60s | expected fail: additive baseline absent | 1 failed | `/tmp/quicklauncher-agent-logs/gradle-20260917T113117Z-860627.log` |
| P8-GATES visual subset | `a4b6751b61527b93739d40ff1e5b6f3a7715233c8b774f4c1ae13abf79e8f59f` | repository JDK 17, local SDK, no device | focused settings Paparazzi record after image inspection | 42s | pass | 1 | `/tmp/quicklauncher-agent-logs/gradle-20260917T113545Z-865812.log` |
| P8-GATES visual subset | `a4b6751b61527b93739d40ff1e5b6f3a7715233c8b774f4c1ae13abf79e8f59f` | repository JDK 17, local SDK, no device | focused settings Paparazzi verification without recording | 41s | pass | 1 | `/tmp/quicklauncher-agent-logs/gradle-20260917T113633Z-866922.log` |
| P8-SCHEMA, P8-CRYPTO | `727c27879007a1fdbba25960f3f4f8e919c57130154d711813a9bc5ee2cb2b7b` | repository JDK 17, local SDK, no device | checked-in fixtures and crypto rejection suites, debug | 22s | pass | 29 | `/tmp/quicklauncher-agent-logs/gradle-20260917T115845Z-890872.log` |
| P8-SCHEMA, P8-CRYPTO | `727c27879007a1fdbba25960f3f4f8e919c57130154d711813a9bc5ee2cb2b7b` | repository JDK 17, local SDK, no device | checked-in fixtures and crypto rejection suites, release | 96s | pass | 29 | `/tmp/quicklauncher-agent-logs/gradle-20260917T115920Z-891756.log` |
| P8-RESTORE | intermediate worker integration fingerprint | repository JDK 17, local SDK, Robolectric, no device | theme asset store suite including interrupted-stage recreation cleanup | 89s | pass | 5 focused; wrapper observed 34 current reports | `/tmp/quicklauncher-agent-logs/gradle-20260917T115857Z-891087.log` |
| P8-SUPPORT | `727c27879007a1fdbba25960f3f4f8e919c57130154d711813a9bc5ee2cb2b7b` | repository JDK 17, local SDK, no device | private diagnostic log and automatic preference policy | 116s | pass | 9 | `/tmp/quicklauncher-agent-logs/gradle-20260917T120115Z-893820.log` |
| P8-SUPPORT | intermediate worker integration fingerprint | repository JDK 17, local SDK, no device | redacted support bundle, private diagnostic log, and preference policy | 108s | pass | 10 | `/tmp/quicklauncher-agent-logs/gradle-20260917T115633Z-888156.log` |
| P8-SAF, P8-RETENTION | intermediate worker integration fingerprint | repository JDK 17, local SDK, no device | stable Android-test source compilation after SAF boundary fixes | 35s | pass | compile only | `/tmp/quicklauncher-agent-logs/gradle-20260917T120444Z-897917.log` |
| P8-SAF, P8-RETENTION, P8-RESTORE | intermediate worker integration fingerprint | repository JDK 17, local SDK, no device | backup library inventory, retention, selection, and restore paths | 70s | pass | 17 | `/tmp/quicklauncher-agent-logs/gradle-20260917T120533Z-898949.log` |
| P8-REAUTH, P8-GATES visual subset | intermediate release-candidate fingerprint | repository JDK 17, local SDK, Paparazzi, no device | Settings page separation, category selection, and recovery actions | 122s | pass | 18 | `/tmp/quicklauncher-agent-logs/gradle-20260917T115235Z-883591.log` |
| P8-RESTORE, P8-REAUTH | intermediate release-candidate fingerprint | repository JDK 17, local SDK, no device | backup library, recreation policy, and Android-test compilation | 76s | pass | 21 | `/tmp/quicklauncher-agent-logs/gradle-20260917T120913Z-907069.log` |
| P8-SAF, P8-RETENTION, P8-RESTORE | intermediate release-candidate fingerprint | repository JDK 17, local SDK, no device | final backup library paths and Android-test compilation | 56s | pass | 18 | `/tmp/quicklauncher-agent-logs/gradle-20260917T121246Z-913882.log` |
| P8-GATES visual subset | intermediate fingerprint before the updated review baseline | repository JDK 17, local SDK, Paparazzi, no device | full settings Paparazzi verification after intentional restore UI changes | 136s | expected fail: one changed review image | 33 total, 1 mismatch | `/tmp/quicklauncher-agent-logs/gradle-20260917T121349Z-916132.log` |
| P8-GATES visual subset | `e1cbe86a8564111b394b65735a05dab57c72efd594a31f0bd96be73733476c51` | repository JDK 17, local SDK, Paparazzi, no device | full settings Paparazzi recording after image inspection | 131s | pass | 33 | `/tmp/quicklauncher-agent-logs/gradle-20260917T121646Z-922041.log` |
| P8-GATES visual subset | `e1cbe86a8564111b394b65735a05dab57c72efd594a31f0bd96be73733476c51` | repository JDK 17, local SDK, Paparazzi, no device | full settings Paparazzi verification without recording | 119s | pass | 33 | `/tmp/quicklauncher-agent-logs/gradle-20260917T121912Z-927024.log` |
| P8-SAF, P8-RETENTION, P8-CRYPTO | intermediate fingerprint after the visual verification | repository JDK 17, local SDK, no device | protected manual discovery and decoded automatic retention | 54s | pass | 18 | `/tmp/quicklauncher-agent-logs/gradle-20260917T122353Z-936824.log` |

The first row reused the exact handoff command before any new edits. Later rows cover the changes named in each row. The nightly scheduling failure was the new service's missing scheduler import; the focused rerun passed after adding it and bounding actual execution to the documented window. Its exact command was `tools/gradle --summary :host:backup:testDebugUnitTest --tests org.quicklauncher.host.backup.android.NightlyBackupScheduleTest :app:compileStableDebugKotlin :app:compileStableDebugAndroidTestKotlin --no-daemon --console=plain`. The evidence-row write itself is not part of its source fingerprint.

The asset collector compile failure came from a prohibited non-local return in `Sequence.map`; an explicit bounded loop fixed it. The next run exposed namespaced destination identities missing from four test fixtures. The final forced run used `tools/gradle --summary :host:backup:testDebugUnitTest --tests org.quicklauncher.host.backup.library.PortableBackupLibraryTest --tests org.quicklauncher.host.backup.payload.VersionedSectionPayloadTest :app:testStableDebugUnitTest --tests org.quicklauncher.app.ThemeAssetBackupAdaptersTest :app:compileStableDebugAndroidTestKotlin --rerun-tasks --no-daemon --console=plain` and passed all 21 tests.

The later focused commands were:

```sh
tools/gradle --summary :host:backup:testDebugUnitTest \
  --tests 'org.quicklauncher.host.backup.payload.LauncherSnapshotPayloadTest' \
  --tests 'org.quicklauncher.host.backup.payload.VersionedSectionPayloadTest' \
  --tests 'org.quicklauncher.host.backup.archive.PortableBackupArchiveTest' \
  --tests 'org.quicklauncher.host.backup.crypto.PassphraseArchiveProtectionTest' \
  --no-daemon --console=plain

tools/gradle --summary :host:backup:testReleaseUnitTest \
  --tests 'org.quicklauncher.host.backup.payload.LauncherSnapshotPayloadTest' \
  --tests 'org.quicklauncher.host.backup.payload.VersionedSectionPayloadTest' \
  --tests 'org.quicklauncher.host.backup.archive.PortableBackupArchiveTest' \
  --tests 'org.quicklauncher.host.backup.crypto.PassphraseArchiveProtectionTest' \
  --no-daemon --console=plain

tools/gradle --summary :host:platform:testDebugUnitTest \
  --tests 'org.quicklauncher.host.platform.theme.AndroidThemeAssetStoreRobolectricTest' \
  --no-daemon --no-configuration-cache --console=plain

tools/gradle --summary :app:testStableDebugUnitTest \
  --tests 'org.quicklauncher.app.PrivateBackupDiagnosticLogTest' \
  --tests 'org.quicklauncher.app.AutomaticBackupPreferencePolicyTest' \
  --no-daemon --console=plain

tools/gradle --summary :host:backup:testDebugUnitTest \
  --tests 'org.quicklauncher.host.backup.support.RedactedSupportBundleTest' \
  :app:testStableDebugUnitTest \
  --tests 'org.quicklauncher.app.PrivateBackupDiagnosticLogTest' \
  --tests 'org.quicklauncher.app.AutomaticBackupPreferencePolicyTest' \
  --no-daemon --console=plain

tools/gradle --summary :host:settings:testDebugUnitTest \
  --tests 'org.quicklauncher.host.settings.HostOverlaysTest' \
  --no-daemon --no-configuration-cache --console=plain

tools/gradle --summary :app:compileStableDebugAndroidTestKotlin \
  --no-daemon --console=plain

tools/gradle --summary :host:backup:testDebugUnitTest \
  --tests 'org.quicklauncher.host.backup.library.PortableBackupLibraryTest' \
  --rerun-tasks --no-daemon --console=plain

tools/gradle --summary :host:backup:testDebugUnitTest \
  --tests 'org.quicklauncher.host.backup.library.PortableBackupLibraryTest' \
  :app:testStableDebugUnitTest --tests 'org.quicklauncher.app.HomeEntryPolicyTest' \
  :app:compileStableDebugAndroidTestKotlin \
  --no-daemon --no-configuration-cache --console=plain

tools/gradle --summary :host:backup:testDebugUnitTest \
  --tests 'org.quicklauncher.host.backup.library.PortableBackupLibraryTest' \
  :app:compileStableDebugAndroidTestKotlin \
  --no-daemon --no-configuration-cache --console=plain

tools/gradle --summary :host:settings:verifyPaparazziDebug \
  --no-daemon --no-configuration-cache --console=plain
tools/gradle --summary :host:settings:recordPaparazziDebug \
  --no-daemon --no-configuration-cache --console=plain
tools/gradle --summary :host:settings:verifyPaparazziDebug \
  --no-daemon --no-configuration-cache --console=plain

tools/gradle --summary :host:backup:testDebugUnitTest \
  --tests 'org.quicklauncher.host.backup.library.PortableBackupLibraryTest' \
  --no-daemon --no-configuration-cache --console=plain
```

The first combined SAF/library attempt passed its 17 host tests but then hit a concurrent Gradle output-directory race while compiling the app. Isolated Android-test compilation first exposed three test API errors, then an out-of-scope concurrent `MainActivity` coroutine error. The fixes passed in the 35s compilation and 70s library rows above. Logs: `/tmp/quicklauncher-agent-logs/gradle-20260917T120124Z-894105.log`, `/tmp/quicklauncher-agent-logs/gradle-20260917T120319Z-896187.log`, and `/tmp/quicklauncher-agent-logs/gradle-20260917T120356Z-896977.log`.

The first fixture run was blocked by a concurrent shared compile error. A later four-suite run passed all 29 tests but lost a Gradle test-index file to a concurrent output-directory race. The isolated 22s rerun passed. Logs: `/tmp/quicklauncher-agent-logs/gradle-20260917T114932Z-879595.log` and `/tmp/quicklauncher-agent-logs/gradle-20260917T115630Z-888063.log`.

## Authoritative release ledger

All rows use relevant source fingerprint `65820aea82c057acf53678db1dbc5a5946eeba962f7df7d5ca653ae55d086656` and repository JDK 17/local Android SDK unless the environment says otherwise.

| Acceptance IDs | Environment | Exact command | UTC / duration | Result | Tasks/tests | Full evidence |
| --- | --- | --- | --- | --- | --- | --- |
| P8-ENVELOPE through P8-GATES | local, no device | `tools/gradle --summary build checkModuleBoundaries verifyNoGoogleDependencies buildHealth --no-daemon --no-configuration-cache --console=plain` | 2026-09-17 14:17:09–14:18:37 / 88s | pass | 3,831 actionable (93 executed, 6 cache, 3,732 up-to-date); 713 tests, 0 failures/errors/skips | `/tmp/quicklauncher-agent-logs/gradle-20260917T141709Z-159573.log` |
| P8-GATES visual | local Paparazzi, no device, no record mode | `tools/gradle --summary :app:verifyPaparazziStableDebug :modules:block:core:verifyPaparazziDebug :modules:layout:core:verifyPaparazziDebug :host:editor:verifyPaparazziDebug :host:runtime:verifyPaparazziDebug :host:settings:verifyPaparazziDebug --no-daemon --no-configuration-cache --console=plain` | 2026-09-17 14:19:13 / 18s | pass | 424 task events; 334 tests, 0 failures/errors/skips | `/tmp/quicklauncher-phase8-final-gates/visual/post-review-fixes/gradle-20260917T141913Z-164299.log` |
| P8-GATES licenses | local, CI-derived tasks, no device | `rg -o ':[A-Za-z0-9:_-]+:licensee' .github/workflows/ci.yml \| sort -u \| xargs tools/gradle --summary --no-daemon --no-configuration-cache --console=plain` | 2026-09-17 14:20:34 / 11s | pass | 23 declared entry tasks; 38 actionable, all up-to-date | `/tmp/quicklauncher-agent-logs/gradle-20260917T142034Z-167211.log` |
| P8-GATES workflow | Nix actionlint, no device | `nix shell nixpkgs#actionlint -c actionlint .github/workflows/ci.yml` | 2026-09-17 14:20:49 / 0.226s | pass, no diagnostics | n/a | `/tmp/quicklauncher-phase8-final-gates/license-workflow/post-review-fixes/actionlint-rerun.log` |
| P8-SAF, P8-RETENTION, P8-RESTORE, P8-REAUTH, P8-GATES | API 35 AOSP full-phone AVD `quicklauncher_aosp_full35_phase5`, `emulator-5554` | `ANDROID_SERIAL=emulator-5554 tools/gradle --summary :host:data:connectedDebugAndroidTest :host:platform:connectedDebugAndroidTest :host:runtime:connectedDebugAndroidTest :host:editor:connectedDebugAndroidTest :app:connectedStableDebugAndroidTest --no-daemon --no-configuration-cache --console=plain` | 2026-09-17 14:24:44 / 127s | pass | 410 actionable (10 executed, 400 up-to-date); 78 tests, 75 pass, 3 skip, 0 failures/errors | `/tmp/quicklauncher-agent-logs/gradle-20260917T142444Z-177360.log` |
| P8-GATES repository/privacy/release | read-only repository audit, no device | governing Phase 8 diff, fixture/schema, negative network/telemetry/storage, module-boundary, backup-rule, privacy, release-policy, status, and `git diff --check` checks from the validation index and gate inventory | 2026-09-17 UTC | pass | no unclassified privacy hit or policy change | `/tmp/quicklauncher-phase8-final-gates/repository-audit/post-review-fixes/findings.md` |
| P8-GATES review: spec | pinned tracked/untracked artifact set | code-review spec axis against `6863c7d3ad8f3e8968fafc3475909d291c06e721`, including explicit SAF finalization recheck | 2026-09-17 UTC | pass | 0 findings | `/tmp/quicklauncher-phase8-final-review-final2/spec.md` |
| P8-GATES review: standards | pinned tracked/untracked artifact set | code-review documented-standards and smell axis against `6863c7d3ad8f3e8968fafc3475909d291c06e721` | 2026-09-17 UTC | pass for documented standards | 0 documented violations; 2 medium and 1 low non-blocking smells | `/tmp/quicklauncher-phase8-final-review-final2/standards.md` |

The visual gate did not record baselines. The checked light portrait, dark landscape, large-text portrait, and large-text actions images retained their inspected hashes. At original/normal detail, contrast and hierarchy were clear, large-text labels wrapped without collision or clipping, and the independent destination-map, themes/assets, web-adapter, restore, and cancel controls were visible across the paired scroll positions. Contact sheet: `/tmp/quicklauncher-phase8-final-gates/visual/post-review-fixes/phase8-backup-review-contact-sheet.png`.

The requested AVD was absent, so the API 35 default x86_64 system image revision 2 and Emulator 37.1.11 were installed and a new Pixel 6 full-phone `quicklauncher_aosp_full35_phase5` profile was created; `/tmp/quicklauncher-phase8-final-gates/avd-provision/README.md` records exact commands, versions, configuration hashes, and the required Nix `LD_LIBRARY_PATH`. The connected run launched `/home/costeer/.cache/quicklauncher-android-sdk/emulator/emulator -avd quicklauncher_aosp_full35_phase5 -port 5554 -no-window -no-audio -no-boot-anim -no-snapshot-save` and used exact device fingerprint `Android/sdk_phone64_x86_64/emu64x:15/AE3A.240806.019/12368160:userdebug/test-keys`. Before testing it captured Home and resolved Home, font scale, rotation, night mode, notification-listener state, users and owners, packages and permissions, wallpaper, widgets, URI grants, jobs, and relevant shared-storage files. Restoration canceled the one test-created system full-backup job and force-stopped the activated DocumentsUI providers. Exact stable comparisons matched and `/tmp/quicklauncher-phase8-final-gates/connected/post-review-fixes/compare/semantic-diffs.txt` is empty. `emu kill` exited 0, pinned `get-state` failed on attempt 3, and no snapshot was saved. No GrapheneOS or other device was queried; GrapheneOS results remain owner-supplied evidence only.

## Failure and repair history

- `/tmp/quicklauncher-agent-logs/gradle-20260917T123846Z-967677.log` failed to compile until `AndroidThemeAssetStore.deleteUnreferenced` explicitly returned `Unit`; `/tmp/quicklauncher-agent-logs/gradle-20260917T124452Z-981513.log` then passed 36 tests.
- The first full local gate failed lint because persisted-job analysis could not see `RECEIVE_BOOT_COMPLETED`. The permission and Android SAF/grant/scheduler adapters were moved to `:host:platform`; `:host:backup` retains pure `NightlyBackupSchedule` policy. Focused lint and Android-test compilation passed in `/tmp/quicklauncher-agent-logs/gradle-20260917T134340Z-87967.log`.
- The next full run hit an OpenJDK 17 `SIGSEGV` in a Paparazzi test process after 680 clean XML tests. `modules/layout/core/hs_err_pid51270.log` was preserved unchanged and remains outside source sets; later gates passed.
- A later rerun exposed dependency-analysis advice. Direct production and Android-test declarations were corrected, two newly reported unused test declarations were removed, and focused `buildHealth` passed before the complete gate. Policy was not weakened.
- Backup-rule audit found incomplete durable includes. Both rule formats and their instrumentation contract were corrected to cover seven exact durable locations while excluding restore staging, diagnostics, authorization, and passphrases; focused lint/compilation and every invalidated final gate then passed.
- The first final standards/spec reviews found the misplaced Android adapters and incomplete cleanup after partially acquired composite stages. Moving adapters, making composite and post-stage cancellation cleanup non-cancellable, adding regressions, and making exact-kind asset cleanup explicit closed both findings. The final separate reports above supersede the stale first reports.

P8-ENVELOPE through P8-GATES are therefore closed, and the exit condition holds. This repository has no Phase 8 macrobenchmark task or module. Measured accessibility and performance thresholds remain Phase 9 release-hardening work under the architecture plan; no Phase 8 benchmark result is claimed.
