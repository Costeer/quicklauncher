# Quicklauncher

Quicklauncher is an Android home app whose top-level destinations and their visual composition are user-configurable.

## Language

**Destination**:
A user-created top-level place in the launcher's navigation. It has a stable identity independent of the layout currently displaying it.
_Avoid_: Page, screen, tab

**Start destination**:
The destination shown when Android opens or returns to the launcher. "Home" is this designation, not a distinct destination type.
_Avoid_: Home page, home screen type

**Destination template**:
A ready-made destination configuration, such as a traditional home grid or full-screen search. A template creates an ordinary destination and has no special status afterward.
_Avoid_: Destination type, page type

**Layout**:
A module-provided visual arrangement for an entire destination. A layout defines typed slots and keeps its own configuration when the user selects another layout.
_Avoid_: Theme, page

**Slot**:
A typed insertion point exposed by a layout or block. Its type determines which blocks may occupy it.
_Avoid_: Container, placeholder

**Block**:
A reusable module-provided part placed into a compatible slot. Widgets, app collections, and search results are possible blocks.
_Avoid_: Component, tile, widget

**Capability**:
A declared launcher behavior that a layout or block supports or requires. The editor uses capabilities to prevent incompatible compositions.
_Avoid_: Feature flag

**Content item**:
A host-owned piece of user content, such as a favorite, folder, or shortcut. Its identity and meaning survive changes of layout.
_Avoid_: Icon, tile, placement

**Placement**:
A layout-owned record of where and how a content item or block instance appears. A placement has no meaning outside the layout that created it.
_Avoid_: Content item

**Destination map**:
The connected, user-edited arrangement of destinations on an unbounded two-dimensional grid. One destination occupies each cell, and cardinal gestures move between adjacent cells.
_Avoid_: Page order, tab list, navigation stack

**Map editor**:
The zoomed-out editor where users create, move, and remove destinations without breaking reachability from the start destination.
_Avoid_: Page settings, destination editor

**Destination editor**:
The editor where users place blocks and configure the selected layout or block. It does not move destinations within the destination map.
_Avoid_: Map editor, global settings

**Gesture handoff**:
The transfer of a drag from scrollable module content to spatial navigation after that content reaches its boundary. Users may instead require spatial navigation gestures to begin in a narrow activation band inside Android's system gesture region.
_Avoid_: Gesture override

**Module**:
A build-time registered implementation that contributes a layout, block, or supporting behavior. A module declares its capabilities and settings rather than accessing launcher storage or Android launcher APIs directly.
_Avoid_: Plugin, extension APK

**Module settings**:
Configuration owned and versioned by a module. The placement editor exposes settings on the module instance through a settings control.
_Avoid_: Global settings

**Module instance**:
A configured use of a module within a destination, such as one app-list block or the selected grid layout. Instances have their own settings and may share host-owned content items.
_Avoid_: Module, content item

**Widget placement**:
A placement with its own Android widget ID and provider state. The host manages its binding, while a compatible layout controls its position and size.
_Avoid_: Shared widget, content item

**Drop commit**:
The atomic persistence of a valid placement when the user releases a dragged module instance. Movement before release is an in-memory preview, and an invalid drop restores the prior placement.
_Avoid_: Draft session, autosave

**Private Space overlay**:
The host-owned secure container for viewing, unlocking, locking, and launching private-profile apps. It sits outside the destination map and never exposes locked private apps to modules.
_Avoid_: Private destination, private page

**App collection**:
A host-built, policy-filtered set of launchable items with profile-aware identities and required badging. Modules choose how to present a collection but cannot bypass its profile rules.
_Avoid_: Raw package list, app drawer data

**Search provider**:
A module that returns typed results to a host-owned search session. Users enable each provider separately, and permission denial switches only that provider back off.
_Avoid_: Search layout, search page

**Search action**:
A typed, host-validated operation attached to a search result. Modules request an action by identity rather than launching an Android intent directly.
_Avoid_: Raw intent, result callback

**Placement flow**:
The host-owned interaction for dropping a new widget, shortcut, or block into a compatible slot. It commits only after the user makes a valid drop.
_Avoid_: Auto-add, placement inbox

**Single-block layout**:
The core layout that promotes one compatible block to fill an entire destination. It lets the same block implementation work as full-page content or inside a multi-slot layout.
_Avoid_: Full-page block, recursive layout

**Safe layout**:
The non-removable core recovery layout used when a selected layout cannot load. It exposes an alphabetical app list, search, the map overview, and settings without depending on optional modules.
_Avoid_: Default layout, blank fallback

**Copy**:
A new module instance with independent settings and placement identity. It retains references to shared content items, while copied widgets receive new Android bindings.
_Avoid_: Clone, duplicate reference

**Clone**:
A module instance that shares its configuration graph with another instance. Placement identity, map coordinates, transient state, and widget IDs remain independent, while structural and setting edits propagate across the clone group.
_Avoid_: Copy

**Configuration document**:
The versioned module settings graph referenced by a module instance. Copy creates a new document, while the later clone feature will let several instances reference one document.
_Avoid_: Module instance, settings blob

**File search scope**:
The folders and shared-storage areas a user has explicitly allowed search providers to inspect. Folder-picker grants are the default, with all-shared-storage access as an optional advanced mode.
_Avoid_: Device files, unrestricted storage

**Web search adapter**:
A shipped or user-defined description of an HTTPS suggestion endpoint, its response format, and result URLs. It contains data and parsing rules rather than executable code.
_Avoid_: Search script, browser provider

**Launcher theme**:
The named launcher-wide visual profile supplied to every module, including fonts, colors, corner radii, spacing, shapes, and icon rendering. Users may set values manually or generate Material You and Material Expressive colors; a theme may embed a background, and modules may expose bounded overrides.
_Avoid_: Module theme, skin

**Font family**:
A user-imported set of static regular, bold, italic, and bold-italic font files, or one variable font with supported curated axes. The launcher keeps a validated private copy rather than depending on the source document.
_Avoid_: Font file, system font

**Icon-pack profile**:
Quicklauncher's documented subset of the Nova and ADW `appfilter.xml` convention. Unsupported or missing mappings fall back to a per-app override or the app's adaptive icon.
_Avoid_: Universal icon-pack support

**Folder overlay**:
The host-owned themed view for opening and editing a folder. Modules render the folder's closed representation and emit typed actions against its shared content identity.
_Avoid_: Folder destination, module folder window

**Destination background**:
A host-managed image, color, gradient, or scrim that overrides the launcher-wide background for one destination. Modules receive resolved contrast values rather than loading the background asset.
_Avoid_: Module wallpaper, theme

**Backup archive**:
A versioned user-exported snapshot of launcher configuration and imported theme assets, optionally protected by a passphrase. Import previews and then atomically replaces the current map after backing it up; permissions and widget bindings require reauthorization.
_Avoid_: Database dump, device backup

**Backup library**:
The host-owned Settings view that scans a user-selected folder and shows preview cards for compatible archives. Manual and automatic backups have separate pages; nightly backups retain seven snapshots while manual backups remain until the user deletes them.
_Avoid_: Backup destination, file picker

**Support bundle**:
A user-exported, redacted package of bounded local diagnostics. Quicklauncher sends no diagnostics, analytics, search queries, app inventory, or profile state automatically.
_Avoid_: Telemetry, crash report upload

**Quarantined instance**:
A module instance disabled after repeated restore-time failure. The host preserves its configuration and opens the destination with the safe layout so the user can repair or remove it.
_Avoid_: Deleted module, disabled destination

**Search history**:
A bounded on-device record of launched apps and shortcuts used for ranking, with decay and user controls to disable or clear it. Raw contact, file, and web queries are never retained.
_Avoid_: Query log, analytics

**GrapheneOS settings catalog**:
A manually enabled, versioned set of GrapheneOS terms and best-effort routes. The host shows a private route only after confirming it is callable and otherwise opens a public parent screen.
_Avoid_: Settings index, privileged integration

**App override**:
A host-owned customization of a profile-specific app identity, including its label, icon, favorite status, folder membership, and visibility. Every app-list and search presentation consumes the same override.
_Avoid_: Layout app setting

**Hidden app**:
An app excluded from ordinary app collections. When hiding it, the user separately chooses whether search may reveal it; launcher settings always retain a recovery entry.
_Avoid_: Disabled app, locked app

**Item action overlay**:
The host-owned menu for validated app and shortcut actions such as app info, uninstall or disable, favorite, hide, rename, icon override, and profile controls.
_Avoid_: Module context menu

**Notification indicator**:
Optional per-app state derived by the host from notification-listener access. Modules decide whether and how to render dots or approximate counts, but receive no notification content or notification objects.
_Avoid_: Notification, badge data

**Command binding**:
A user mapping from a non-navigation gesture to a typed launcher command. One-finger cardinal drags remain reserved for movement through the destination map.
_Avoid_: Module gesture, intent shortcut

**Launcher command**:
A registered typed action that the host validates and executes. Commands may appear in search, gesture settings, recovery UI, or module interactions without exposing raw Android intents.
_Avoid_: Intent action, module callback

**Migration**:
The upgrade and restoration of Quicklauncher's own versioned state through schema migrations, Android backup, and backup archives. The first release does not import another launcher's private workspace or backup format.
_Avoid_: Launcher import, workspace scraping

**Theme wallpaper application**:
The explicit application of a theme's embedded background through Android's wallpaper service. The user previews the crop, chooses the home, lock, or combined target each time, and accepts that the prior static or live wallpaper may not be recoverable.
_Avoid_: Theme preview, launcher background

**Launcher settings**:
Host-owned transient configuration and recovery UI accessible independently of the destination map. It remains reachable through the map overview, safe layout, item actions, and command bindings.
_Avoid_: Settings destination, module settings
