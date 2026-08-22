# Block contribution contract

Persisted contribution type: `org.quicklauncher.contribution/block`

Supported code-contract major: `1`

## Purpose and the host-contribution seam

The [shared seam rules](shared-contract-rules.md#purpose-and-the-host-contribution-seam) apply. A block contribution renders a reusable part inside a compatible typed slot. It may declare child slots, but the host owns the placement tree, content preparation, compatibility checks, and lifecycle.

`BlockContribution<C>.open` accepts a decoded `ContributionContext<C>` and returns a `BlockSession`. `BlockSession.Render(BlockRenderInput)` is an `@Composable` entry point that returns `Unit`. The host supplies typed child-slot, prepared-content, and action adapters in the input. The same block may appear inside a multi-slot layout or be promoted by the core single-block layout without changing the block interface.

## Stable identity and descriptor metadata

The [shared identity rules](shared-contract-rules.md#stable-identity-and-descriptor-metadata) apply. A `BlockDescriptor` has `ContributionMetadata` whose `typeId` is exactly `ContributionTypes.BLOCK`, a nonempty immutable set of compatible `SlotTypeId` values, an immutable ordered list of child `SlotDescriptor` values, and an immutable set of occupied scroll axes.

Child slot descriptors use stable local IDs, namespaced slot types, explicit accepted block IDs, capability requirements, positive maximum child counts, and allowed scroll axes. The block's contribution ID and config type remain stable across placements. Each placement uses a distinct `ModuleInstanceId`.

## Contract-major compatibility

The [shared compatibility rules](shared-contract-rules.md#contract-major-compatibility) apply. Block major 1 consists of `BlockContribution`, `BlockSession`, the composable `Render` entry point, `BlockDescriptor`, `BlockRenderInput`, `BlockRenderState`, `SlotRenderer`, `PreparedContentRenderer`, `BlockAction`, and their lifecycle and validation rules. The registry rejects any block major other than 1.

An incompatible change to slot compatibility, child-slot meaning, render state, composition meaning, or actions requires a later block code-contract major. It does not renumber the persisted block category or force a configuration schema change.

## Configuration documents and schema versions

The [shared configuration rules](shared-contract-rules.md#configuration-documents-and-schema-versions) apply. Block configuration owns the block's presentation and behavior settings. It does not own host content items, folder membership, widget IDs or provider state, app/profile identities, notification data, or its parent placement.

Every block registers a codec, including a stateless block. Copies receive new configuration documents. Shared semantic content remains host-owned. Future clone behavior may share a configuration document without changing this contract.

## Lifecycle and cancellation

The [shared lifecycle rules](shared-contract-rules.md#lifecycle-and-cancellation) apply. The host opens a block session only while that placement is active in current, required neighbor-preview, or editor-preview composition. Removing the block, deactivating its layout tree, or disposing the composition closes the session.

`BlockSession.close` is idempotent. It stops block-owned work, releases retained prepared content, marks `isClosed`, and prevents actions after disposal. Block jobs are children of `ContributionContext.instanceScope` and must terminate on close or instance cancellation. Composition-local resources are disposed when `Render` leaves composition. Child slot sessions remain host-owned through `SlotRenderer`; a block never assumes their lifecycle. `CompositionState.role` distinguishes current, neighbor-preview, and editor-preview composition, and only current composition may be interactive.

## Immutable inputs and typed outputs or actions

The [shared immutable-data rules](shared-contract-rules.md#immutable-inputs-and-typed-outputs-or-actions) apply. `BlockRenderInput` contains the placement's `ModuleInstanceId`, immutable `BlockRenderState`, typed `SlotRenderer` and `PreparedContentRenderer` adapters, and `ActionSink<BlockAction>`.

`BlockRenderState` contains resolved theme, window, `BackgroundContrast`, overall status, editor mode, `CompositionState`, `PlacementState`, immutable child-slot state, and `PreparedHostContent`. Prepared content contains immutable typed item metadata and host-surface tokens only. The contribution passes those values back to `PreparedContentRenderer`; it never receives an Android launcher object, widget view, executable callback, or mutable host model.

`Render` emits no metadata output. It composes block-owned UI, publishes accessibility through Compose semantics, delegates child slots through `SlotRenderer`, and delegates prepared items and surfaces through `PreparedContentRenderer`. Block actions are limited to:

- `BlockAction.OpenSettings(instanceId)`;
- `BlockAction.ActivateItem(itemId)`;
- `BlockAction.OpenItemActions(itemId)`.

The host resolves and validates content identity before any effect.

## Host responsibilities

The [shared host rules](shared-contract-rules.md#host-responsibilities) apply. The host checks the parent slot type, required capabilities, multiplicity, scroll-axis policy, and placement-tree acyclicity before activating a block. It supplies policy-filtered app collections, folder state, host-surface renderers, resolved theme, window, contrast, composition, placement, and sanitized profile state through contract DTOs and renderer adapters.

The host owns content identities, item activation, item-action overlays, widget bindings, persistence, copy/clone rules, placement commits, and block failure placeholders.

## Contribution responsibilities

The [shared contribution rules](shared-contract-rules.md#contribution-responsibilities) apply. A block renders only prepared state, exposes child slots through the supplied renderer, delegates host-prepared content back to its supplied renderer, publishes complete Compose semantics, and emits typed block actions. It must not query apps, profiles, notifications, widgets, storage, or Android services.

A block treats locked or denied state as already filtered. It must not attempt to recover hidden data. It handles every render status, both orientations, editor modes, large text, and reduced motion.

## Invariants and invalid states

The [shared validation rules](shared-contract-rules.md#invariants-and-invalid-states) apply. Block validation additionally rejects:

- metadata whose type is not `ContributionTypes.BLOCK`;
- an empty compatible-slot-type set;
- duplicate child-slot IDs;
- an invalid or unknown accepted child block ID;
- a child slot whose `maximumChildren` is zero or negative;
- a capability guard not declared by the contribution;
- placement into a slot type absent from `compatibleSlotTypes`;
- a child placement that violates slot capability, multiplicity, or scroll rules;
- a registered accepted-block edge that violates slot type, required capabilities, or occupied scroll axes;
- a directed cycle in the registered block-type nesting graph;
- a cycle in the runtime placement tree;
- an action that names another block instance or a content item absent from supplied state.

KSP validates the static graph formed by each descriptor slot's accepted block IDs and rejects incompatible edges or block-type nesting cycles. The host separately validates the runtime instance placement tree because saved `ModuleInstanceId` relationships and multiplicity are not known to KSP.

## Errors and recovery

The [shared recovery rules](shared-contract-rules.md#errors-and-recovery) apply. Invalid block metadata fails compilation. A configuration failure preserves the original document and quarantines only the block instance.

A render failure closes that session and produces a host-owned explanatory placeholder in its parent slot. Sibling blocks and the destination layout continue. The user can repair, reset, replace, export, or delete the failed block. Cancellation and a host-rejected action are not renderer failures.

## Accessibility

The [shared accessibility rules](shared-contract-rules.md#accessibility) apply. The block's composable publishes block-owned semantics, actions, states, and logical traversal information. Its contract-test declaration supplies the expected focus order for each fixture. The host composes block semantics with its parent layout, child blocks, prepared host content, and overlays.

Item activation and item-action access require keyboard or D-pad equivalents. Editing and removal cannot require drag. Large text must retain essential item labels and actions. Locked and permission-denied states need readable explanations without revealing protected content.

## Performance

The [shared performance rules](shared-contract-rules.md#performance) apply. A block contract declaration includes hooks for `FIRST_RENDER`, `ACTION_DISPATCH`, and `DISPOSAL`.

Rendering and scrolling do not trigger platform or storage access. Work is bounded by host-prepared state. A block must not keep child content, jobs, or actions alive after disposal.

## Required preview fixtures

The [shared fixture rules](shared-contract-rules.md#required-preview-fixtures) apply. A block provides public `BlockRenderInput` fixtures for `EMPTY`, `NORMAL`, `LOADING`, `PERMISSION_DENIED`, `PROFILE_LOCKED`, `LARGE_TEXT`, and `ERROR`.

The set covers portrait and landscape, light and dark themes, browsing plus editing or preview composition, reduced motion independent of large text, placement modes, empty and occupied child slots, prepared items and host surfaces, host-rejected actions, and large text at a scale of at least 2. Every fixture has a unique stable screenshot ID and a checked-in golden produced through the real `Render` entry point. The locked-profile fixture contains only locked-state metadata, never private app labels, package identities, icons, or actions.

## Black-box contract suite

The [shared suite rules](shared-contract-rules.md#black-box-contract-suite) apply. The block suites open the public contribution, render every fixture through Compose, validate semantics and declared focus order, compare golden screenshots, record typed child-slot and prepared-content rendering, capture typed actions, prove rejected-action handling without retry, and close the session twice.

They also check descriptor slot compatibility and static nesting, the complete configuration failure matrix, codec defaults and round trips, sequential migrations, byte-exact original-data preservation, immutable state, placement, child, and prepared-content snapshots, exact major support, cancellation, actual termination of owned jobs, contrast, large text, orientation and theme coverage, non-drag editing actions, and all three required performance hooks.

## Registration

The [shared registration rules](shared-contract-rules.md#registration) apply. A source-retained block registration names a target implementing `BlockContribution<C>`, supplies the metadata from which KSP constructs a `BlockDescriptor`, and references `ConfigurationCodec<C>` plus `BlockContractTestDeclaration<C>` for that same concrete `C`. Migrations remain part of the referenced codec's supported configuration path.

The codec's `ConfigurationCodecSpec` ID must equal the registration's `configTypeId`. The declaration's `ContractTestSpec` must name the block contribution, select the block category, list exactly all scenarios, and include `FIRST_RENDER`, `ACTION_DISPATCH`, and `DISPOSAL`. KSP rejects any generic, manifest, category, ID, accepted-block, slot-compatibility, nesting-cycle, or required-hook mismatch and emits `RegisteredBlock<C>` only after validation.

## Contract and category evolution

The [shared evolution rules](shared-contract-rules.md#contract-and-category-evolution) apply. Later block majors keep the persisted block type ID and existing contribution, config, instance, capability, and slot type IDs.

Adding a new compatible slot type or optional descriptor metadata within a major must preserve old defaults. Breaking the meaning of a saved slot or configuration requires an explicit version path, not an ID reuse. A later contribution category cannot renumber the block category.
