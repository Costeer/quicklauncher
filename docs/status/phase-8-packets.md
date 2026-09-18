# Phase 8 execution packets

These packets applied the bounded process in `docs/agents/execution.md` to Phase 8. P8-ENVELOPE through P8-GATES are complete at relevant source fingerprint `65820aea82c057acf53678db1dbc5a5946eeba962f7df7d5ca653ae55d086656`.

At 8 million recorded tokens, 75 model steps, 100 tool calls, 45 minutes, or about 15 changed files, record a compact checkpoint and delegate the next context-heavy slice. High-output work expected to produce more than 200 lines or 20 KB is mandatory delegation, including full builds or tests, broad searches, complete diffs, long logs or documents, and device matrices. Subagents save full evidence and report no more than 400 words; bounded-summary wrappers may run in the main agent. Simple mechanical file changes with exact acceptance criteria go to lower-reasoning subagents when model choice is available. The main agent owns integration and completion, and there is no fixed subagent cap. New work outside the owned acceptance IDs becomes a later packet in the same task.

## P8-02: portable payload schemas

Status: complete.

- Acceptance ID: P8-SCHEMA
- Completion: typed launcher-map, theme, web-adapter, and asset payloads round-trip through checked-in version 1 fixtures. Opaque launcher extensions and web-adapter entries survive selected restore and later export without execution.
- Initial context: P8-SCHEMA entries in `docs/agents/validation-index.md` and the archive format.
- Owned area: `host/backup` pure models, codecs, fixtures, and focused tests.
- Focused check: `tools/gradle --summary :host:backup:testDebugUnitTest :host:backup:testReleaseUnitTest --no-daemon --console=plain`

## P8-03: authorized folder and inventory

Status: complete.

- Acceptance IDs: P8-SAF and P8-RETENTION
- Completion: bounded folder streaming, persisted authorization, separate inventories, one-shot nightly charging-only scheduling, scheduling-preference reconciliation, and decoded-kind retention of seven automatic snapshots work without pruning manual archives.
- Initial context: P8-SAF and P8-RETENTION index entries.
- Owned area: backup platform adapter, authorization state, scheduler, inventory, and focused tests.
- Focused check: the smallest host backup and platform tests covering authorization, streaming, inventory, and retention.

## P8-04: encrypted envelope

Status: complete.

- Acceptance ID: P8-CRYPTO
- Completion: authenticated passphrase encryption has a versioned envelope, bounded parameters, typed failures, tamper coverage, and no retained passphrase.
- Initial context: P8-CRYPTO index entry and archive feature-flag rules.
- Owned area: encryption specification, pure crypto adapter, test vectors, and focused tests.
- Focused check: host backup encryption tests for round trip, wrong passphrase, tamper, bounds, and memory ownership.

## P8-05: staged restore transaction

Status: complete. Composite staging also cleans up already acquired stages after later acquisition failure or cancellation, and exact-kind archive-local preservation prevents interleaved previews from changing another archive's restore.

- Acceptance ID: P8-RESTORE
- Completion: validation and pre-restore backup finish before one atomic live-state replacement. Selection keeps themes coupled to assets, auxiliary rollback handles rejection or cancellation, interrupted asset staging is cleaned after recreation, and every path retains the safe layout.
- Initial context: P8-RESTORE, SH-STORE, and SH-RECOVERY index entries.
- Owned area: restore staging, store transaction, rollback, cancellation, and focused tests.
- Focused check: restore tests for corrupt input, injected failure, cancellation, safe-layout retention, and successful atomic replacement.

## P8-06: post-restore recovery

Status: complete.

- Acceptance ID: P8-REAUTH
- Completion: restored state drives explicit document and permission reauthorization, widget rebinding, quarantine review, and profile review without exposing unavailable data. Recovery state survives Activity recreation while a pending preview expires.
- Initial context: P8-REAUTH and SH-PRIVACY index entries.
- Owned area: recovery models, host coordination, settings presentation, and focused tests.
- Focused check: focused runtime, settings, widget, and profile recovery tests.

## P8-07: support bundle and release evidence

Status: complete. The final source candidate passed the complete local, no-record visual, CI-derived license, workflow, repository/privacy/release, and pinned API 35 connected gates. The AVD was restored to an empty semantic diff and shut down without a snapshot. Final spec review found no issues; final standards review found no documented violations and recorded two medium plus one low non-blocking design smells.

- Acceptance IDs: P8-SUPPORT and P8-GATES
- Completion: support bundles are bounded and redacted, cross-version and corruption matrices pass, required visuals and devices are recorded, restoration is exact, and the Phase 8 exit condition is satisfied.
- Initial context: P8-SUPPORT and P8-GATES index entries plus completed packet ledgers.
- Owned area: support export, final evidence, visual fixtures, and release-gate fixes only.
- Focused check: support redaction and bound tests before release gates.
- Release gates: run each applicable local, debug, release, lint, visual, license, workflow, and diff gate once for the final fingerprint. Run connected checks only on the pinned API 35 AOSP full-phone AVD, restore its exact state, and stop it without saving a snapshot. GrapheneOS remains owner-supplied evidence and must not be rerun. Record every command with `docs/agents/verification-ledger-template.md`.

The repository has no Phase 8 macrobenchmark task or module. Measured accessibility and performance thresholds remain Phase 9 release-hardening work under the architecture plan; no Phase 8 benchmark result is claimed.
