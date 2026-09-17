# Phase 8 backup evidence

Phase 8 is in progress. This file records focused checks only. It is not a release ledger and does not satisfy P8-GATES.

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

## Open evidence

- The original normal portrait capture clips one restore action, dark landscape does not reach the review card, and the large-text top capture stops before it. The additive `backup-review-actions-large-text-light-portrait` baseline keeps the full heading, summaries, category choices, primary action, and cancellation visible at 2x text. The initial four-image contact sheet was inspected at `/tmp/quicklauncher-phase8-final-backup-review-contact-sheet.png`. The later intentional category and recovery UI changes produced one expected mismatch; the updated large-text review/action image was inspected before recording, and the following full settings verification passed without recording.
- No release, lint, license, workflow, diff, or connected result is recorded for the current fingerprint.
- The API 35 AOSP full-phone run, exact before/after restoration, and no-snapshot shutdown remain required.
- The attached GrapheneOS phone remains out of scope. Only owner-supplied GrapheneOS results may be cited.
