# Quicklauncher architecture and implementation plan

Quicklauncher is an Android home app where users arrange destinations on a two-dimensional map and compose each destination from build-time modules. [CONTEXT.md](../../CONTEXT.md) defines project terms, [ADRs](../adr) record accepted decisions, [contracts](../contracts/README.md) specify contribution seams, and [current state](../status/current-state.md) records completed work.

## Fixed constraints

| Concern | Decision |
| --- | --- |
| Platform | Android 15/API 35 minimum; phones in portrait and landscape |
| UI | Kotlin and Compose; `AndroidView` only for host-owned widget views |
| Extensions | Build-time modules in this repository; no runtime plugins |
| Distribution | Separate stable and preview GitHub/Obtainium channels |
| Dependencies | No Google Play Services or Firebase |
| License | Apache-2.0 |
| First public build | Blocked until all first-release contracts and release gates pass |
| Deferred data paths | Clone-ready storage, but no linked-clone UI or foreign-launcher import in 1.0 |

## Ownership rule

The host owns policy and effects. It prepares immutable state, renders host surfaces, validates typed actions, and owns persistence, Android APIs, permissions, profiles, lifecycle, recovery, search ranking, and navigation.

Contribution modules implement layouts, blocks, search providers, launcher commands, or destination templates. They depend only on contracts, receive no Android launcher or Room objects, and never call another contribution implementation. `:app` selects contributions; generated registry fragments connect them to the host.

Dependency checks enforce:

- `:modules:*` may depend on `:contracts:*`.
- `:modules:*` may not depend on `:host:*`, `:app`, Room, platform adapters, or another contribution implementation.
- `:host:*` depends on contracts, not contribution implementations.
- `:registry:ksp` validates registrations at compile time.
- `:registry:production` explicitly aggregates installed module fragments.

`settings.gradle.kts` is the source of truth for the current Gradle module list.

## Host modules

| Interface | Owns |
| --- | --- |
| `ContributionRegistry` | Static registration, stable IDs, codecs, contract majors, cross-contribution validation |
| `CompositionEngine` | Configuration loading, typed placement trees, sessions, prepared content, isolation, safe fallback |
| `SpatialNavigator` | Connected-map movement, gestures, neighbor previews, Home behavior |
| `LauncherStore` | Revisioned task-level transactions, Room mapping, migrations, state invariants |
| `AppCatalog` | Profile-aware apps and shortcuts, policy, overrides, folders, indicators |
| `SearchEngine` | Provider concurrency, filtering, ranking, history, typed action execution |
| `WidgetHost` | Widget IDs, consent, configuration, views, options, deletion, restore mapping |
| `ThemeEngine` | Theme tokens, palettes, fonts, icons, backgrounds, contrast, wallpaper requests |
| `BackupLibrary` | Archives, SAF grants, preview, encryption, rotation, restore staging |
| `PermissionCoordinator` | Home role, runtime and special access, denial and recovery routes |
| `RecoveryController` | Crash markers, quarantine, safe-layout selection, redacted diagnostics |

Production and fake adapters meet at the same interfaces. New interfaces need a second implementation or a dependency rule that the existing interface cannot express.

## Composition and navigation invariants

- Each destination selects one retained layout tree. Other layout trees remain dormant with no live session.
- A layout or block exposes typed slots. The registry and runtime validate block type, capabilities, capacity, scroll axes, and acyclic nesting.
- A drag changes memory only. A valid drop commits one transaction; rejection preserves the prior state.
- A copy gets new instance, placement, configuration, and widget identities while retaining shared content references. Future clones may share configuration only.
- The host-owned safe layout is non-removable and independent of optional contributions.
- Destination coordinates are unique integers. Exactly one stable start destination exists, and every destination stays cardinally reachable from it.
- The runtime composes only the current destination and needed cardinal neighbors. Preview neighbors have no actions or semantics.
- Scrollable content consumes a drag until its boundary, then passes unconsumed motion to spatial navigation. Edge-only mode remains available.
- Home closes transient UI and selects the start destination. Back closes overlays; it does not mean movement left.
- Map editing and destination composition are separate modes. Every drag operation has a non-drag command.

## Persistence

Room stores destinations, retained layouts, module instances, configuration documents, placements, content, folders, app overrides, widgets, themes, backgrounds, and crash markers. Proto DataStore stores launcher-wide preferences. These are separate transaction domains.

`LauncherStore` exposes revision-checked task operations rather than table repositories. Accepted operations commit one immutable snapshot; rejected, failed, or cancelled operations preserve the exact prior snapshot. Unknown or failed module data remains byte-preserved until compatible code returns or the user deletes it.

Persist profile serials, never `UserHandle`. App and shortcut identity includes profile. Module configuration and parent-owned placement data have independent schemas and pure sequential migrations.

## Android, privacy, and failure rules

Android objects stop in `:host:platform`. Adapters own Home role, launcher apps and profiles, widgets, notification access, wallpaper, document grants, permissions, and package actions.

- Locked private apps never enter ordinary app collections, search, diagnostics, or module state.
- Private Space support is host-owned and is enabled only with its secure overlay and complete lock, unlock, visibility, and refresh behavior.
- Notification adapters expose only profile/package aggregates, never notification objects or content.
- Imported assets, archives, and web adapters have explicit size, time, and parsing limits. Web adapters use HTTPS data and cannot execute code.
- Permissions are requested in context. Settings return is followed by a fresh grant check.
- Nothing uploads automatically. Support bundles require explicit export and redact sensitive values.
- Visibility-policy failure makes ordinary collections fail closed while host Settings keeps a typed app-recovery route.
- A contribution failure affects one instance. Layout failure selects the safe layout. Restore-time crash markers provide next-launch recovery where Compose cannot contain an in-process exception.

Phase 5 platform constraints are summarized in [its research note](../research/phase-5-android-platform-behavior.md). Prototype evidence lives under [docs/prototypes](../prototypes).

## Search invariants

The host owns retrieval, validation, ranking, lifecycle, history, permission recovery, and execution. A visible presentation opens one bounded `SearchSession`; removing it from composition closes that session and every provider session and coroutine it owns. Query changes debounce, validate against the contribution contract, advance a generation, cancel prior work, and release prior ephemeral target mappings.

Enabled providers fan out concurrently. Each provider has a bounded candidate input, result limit, and first-result timeout. Healthy snapshots publish without waiting for slow providers. A provider crash, malformed snapshot, duplicate identity, flow failure, timeout, denial, or unavailable capability changes only that provider's state. Every accepted result must match the current session and generation, use its provider namespace, declare the registered kind, carry relevance in `0..1000`, resolve to a typed action or generated-registry command, and remain eligible under the current profile policy.

Profile classification precedes labels, icons, shortcuts, contacts, or other protected metadata. Locked, hidden, quiet, unavailable, and policy-ambiguous Private Space candidates cannot enter candidate preparation, result maps, history, fixtures, or diagnostics. Managed-work results receive mandatory host badging. Profile callbacks purge visible results and ephemeral targets immediately; activation revalidates the session, generation, target, profile, permission, route, and capability before any side effect.

Ranking combines provider relevance with deterministic exact, prefix, token, and bounded fuzzy matching, then applies time-decayed launch history only to app and shortcut targets. Duplicate semantic targets merge deterministically and stable namespaced identity breaks ties. Room retains at most 256 app/shortcut history rows and clamps backward clocks; raw queries and contact, file, web, and Private Space data have no durable representation.

Production providers are build-time contributions for apps, shortcuts, commands, contacts, user-authorized files, public Android Settings, a manually enabled versioned GrapheneOS Settings catalog, safe external HTTPS web actions, and recovery information. Framework objects remain in host/platform adapters. Contacts use runtime permission, files use persisted Storage Access Framework grants, Settings activities must resolve exactly at execution, GrapheneOS private routes require a reviewed build catalog plus a currently callable activity, and web destinations use exact-host HTTPS construction with encoded bounded parameters. The search block consumes immutable host-prepared state and emits typed actions only.

The platform behavior behind these rules is recorded in [Phase 6 search platform behavior](../research/phase-6-search-platform-behavior.md).

## Accessibility, testing, and release

All visual paths support complete semantics, logical focus, large text, contrast, reduced motion, keyboard or D-pad input, predictive Back, and non-drag editing. Contribution suites and host integration tests use the same public interfaces as production.

Release gates cover:

- Pure and randomized state invariants, configuration migrations, and failure preservation.
- Each contribution's shared scenarios, accessibility declarations, lifecycle, performance hooks, and visual goldens.
- Host integration with fake platform adapters.
- Instrumentation for real Home-role, widget, shortcut, profile, notification, wallpaper, document, and process-restoration behavior.
- API 35 AOSP and the current GrapheneOS reference Pixel. Extra devices add evidence but do not replace either required target.
- Dependency boundaries, absence of Google dependencies, static analysis, licenses, signing identity, provenance, checksums, and downloaded-artifact verification.

Stable and preview use separate application IDs, stores, signing keys, and release channels. Protected GitHub Actions environments reconstruct the channel key only after maintainer approval, sign one universal APK, and publish provenance, certificate, and digest data. Pull-request jobs cannot access signing secrets. Each key also has two encrypted offline backups and a planned v3 signing lineage. See [ADR 0025](../adr/0025-sign-release-apks-in-github-actions.md) and the earlier [release-signing research](../research/launcher-release-signing.md).

## Remaining sequence

Phases 0 through 7 are complete. Details and latest verification are in [current state](../status/current-state.md).

### Phase 5: platform content

Add widget allocation through cleanup and restore, pinned and dynamic shortcuts, work-profile state and badging, Private Space, notification indicators, item actions, and folder overlays.

Exit when widgets and shortcuts survive restart, profile transitions leak no protected data, and contributions receive only prepared state.

Status: complete. The API 35 and GrapheneOS matrices pass, and the final physical managed-profile and intended-installer notification-recovery checks are recorded in [Phase 5 device evidence](../status/phase-5-device-evidence.md).

### Phase 6: search

Add the search engine, typed action executor, provider permissions, apps, shortcuts, commands, contacts, files, public and GrapheneOS settings, and web providers. Bound concurrency, cancellation, history, and ranking.

Exit when presentation can change without changing retrieval, locked results never enter a session, and one slow provider cannot delay others.

Status: complete. Local, visual, contract, dependency, privacy, API 35 AOSP, GrapheneOS, real managed-profile search, and exact restoration checks pass. See [Phase 6 search evidence](../status/phase-6-search-evidence.md).

### Phase 7: themes

Add Material palettes, theme profiles, manual tokens, bounded overrides, fonts, icon packs, backgrounds, wallpaper confirmation, validation, and visual tests.

Exit when all modules consume one resolved host theme and system wallpaper changes only after explicit target confirmation.

Status: complete. One host resolver now owns built-in and named profiles, Material Color Utilities palettes, bounded overrides, imported fonts, Nova/ADW icon mappings, launcher and destination backgrounds, image-derived palettes, and typed wallpaper execution. The existing Room schema 7 and Proto selection shape required no migration; built-in destination customization atomically materializes a derived profile before selection, and malformed records fail as complete profile fallbacks. The root Material surface, host UI, composition, previews, layouts, and blocks consume the same immutable resolved values. Local, visual, license, workflow, privacy, module-boundary, Robolectric, and pinned API 35 AOSP gates pass; see [Phase 7 theme evidence](../status/phase-7-theme-evidence.md).

### Phase 8: backup and migration

Publish the archive format. Add user-selected folders, manual and seven-snapshot automatic backups, optional passphrase encryption, staged validation, pre-restore backup, atomic replacement, reauthorization, widget rebinding, profile review, and support bundles.

Exit when cross-version round trips pass, corrupt archives cannot alter live state, and restore always retains the safe layout.

### Phase 9: release hardening

Finish device accessibility and performance thresholds, security and privacy audits, signing and provenance, Obtainium metadata, key-restore drills, and user documentation.

Exit when every contract has evidence and the downloaded draft APK matches its tag, application ID, certificate, and digest.

## Deferred beyond 1.0

- Runtime third-party APK plugins.
- Linked-clone UI.
- Third-party launcher backup adapters.
- Tablet and foldable release support.
- Every icon-pack dialect.
- Public binary compatibility for contribution contracts.

## Primary references

- [Android `LauncherApps`](https://developer.android.com/reference/android/content/pm/LauncherApps)
- [Android app-widget host guide](https://developer.android.com/develop/ui/views/appwidgets/host)
- [Android Private Space](https://source.android.com/docs/security/features/private-space)
- [Compose nested scrolling](https://developer.android.com/develop/ui/compose/touch-input/scroll/nested-scroll-modifiers)
- [Android package visibility](https://developer.android.com/training/package-visibility)
- [Android backup](https://developer.android.com/identity/data/autobackup)
