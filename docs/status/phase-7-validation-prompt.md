# Phase 7 independent validation prompt

Validate Quicklauncher's Phase 7 theme system without trusting the completion claim in advance.

Work in `/home/costeer/git/quicklauncher`. Preserve every staged, unstaged, and untracked change. Do not reset, clean, stash, discard, stage, commit, push, publish, sign, or change a dependency/check policy. Do not interact with the attached GrapheneOS phone; its recorded results are owner-supplied evidence reviewed, not independently rerun.

Read `AGENTS.md` and `docs/agents/execution.md`. Use the P7 acceptance IDs in `docs/agents/validation-index.md`. Start with each ID's indexed headings and open another ADR or long document only when a concrete conflict or missing fact requires it. Capture `git status --short`, the complete HEAD diff, and the untracked-file manifest once in a temporary review directory. Separate the Phase 6 manifest, pre-existing Phase 7 slice 1, and later Phase 7 work, then review those slices without regenerating the complete diff.

Audit these claims through public seams and source ownership:

1. P7-RESOLVE: There is one host resolver and one resolved immutable theme for root Material, host UI, composition, previews, layouts, and blocks. No contribution reads Android state, files, package resources, wallpaper, or unrestricted inventories.
2. P7-PALETTE: Built-ins, custom profiles, Material You/Expressive/manual palettes, complete fallback, preview/selection separation, capability-bounded overrides, process recreation, and atomic propagation are deterministic.
3. P7-PERSIST: Room schema 7 and Proto selection are reused without a new durable shape. Recheck all migration, reopen, rollback, malformed-row isolation, active-delete conflict, empty-database, built-in destination-background materialization, and opaque-byte cases.
4. P7-ASSET: SAF font/image imports, SFNT validation, private copies, denial/revocation, missing/corrupt fallback, image bounds/orientation/previews, retention, lazy bounded inventory, and unreferenced cleanup are bounded and do not log identifying material.
5. P7-ICON: Only the Nova/ADW declarative subset is parsed. Verify package/component/mapping/time/decode/cache-byte bounds, duplicate order, prepared fallback, installed-version checks, binary Android XML handling, and cache invalidation without loading package code.
6. P7-WALLPAPER: Wallpaper commands contain opaque identity and explicit Home/Lock/Both target, display a crop resolved from opaque host identity, revalidate immediately before exactly-once execution, and return typed cancellation/unavailable/stale/denied/recoverable results. Back, rotation, recreation, and stale preview cannot apply wallpaper.
7. P7-RECOVERY: Settings and safe recovery retain semantics, keyboard/D-pad actions, large-text/landscape usability, cancellation, Back/Home/recreation behavior, and corrupt-state recovery.

Use `tools/gradle --summary ...`. Run focused tests first, then run the exact local, visual, CI-derived license, workflow, and diff gates documented in the scope once for this release-candidate fingerprint. Reuse a recorded pass when the fingerprint, command, and environment identity match. Record each gate with `docs/agents/verification-ledger-template.md`. Build one contact sheet for new or changed Phase 7 PNGs, inspect it at normal detail, and open original detail only for a disputed region.

For connected coverage, start only the API 35 AOSP full-phone AVD, resolve its serial, and pin `ANDROID_SERIAL` on the exact five-module command in the Phase 7 evidence. Never substitute another API level or the GrapheneOS device. Use synthetic data and record the fingerprint, API, module totals, failures, errors, skips, and before/after Home, permissions, files, profiles, packages, wallpaper, Settings, grants, widgets, and test-data state. Restore the AVD exactly and stop it without saving a snapshot.

Report findings first with file/line references. Do not declare Phase 7 complete if any behavior, public-seam regression, migration/persistence invariant, privacy or module boundary, visual inspection, local/license/workflow/diff gate, required API 35 result, or emulator restoration fact is missing or contradicted.
