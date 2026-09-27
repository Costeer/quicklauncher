# Phase 9 execution packets

These packets apply [`docs/agents/execution.md`](../agents/execution.md) and the [packet template](../agents/phase-packet-template.md). Every packet owns one to three validation IDs, an exact bounded path set, exact focused commands, and an evidence destination. The shared checkpoint thresholds are 8 million recorded tokens, 75 model steps, 100 tool calls, 45 minutes, or about 15 changed files. Reads or commands over 200 lines or 20 KB are delegated to depth-one workers that save full evidence and return at most 400 words.

## P9-01A: navigation and input accessibility

Status: repository-local implementation and pinned API 35 executable coverage complete; TalkBack speech/traversal and framework predictive-gesture delivery remain explicit integration gaps.

- Acceptance IDs: `P9-A11Y`.
- User goal: verify logical focus, keyboard/D-pad, reduced motion, predictive Back, and exact launcher-shell behavior.
- Acceptance condition: local policy/focus tests pass, stable and preview instrumentation compile, the pinned API 35 shell/recovery/spatial tests execute, and every unexecuted framework behavior remains `DR` in the matrix.
- Governing sections: Phase 9 scope “Accessibility contract”; ADR 0019, 0022, 0028, and 0029; validation-index `P9-A11Y`.
- Owned files:
  - `app/src/main/java/org/quicklauncher/app/MainActivity.kt`
  - `app/src/main/java/org/quicklauncher/app/ProductionCompositionSurface.kt`
  - `app/src/androidTest/kotlin/org/quicklauncher/app/ProductionSelectedLayoutShellInstrumentedTest.kt`
  - `app/src/androidTest/kotlin/org/quicklauncher/app/ProductionAppRecoverySurfaceInstrumentedTest.kt`
  - `host/runtime/src/main/kotlin/org/quicklauncher/host/runtime/navigation/PredictiveBackCommitPolicy.kt`
  - `host/runtime/src/main/kotlin/org/quicklauncher/host/runtime/navigation/SpatialNavigationSurface.kt`
  - `host/runtime/src/test/kotlin/org/quicklauncher/host/runtime/navigation/PredictiveBackCommitPolicyTest.kt`
  - `host/runtime/src/test/kotlin/org/quicklauncher/host/runtime/navigation/SpatialNavigationMotionTest.kt`
  - `host/runtime/src/androidTest/kotlin/org/quicklauncher/host/runtime/navigation/SpatialNavigationSurfaceInstrumentedTest.kt`
  - `host/platform/src/main/kotlin/org/quicklauncher/host/platform/motion/AndroidMotionPolicy.kt`
  - `host/platform/src/test/kotlin/org/quicklauncher/host/platform/motion/AndroidMotionPolicyTest.kt`
- Excluded work: P9-01B owns rendered settings/widget accessibility; P9-01C owns measurements.
- Focused checks:
  - `GRADLE_USER_HOME=/tmp/quicklauncher-phase9-gradle-home tools/gradle --summary --project-cache-dir /tmp/quicklauncher-phase9-project-cache :host:runtime:testDebugUnitTest :host:platform:testDebugUnitTest :app:compileStableDebugAndroidTestKotlin :app:compilePreviewDebugAndroidTestKotlin --max-workers=1 --no-daemon --no-configuration-cache --console=plain`
  - `ANDROID_SERIAL=emulator-5554 GRADLE_USER_HOME=/tmp/quicklauncher-phase9-gradle-home tools/gradle --summary --project-cache-dir /tmp/quicklauncher-phase9-project-cache :host:data:connectedDebugAndroidTest :host:platform:connectedDebugAndroidTest :host:runtime:connectedDebugAndroidTest :host:editor:connectedDebugAndroidTest :app:connectedStableDebugAndroidTest --max-workers=1 --no-daemon --no-configuration-cache --console=plain`
- Evidence output: `/tmp/quicklauncher-phase9-a11y-runtime-repair/`, `/tmp/quicklauncher-phase9-a11y-finalize/`, `/tmp/quicklauncher-phase9-api35-authorized/connected-final3/`, and the navigation rows in [`phase-9-accessibility-evidence.md`](phase-9-accessibility-evidence.md).

## P9-01B: rendered surface accessibility

Status: repository-local implementation complete.

- Acceptance IDs: `P9-A11Y`.
- User goal: verify 2x text, non-sensitive semantics, deterministic focus, keyboard/D-pad activation, and non-drag editing on production surfaces.
- Acceptance condition: focused Compose tests and the six-module no-record visual gate pass with all baseline hashes unchanged.
- Governing sections: Phase 9 scope “Accessibility contract”; ADR 0019, 0022, and 0028; matrix host/contribution rows.
- Owned files:
  - `app/src/test/java/org/quicklauncher/app/ProductionPreparedContentVisualTest.kt`
  - `app/src/test/java/org/quicklauncher/app/WidgetResizeControlsVisualTest.kt`
  - `app/src/androidTest/kotlin/org/quicklauncher/app/WidgetResizeControlsInstrumentedTest.kt`
  - `app/src/test/snapshots/images/*prepared-fallbacks-large-text*.png`
  - `app/src/test/snapshots/images/*selected-layout-shell-large-text*.png`
  - `app/src/test/snapshots/images/*widget-resize-controls-large-text*.png`
  - `host/runtime/src/test/kotlin/org/quicklauncher/host/runtime/SafeLayoutSurfaceComposeTest.kt`
  - `host/editor/src/test/kotlin/org/quicklauncher/host/editor/LauncherEditorSurfaceTest.kt`
  - `host/settings/src/main/kotlin/org/quicklauncher/host/settings/PrivateSpaceOverlay.kt`
  - `host/settings/src/main/kotlin/org/quicklauncher/host/settings/HostOverlays.kt`
  - `host/settings/build.gradle.kts`
  - `host/settings/src/test/kotlin/org/quicklauncher/host/settings/HostOverlaysTest.kt`
  - `host/settings/src/test/kotlin/org/quicklauncher/host/settings/PhaseFiveOverlaysTest.kt`
  - `host/settings/src/test/kotlin/org/quicklauncher/host/settings/PrivateSpaceOverlayTest.kt`
  - `host/settings/src/test/kotlin/org/quicklauncher/host/settings/visual/HostOverlayVisualTest.kt`
  - `host/settings/src/test/snapshots/images/*large-text*.png`
- Excluded work: P9-01A owns navigation policies; provider-owned live widget behavior remains an authorized-device row.
- Focused checks:
  - `GRADLE_USER_HOME=/tmp/quicklauncher-phase9-gradle-home tools/gradle --summary --project-cache-dir /tmp/quicklauncher-phase9-project-cache :host:settings:testDebugUnitTest :host:editor:testDebugUnitTest :host:runtime:testDebugUnitTest --max-workers=1 --no-daemon --no-configuration-cache --console=plain`
  - `GRADLE_USER_HOME=/tmp/quicklauncher-phase9-gradle-home tools/gradle --summary --project-cache-dir /tmp/quicklauncher-phase9-project-cache :app:verifyPaparazziStableDebug :modules:block:core:verifyPaparazziDebug :modules:layout:core:verifyPaparazziDebug :host:editor:verifyPaparazziDebug :host:runtime:verifyPaparazziDebug :host:settings:verifyPaparazziDebug --max-workers=1 --no-daemon --no-configuration-cache --console=plain`
- Evidence output: `/tmp/quicklauncher-phase9-a11y-host-focus/`, `/tmp/quicklauncher-phase9-a11y-visual-gaps/`, `/tmp/quicklauncher-phase9-a11y-finalize/`, `/tmp/quicklauncher-phase9-dialog-back-fix/`, and `/tmp/quicklauncher-phase9-final-gates-final2/gradle/visual/`.

## P9-01C: measured-performance contract

Status: complete; five real API 35 scenarios and the fail-closed verifier pass.

- Acceptance IDs: `P9-PERF`.
- User goal: define fail-closed Home-entry, spatial-gesture, app-catalog-loading, search, and current-plus-neighbors memory thresholds and bind results to the installed candidate.
- Acceptance condition: the isolated module compiles, 32 verifier tests and the 51-coordinate audit pass, all five pinned API 35 scenarios satisfy the committed limits, and fixtures are never presented as measurements.
- Governing sections: Phase 9 scope “Performance contract”; ADR 0019, 0024, and 0029.
- Owned files:
  - `benchmark/macrobenchmark/build.gradle.kts`
  - `benchmark/macrobenchmark/src/main/AndroidManifest.xml`
  - `benchmark/macrobenchmark/src/main/kotlin/org/quicklauncher/benchmark/macrobenchmark/PhaseNinePerformanceBenchmark.kt`
  - `app/src/benchmark/AndroidManifest.xml`
  - `app/src/benchmark/java/org/quicklauncher/app/BenchmarkFixtureReceiver.kt`
  - `tools/performance/**`
  - `app/build.gradle.kts`
  - `build.gradle.kts`
  - `settings.gradle.kts`
  - `gradle/libs.versions.toml`
- Excluded work: release signing is P9-03A; P9-05 owns final integration of the completed device evidence.
- Focused checks:
  - `env PYTHONDONTWRITEBYTECODE=1 python3 -m unittest discover -v -s tools/performance/tests -p 'test*.py'`
  - `GRADLE_USER_HOME=/tmp/quicklauncher-phase9-gradle-home tools/gradle --summary --project-cache-dir /tmp/quicklauncher-phase9-project-cache checkPhase9Performance :benchmark:macrobenchmark:assemble --max-workers=1 --no-daemon --no-configuration-cache --console=plain`
  - `ANDROID_SERIAL=emulator-5554 GRADLE_USER_HOME=/tmp/quicklauncher-phase9-gradle-home tools/gradle --summary --project-cache-dir /tmp/quicklauncher-phase9-project-cache :benchmark:macrobenchmark:connectedStableBenchmarkAndroidTest --max-workers=1 --no-daemon --no-configuration-cache --console=plain`
- Evidence output: `/tmp/quicklauncher-phase9-p9-01/`, `/tmp/quicklauncher-phase9-api35-authorized/benchmark/run7/`, and the `P9-PERF` ledger row in [`phase-9-release-evidence.md`](phase-9-release-evidence.md).

## P9-02: security and privacy audit

Status: complete for the repository candidate.

- Acceptance IDs: `P9-SECURITY`.
- User goal: audit storage, networking, exports, permissions, backup, logs, diagnostics, secrets, dependencies, and release configuration.
- Acceptance condition: all positive ownership and negative leakage checks pass for exact stable/preview merged manifests.
- Governing sections: Phase 9 scope “Security and privacy contract”; validation-index `P9-SECURITY`, `SH-BOUNDARY`, and `SH-PRIVACY`.
- Owned files:
  - `app/src/main/res/xml/backup_rules.xml`
  - `app/src/main/res/xml/data_extraction_rules.xml`
  - `host/platform/src/main/AndroidManifest.xml`
  - `app/src/androidTest/kotlin/org/quicklauncher/app/FileAuthorizationContractInstrumentedTest.kt`
  - `app/src/androidTest/kotlin/org/quicklauncher/app/HomeManifestInstrumentedTest.kt`
  - `tools/security/release_security_audit.py`
  - `tools/security/test_release_security_audit.py`
- Excluded work: artifact identity/provenance is P9-03A; user prose is P9-04.
- Focused checks:
  - `env PYTHONDONTWRITEBYTECODE=1 python3 -m unittest discover -v -s tools/security -p 'test*.py'`
  - `env PYTHONDONTWRITEBYTECODE=1 python3 tools/security/release_security_audit.py --stable-manifest app/build/intermediates/merged_manifests/stableRelease/processStableReleaseManifest/AndroidManifest.xml --preview-manifest app/build/intermediates/merged_manifests/previewRelease/processPreviewReleaseManifest/AndroidManifest.xml`
- Evidence output: `/tmp/quicklauncher-phase9-final-gates-final2/non-gradle-final/` and the `P9-SECURITY` ledger row.

## P9-02B: restore and theme-asset release-safety seams

Status: complete.

- Acceptance IDs: `P9-SECURITY`, `P9-GATES`.
- User goal: deepen the restore auxiliary interface and preserve exact-kind, private theme-asset cleanup so release recovery cannot stage ambiguous context or consume the wrong asset.
- Acceptance condition: immutable validation/staging requests preserve all restore context, reverse cleanup/rollback remains correct, exact-kind asset preservation passes, and the full dependency/module gates remain clean.
- Governing sections: Phase 9 design-smell evaluation; `SH-RECOVERY`; ADR 0017, 0030, and 0031.
- Owned files:
  - `host/backup/build.gradle.kts`
  - `host/backup/src/main/kotlin/org/quicklauncher/host/backup/library/BackupLibrary.kt`
  - `host/backup/src/test/kotlin/org/quicklauncher/host/backup/library/PortableBackupLibraryTest.kt`
  - `app/src/main/java/org/quicklauncher/app/BackupAuxiliaryAdapters.kt`
  - `app/src/androidTest/kotlin/org/quicklauncher/app/BackupAuxiliaryAdaptersInstrumentedTest.kt`
  - `app/src/androidTest/kotlin/org/quicklauncher/app/BackupSafBoundaryInstrumentedTest.kt`
  - `app/src/main/java/org/quicklauncher/app/ThemeAssetBackupAdapters.kt`
  - `app/src/test/java/org/quicklauncher/app/ThemeAssetBackupAdaptersTest.kt`
  - `host/platform/src/main/kotlin/org/quicklauncher/host/platform/theme/AndroidThemeAssetStore.kt`
  - `host/platform/src/test/kotlin/org/quicklauncher/host/platform/theme/AndroidThemeAssetStoreRobolectricTest.kt`
  - `host/runtime/src/main/kotlin/org/quicklauncher/host/runtime/theme/ThemeAssetContracts.kt`
  - `host/runtime/src/main/kotlin/org/quicklauncher/host/runtime/theme/ThemeController.kt`
  - `host/runtime/src/test/kotlin/org/quicklauncher/host/runtime/theme/ThemeControllerTest.kt`
  - `host/backup/src/main/kotlin/org/quicklauncher/host/backup/android/AndroidBackupStorage.kt` (moved out of this module)
  - `host/platform/src/main/kotlin/org/quicklauncher/host/backup/android/AndroidBackupStorage.kt`
- Excluded work: portable archive feature scope remains Phase 8; P9-02 owns repository-wide security audit tooling.
- Focused checks:
  - `GRADLE_USER_HOME=/tmp/quicklauncher-phase9-gradle-home tools/gradle --summary --project-cache-dir /tmp/quicklauncher-phase9-project-cache :host:backup:testDebugUnitTest --tests 'org.quicklauncher.host.backup.library.PortableBackupLibraryTest' :app:testStableDebugUnitTest --tests 'org.quicklauncher.app.ThemeAssetBackupAdaptersTest' --max-workers=1 --no-daemon --no-configuration-cache --console=plain`
  - `GRADLE_USER_HOME=/tmp/quicklauncher-phase9-gradle-home tools/gradle --summary --project-cache-dir /tmp/quicklauncher-phase9-project-cache buildHealth checkModuleBoundaries --max-workers=1 --no-daemon --no-configuration-cache --console=plain`
- Evidence output: `/tmp/quicklauncher-phase9-restore-port/`, `/tmp/quicklauncher-phase9-p9-02/`, and `/tmp/quicklauncher-phase9-final-gates-final2/gradle/local/`.

## P9-03A: release identity, provenance, and channels

Resume update (2026-09-27): public visibility, reviewer protection, first stable signing identity, vault recovery record, encrypted backup restoration, GitHub secrets, and trusted certificate are configured. Hosted SDK setup needs the bounded correction in `/tmp/quicklauncher-phase9-release-resume/packet.md`: explicit `platform-tools`, candidate `0.1.1`/code `2`, matching security audit policy. Existing `v0.1.0` is preserved. Candidate gates/reviews and downloaded-draft verification remain open; earlier blocker notes below are historical.

Status: local/dummy verification complete; protected real draft is authorized but blocked at preflight.

- Acceptance IDs: `P9-RELEASE`, `P9-CHANNELS`.
- User goal: keep channels separate and independently verify a downloaded draft's tag, application ID, certificate, digest, and provenance.
- Acceptance condition: workflows/metadata fail closed and both generated-key channel drills pass without publishing or production secrets.
- Governing sections: Phase 9 scope “Release artifact contract” and “Release channels”; ADR 0009, 0024, 0025, and 0026.
- Owned files:
  - `.github/workflows/release-stable.yml`
  - `.github/workflows/release-preview.yml`
  - `.github/release/channels.json`
  - `.github/release/obtainium-stable.json`
  - `.github/release/obtainium-preview.json`
  - `.github/release/obtainium-source-contract.json`
  - `tools/release/verify_release.py`
  - `tools/release/create_metadata.py`
  - `tools/release/previous_version_code.py`
  - `tools/release/release_lib.py`
  - `tools/release/validate_channels.py`
  - `tools/release/validate_release_assets.py`
  - `tools/release/validate_workflows.py`
  - `tools/release/dummy_release_drill.py`
  - `tools/release/test_release_tools.py`
  - `tools/release/fixtures/github-attestation-verification.json`
  - `tools/release/__init__.py`
- Excluded work: disposable recovery is P9-03B. Candidate commit `eaf6af3503b30254e8a5917c579b83620ed8d6e4`, remote stable tag `v0.1.0`, and authenticated `gh` now satisfy the source-control prerequisites. Production signing/draft evidence remains blocked until the repository plan supports the mandated required-reviewer protection and the owner provisions the existing stable secrets plus independently trusted certificate variable. Publication remains excluded.
- Focused checks:
  - `env PYTHONDONTWRITEBYTECODE=1 python3 -m unittest discover -v -s tools/release -p 'test*.py'`
  - `env PYTHONDONTWRITEBYTECODE=1 python3 tools/release/validate_channels.py && env PYTHONDONTWRITEBYTECODE=1 python3 tools/release/validate_workflows.py`
  - `env PYTHONDONTWRITEBYTECODE=1 python3 tools/release/dummy_release_drill.py --unsigned-apk app/build/outputs/apk/stable/release/app-stable-release-unsigned.apk --channel stable --aapt /home/costeer/.cache/quicklauncher-android-sdk/build-tools/35.0.0/aapt --apksigner /home/costeer/.cache/quicklauncher-android-sdk/build-tools/35.0.0/apksigner --zipalign /home/costeer/.cache/quicklauncher-android-sdk/build-tools/35.0.0/zipalign --keytool /home/costeer/.nix-profile/bin/keytool`
  - `env PYTHONDONTWRITEBYTECODE=1 python3 tools/release/dummy_release_drill.py --unsigned-apk app/build/outputs/apk/preview/release/app-preview-release-unsigned.apk --channel preview --aapt /home/costeer/.cache/quicklauncher-android-sdk/build-tools/35.0.0/aapt --apksigner /home/costeer/.cache/quicklauncher-android-sdk/build-tools/35.0.0/apksigner --zipalign /home/costeer/.cache/quicklauncher-android-sdk/build-tools/35.0.0/zipalign --keytool /home/costeer/.nix-profile/bin/keytool`
  - `nix shell nixpkgs#actionlint -c actionlint .github/workflows/ci.yml .github/workflows/release-stable.yml .github/workflows/release-preview.yml`
- Evidence output: `/tmp/quicklauncher-phase9-p9-03/`, `/tmp/quicklauncher-phase9-final-gates-final2/non-gradle-final/`, `/tmp/quicklauncher-phase9-protected-draft-preflight/report.md`, and `/tmp/quicklauncher-phase9-stable-pre-dispatch/`.

## P9-03B: disposable signing-key recovery

Status: complete with test-only material.

- Acceptance IDs: `P9-KEYS`.
- User goal: prove two independent encrypted backups and v3 lineage without production-key access.
- Acceptance condition: the drill destroys/restores the disposable working key twice, preserves the public certificate, checks lineage, and removes plaintext.
- Governing sections: Phase 9 scope “Key recovery drill”; ADR 0025.
- Owned files:
  - `tools/release/key_recovery_drill.py`
- Excluded work: P9-04 owns the recovery documentation; real signing secrets, production recovery, release creation, and publication are outside this disposable-key packet, and no production key was handled.
- Focused checks:
  - `env PYTHONDONTWRITEBYTECODE=1 python3 tools/release/key_recovery_drill.py --apksigner /home/costeer/.cache/quicklauncher-android-sdk/build-tools/35.0.0/apksigner --keytool /home/costeer/.nix-profile/bin/keytool --openssl /run/current-system/sw/bin/openssl`
- Evidence output: `/tmp/quicklauncher-phase9-p9-03/` and `/tmp/quicklauncher-phase9-final-gates-final2/non-gradle-final/`.

## P9-04: user release documentation

Status: complete.

- Acceptance IDs: `P9-DOCS`.
- User goal: document installation, verification, upgrades, channels, backup/restore/recovery, permissions, privacy, and limitations.
- Acceptance condition: all local links, anchors, and documented release/Python CLI contracts resolve without embedding an unverified production identity.
- Governing sections: Phase 9 scope “User documentation”; ADR 0009, 0017, 0023, 0024, and 0028.
- Owned files:
  - `README.md`
  - `docs/user-guide.md`
  - `docs/release-verification.md`
- Excluded work: status/evidence ledgers are P9-05; production release notes are not authorized.
- Focused checks:
  - `env PYTHONDONTWRITEBYTECODE=1 python3 /tmp/quicklauncher-phase9-final-gates-final2/non-gradle-final/docs_coherence.py`
- Evidence output: `/tmp/quicklauncher-phase9-final-gates-final2/non-gradle-final/` and the `P9-DOCS` ledger row.

## P9-05: final release evidence

Status: terminal repository gates, current GrapheneOS compatibility, pinned API 35 accessibility/performance evidence, and separate final two-axis reviews are complete; protected real-draft evidence is the sole external blocker.

- Acceptance IDs: `P9-GATES`.
- User goal: bind every applicable gate and both review axes to the final relevant candidate without publishing.
- Acceptance condition: exact commands/environments/durations/counts/results/fingerprints/paths are recorded; every authority-bound prerequisite stays open until supplied.
- Governing sections: Phase 9 scope “Exit condition”; ADR 0019, 0025, 0026, and 0029.
- Owned files:
  - `docs/architecture/launcher-architecture-plan.md`
  - `docs/agents/validation-index.md`
  - `docs/status/current-state.md`
  - `docs/status/current-state-overview.html`
  - `docs/status/phase-9-release-scope.md`
  - `docs/status/phase-9-packets.md`
  - `docs/status/phase-9-accessibility-evidence.md`
  - `docs/status/phase-9-release-evidence.md`
  - `docs/status/phase-9-handoff.md`
- Excluded work: publishing, production signing, source-control mutation without explicit scope, unauthorized device access, and owner evidence fabrication.
- Focused checks:
  - `GRADLE_USER_HOME=/tmp/quicklauncher-phase9-gradle-home tools/gradle --summary --project-cache-dir /tmp/quicklauncher-phase9-project-cache build checkModuleBoundaries verifyNoGoogleDependencies buildHealth checkPhase9Performance --max-workers=1 --no-daemon --no-configuration-cache --console=plain`
  - `git diff --check && tools/worktree_fingerprint.py`
  - `tools/agent_usage_report.py --repo . --top 15`
- Evidence output: `/tmp/quicklauncher-phase9-final-gates-final2/`, `/tmp/quicklauncher-phase9-final-gates-graphene/`, `/tmp/quicklauncher-phase9-final-gates-api35/`, terminal review reports under `/tmp/quicklauncher-phase9-final-review-api35/`, historical review reports under `/tmp/quicklauncher-phase9-final-review-graphene/`, `/tmp/quicklauncher-phase9-api35-authorized/`, `/tmp/quicklauncher-phase9-protected-draft-preflight/report.md`, and [`phase-9-release-evidence.md`](phase-9-release-evidence.md).

At each threshold, record completed edits, remaining work, check results, blockers, and the main agent's exact next action. The final response is gated on integrated worker reports, focused checks, final repository gates, separate review axes, and explicit authority-bound blockers.
