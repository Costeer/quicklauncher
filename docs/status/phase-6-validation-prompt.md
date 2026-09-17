# Phase 6 independent validation prompt

Checked on 2026-09-15.

This prompt validates the Phase 6 implementation without repeating the completed GrapheneOS device work. The repository owner already ran the GrapheneOS matrix, real managed-profile cases, Private Space checks, intended-installer recovery, and restoration audit. A reviewer should inspect that evidence for internal consistency but must leave the attached phone untouched.

```text
You are the independent validation agent for Quicklauncher's Phase 6 search implementation.

Work in /home/costeer/git/quicklauncher.

Review and verify only. Do not edit files, install dependencies into the repository, push, publish an APK, create a release, alter signing, or weaken dependency and CI checks. The worktree contains intentional staged, unstaged, and untracked changes. Preserve them exactly. Never reset, clean, stash, discard, overwrite, stage, or commit changes.

Read AGENTS.md and docs/agents/execution.md. Use the P6 acceptance IDs in docs/agents/validation-index.md. Start with the indexed headings for the acceptance ID under review, then open source and tests through search. Open another ADR or long document only when a concrete conflict or missing fact requires it. Accepted ADRs override older prose.

Treat the changed-file manifest in docs/status/phase-6-search-evidence.md as the review inventory. Capture git status and the complete HEAD diff once in a temporary review directory. Divide that inventory into acceptance-ID slices; do not make each review worker rescan the complete diff.

GrapheneOS exclusion

Do not interact with the attached GrapheneOS phone. Do not install or uninstall packages, change Home or notification access, create or remove profiles, unlock Private Space, alter permissions, add ADB reverse rules, or run connected tests against that device.

Do not rerun:

- the GrapheneOS five-module connected matrix;
- the real managed-work-profile tests;
- Private Space device flows;
- the intended-installer notification recovery flow;
- the GrapheneOS restoration audit.

Review the GrapheneOS provider, Settings-route validation, profile policy, tests, and recorded evidence as code and documentation. Record the device results as "owner-supplied evidence reviewed, not independently rerun." This requested exclusion is not a test failure. Report any contradiction or unsupported claim you find in the evidence.

If you run connected tests, use only the API 35 AOSP full-phone emulator. Resolve and pin its serial before invoking Gradle. Never run an unpinned connected task while another device is attached. If the required emulator is unavailable, report the API 35 matrix as not run. Do not substitute the GrapheneOS phone or another API level.

Implementation review

Inspect these behaviors through public seams and their regression tests:

1. P6-SESSION: Search session ownership
   - One bounded session exists for each visible search presentation.
   - Query validation and debounce stay within contract limits.
   - A new query cancels and replaces prior work.
   - Enabled providers run concurrently and healthy results publish without waiting for slow providers.
   - Provider-local timeouts, limits, crash containment, malformed-output containment, flow-failure isolation, cancellation, stale-generation rejection, post-close rejection, and immutable deterministic snapshots all hold.
   - Closing or removing search releases every provider session and owned coroutine.

2. P6-PRIVACY: Result validation and profile privacy
   - The host validates result kind, namespaced stable identity, uniqueness, relevance in 0 through 1,000, typed action, command resolution, session generation, ownership, and current profile eligibility.
   - Profile classification happens before labels, icons, shortcuts, contacts, files, or other protected metadata are read.
   - Locked, hidden, quiet, unavailable, and policy-ambiguous Private Space data cannot enter sessions, presentation state, caches, history, fixtures, or diagnostics.
   - Work results receive mandatory visual and accessible badging before crossing the host boundary.
   - Profile changes remove ineligible results and targets immediately. Activation revalidates profile and target state.

3. P6-RANK: Ranking and history
   - Ranking defines relevance normalization, exact, prefix, token, and fuzzy matching, deterministic tie-breaking, app and shortcut history influence, time decay, duplicate-target merging, and work presentation.
   - Backward clocks and malformed persisted rows fail safely.
   - History retains at most 256 app or shortcut targets. Its types cannot encode raw queries, contacts, files, web data, or Private Space metadata.
   - Room schema 7 exports a current schema and supports every required migration, reopen, injected rollback, malformed-row handling, and unrelated opaque-byte preservation case.

4. P6-EXEC: Typed execution
   - The executor accepts only typed actions or command identities.
   - Commands resolve through the generated production registry.
   - Context, parameters, result kind, permission, target, destination, item action, Settings route, file, contact, and web state are revalidated immediately before execution.
   - Validation failure causes no side effect. Missing access, unavailable or stale targets, recoverable failure, and domain cancellation produce typed results.
   - Query replacement and session closure invalidate old activations.
   - Providers and commands do not call each other or retain invocation data.

5. P6-PROVIDERS: Production providers
   - Generated production registration contains profile-aware apps, shortcuts, commands, contacts, authorized files, public Android Settings, versioned manually enabled GrapheneOS Settings, safe web actions, and recovery information.
   - Contacts and files fail closed after missing, denied, revoked, or invalid access and change only their own enabled preference.
   - Access requests begin only after explicit user action.
   - Public Settings routes and GrapheneOS routes require a current callable activity. An uncallable GrapheneOS route retains its public Android parent fallback.
   - Web actions use bounded input, HTTPS, explicit URI construction, encoded parameters, exact allowlisted destinations, and typed external actions. They reject credentials, dangerous schemes, embedded intents, malformed destinations, and downloaded code.

6. P6-UI: Presentation, permissions, and lifecycle
   - The search block consumes host-prepared state and emits typed UI actions. It does not retrieve, filter, rank, launch, or own provider sessions.
   - Empty, loading, partial, denied, unavailable, timed-out, and failed provider states remain usable alongside host recovery, Settings, Map, and the safe layout.
   - Enter, Escape, predictive Back, D-pad traversal, screen-reader semantics, large text, repeated Home, rotation, Activity recreation, presentation removal, and recreation have coverage.
   - Permission denial, later grant, revocation during visible search, process recreation, and recovery affect only the relevant provider.

7. P6-BOUNDARY: Module and privacy boundaries
   - Android framework types stay in host/platform or Android entry and instrumentation adapters.
   - Contributions depend only on approved contracts.
   - No raw query or result content reaches logs, diagnostics, analytics, backup, durable history, or fixtures.
   - No private labels, icons, shortcut data, contact data, file data, web queries, or Private Space content appears in fixtures or evidence.
   - Flows, caches, history, concurrency, result lists, cursor reads, directory traversal, and network reads are bounded.
   - No process-wide contribution scope, placeholder exception, raw contribution-ID routing, stale Room schema, generated build output, local machine path, new Google or Play Services dependency, weakened check, or unrelated edit is present.

Verification

Run focused tests when investigating a finding. Run the repository gates once for this release-candidate fingerprint. Reuse a recorded pass when the fingerprint, command, and relevant environment identity match. Use docs/agents/verification-ledger-template.md and record the exact command, duration, task count, test count, failures, errors, skips, and full-log path.

Full local gate:

tools/gradle --summary build checkModuleBoundaries verifyNoGoogleDependencies buildHealth \
  --no-daemon --no-configuration-cache --console=plain

Paparazzi gate:

tools/gradle --summary \
  :app:verifyPaparazziStableDebug \
  :modules:block:core:verifyPaparazziDebug \
  :modules:layout:core:verifyPaparazziDebug \
  :host:editor:verifyPaparazziDebug \
  :host:runtime:verifyPaparazziDebug \
  :host:settings:verifyPaparazziDebug \
  --no-daemon --no-configuration-cache --console=plain

Inspect the search normal and large-text Paparazzi images. A task pass alone does not replace visual inspection.

CI license tasks:

rg -o ':[A-Za-z0-9:_-]+:licensee' .github/workflows/ci.yml | sort -u | \
  xargs tools/gradle --summary --no-daemon --no-configuration-cache --console=plain

Workflow and diff checks:

nix shell nixpkgs#actionlint -c actionlint .github/workflows/ci.yml
git diff --check

API 35 AOSP connected matrix, only when the full-phone emulator is available and its serial has been pinned:

ANDROID_SERIAL=<api-35-emulator-serial> tools/gradle --summary \
  :host:data:connectedDebugAndroidTest \
  :host:platform:connectedDebugAndroidTest \
  :host:runtime:connectedDebugAndroidTest \
  :host:editor:connectedDebugAndroidTest \
  :app:connectedStableDebugAndroidTest \
  --no-daemon --no-configuration-cache --console=plain

Use synthetic test data. Restore the emulator's Home role, permissions, selected files, profiles, packages, Settings state, and test data. Record its fingerprint, API level, per-module test totals, failures, skips, and final restoration state. Treat authority-dependent skips as skips.

Report

Report findings first, ordered by severity, with file and line references. Include missing behavior and missing public-seam regression coverage as findings. Then report:

- architecture and ownership conclusions;
- exact local, visual, license, workflow, diff, and API 35 results;
- privacy and module-boundary audit results;
- evidence claims checked against repository artifacts;
- GrapheneOS evidence as owner-supplied and not independently rerun;
- every skipped or unavailable check;
- remaining defects or evidence gaps;
- confirmation that the worktree remained unchanged.

Say plainly when no defect is found. Do not endorse "Phase 6 is complete" if any required non-GrapheneOS behavior or gate fails. Do not turn the requested GrapheneOS exclusion into an independent pass, and do not erase the repository owner's recorded physical-device evidence.
```
