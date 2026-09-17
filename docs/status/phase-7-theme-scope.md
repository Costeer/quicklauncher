# Phase 7 theme scope

Status: complete on 2026-09-16. All six bounded slices and their required gates are implemented and verified.

## Objective

Phase 7 gives the launcher one host-owned visual system. Layouts and blocks receive an immutable resolved theme and resolved background contrast through shared contracts. They do not read system theme state, open imported files, resolve icon packs, load backgrounds, or change wallpaper.

ADR 0012, ADR 0014, and ADR 0016 are authoritative for this phase.

## Required behavior

- The host resolves system appearance, a named launcher-wide profile, accessibility inputs, and bounded overrides into one immutable theme for a presentation.
- The same resolved colors and mode drive the root Material theme, host surfaces, layouts, blocks, previews, and contribution fixtures.
- Theme values are validated before publication. Invalid, missing, incompatible, or corrupt stored values fall back to a complete built-in profile without producing a partially mixed theme.
- Material You and Material Expressive palettes are generated with Material Color Utilities. Manual palettes and bounded module overrides cannot bypass contrast, range, or completeness validation.
- Static and variable font imports are validated and copied into private storage. Missing or invalid fonts fall back without breaking the active profile.
- The supported Nova and ADW icon-pack subset is resolved by a host adapter. Missing mappings and unavailable packs fall back to the app's prepared icon.
- Launcher-wide and per-destination color, gradient, image, and scrim backgrounds are owned and loaded by the host. Contributions receive only resolved contrast values.
- An image-derived theme retains its seed, generated palette, and bounded preview. The source image is retained only when it is also the configured theme background.
- Applying an embedded image as system wallpaper requires a crop preview, an explicit home, lock, or both target, and a warning that the displaced wallpaper may not be recoverable. Preview, cancellation, failure, and process recreation cause no unintended wallpaper change.

## Privacy, safety, and bounds

- Theme records, diagnostics, fixtures, and evidence contain no private labels, icons, contacts, files, web queries, or Private Space content.
- Imported font, icon-pack, preview, and background reads have explicit size, count, traversal, decode, and lifetime bounds.
- Imported data is treated as untrusted. No theme, font, icon, background, or web-adapter import can execute code or introduce a runtime plugin.
- Android framework objects and file handles remain in host/platform or Android entry adapters. Contribution contracts contain immutable platform-neutral values.
- Wallpaper changes occur only through a typed host command whose target and preview generation are revalidated immediately before the platform call.

## Delivery slices

1. Establish the host resolver and wire one resolved built-in theme through the root Material surface, production composition, onboarding preview, layouts, and blocks. Add public-seam contract and resolver tests.
2. Audit and reuse the versioned named-profile persistence shape, adding a migration only if required; add built-in profiles, Material Color Utilities palette generation, manual tokens, and bounded overrides.
3. Add validated font import and host font resolution with deterministic fallback.
4. Add the documented Nova and ADW icon subset with host-side mapping, validation, cache bounds, and prepared-icon fallback.
5. Add host-owned launcher and destination backgrounds, image-derived themes, bounded previews, and resolved contrast.
6. Add wallpaper crop preview, explicit target confirmation, typed execution, recreation handling, and restoration-aware connected evidence.

Each slice must leave the full local, module-boundary, no-Google-dependency, dependency-health, license, and relevant visual gates passing. Android platform behavior must be covered on the pinned API 35 AOSP target. GrapheneOS-only behavior is recorded separately and is never inferred from an AOSP pass.

## First-slice acceptance

- A pure host resolver returns deterministic light and dark built-in themes from validated accessibility inputs.
- The root Material color scheme and every contribution render state derive from the same resolved object.
- Production composition, cold restore, and onboarding preview use the resolver rather than constructing theme values locally.
- Resolution rejects invalid text scale and exposes no Android type through contribution contracts.
- Focused contract, runtime, app integration, and visual regression tests pass.

## First-slice evidence

Implemented on 2026-09-15. The host resolver now owns the built-in light and dark theme decision, validates accessibility inputs before publication, supplies one aggregate theme and background-contrast object to composition, and maps the same colors into the root Material scheme. Production composition, cold restore, and onboarding preview no longer construct independent colors.

The focused runtime and app unit gate passed in 3m 4s with 215 actionable tasks, 8 executed and 207 up-to-date; its XML reports contain 217 tests with no failure, error, or skip. The authoritative local gate passed in 9m 42s with 3,807 actionable tasks, 1,205 tests, and no failure, error, or skip. The six-module Paparazzi gate passed in 2m 48s with 285 actionable tasks and 280 passing tests. Visual inspection found the normal search state intact and the large-text state wrapping without horizontal clipping while retaining the work badge.

All 23 CI-declared `licensee` tasks expanded to 38 passing tasks in 39 seconds. `actionlint` and `git diff --check` passed. The pinned API 35 full-phone matrix passed on the final current worktree in 5m 9s with 390 actionable tasks: 62 tests, 59 passed, 3 explicit authority-dependent skips, and no failure or error. The emulator was restored to Launcher3 Home with empty notification access, one Owner user, no device owner, and no Quicklauncher package, widget, or provider residue before shutdown. GrapheneOS evidence was not independently rerun.

## Completion evidence

Slices 2 through 6 are complete. Named profile selection uses the existing Room schema 7 theme/background records and Proto DataStore selection field, so no durable shape or migration was added. Built-ins remain code-owned defaults rather than rows; assigning a destination background from a built-in atomically materializes a derived custom profile and its background before selection. Corrupt, incomplete, incompatible, or missing records resolve as one complete built-in fallback. Material You and Material Expressive palettes use Material Color Utilities, while manual palettes and registered module overrides pass the same completeness and contrast validation.

The host/platform layer now owns bounded SAF font and image import, private copies and cleanup, static and variable font validation, Nova and ADW declarative icon-pack parsing and cache invalidation, launcher and destination background resolution, bounded image-derived previews, and the only `WallpaperManager` calls. Typed wallpaper commands contain opaque identities, crop generation, target, and warning acknowledgement. Execution revalidates support, ownership, preview freshness, and the selected target immediately before the exactly-once platform effect.

The final authoritative build gate passed in 57 seconds with 3,807 actionable tasks (32 executed, 6 from cache, 3,769 up-to-date) and 1,259 tests with no failure, error, or skip. The final six-module Paparazzi gate passed in 1m 56s with 285 actionable tasks (6 executed, 279 up-to-date) and 299 passing selected-module tests. All nine new Phase 7 profile, manual-palette, and wallpaper settings baselines were inspected at normal or large text in light portrait or dark landscape; controls and the real crop preview remain readable, the landscape list scrolls, and no control is horizontally clipped.

All CI-declared `licensee` tasks passed in 12 seconds with 38 actionable tasks, all up-to-date. The final `actionlint` and `git diff --check` reruns passed. The pinned API 35 AOSP full-phone matrix passed in 2m 39s with 390 actionable tasks (9 executed, 381 up-to-date): 69 tests, 66 passed, 3 explicit authority-dependent skips, and no failure or error. Its Phase 7 coverage includes synthetic font/image import and private recreation, revoked access, bounded icon-pack lookup/invalidation, displayed crop preview, stale wallpaper rejection, and cleanup. The emulator was restored to its original Launcher3 Home, permission, user, rotation, font-scale, night-mode, wallpaper-name, package, grant, widget, provider, and absent notification-listener-key state, then stopped without saving a snapshot.

The complete command ledger, persistence analysis, privacy audit, changed-file separation, and restoration record are in [Phase 7 theme evidence](phase-7-theme-evidence.md). The independent rerun instructions are in [the Phase 7 validation prompt](phase-7-validation-prompt.md). GrapheneOS results are owner-supplied evidence reviewed, not independently rerun.

## Out of scope

Backup archives, scheduled backup, release publication, signing changes, runtime plugins, remote theme execution, and network-fetched executable content are not part of Phase 7.
