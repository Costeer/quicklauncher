# Shared contribution contract rules

This document is the authoritative definition of rules shared by the five first-release contribution types. The dedicated specifications link to the relevant section and define only their type-specific additions.

The Kotlin types in `:contracts:domain`, `:contracts:contribution`, and `:contracts:ui` are the executable form of this specification. Accepted architecture decisions remain authoritative if later prose conflicts with them.

## Purpose and the host-contribution seam

The launcher host owns policy. A contribution is an adapter at a narrow seam: it receives validated configuration and prepared immutable state, then composes host-owned UI, publishes an immutable result snapshot, returns a typed result, or emits a typed action according to its category. The host owns persistence, Android integration, profile and privacy policy, permissions, action validation, lifecycle, failure isolation, and recovery.

A contribution must not expose or receive Room entities or DAOs, Android `UserHandle`, launcher framework objects, mutable host state, arbitrary executable intents, or host implementation objects. It must not depend on another contribution implementation. Google Play Services and Firebase are outside the contract.

The interface is also the test surface. Contract suites exercise these public types rather than contribution internals or host storage.

## Stable identity and descriptor metadata

Persisted identities use validated value types. `ContributionId`, `ContributionTypeId`, `CapabilityId`, `SlotTypeId`, `ConfigTypeId`, `ModuleInstanceId`, `DestinationId`, `ContentItemId`, `SearchResultId`, `SearchActionId`, and `CommandInvocationId` all use the same namespaced grammar:

```text
<lowercase reverse-DNS namespace>/<lowercase local name>
```

The namespace has at least two dot-separated segments. Each segment starts with a lowercase letter and then contains lowercase letters or digits. The local name starts with a lowercase letter. Its remaining groups contain lowercase letters or digits and may be separated by `.`, `_`, or `-`. Examples include `org.quicklauncher.samples/grid` and `org.quicklauncher.instance/grid-01`. Parsing rejects incomplete namespaces, uppercase characters, whitespace, empty groups, and unsupported punctuation.

`StableKey` uses the local-name portion of that grammar for descriptor-local keys such as slot IDs, setting keys, diagnostic codes, and accessibility IDs. Raw identity strings may appear only while parsing or serializing. Human-facing labels and diagnostic messages are text, not identities.

`ContributionMetadata` contains:

- a globally unique `ContributionId`;
- one stable `ContributionTypeId`;
- a positive `ContractMajor`;
- nonblank `DisplayText` for the name and description, each limited to 2,000 characters and no null character;
- immutable sets of provided and required `CapabilityId` values;
- an optional declarative `SettingsSchema`;
- a `ConfigTypeId` whose codec is present in the same registration.

The first-release persisted type IDs are:

| Type | `ContributionTypeId` |
| --- | --- |
| Layout | `org.quicklauncher.contribution/layout` |
| Block | `org.quicklauncher.contribution/block` |
| Search provider | `org.quicklauncher.contribution/search-provider` |
| Launcher command | `org.quicklauncher.contribution/launcher-command` |
| Destination template | `org.quicklauncher.contribution/destination-template` |

Registry entries also carry a type-specific descriptor and a target, configuration codec, and category-specific contract-test declaration bound to one configuration type `C`. Collections exposed by contract DTOs are snapshots. Contributions must likewise emit immutable snapshots through flows and results.

## Contract-major compatibility

`ContractMajor` starts at 1. Compatibility is exact, per persisted contribution type. The first release supports only major 1 for each of the five type IDs. Major 0, any future major, and any major for an unknown type fail registry validation.

The code-contract major describes Kotlin interface compatibility. It does not describe saved configuration. A contribution cannot claim a range or infer support from a configuration schema version.

Backward-compatible descriptor fields may be added with defaults within a major. A breaking input, output, action, descriptor, lifecycle, or error change requires a new code-contract major and coordinated updates to all in-repository callers and implementations.

## Configuration documents and schema versions

Every registration has a `ConfigurationCodec`, including a contribution with no user-visible settings. A stateless contribution uses a typed empty configuration, a canonical encoded default chosen by its codec, and schema version 1. `settings = null` means the host has no declarative settings controls; it does not remove the codec requirement.

`ConfigurationDocument` contains a validated `ConfigTypeId`, a positive `SchemaVersion`, and `EncodedConfiguration`. `EncodedConfiguration.of` stores the supplied text without normalization. The codec owns its config type, current schema version, typed default, encoding, and decoding.

`ConfigurationPipeline.load` follows this order:

1. Reject a document whose config type differs from the codec.
2. Reject a schema newer than the codec's current schema.
3. While the document is older, find exactly one migration for its config type and current version.
4. Require each migration to advance exactly one version.
5. Run migrations on encoded text, in order, before typed decode.
6. Decode only the final migrated text.

Each migration is pure: the same encoded input produces the same result and it does not mutate external state. Migration and codec implementations must propagate `CancellationException`. Other thrown exceptions become typed load failures.

Every failure returns `ConfigurationLoadResult.Failed` with the original `ConfigurationDocument`. The original encoded text remains byte-for-byte unchanged even if an earlier migration produced intermediate text. The host may store or show a successful migrated document only after the full sequence and typed decode succeed.

Contract-major and schema-version changes are independent. A code-contract major may change while the configuration schema stays fixed. A schema may advance several times within one code-contract major.

Declarative settings support booleans, choices, bounded integers, bounded dimensions, ARGB colors, font roles, and app selectors. Setting and option keys are stable local keys. Validation rejects duplicate field keys, blank labels, empty or duplicate choices, undeclared defaults, reversed or invalid ranges, invalid ARGB defaults, empty font-role sets, and font defaults outside the allowed set. A capability-gated field may reference only a capability in the contribution's provided or required sets.

## Lifecycle and cancellation

The host creates and disposes contribution work. `ContributionContext<C>` supplies the instance ID, decoded typed configuration, a `CancellationSignal`, and a host-owned `instanceScope`. Contributions must check the signal before and during bounded work. `ensureActive()` throws `CancellationException` after cancellation. Any coroutine started for an instance is a child of `instanceScope`; a contribution cannot replace it with an unowned or process-wide scope.

Layout, block, and search contributions open a `ContributionSession`. Closing a session is idempotent, changes `isClosed` to true, stops all jobs owned by that session, releases retained inputs, and prevents new output or actions. Removing a composable from composition does not transfer lifecycle ownership to Compose: the host still closes its session, and a contribution must also dispose composition-local resources when its `Render` call leaves composition. Cancelling the instance scope is the host's final containment mechanism. The host may reject actions emitted during or after disposal. A contribution must treat rejection as terminal for that dispatch and must not retry it indefinitely.

Command execution and template creation also receive cancellation state. They must stop without converting cancellation into an ordinary recoverable error. A contribution must not create an unowned process-wide scope.

## Immutable inputs and typed outputs or actions

Inputs and state are value objects or immutable snapshots. A contribution must not cast contract collections to mutable collections, mutate caller-owned data, or retain a mutable alias. Configuration is decoded to the generic non-null type `C` before invocation.

Layout and block sessions expose one `@Composable Render(input)` entry point and return `Unit`. The input supplies all state and host adapters needed for that composition. A layout or block must not return a second metadata render tree or invoke a child contribution directly. It calls the typed `SlotRenderer` for supplied slot snapshots; a block also calls `PreparedContentRenderer` for host-prepared items and surfaces. Compose semantics are the accessibility source for the rendered tree.

Visual contributions use `ActionSink<A>` for typed actions. `ActionDispatchResult.Accepted` confirms that the host accepted an action. `Rejected` contains a stable reason and nonblank message. Rejection may mean that the instance was disposed or current policy no longer permits the action. A rejected action produces no optimistic host-state mutation and no automatic retry.

Search results, command results, and template results use their dedicated sealed types. No contribution may return `Any`, a platform intent, a function that executes later inside the host, or an Android object disguised as opaque data.

## Host responsibilities

The host must:

- parse serialized identities into validated types before exposing them;
- validate every descriptor and the complete registry before use;
- load configuration through `ConfigurationPipeline` and retain original failed data;
- supply decoded configuration and prepared, policy-filtered immutable state;
- own contribution sessions, cancellation, timeouts, and disposal;
- validate typed actions again against current permissions, profile state, installed packages, and instance state;
- isolate a contribution failure and keep recovery routes available;
- render standard settings from `SettingsSchema` and commit encoded configuration only after validation;
- keep Android framework and persistence implementations behind host-owned modules.

## Contribution responsibilities

Each contribution must:

- declare valid type-specific descriptor metadata in its registration;
- provide a matching codec, default, and every sequential migration needed by supported saved data;
- implement the interface declared by its registration type;
- use immutable input and output data and typed actions;
- honor cancellation and session disposal;
- declare all seven preview scenarios and required performance hooks through its category-specific `ContributionContractDeclaration<C>`;
- run the reusable black-box suite for its type;
- provide complete accessibility semantics and focus order where it renders UI;
- avoid platform access, host state mutation, persistence, and cross-contribution implementation dependencies.

## Invariants and invalid states

Registry validation rejects:

- duplicate contribution IDs;
- duplicate persisted configuration type IDs;
- duplicate persisted contribution type IDs in the type catalog;
- an unknown type or unsupported contract major;
- a descriptor whose Kotlin descriptor class and `metadata.typeId` disagree;
- a codec whose `configType` differs from descriptor metadata, or a missing codec;
- a target, codec, or category-specific contract-test declaration that binds a different configuration type `C`;
- a codec without `ConfigurationCodecSpec`, or whose manifest config ID differs from descriptor metadata;
- a contract-test declaration without `ContractTestSpec`, with the wrong category or contribution ID, with an incomplete scenario set, or without every category-required performance hook;
- an invalid settings schema or capability guard;
- a provided capability that also appears in the same descriptor's required set;
- a capability dependency cycle;
- a slot that accepts an unknown or incompatible block, including incompatible slot type, capabilities, or scroll axes;
- a directed cycle in the registered block-type nesting graph;
- a missing or mismatched contract-test declaration;
- a registration target that does not implement its declared contribution interface;
- any type-specific invalid state named by the dedicated specification.

The capability graph has one node per `CapabilityId`. For each contribution, validation adds an edge from every provided capability to every required capability. A directed cycle is invalid. Sorting nodes and edges by their stable string values makes diagnostics deterministic.

Validation failures are actionable. `ValidationError` contains a stable code, a field path, and a message that names the offending contribution. KSP diagnostics must preserve those details and point to the registration target when source information is available.

## Errors and recovery

Build-time metadata and registration errors fail compilation. The generator must not produce a partial usable registry after an error.

Configuration failures preserve the original document. At runtime the host quarantines only the affected module instance and offers repair, reset, replacement, export, or deletion. Missing contribution code also preserves its raw configuration.

A failed block or provider must not take down unrelated contributions. A failed layout causes host-owned safe-layout recovery. In-process renderer isolation is best effort; the host also uses restore-time crash markers to recover on a later process start. Cancellation is expected control flow and must not be reported as contribution failure.

## Accessibility

Visual contributions publish Compose semantics from the same composable tree shown to the user. Their contract-test declaration also supplies one `AccessibilityDeclaration` per required scenario as an independently validated black-box expectation. It contains semantics entries, a logical focus order, and declarations for large-text, keyboard or D-pad navigation, and reduced motion. Each semantics entry has a stable ID, nonblank label, role, optional state description, and accessibility-action declarations with stable IDs and nonblank labels.

The validator rejects blank semantics or action labels, duplicate semantics or action IDs, duplicate focus entries, focus IDs without matching semantics, and missing support declarations. The black-box suite validates these declarations, while the visual snapshot suite independently inspects the rendered scenarios with accessibility checks. Together they enforce text contrast of at least 4.5:1 and non-text contrast of at least 3:1, exercise large text, verify logical traversal across host and contribution content, and require a non-drag path for every edit or navigation operation. The host owns overlay focus, predictive Back, and composition between separate contributions.

Nonvisual contributions supply accessible `DisplayText` metadata and fixtures so host-owned presentation can expose a name, description, state, and action.

## Performance

Contributions must not perform disk, network, database, package-manager, or other blocking platform work during rendering or synchronous template creation. Work must be bounded, cancellable where applicable, and released at disposal. Providers must bound result production; renderers must avoid work proportional to data the host did not include in their input.

`ContributionContractDeclaration.performanceHooks` declares measurable hooks with nonblank descriptions. Available metrics are `FIRST_RENDER`, `ACTION_DISPATCH`, `DISPOSAL`, `FIRST_RESULT`, `QUERY_REPLACEMENT`, `COMMAND_EXECUTION`, and `DRAFT_CREATION`. Each dedicated specification states its required metrics.

Phase 1 proves that hooks exist and can run deterministically. Device-specific numeric thresholds follow recorded baseline measurements in release hardening. The absence of a numeric threshold does not permit main-thread I/O, leaked jobs, unbounded collections, or ignored cancellation.

## Required preview fixtures

Every `ContributionContractDeclaration` includes exactly the complete first-release scenario set:

- `EMPTY`
- `NORMAL`
- `LOADING`
- `PERMISSION_DENIED`
- `PROFILE_LOCKED`
- `LARGE_TEXT`
- `ERROR`

Fixtures cross the public interface. A visual fixture uses `PreviewFixture<T>` with immutable input. `LARGE_TEXT` uses a `LauncherTheme.textScale` greater than 1 rather than a separate render status. The other render scenarios map to `RenderStatus` where applicable. A nonvisual contribution uses deterministic public input and expected output for the same named scenarios.

Every visual fixture has a stable, unique screenshot ID and is rendered through the real composable contract into a checked-in golden screenshot. Across the seven-scenario set, visual fixtures cover portrait and landscape windows, light and dark theme modes, browsing plus editing or preview composition, and reduced motion. Reduced motion is exercised independently of the large-text fixture. Slots and prepared host content use recording host renderers so the suite proves that composition crosses those typed seams. Locked-profile fixtures contain no private labels, icons, package identities, or actions.

## Black-box contract suite

Every registered contribution declares and runs a reusable suite that exercises public contracts. At minimum it covers:

- descriptor and exact-major validation;
- default document loading and codec round trips;
- sequential migration before decode;
- wrong-config-type and future-schema rejection;
- missing, ambiguous, nonsequential, failed, and throwing migration paths;
- failed and throwing decode paths, cancellation propagation, and byte-exact original-data preservation for every typed failure;
- immutable inputs, outputs, and collections;
- typed action or result behavior, including rejected actions without mutation or retry;
- all required preview scenarios;
- disposal and cancellation;
- accessibility declaration and focus order;
- visual semantics, non-drag actions, unique golden screenshots, slot rendering, and prepared-content rendering where applicable;
- query replacement, stale-result cancellation, and post-close silence for search providers;
- actual termination of session-owned jobs after disposal;
- every required performance hook.

The registration's category-specific `ContributionContractDeclaration<C>.contributionId` must equal the descriptor ID. Its scenario set must be complete, and its performance hooks must include the type-specific metrics. The declaration does not replace executable tests; CI must run the suite.

## Registration

Registration annotations have source retention. The category-specific registration annotation is the build-time authority for descriptor metadata and supplies references to the target, codec, and contract-test declaration. The three referenced objects preserve one concrete configuration type: for category interface `CategoryContribution<C>`, the codec implements `ConfigurationCodec<C>` and the declaration implements the matching category-specific `CategoryContractTestDeclaration<C>`. Star projections, unresolved generics, and pairings that disagree on `C` are invalid.

The codec object carries `ConfigurationCodecSpec(configTypeId)`. That manifest ID, the registration annotation's descriptor `configTypeId`, and the codec's generated-registry identity must agree. The contract-test object carries `ContractTestSpec(contributionId, category, scenarios, performanceHooks)`. Its contribution ID and category must agree with the registration, its scenarios must be exactly the seven required scenarios, and its hooks must include the category-required metrics. The object must also implement the matching category-specific declaration; implementing only the shared declaration is insufficient.

A registration target, codec, and contract-test declaration are Kotlin objects so generated code can reference them without reflection. If a black-box declaration repeats descriptor metadata as its expected value, generated-registry tests must prove full structural equivalence with the generated descriptor; partial field checks do not make two authorities safe.

KSP adapts source symbols into a platform-neutral validation model. The validator runs before generation. A valid source module receives a static generated registry fragment whose entries have stable ordering by persisted contribution type ID and then contribution ID. Generated code directly constructs registered entries. It performs no runtime classpath scanning or reflection.

Source-retained annotations are not discoverable after another Gradle module has compiled. Later contribution modules therefore run KSP in their own source module and export a generated static fragment. The final application or an explicit build-time aggregation module lists those fragments and generates or constructs the combined registry. Adding a Gradle dependency alone does not cause implicit discovery.

## Contract and category evolution

Changing a code-contract major does not change `ContributionId`, `ContributionTypeId`, `ConfigTypeId`, or saved `ModuleInstanceId` values. Changing a configuration schema does not change the code-contract major. A contribution keeps its stable identities across compatible and breaking contract revisions unless it becomes a genuinely different contribution with different meaning.

A later contribution category receives a new explicit namespaced `ContributionTypeId`, contract interface, descriptor, source-retained registration annotation, registered-entry type, platform-neutral validator model, KSP adapter, generator adapter, and black-box suite. Existing type IDs never change or derive from declaration order. Generated ordering uses stable string values, so adding a category cannot renumber or rewrite existing persisted IDs.

There is no contribution-source annotation that defines a new category from an ID and contract class alone. Such a declaration cannot supply the category-specific descriptor, validation, generation, or test behavior and would create a shallow, misleading seam. Category addition is an explicit registry-module change whose stability test appends the new catalog entry and proves that every existing persisted type ID is unchanged.

Quicklauncher does not promise binary compatibility for third-party contribution binaries. All first-release contributions are built in this repository and move together when a contract major changes.
