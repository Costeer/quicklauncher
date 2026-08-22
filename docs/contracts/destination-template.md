# Destination-template contribution contract

Persisted contribution type: `org.quicklauncher.contribution/destination-template`

Supported code-contract major: `1`

## Purpose and the host-contribution seam

The [shared seam rules](shared-contract-rules.md#purpose-and-the-host-contribution-seam) apply. A destination-template contribution creates a draft for exactly one ordinary destination. After the host accepts the draft, the destination has no template-specific status or ongoing dependency on the template.

`DestinationTemplateContribution<C>.create` is a synchronous interface from immutable `TemplateInput<C>` to `TemplateResult`. It allocates nothing, writes nothing, and performs no platform work. A first-run setup with several destinations composes several one-destination template operations in host-owned onboarding.

## Stable identity and descriptor metadata

The [shared identity rules](shared-contract-rules.md#stable-identity-and-descriptor-metadata) apply. A `DestinationTemplateDescriptor` has `ContributionMetadata` whose `typeId` is exactly `ContributionTypes.DESTINATION_TEMPLATE`, an immutable set of required contribution IDs, and a nonnegative `maximumBlocks`.

`TemplateInput` supplies the host-selected `DestinationId`, validated name, immutable pool of available `ModuleInstanceId` values, decoded template configuration, and cancellation signal. A `DestinationDraft` contains that destination ID and name, one layout `ModuleDraft`, and an immutable list of block drafts. Each module draft contains an instance ID, contribution ID, and configuration document.

## Contract-major compatibility

The [shared compatibility rules](shared-contract-rules.md#contract-major-compatibility) apply. Destination-template major 1 consists of `DestinationTemplateContribution`, `TemplateInput`, `TemplateResult`, `DestinationDraft`, `ModuleDraft`, `DestinationTemplateDescriptor`, and their purity and validation rules. The registry rejects any template major other than 1.

Changing from one-destination output, changing identity allocation ownership, or changing draft meaning incompatibly requires a later template contract major. It does not change destinations already created.

## Configuration documents and schema versions

The [shared configuration rules](shared-contract-rules.md#configuration-documents-and-schema-versions) apply. Template configuration describes options used to construct a draft. It does not become a hidden template marker on the created destination.

Every template registers its own codec, including a template without options. Each `ModuleDraft.configuration` uses the target module's config type and schema. The host validates and later persists those documents. Updating template configuration or defaults does not rewrite destinations already created.

## Lifecycle and cancellation

The [shared lifecycle rules](shared-contract-rules.md#lifecycle-and-cancellation) apply. Major 1 template creation is synchronous and owns no session. It checks `TemplateInput.cancellation` before work and during any bounded iteration.

Creation must not retain its input or start background work. If cancellation is observed, it propagates `CancellationException`; it must not return a partial draft. An asynchronous template lifecycle would require a later code-contract major.

## Immutable inputs and typed outputs or actions

The [shared immutable-data rules](shared-contract-rules.md#immutable-inputs-and-typed-outputs-or-actions) apply. `TemplateInput.availableInstanceIds` is an immutable snapshot. The template chooses distinct IDs from that pool for the layout and block drafts. It does not synthesize raw identity strings.

`TemplateResult` is either `Created(DestinationDraft)` or `Invalid(code, message)`. `Invalid` uses a stable local code and validated `DisplayText`. A template emits no callback or executable action. Its draft is declarative data for host validation.

## Host responsibilities

The [shared host rules](shared-contract-rules.md#host-responsibilities) apply. Before creation, the host checks that every descriptor-required contribution is registered and supplies sufficient instance IDs. After creation, it validates destination identity, name, module categories, instance uniqueness, configuration type and schema, block count, capabilities, and layout/block compatibility.

The host owns map coordinates, start-destination designation, connectivity, persistence, atomic commit, first-run composition of several templates, and cleanup. An invalid or cancelled template operation leaves live state unchanged.

## Contribution responsibilities

The [shared contribution rules](shared-contract-rules.md#contribution-responsibilities) apply. A template deterministically creates one draft from its input, uses only declared required contributions, stays within `maximumBlocks`, selects only supplied instance IDs, and provides configuration documents accepted by the referenced contributions' codecs.

It must not inspect the registry through reflection, construct contribution targets, reserve map coordinates, select the start destination, persist data, or retain a relationship to the created destination.

## Invariants and invalid states

The [shared validation rules](shared-contract-rules.md#invariants-and-invalid-states) apply. Destination-template validation additionally rejects:

- metadata whose type is not `ContributionTypes.DESTINATION_TEMPLATE`;
- a negative `maximumBlocks`;
- a missing descriptor-required contribution;
- a draft whose ID or name differs from the input;
- a layout whose contribution is not a registered layout;
- a block whose contribution is not a registered block;
- a module contribution absent from `requiredContributions`;
- a reused or unsupplied module instance ID;
- more block drafts than `maximumBlocks`;
- a configuration document whose type has no matching target codec or cannot load;
- an incompatible layout/block composition.

The draft represents one destination. It contains no coordinate, start flag, or template type marker.

## Errors and recovery

The [shared recovery rules](shared-contract-rules.md#errors-and-recovery) apply. Invalid template metadata fails compilation. Template configuration failure preserves the original document and prevents creation.

The contribution reports expected input problems with `TemplateResult.Invalid`. The host reports post-creation validation errors without committing any part of the draft. Unexpected exceptions are isolated to the operation. No rollback is needed because creation is pure and the later persistence commit is host-owned and atomic.

## Accessibility

The [shared accessibility rules](shared-contract-rules.md#accessibility) apply. Templates do not own final destination rendering. Their display name, description, option labels, invalid messages, destination name, and preview fixture data must support accessible host-owned selection and preview UI.

Large text must preserve readable template names, option controls, and error messages. The host owns focus order, keyboard and D-pad selection, confirmation, preview navigation, and predictive Back. Template creation itself must never require a drag.

## Performance

The [shared performance rules](shared-contract-rules.md#performance) apply. A destination-template declaration includes `DRAFT_CREATION`.

Creation is pure, synchronous, and bounded by the supplied instance-ID pool and `maximumBlocks`. It performs no I/O, registry scan, or platform call. The performance hook states the input size, start and completion points, and expected `TemplateResult`.

## Required preview fixtures

The [shared fixture rules](shared-contract-rules.md#required-preview-fixtures) apply. A template supplies deterministic input and expected-result fixtures for `EMPTY`, `NORMAL`, `LOADING`, `PERMISSION_DENIED`, `PROFILE_LOCKED`, `LARGE_TEXT`, and `ERROR`.

Empty creates a valid destination with no blocks when the descriptor permits it. Normal creates one layout and representative blocks. Loading is a host preview state and does not make `create` asynchronous. Permission-denied and profile-locked previews contain no protected content. Error returns `Invalid` or supplies a draft that the host rejects for one stated reason. Large text exercises host preview metadata.

## Black-box contract suite

The [shared suite rules](shared-contract-rules.md#black-box-contract-suite) apply. The template suite calls only `create`, compares repeated calls for deterministic output, verifies input snapshots, checks instance-ID use and uniqueness, validates the layout and block references, and confirms that failure or cancellation produces no partial state.

It also covers descriptor and exact-major validation, configuration defaults and round trips, sequential migrations and original preservation, all fixtures, one-destination output, maximum block count, and the `DRAFT_CREATION` hook.

## Registration

The [shared registration rules](shared-contract-rules.md#registration) apply. A source-retained destination-template registration names a target implementing `DestinationTemplateContribution<C>`, supplies the metadata from which KSP constructs a `DestinationTemplateDescriptor`, and references `ConfigurationCodec<C>` plus `DestinationTemplateContractTestDeclaration<C>` for that same concrete `C`. Migrations remain part of the referenced codec's supported configuration path.

The codec's `ConfigurationCodecSpec` ID must equal the registration's `configTypeId`. The declaration's `ContractTestSpec` must name the template contribution, select the destination-template category, list exactly all scenarios, and include `DRAFT_CREATION`. KSP rejects a generic, manifest, category, ID, or required-hook mismatch and a negative maximum block count. It emits `RegisteredDestinationTemplate<C>` only after validation.

## Contract and category evolution

The [shared evolution rules](shared-contract-rules.md#contract-and-category-evolution) apply. Later destination-template majors keep the persisted template type ID and stable contribution/config identities. A destination created under an older version remains an ordinary destination and does not migrate with the template.

New template options use configuration schema evolution. Changing draft behavior must preserve old saved template configuration or migrate it sequentially. A later contribution category cannot renumber the template category.
