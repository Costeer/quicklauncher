# Phase 5 blocker and review prompt

Checked on 2026-09-14.

## Open blocker

The attached GrapheneOS Pixel 10a has no managed-work profile. It has a personal profile, Private Space, and two full secondary users. Full secondary users cannot prove managed-work behavior.

Phase 5 therefore lacks physical-device evidence for:

- managed-work discovery and mandatory badging;
- cross-profile app and shortcut launch;
- pause and resume through the typed work-mode action;
- removal while quiet or unavailable;
- restoration after a validated profile callback.

The GrapheneOS notification recovery check has one more external limit. The shell-installed build reproduced restricted-settings rejection, reported revocation without retaining indicators, and opened App Info through the production recovery action. App Info did not offer the documented "Allow restricted settings" control for that installation source. Repeat the grant check using the intended distribution installer before closing Phase 5.

Keep Phase 5 open and Phase 6 pending until both checks pass. Do not substitute fakes, a private profile, or a full secondary user for managed-work evidence. The exact completed commands, device identity, test counts, and restoration state are in [Phase 5 device evidence](phase-5-device-evidence.md). The current gate summary is in [current state](current-state.md).

## Prompt for an independent review agent

```text
You are the independent verification agent for Quicklauncher's Phase 5 implementation.

Work in /home/costeer/git/quicklauncher.

Review only. Do not edit files, install new dependencies into the repository, publish an APK, create a release, push changes, or alter signing. The worktree contains intentional staged, unstaged, and untracked work. Preserve it exactly. Never reset, clean, stash, discard, or overwrite changes.

Read the accepted ADRs first. Then read:

- docs/status/current-state.md
- docs/status/phase-5-device-evidence.md
- docs/status/phase-5-blocker-and-review-prompt.md
- docs/research/phase-5-android-platform-behavior.md
- docs/architecture/launcher-architecture-plan.md

Check the implementation against the complete Phase 5 requirements, not only the status summary. Accepted ADRs override older prose.

Audit these areas through their public seams:

1. Widget IDs, binding, configuration, resize, copy, dormancy, deletion, restore, host-view ownership, cancellation cleanup, and cross-system pending states.
2. Profile-aware shortcut identity, duplicate placements, complete pinned-set reconciliation, callback refresh, stale-state degradation, and launch revalidation.
3. Profile classification before metadata reads, current-handle resolution, managed-work quiet-mode transitions, Private Space visibility, authentication, secure overlay, and immediate cache clearing.
4. Notification onboarding, denial, revocation, disconnect, restricted-settings recovery, sanitized indicators, and absence of notification content outside host/platform.
5. Folder ordering and filtering, item-action policy and confirmation, predictive Back, repeated Home, keyboard, D-pad, and accessibility behavior.
6. LauncherStore atomic rejection, cancellation propagation, orphan prevention, Room schema 6 migrations, reopen, rollback, duplicate shortcuts, folder order, opaque-byte preservation, and preferences.
7. Production wiring, lifecycle cleanup, contribution contracts, registry aggregation, CI coverage, and dependency rules.

Confirm that Android framework types remain inside host/platform, except Android entry-adapter code such as MainActivity and instrumentation tests. Search for raw notification content, private labels or icons in logs and fixtures, stale Room schemas, generated build output, local machine paths, placeholder exceptions, weakened dependency checks, and unrelated changes.

Rerun the exact local, Paparazzi, licensee, actionlint, diff, API 35, and GrapheneOS commands recorded in the status documents when the required environment is available. Record commands, durations, task counts, test counts, failures, skips, fingerprints, API levels, and restoration results. Treat an authority-dependent skip as a skip.

The current GrapheneOS reference phone has no managed-work profile. Report that as a missing external prerequisite. Do not create or simulate completion evidence. If another compliant reference device with a real managed-work profile becomes available, verify discovery, mandatory badging, app and shortcut launch, pause, resume, quiet/unavailable removal, callback restoration, personal-profile continuity, and final profile-state restoration. Redact personal app labels, user names, shortcut data, and notification data.

Also repeat notification restricted-settings recovery using the intended distribution installer. Confirm explicit user initiation, system denial or grant, revocation, listener disconnect, App Info recovery, successful grant when the OS offers it, and exact listener restoration.

Report findings first, ordered by severity, with file and line references. Then report verification results and remaining evidence gaps. If no code defect is found, say so plainly. State "Phase 5 is complete" only if every definition-of-done item and both physical-device checks pass. Otherwise state that Phase 5 remains open and name each blocker.
```
