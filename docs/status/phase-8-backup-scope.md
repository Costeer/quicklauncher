# Phase 8 backup and migration scope

Phase 8 publishes and implements portable Quicklauncher archives, user-selected Storage Access Framework folders, manual and seven-snapshot automatic backups, optional passphrase encryption, staged restore validation, a pre-restore backup, atomic live-state replacement, reauthorization, widget rebinding, profile review, and redacted support bundles.

The exit condition remains: cross-version round trips pass, corrupt archives cannot alter live state, and restore always retains the safe layout.

Release validation continues through the bounded acceptance packets in [Phase 8 execution packets](phase-8-packets.md). High-output work expected to produce more than 200 lines or 20 KB is mandatory delegation, including full builds or tests, broad searches, complete diffs, long logs or documents, and device matrices. Subagents save full evidence and report no more than 400 words; bounded-summary wrappers may run in the main agent. Simple mechanical file changes with exact acceptance criteria go to lower-reasoning subagents when model choice is available. The main agent owns integration and completion, with no fixed subagent cap. Each packet uses the context, verification, and checkpoint thresholds in `docs/agents/execution.md`.

## Slice 1: deterministic archive envelope

The first slice establishes the pure archive seam in `:host:backup` and publishes [format version 1](../architecture/portable-backup-archive-v1.md). The codec owns canonical ordering, strict UTF-8 names, path rejection, immutable byte ownership, format/version checks, fixed size and count ceilings, per-section SHA-256 verification, required launcher-map validation, and typed rejection of corrupt or unsupported input.

This slice intentionally has no Android or filesystem access and cannot mutate launcher state. It also does not claim passphrase encryption: nonzero feature flags fail closed until the encrypted envelope is specified and implemented.

The initial focused unit/lint command passed in 52s with 44 actionable tasks (20 executed, 24 up-to-date). After adding malformed-name, non-canonical-order, decoded-duplicate, and immutable-list coverage, the final debug/release/lint command passed in 41s with 60 actionable tasks (22 executed, 38 up-to-date). Debug and release each ran the same 12 archive tests with no failure, error, or skip. `:host:backup:build checkModuleBoundaries verifyNoGoogleDependencies buildHealth` passed in 45s with 2,977 actionable tasks; the final current-source boundary/dependency rerun passed in 33s with 2,936 actionable tasks (47 executed, 2 from cache, 2,887 up-to-date). Final `actionlint` and whitespace checks passed.

## Implemented slices awaiting final gates

1. Typed launcher-map, theme, web-adapter, and asset payloads now have checked-in v1 fixtures. Opaque launcher extensions and web-adapter entries survive decode, selected restore, app recreation, and later export without execution.
2. SAF access uses persisted tree authorization, bounded reads, root-child checks, and typed provider failures. Manual archives and automatic snapshots have separate Settings pages. Protected wrapper magic keeps encrypted manual archives discoverable; preview performs authentication.
3. Automatic backup uses one persisted charging-only job for the next 03:00 local boundary and runs only before 06:00. Preference state follows actual scheduling success. Retention uses decoded archive kind and creation time, keeps seven automatic snapshots, and does not trust display names or provider modification times.
4. Passphrase protection uses the published PBKDF2-HMAC-SHA256 and AES-256-GCM wrapper. Fixed vectors and rejection tests cover malformed parameters, truncation, tampering, bounds, and working-copy clearing.
5. Restore validates before review, creates a pre-restore backup, stages auxiliary data, and uses one revision-checked live-state replacement. Theme references and assets are one selection. Failed or cancelled work rolls back, and recreation cleanup removes interrupted private asset staging.
6. Post-restore Settings actions cover document and permission reauthorization, widget rebinding, quarantine review, and profile review. Recovery counts survive Activity recreation while an unusable pending preview expires.
7. Support diagnostics use a bounded typed private log and redacted export. The additive 2x-text backup-review baseline shows the review card, all summaries, the restore action, and cancellation. Final repository and connected gates remain open.

## Current release candidate

All behavior packets from P8-SCHEMA through P8-SUPPORT have focused implementation evidence. Automatic backup uses a one-shot persisted charging constraint for the next 03:00 local boundary, rejects late execution at or after 06:00, and reconciles the enabled preference with the scheduled job. Inventory and retention use decoded archive kind and time. Current restore coverage includes corrupt and stale input, pre-restore backup, one revisioned store replacement, category selection, cancellation and runtime rollback, private theme-asset staging, interrupted-stage cleanup, recreation expiry, and idempotent Home/Back cancellation. SAF discovery retains documents whose provider omits size metadata and relies on the bounded read before decoding. Empty optional categories are omitted. Export and preview require every current decoded theme asset reference to match a canonical imported-asset section. The manifest stays optional; when present it exactly indexes all asset sections by name and SHA-256 digest. Web adapters and opaque launcher extensions persist without becoming executable contributions.

Phase 8 is not complete. The focused implementation checks and inspected large-text restore visuals are complete, but the final local, release, lint, license, workflow, diff, full visual, and pinned API 35 AOSP gates have not run on the final documentation fingerprint. See [Phase 8 backup evidence](phase-8-backup-evidence.md) for the ledger.
