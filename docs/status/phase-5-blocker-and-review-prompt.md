# Phase 5 completion and review prompt

Checked on 2026-09-14.

## Completion status

Phase 5 is complete. Both physical-device blockers were removed on the GrapheneOS Pixel 10a without substituting Private Space, a full secondary user, a fake platform, or a JVM test.

A disposable profile provisioned through Android ManagedProvisioning was reported as `android.os.usertype.profile.MANAGED` and supplied passing physical evidence for:

- managed-work discovery and mandatory badging;
- cross-profile app and shortcut launch;
- pause and resume through the typed work-mode action;
- removal while quiet or unavailable;
- restoration after a validated profile callback.

The notification recovery check was repeated through official Obtainium 1.6.17. Android recorded Obtainium as the installer. Explicit user initiation, system denial, typed recovery to App Info, later grant, listener process restart, visible revocation, immediate indicator removal, and exact listener restoration passed. Obtainium, Quicklauncher, its tests, the disposable profile, local serving state, and temporary artifacts were removed afterward.

The exact commands, device identity, test counts, installer digests, and restoration state are in [Phase 5 device evidence](phase-5-device-evidence.md). Phase 6 is the active implementation slice. The current gate summary is in [current state](current-state.md).

## Prompt for an independent review agent

```text
You are the independent verification agent for Quicklauncher's Phase 5 implementation.

Work in /home/costeer/git/quicklauncher.

Review only. Do not edit files, install new dependencies into the repository, publish an APK, create a release, push changes, or alter signing. The worktree contains intentional staged, unstaged, and untracked work. Preserve it exactly. Never reset, clean, stash, discard, or overwrite changes.

Read AGENTS.md and docs/agents/execution.md. Use the P5 acceptance IDs in docs/agents/validation-index.md. Start with the indexed headings for the assigned acceptance IDs and open another ADR or long document only when a concrete conflict or missing fact requires it. Capture the complete diff and inventory once, then give review workers disjoint slices. Check the implementation against the complete Phase 5 requirements represented by the IDs. Accepted ADRs override older prose.

Audit these areas through their public seams:

1. P5-WIDGET: Widget IDs, binding, configuration, resize, copy, dormancy, deletion, restore, host-view ownership, cancellation cleanup, and cross-system pending states.
2. P5-WIRING: Profile-aware shortcut identity, duplicate placements, complete pinned-set reconciliation, callback refresh, stale-state degradation, and launch revalidation.
3. P5-PROFILE: Profile classification before metadata reads, current-handle resolution, managed-work quiet-mode transitions, Private Space visibility, authentication, secure overlay, and immediate cache clearing.
4. P5-NOTIFY: Notification onboarding, denial, revocation, disconnect, restricted-settings recovery, sanitized indicators, and absence of notification content outside host/platform.
5. P5-FOLDER: Folder ordering and filtering, item-action policy and confirmation, predictive Back, repeated Home, keyboard, D-pad, and accessibility behavior.
6. P5-STORE: LauncherStore atomic rejection, cancellation propagation, orphan prevention, Room schema 6 migrations, reopen, rollback, duplicate shortcuts, folder order, opaque-byte preservation, and preferences.
7. P5-WIRING: Production wiring, lifecycle cleanup, contribution contracts, registry aggregation, CI coverage, and dependency rules.

Confirm that Android framework types remain inside host/platform, except Android entry-adapter code such as MainActivity and instrumentation tests. Search for raw notification content, private labels or icons in logs and fixtures, stale Room schemas, generated build output, local machine paths, placeholder exceptions, weakened dependency checks, and unrelated changes.

Run focused checks for findings. Run the exact local, Paparazzi, licensee, actionlint, diff, API 35, and GrapheneOS gates once for a release-candidate fingerprint when their prerequisites apply. Reuse recorded results when the fingerprint, command, and environment identity match. Use `tools/gradle --summary ...` and `docs/agents/verification-ledger-template.md`. Treat an authority-dependent skip as a skip.

Recheck the recorded disposable ManagedProvisioning path only when managed-work behavior changes. Verify discovery, mandatory badging, app and shortcut launch, pause, resume, quiet/unavailable removal, callback restoration, personal-profile continuity, and final profile-state restoration. Redact personal app labels, user names, shortcut data, and notification data.

Repeat notification restricted-settings recovery using the intended distribution installer only when P5-NOTIFY behavior changed. Confirm explicit user initiation, system denial or grant, revocation, listener disconnect, App Info recovery, successful grant when the OS offers it, and exact listener restoration.

Report findings first, ordered by severity, with file and line references. Then report verification results and remaining evidence gaps. If no code defect is found, say so plainly. Do not preserve the completion claim if a required gate is shown to be false.
```
