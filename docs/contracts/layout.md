# Layout contribution contract

Persisted contribution type: `org.quicklauncher.contribution/layout`

Supported code-contract major: `1`

## Purpose and the host-contribution seam

The [shared seam rules](shared-contract-rules.md#purpose-and-the-host-contribution-seam) apply. A layout contribution arranges one destination and describes which typed slots it renders. It does not own the destination's identity, navigation, stored placement tree, block implementations, Android state, or failure recovery.

`LayoutContribution<C>.open` accepts a decoded `ContributionContext<C>` and returns a `LayoutSession`. `LayoutSession.Render(LayoutRenderInput)` is an `@Composable` entry point that returns `Unit`. The host supplies typed slot rendering and action adapters in the input. This small interface lets the host change composition, persistence, child-block adapters, or platform implementations without changing layouts.

## Stable identity and descriptor metadata

The [shared identity rules](shared-contract-rules.md#stable-identity-and-descriptor-metadata) apply. A `LayoutDescriptor` has `ContributionMetadata` whose `typeId` is exactly `ContributionTypes.LAYOUT`, plus an immutable ordered list of `SlotDescriptor` values.

A slot descriptor contains a local stable `id`, namespaced `SlotTypeId`, immutable accepted-block-ID and required-capability sets, positive `maximumChildren`, and immutable allowed-scroll-axis set. Slot IDs identify positions within this layout descriptor. Slot types express compatibility across contributions and must remain stable if saved placement data refers to them. `acceptedBlocks` is the explicit static edge from this slot to each block type it may contain; an empty set accepts no registered block.

The layout's `ContributionId`, `ConfigTypeId`, capability IDs, slot types, and module instance IDs follow the shared stable grammar. Display metadata uses `DisplayText`.

## Contract-major compatibility

The [shared compatibility rules](shared-contract-rules.md#contract-major-compatibility) apply. Layout major 1 consists of `LayoutContribution`, `LayoutSession`, the composable `Render` entry point, `LayoutDescriptor`, `LayoutRenderInput`, `LayoutRenderState`, `SlotRenderer`, `LayoutAction`, and their lifecycle and validation rules. The registry rejects any layout major other than 1.

Changing slot rendering, render input ownership, composition meaning, actions, or session disposal incompatibly requires a later layout contract major. It does not require a configuration schema change.

## Configuration documents and schema versions

The [shared configuration rules](shared-contract-rules.md#configuration-documents-and-schema-versions) apply. Each layout registration includes a codec whose `configType` matches `metadata.configType`. Layout configuration may describe visual arrangement options and presentation settings. Host-owned placement records, destination identity, selected-layout state, shared content identities, profile state, and Android bindings are not layout configuration.

A layout with no settings still registers a typed empty codec. Switching to another layout leaves this layout's configuration document and placement tree dormant. Loading it again runs its own sequential configuration migrations before decode.

## Lifecycle and cancellation

The [shared lifecycle rules](shared-contract-rules.md#lifecycle-and-cancellation) apply. The host opens one session for an active layout instance and closes it when that instance leaves its current, neighbor-preview, or editor-preview composition, is replaced, or is disposed. A dormant layout has no live session, jobs, animations, or retained render state.

`LayoutSession.close` is idempotent. After close, `isClosed` is true, rendering stops, and the session emits no actions. Layout-owned jobs are children of `ContributionContext.instanceScope` and must terminate when the session closes or the instance is cancelled. Composition-local resources are disposed when `Render` leaves composition. `CompositionState.role` distinguishes current, neighbor-preview, and editor-preview composition; only a current composition may be interactive. A rejected action is not a reason to reopen work or retry.

## Immutable inputs and typed outputs or actions

The [shared immutable-data rules](shared-contract-rules.md#immutable-inputs-and-typed-outputs-or-actions) apply. `LayoutRenderInput` contains the `ModuleInstanceId`, an immutable `LayoutRenderState`, a typed `SlotRenderer`, and `ActionSink<LayoutAction>`.

`LayoutRenderState` contains resolved `LauncherTheme`, `WindowInfo`, `BackgroundContrast`, overall `RenderStatus`, `EditorMode`, `CompositionState`, `PlacementState`, and immutable `SlotRenderState` snapshots. `PlacementState` is a render snapshot, not a persistence entity. A slot state contains its local ID, type, current status, and immutable `PlacedChild` snapshots with explicit placement indexes. It contains no Android object, mutable host model, or block implementation.

`Render` emits no metadata output. It composes layout-owned UI, publishes accessibility through Compose semantics, and calls `SlotRenderer.Render` for each supplied slot it displays. Layout actions are limited to:

- `LayoutAction.OpenSettings(instanceId)`;
- `LayoutAction.SelectSlot(slotId)`.

The host may accept or reject either action. A layout cannot navigate directly, persist a placement, or invoke a block.

## Host responsibilities

The [shared host rules](shared-contract-rules.md#host-responsibilities) apply. The host selects the active layout, resolves stored placements, checks slot and capability compatibility, creates `SlotRenderState`, and supplies resolved theme, window, contrast, composition, placement, and interaction state. It dispatches accepted actions and owns drop commits. It decides which current, cardinal-neighbor, and editor-preview layouts have live sessions.

The host-owned `SlotRenderer` validates slot identity against the descriptor and input state, resolves placed block sessions, and composes those blocks. The host owns layout switching, dormant state, safe-layout substitution, quarantine, and editor overlays.

## Contribution responsibilities

The [shared contribution rules](shared-contract-rules.md#contribution-responsibilities) apply. A layout renders only from supplied state, delegates displayed slots to the supplied `SlotRenderer`, publishes complete Compose semantics, and emits only `LayoutAction` values. It must render the same semantic state for equivalent input and must not instantiate or call block targets.

The layout must support browsing, editing, and preview modes. It must handle every `RenderStatus`, both `WindowOrientation` values, reduced motion, and large text without obtaining more host state.

## Invariants and invalid states

The [shared validation rules](shared-contract-rules.md#invariants-and-invalid-states) apply. Layout validation additionally rejects:

- metadata whose type is not `ContributionTypes.LAYOUT`;
- duplicate slot IDs;
- an invalid or unknown accepted block ID;
- a slot whose `maximumChildren` is zero or negative;
- an undeclared capability used by a guarded setting;
- an accepted block whose compatible slot types, provided capabilities, or occupied scroll axes do not satisfy the slot;
- a slot render request duplicated or absent from the supplied slot state;
- an action that names another layout instance or an undeclared slot.

An empty slot list is legal for a skeletal or host-assisted layout. The safe layout's product guarantees remain host-owned and are not inferred from an arbitrary layout descriptor.

## Errors and recovery

The [shared recovery rules](shared-contract-rules.md#errors-and-recovery) apply. Invalid layout metadata fails compilation. Configuration failure preserves the original document and quarantines only that layout instance.

If rendering fails, the host records bounded diagnostics, closes the session, and exposes the core safe layout. In-process containment is best effort. Restore-time crash detection supplies the next-launch guarantee. The failed layout's configuration and dormant placements remain available for repair, export, reset, replacement, or deletion.

## Accessibility

The [shared accessibility rules](shared-contract-rules.md#accessibility) apply. The layout's composable publishes layout-owned semantics and logical traversal information without claiming semantics owned by a child block. The contract-test declaration supplies the expected logical focus order for each fixture. The host joins layout, block, and overlay semantics.

Layout settings, slot selection, and placement editing need keyboard or D-pad access and a non-drag action. Large text must not clip recovery or editing controls. Reduced motion must replace destination or editor motion owned by the layout.

## Performance

The [shared performance rules](shared-contract-rules.md#performance) apply. A layout contract declaration includes hooks for `FIRST_RENDER`, `ACTION_DISPATCH`, and `DISPOSAL`. Each description states the fixture, start point, end point, and observed composition or dispatch result.

Rendering performs no I/O or platform lookup. Work is bounded by the slot and state snapshots supplied by the host. Disposal stops layout-owned jobs and releases the last render input.

## Required preview fixtures

The [shared fixture rules](shared-contract-rules.md#required-preview-fixtures) apply. A layout provides public `LayoutRenderInput` fixtures for `EMPTY`, `NORMAL`, `LOADING`, `PERMISSION_DENIED`, `PROFILE_LOCKED`, `LARGE_TEXT`, and `ERROR`.

The empty fixture has empty or unoccupied slot state. Loading, denial, locked-profile, and error fixtures use the matching `RenderStatus`. The large-text fixture uses a text scale of at least 2 without also enabling reduced motion. Across the set, fixtures cover portrait and landscape, light and dark themes, browsing plus editing or preview composition, reduced motion, placement modes, and a child-slot failure. Every fixture has a unique stable screenshot ID and a checked-in golden produced through the real `Render` entry point. Locked-profile input contains no private data.

## Black-box contract suite

The [shared suite rules](shared-contract-rules.md#black-box-contract-suite) apply. The layout suites open the public contribution, render every fixture through Compose, validate semantics and declared focus order, compare golden screenshots, record typed slot-renderer calls, capture typed actions and host rejection without retry, and close the session twice to prove idempotent disposal.

They also test the complete configuration failure matrix, codec round trips, every sequential migration, byte-exact original-data preservation, exact major 1 validation, immutable state and placement snapshots, cancellation, actual termination of owned jobs, background contrast, large text, both orientations and themes, independent reduced motion, non-drag editing actions, and all three required performance hooks.

## Registration

The [shared registration rules](shared-contract-rules.md#registration) apply. A source-retained layout registration names a target implementing `LayoutContribution<C>`, supplies the metadata from which KSP constructs a `LayoutDescriptor`, and references a `ConfigurationCodec<C>` plus `LayoutContractTestDeclaration<C>` for that same concrete `C`. Migrations remain part of the referenced codec's supported configuration path.

The codec's `ConfigurationCodecSpec` ID must equal the registration's `configTypeId`. The declaration's `ContractTestSpec` must name the layout contribution, select the layout category, list exactly all scenarios, and include `FIRST_RENDER`, `ACTION_DISPATCH`, and `DISPOSAL`. KSP rejects any generic, manifest, category, ID, slot-compatibility, or required-hook mismatch and emits `RegisteredLayout<C>` only after validation. Later build-time aggregation includes that source module's static fragment explicitly.

## Contract and category evolution

The [shared evolution rules](shared-contract-rules.md#contract-and-category-evolution) apply. A later layout contract major keeps the persisted layout type ID. Existing contribution, config, instance, capability, and slot type IDs do not change merely because Kotlin interfaces change.

Configuration schema migrations preserve existing layout identity and dormant state. Adding a different contribution category does not change the layout type ID or its ordering key.
