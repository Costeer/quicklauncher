# Phase 8 handoff

Status: Phase 8 is complete at relevant source fingerprint `65820aea82c057acf53678db1dbc5a5946eeba962f7df7d5ca653ae55d086656`. P8-ENVELOPE through P8-GATES and the exit condition are satisfied. Preserve the mixed Phase 5 through Phase 8 worktree and all untracked files; no change was staged, committed, reset, cleaned, stashed, discarded, pushed, published, or signed.

## Completed behavior

- Portable v1 archives have canonical bounded envelopes, typed launcher/theme/web/asset payloads, checked fixtures, opaque extension preservation, and a separately authenticated passphrase wrapper.
- SAF authorization and bounded streaming, protected-manual discovery, distinct manual/automatic/pre-restore inventories, seven-snapshot decoded-kind retention, and one-shot charging-only nightly scheduling are complete. Android adapters live in `:host:platform`; pure nightly policy remains in `:host:backup`.
- Preview validates exact-kind theme/background references, optional exact manifests, and malformed assets before review. Well-formed future assets remain opaque and archive-local.
- Restore creates a pre-restore archive, uses independent destination-map, themes/assets, and web-adapter selections, stages auxiliary work, and performs one revision-checked Room replacement. Composite staging discards all acquired stages after later acquisition failure or cancellation; reconciliation removes interrupted stages and exact-kind unreferenced assets.
- Recovery actions and real restored profile serials survive recreation while pending preview tokens expire. Support diagnostics are bounded, checksummed, private, corruption-safe, and cleared only after successful export.
- Android backup rules include only durable database, Proto preferences, installed theme assets, preservation metadata, and opaque web/extension preferences; staging, diagnostics, authorization, and passphrases are excluded.

## Final release evidence

- Full local gate: `tools/gradle --summary build checkModuleBoundaries verifyNoGoogleDependencies buildHealth --no-daemon --no-configuration-cache --console=plain`; 2026-09-17 14:17:09–14:18:37 UTC, 88s, 3,831 actionable tasks, 713 tests, 0 failures/errors/skips. Log: `/tmp/quicklauncher-agent-logs/gradle-20260917T141709Z-159573.log`.
- Six-module no-record Paparazzi gate: 18s, 334 tests, all passed. Log: `/tmp/quicklauncher-phase8-final-gates/visual/post-review-fixes/gradle-20260917T141913Z-164299.log`. The four backup-review baselines were visually inspected; controls, independent categories, restore/cancel actions, contrast, wrapping, and clipping were acceptable at light/dark, portrait/landscape, and 2x text.
- CI-derived license gate: 23 declared entry tasks, 38 actionable tasks, all passed in 11s. `nix shell nixpkgs#actionlint -c actionlint .github/workflows/ci.yml` passed in 0.226s with no diagnostics.
- Repository/privacy/release audit and `git diff --check` passed. No release, dependency-verification, signing, publishing, workflow-permission, application-ID, or version policy changed.
- Pinned API 35 AOSP gate on `quicklauncher_aosp_full35_phase5`, serial `emulator-5554`, exact fingerprint `Android/sdk_phone64_x86_64/emu64x:15/AE3A.240806.019/12368160:userdebug/test-keys`: 127s, 78 tests, 75 passed and 3 capability skips. The requested AVD had been absent, so Emulator 37.1.11, API 35 default x86_64 revision 2, and a Pixel 6 full-phone profile were provisioned as recorded in `/tmp/quicklauncher-phase8-final-gates/avd-provision/README.md`. The required Home/resolved Home, font, rotation, night, listener, users/owners, packages/permissions, wallpaper, widgets, URI grants, jobs, and shared files were captured and restored; the semantic diff was empty. `emu kill` succeeded, pinned `get-state` failed afterward, and `-no-snapshot-save` was used. No GrapheneOS or other serial was queried.
- Final reviews are separate: spec found 0 issues and explicitly rechecked SAF close-before-rename/partial deletion; standards found 0 documented-standard violations. The standards reviewer recorded two medium divergent-responsibility smells and one low restore-port API-surface smell as non-blocking follow-up.

## Relevant failure history

- The handed-off `/tmp/quicklauncher-agent-logs/gradle-20260917T123846Z-967677.log` compile failure was fixed by making `AndroidThemeAssetStore.deleteUnreferenced` return `Unit`; `/tmp/quicklauncher-agent-logs/gradle-20260917T124452Z-981513.log` then passed 36 tests.
- The first full gate found missing `RECEIVE_BOOT_COMPLETED`; the permission moved with the scheduler adapter to `:host:platform`, and focused lint/Android-test compilation passed.
- A later full run suffered an OpenJDK/Paparazzi native JVM crash. `modules/layout/core/hs_err_pid51270.log` was preserved; subsequent runs passed.
- Dependency-health advice was resolved iteratively without weakening policy. Backup-rule auditing then found missing durable state, the rules and exact contract test were corrected, and every invalidated local, visual, license/workflow, repository, connected, and review gate passed again on the final fingerprint.
- First final reviews found the Android adapter boundary and partial composite-stage cleanup issues; both were fixed, focused regressions passed, and both review axes were rerun.

The full focused and final ledger is [Phase 8 backup evidence](phase-8-backup-evidence.md). There is no Phase 8 macrobenchmark task or module; measured accessibility and performance thresholds remain Phase 9 release-hardening work under the existing architecture plan. GrapheneOS results remain owner-supplied evidence only.
