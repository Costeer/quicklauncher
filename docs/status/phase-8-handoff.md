# Phase 8 handoff

Status: Phase 8 is in progress and must not be declared complete. Preserve the mixed Phase 5 through Phase 8 worktree and all untracked files. Do not stage, commit, reset, clean, stash, discard, push, publish, sign, change dependency/check policy, or use the attached GrapheneOS phone.

## Completed in this packet

- The exact inherited focused command passed before edits and again after them.
- Restore now performs non-cancellable best-effort auxiliary rollback after cancellation, commit failure, store exception, or store rejection. Rollback failures cannot mask the original result.
- Focused tests cover theme-asset stage/commit/discard, restore cancellation and runtime failures, recreation expiry, and idempotent Home/Back cancellation.
- A pending restore review now expires across process recreation instead of retaining an unusable token. Inventory refresh no longer erases operation messages.
- SAF discovery retains archives with unknown provider size and relies on the bounded read. Child validation uses the tree's parent document URI.
- Empty optional theme, web, and asset envelopes are omitted. The launcher snapshot is no longer duplicated into the theme section. Optional typed sections are validated when present.
- Android backup-rule instrumentation coverage compiles and asserts include-only database, preferences-proto, and theme-asset rules.
- Automatic backup now uses a persisted one-shot job for the next 03:00 local boundary, retains the charging constraint, exports only before 06:00, and schedules the next night after completion, failure, or interruption.
- Enabling automatic backup persists only after scheduling succeeds. Startup either finds or recreates the expected job; a failed reconciliation or successor schedule disables the stored preference.
- Focused clock tests cover the nightly boundaries and both daylight-saving transitions. API 35 instrumentation inspects the pending job's one-shot, persisted, charging-only contract.
- Inventory and retention use codec-accepted archive kind and creation time, not names or provider timestamps. Protected wrapper magic keeps an encrypted archive discoverable in the manual list until preview authenticates it; retention never acts on it. Renamed manual and corrupt automatic-looking documents are not pruned.
- Export and preview enforce canonical `font`, `image`, and `preview` sections for every current decoded theme reference before writing or showing restore review. Opaque future records may retain well-formed extra assets.
- Asset manifests remain optional. A present manifest must be non-empty and exactly index all asset sections by canonical name and SHA-256 digest.
- Restore presents themes and imported assets as one category. Destination-map and web-adapter data remain independent. Web-adapter payloads and opaque launcher extensions persist privately across selected restore, app recreation, and later export without being executed.
- Failed or cancelled restore discards auxiliary changes. Normal theme reconciliation removes interrupted `restore-<uuid>` staging directories after process recreation.
- Manual archives and automatic snapshots now use separate Settings pages. Post-restore actions cover document and permission reauthorization, widget rebinding, quarantine review, and profile review. Recovery counts survive Activity recreation; pending preview tokens expire.
- Support diagnostics now use a bounded checksummed private log. A successful user export clears only the exported prefix; cancellation, failure, or concurrently appended events remain.
- Checked-in version 1 launcher, web-adapter, and complete archive fixtures round-trip byte for byte. Crypto coverage includes the published fixed vector, parameter changes, selected truncations, tampering, bounds, and working-copy clearing.
- The large-text restore review has a separate bottom-scrolled actions baseline. It visibly includes the card heading, all review summaries, category choices, the primary restore action, and cancellation at 2x text. The original captures remain in place.
- The archive specification, Phase 8 scope, current state, and focused evidence ledger were updated.

## Verification

- Consolidated focused run: 30 tests, no failures, stable app compilation, 13s. Source fingerprint `acea67be2e3ed2d7c7c5c4d7c5afbe1b191b170e84237e5aea532c02970c0327`.
- Settings Paparazzi verification passed without recording: 30 tests, no failures, 93s. Source fingerprint `6b9261c57849f1c3e7e473e336ab5b4ebebdea74b6e44a623bc296d73c5c26e5`.
- Nightly scheduling focused verification passed 6 tests with stable app and Android-test compilation in 15s. Source fingerprint `5e6a70f5cf07d7bb639a23b768fc7ce5faa4a327b8ef89d2dc58daba4783e66c`.
- Asset consistency focused verification passed 21 tests with stable app Android-test compilation in 2m17s. Source fingerprint `0517ba4fc7a88a21e431fe0c9cd3b63b4af83c1e8a6b6141fdd492fb1781a0fb`.
- Focused settings Paparazzi recording and the following no-record verification each passed 1 test. The final verification took 41s at source fingerprint `a4b6751b61527b93739d40ff1e5b6f3a7715233c8b774f4c1ae13abf79e8f59f`.
- The final fixture and crypto debug/release runs each passed 29 tests. Logs: `/tmp/quicklauncher-agent-logs/gradle-20260917T115845Z-890872.log` and `/tmp/quicklauncher-agent-logs/gradle-20260917T115920Z-891756.log`.
- The final retention/library run passed 17 tests in 70s, and the SAF Android-test source compiled in 35s. Logs: `/tmp/quicklauncher-agent-logs/gradle-20260917T120533Z-898949.log` and `/tmp/quicklauncher-agent-logs/gradle-20260917T120444Z-897917.log`.
- The final diagnostics preference/log run passed 9 selected tests in 116s. Log: `/tmp/quicklauncher-agent-logs/gradle-20260917T120115Z-893820.log`.
- The integrated restore and recreation run passed 21 tests in 76s; the follow-up library run passed 18 tests in 56s. Logs: `/tmp/quicklauncher-agent-logs/gradle-20260917T120913Z-907069.log` and `/tmp/quicklauncher-agent-logs/gradle-20260917T121246Z-913882.log`.
- After the expected UI mismatch, the updated settings baseline record passed 33 tests in 131s and no-record verification passed 33 tests in 119s. Logs: `/tmp/quicklauncher-agent-logs/gradle-20260917T121646Z-922041.log` and `/tmp/quicklauncher-agent-logs/gradle-20260917T121912Z-927024.log`. The review fingerprint at that point was `e1cbe86a8564111b394b65735a05dab57c72efd594a31f0bd96be73733476c51`; the later protected-manual discovery fix and these documentation edits supersede it.
- Protected-manual discovery then passed all 18 library tests in 54s. Log: `/tmp/quicklauncher-agent-logs/gradle-20260917T122353Z-936824.log`.
- `git diff --check` passed. `tools/agent_usage_report.py --repo . --top 15` ran.
- Full commands and logs are in [Phase 8 backup evidence](phase-8-backup-evidence.md).

## Remaining work and risks

1. Record the post-documentation fingerprint, then run the final local, release, lint, full visual, license, workflow, and diff gates. Focused fingerprints above are not final-gate fingerprints.
2. Run required connected checks only on the pinned API 35 AOSP full-phone AVD. Record its state before testing, restore it exactly, and stop without saving a snapshot.
3. Treat GrapheneOS results only as owner-supplied evidence. Do not use the attached phone.

## Exact next command

```sh
tools/gradle --summary build checkModuleBoundaries verifyNoGoogleDependencies buildHealth --no-daemon --no-configuration-cache --console=plain
```
