# Phase 9 release-hardening handoff

Phase 9 repository work is implemented; its final local, visual, dependency, license, workflow, privacy, security, release, repository, and separate two-axis review gates pass, as do the owner-authorized current GrapheneOS and pinned API 35 AOSP matrices. Phase 9 is not complete because the authorized protected downloaded-draft verification is concretely blocked. The authoritative ledger is [Phase 9 release evidence](phase-9-release-evidence.md).

## Current candidate

- Earlier fully executed device-independent source candidate fingerprint: `33e764da0583059d07ca21906d824964a0b988aa9a39f4193e0015cbcf8d5eac`.
- Connected GrapheneOS candidate fingerprint after the bounded instrumentation-harness repair: `9d013c4b8b79b227243f67aa38efb50dcdff90b6783decc9714247b3dd28289d` before and after the focused and full passing runs.
- Post-GrapheneOS integrated repository-gate fingerprint: `24c17342eba1277a137c91c63f1aea322cffb05fea6e21b1abe2cdbdbc5be679` before and after the aggregate, visual, and license reruns. The first documentation-inclusive terminal review passed at `87f4ea88efcb774a6f724a142f77174d4c2119f36245054d12eb47a049a4d856`; the terminal handoff reports the later self-including fingerprint after these report pointers.
- Final API 35 source fingerprint: `7912ef41bc5ae2a6fffb9150ca9075aedc16b53540bb59c7b724612758ae8287` for the passing connected matrix, benchmark run, and exact restoration.
- Final post-API 35 repository-gate fingerprint: `f8a76ee4c87da5749f52f9fc93cb262cb9434358e81c8e97d9433e71840fa97f` for the passing aggregate, visual, license, and non-Gradle gates. This is distinct from the device source fingerprint above.
- Final documentation-inclusive review fingerprint: `6b1337cd066ca29925563bc02b571a85afb8062eb426a9ed4767e0510577bd88`. Specification passed with zero findings; documented standards passed with zero violations and three medium advisory smells. Reports: `/tmp/quicklauncher-phase9-final-review-api35/spec.md` and `/tmp/quicklauncher-phase9-final-review-api35/standards.md`.
- Worktree state is intentionally dirty. Preserve every tracked and untracked path, including `modules/layout/core/hs_err_pid51270.log`, `hs_err_pid276805.log`, and `hs_err_pid431514.log`.

## Acceptance state

- Passed locally: `P9-SECURITY`, `P9-CHANNELS`, `P9-KEYS`, and `P9-DOCS`.
- Device evidence closed: the current GrapheneOS and pinned API 35 portions of `P9-A11Y`, `P9-PERF`, and `P9-GATES` pass. All five benchmark scenarios and all six measurable thresholds pass on the pinned target.
- Implemented but open: `P9-RELEASE` still lacks the protected real-draft redownload/provenance result.
- Open until all final evidence exists: `P9-GATES`.
- The explicitly authorized GrapheneOS Pixel 10a and pinned API 35 AOSP AVD were used only for their bounded matrices. The AVD was restored exactly, stopped without saving a snapshot, and confirmed offline.
- No APK was published, no Git tag or release was created, no production APK was signed, and no production signing material was accessed or reconstructed.

## Authorized GrapheneOS result

- Device: Pixel 10a (`stallion`), Android 17/API 37, GrapheneOS build `2026091001`, patch `2026-09-01`, release-keys fingerprint `google/stallion/stallion:17/CP2A.260805.005/2026091001:user/release-keys`.
- Focused repaired app-recovery route: 1/1 passed in 40s. Full exact five-module matrix: 213s wrapper time, Gradle 3m31s, 410 actionable tasks, 68 tests, 65 passed, three expected skips, zero failures/errors. Data passed 17/17; platform passed 19 with one shortcut-host-authority skip; runtime/editor were class-level SDK-suppressed on API 37; app passed 29 with two managed-work orchestration skips.
- The initial full run and bounded focused reruns diagnosed a harness-only Compose node-targeting/virtualized-list defect. The final test scrolls to the exact visible Quicklauncher label, activates it through normal UI Automator hit testing, retains the production entry assertion, and passed before the complete rerun.
- Baseline/postflight comparison found exact release-relevant semantic restoration: Launcher3 remained Home, profile/user states and owners matched, no Quicklauncher package/widget/grant/job residue remained, and captured settings and readable shared-file metadata matched. Advancing whole-service dump hashes are retained as advisory volatile evidence. The physical device remained online.
- Complete evidence: `/tmp/quicklauncher-phase9-grapheneos-physical/repaired5-focused/`, `/tmp/quicklauncher-phase9-grapheneos-physical/repaired5-full/`, `/tmp/quicklauncher-phase9-grapheneos-physical/baseline/`, `/tmp/quicklauncher-phase9-grapheneos-physical/postflight/`, and `/tmp/quicklauncher-phase9-grapheneos-physical/comparison/`.

## Authorized pinned API 35 result

- Source fingerprint: `7912ef41bc5ae2a6fffb9150ca9075aedc16b53540bb59c7b724612758ae8287`.
- Connected matrix: 129s, 83 tests, 80 passed, three expected skips, zero failures/errors. Complete evidence: `/tmp/quicklauncher-phase9-api35-authorized/connected-final3/`.
- Macrobenchmark matrix: five of five scenarios passed in 329s at thermal status `NONE` and refresh rate `60.000004Hz`. All six thresholds passed: Home startup `830.310ms`; spatial navigation CPU `22.196ms`; frame overrun `5.845ms`; catalog browse `84.749ms`; search first result `251.307ms`; memory `253.062MiB`. Complete evidence: `/tmp/quicklauncher-phase9-api35-authorized/benchmark/run7/`.
- The measured target was the exact installed `org.quicklauncher` APK, version `0.1.0`/code `1`, SHA-256 `ab1634d54b5f258d61a6b8a94f805d053910d63a02105c31bdf705f1744f5f49`, certificate SHA-256 `9be4fbc5c7311b023c58a1d0ffa105b30503805471948122b901796f71c15940`.
- Repairs proven by the final runs include a debug-only Compose test activity excluded from release variants, explicit production shell focus order, deterministic keyboard test input mode, cold-start onboarding-state synchronization, benchmark signing/emulator acknowledgement/permission/selector corrections, and R8 plus resource shrinking from 34.7MB to 5.78MB with narrow KSP/protobuf keep rules.
- Postflight state matched the captured baseline semantically. The emulator was killed with snapshot saving disabled and its serial was confirmed offline. Evidence: `/tmp/quicklauncher-phase9-api35-authorized/restoration/` and `/tmp/quicklauncher-phase9-api35-authorized/shutdown/`.

## Earlier post-GrapheneOS repository gates

- Aggregate local gate: pass in 29s with 4,617 task events; outputs were current, so no new XML count is claimed.
- Six-module no-record visual gate: pass in 20s with 365 tests; all 118 governed baselines and all 146 repository PNGs remained byte-identical.
- License gate: pass in 14s; 23 module checks plus the benchmark audit approved all 51 exact dependencies.
- The fingerprint remained `24c17342eba1277a137c91c63f1aea322cffb05fea6e21b1abe2cdbdbc5be679` throughout. Evidence: `/tmp/quicklauncher-phase9-final-gates-graphene/gradle/`.
- The complete non-Gradle suite then passed 15/15 commands at unchanged fingerprint `481f364081bc5bdef716a90d820a26fe41e380a3aa5b5dec5d3f0fff4b36a759`: 20 security, 38 release, and 32 performance tests; 51-dependency audit; channel/workflow/actionlint; disposable key recovery; stable/preview dummy download drills; repository/docs coherence; and whitespace. Evidence: `/tmp/quicklauncher-phase9-final-gates-graphene/non-gradle-final/`.

## Final post-API 35 repository gates

- At repository-gate fingerprint `f8a76ee4c87da5749f52f9fc93cb262cb9434358e81c8e97d9433e71840fa97f`, the aggregate passed in 68s with 4,038 actionable tasks.
- The six-module no-record visual gate passed in 21s with 365 tests and no mismatch; all 146 repository PNG hashes remained unchanged.
- The license gate passed in 13s and approved all 51 exact dependencies.
- The complete non-Gradle suite passed in about 9s: 20 security, 38 release, and 32 performance tests plus canonical audits, workflow/channel validators, `actionlint`, disposable-key recovery, stable/preview dummy drills, documentation coherence, and `git diff --check`.
- Dependency health first identified the Compose test manifest as an implementation dependency. Moving it from `debugImplementation` to `debugRuntimeOnly` preserved the debug-only test activity while restoring the intended dependency graph; the complete affected gate then passed.
- Complete evidence: `/tmp/quicklauncher-phase9-final-gates-api35/`.

## Remaining actions

1. The protected real-draft run is authorized but cannot yet produce valid evidence: the candidate is dirty and uncommitted, absent from the remote, the remote has zero tags, `gh` is unauthenticated, and the protected environment, secrets, public certificate variable, and reviewer configuration cannot be inspected. No external or source-control mutation was performed. Preflight evidence: `/tmp/quicklauncher-phase9-protected-draft-preflight/report.md`.
2. Once those prerequisites exist, run the protected stable or preview workflow at a matching immutable tag, download only the expected APK/checksum/metadata assets, and run the independent verifier plus GitHub attestation verification. Keep the release a draft until the exit condition is reviewed.

## Local reproduction

The normal command remains:

```bash
tools/gradle --summary build checkModuleBoundaries verifyNoGoogleDependencies buildHealth \
  checkPhase9Performance --no-daemon --no-configuration-cache --console=plain
```

This host retains a zombie PID recorded in old Gradle locks after a kernel page-migration stall. The successful local evidence isolated both cache roots instead of deleting locks:

```bash
GRADLE_USER_HOME=/tmp/quicklauncher-phase9-gradle-home \
  tools/gradle --summary \
  --project-cache-dir /tmp/quicklauncher-phase9-project-cache \
  build checkModuleBoundaries verifyNoGoogleDependencies buildHealth checkPhase9Performance \
  --max-workers=1 --no-daemon --no-configuration-cache --console=plain
```

Complete gate, visual, license, security, release-drill, failure, repair, and external-gap details are in the authoritative ledger. Deduplicated agent usage is recorded under `/tmp/quicklauncher-phase9-agent-usage/`: 30 matching transcripts, 118 completed turns, 242.31 million total tokens, 2,491 model steps, and 2,373 tool calls.

The post-review-repair aggregate passed in 241s with 4,039 actionable tasks, 598 XML tests, and 32/32 performance-verifier tests. The six-module no-record visual gate passed 365 tests in 47s with all 118 baselines unchanged; the 24-task license request passed in 12s; and the final non-Gradle suite passed in about 9s. Complete commands, counts, fingerprints, and evidence paths are in the ledger and `/tmp/quicklauncher-phase9-final-gates-final2/`.
