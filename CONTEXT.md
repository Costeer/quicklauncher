# Quicklauncher

Quicklauncher is an Android home app with user-configured destinations, layouts, and reusable blocks.

## Navigation and editing

**Destination**:
A user-created place in the launcher's spatial map. Its identity does not depend on its coordinate or layout.
_Avoid_: Page, screen, tab

**Start destination**:
The destination shown after a Home action. "Home" is a role, not a destination type.

**Destination map**:
The connected arrangement of destinations on a two-dimensional grid.

**Map editor**:
The editor for destination creation, position, and removal.

**Destination editor**:
The editor for a destination's layout, blocks, placements, and settings.

**Gesture handoff**:
Transfer of a drag from scrollable content at its boundary to destination navigation.

**Drop commit**:
The atomic save performed when a valid drag ends. Motion before release is only a preview.

## Composition

**Module**:
A build-time registered layout, block, search provider, command, or destination template.
_Avoid_: Plugin, extension APK

**Module instance**:
One configured use of a module in launcher state.

**Configuration document**:
The versioned settings referenced by a module instance. Copies get new documents; future clones may share one.

**Layout**:
A module that arranges one destination and exposes typed slots.
_Avoid_: Theme, page

**Slot**:
A typed insertion point exposed by a layout or block.

**Block**:
A reusable module placed in a compatible slot.
_Avoid_: Component, tile, widget

**Placement**:
A parent-owned record describing where a block instance appears. It is separate from the content shown there.

**Capability**:
A declared behavior used to validate whether modules can compose.

**Single-block layout**:
A layout that lets one compatible block fill a destination.

**Safe layout**:
The host-owned recovery layout that remains available when contributed rendering fails.

**Destination template**:
A recipe that creates ordinary destinations and module instances. Installed state has no continuing template status.

**Copy**:
A new module instance with independent configuration and placement identity.

**Clone**:
Future linked instances that share configuration while keeping independent placement and transient state.

## Content and policy

**Content item**:
Host-owned user content whose identity survives layout changes, such as a favorite, folder, or shortcut.

**App collection**:
A host-prepared, profile-aware set of launchable items. Modules only choose its presentation.

**App override**:
A host-owned customization of one profile-specific app identity.

**Widget placement**:
A placement with an independent Android widget binding.

**Folder overlay**:
The host-owned view for opening and editing a folder.

**Private Space overlay**:
The host-owned secure view for private-profile apps. Locked private data never enters ordinary collections.

**Item action overlay**:
The host-owned menu for validated app and shortcut actions.

**Notification indicator**:
Host-derived per-app state that excludes notification content.

## Search and commands

**Search provider**:
A module that returns typed results to a host-owned search session.

**Search action**:
A host-validated operation attached to a search result.

**Launcher command**:
A registered typed action that the host validates and executes.

**Command binding**:
A user mapping from a non-navigation gesture to a launcher command.

## Appearance and recovery

**Launcher theme**:
The launcher-wide visual profile supplied to every module.

**Destination background**:
A host-managed background override for one destination.

**Backup archive**:
A portable, versioned export of launcher configuration and imported assets.

**Quarantined instance**:
A preserved module instance disabled after configuration or restore-time failure.

**Support bundle**:
A user-exported, redacted set of bounded local diagnostics.

**Launcher settings**:
Host-owned configuration and recovery UI that remains reachable without a working destination layout.
