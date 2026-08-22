# Search-provider contribution contract

Persisted contribution type: `org.quicklauncher.contribution/search-provider`

Supported code-contract major: `1`

## Purpose and the host-contribution seam

The [shared seam rules](shared-contract-rules.md#purpose-and-the-host-contribution-seam) apply. A search-provider contribution retrieves typed results for a host-owned search session. Retrieval varies independently from presentation, ranking, permission policy, privacy filtering, and action execution.

`SearchProviderContribution<C>.open` accepts decoded configuration in `ContributionContext<C>` and returns a `SearchProviderSession`. The provider accepts typed query updates and publishes immutable result snapshots. It never controls the search UI or launches a result action.

## Stable identity and descriptor metadata

The [shared identity rules](shared-contract-rules.md#stable-identity-and-descriptor-metadata) apply. A `SearchProviderDescriptor` has `ContributionMetadata` whose `typeId` is exactly `ContributionTypes.SEARCH_PROVIDER`, a nonempty immutable set of `SearchResultKind`, and a nonnegative `minimumQueryLength`.

Supported result kinds are `APP`, `SHORTCUT`, `CONTACT`, `FILE`, `SETTING`, `WEB`, `COMMAND`, and `INFORMATION`. Every `ProviderResult` has a namespaced `SearchResultId`, validated display title, optional validated subtitle, declared kind, typed action, and provider relevance from 0 through 1,000.

`SearchResultAction.Execute` contains a `SearchActionId`. `InvokeCommand` contains the stable `ContributionId` of a launcher command. Neither form contains an intent or executable callback.

## Contract-major compatibility

The [shared compatibility rules](shared-contract-rules.md#contract-major-compatibility) apply. Search-provider major 1 consists of `SearchProviderContribution`, `SearchProviderSession`, `SearchQuery`, `ProviderResult`, `SearchResultAction`, `SearchProviderDescriptor`, and their stream and cancellation rules. The registry rejects any search-provider major other than 1.

An incompatible change to query limits, result meaning, action forms, stream behavior, or session disposal requires a later search-provider contract major. Provider configuration can evolve independently.

## Configuration documents and schema versions

The [shared configuration rules](shared-contract-rules.md#configuration-documents-and-schema-versions) apply. Provider configuration may contain declarative provider options and scoped source choices. The host-owned enabled flag, permission grant state, selected document grants, search history policy, query history, profile visibility, and ranking data are not provider configuration.

Every provider has a codec, including a stateless provider. The host decodes and migrates configuration before opening a session. A failure disables only that provider session and preserves the original document.

## Lifecycle and cancellation

The [shared lifecycle rules](shared-contract-rules.md#lifecycle-and-cancellation) apply. One `SearchProviderSession` belongs to one visible host search session. `updateQuery` replaces prior query work. The provider cancels stale work and ensures that results published after replacement correspond to the latest query.

`close` is idempotent, marks `isClosed`, cancels all provider work, and prevents later flow emissions. The provider observes the context cancellation signal. It must propagate cancellation rather than converting it to an error result.

## Immutable inputs and typed outputs or actions

The [shared immutable-data rules](shared-contract-rules.md#immutable-inputs-and-typed-outputs-or-actions) apply. `SearchQuery.of` accepts up to 512 characters, including an empty query, and rejects a null character.

`SearchProviderSession.results` is a `Flow<List<ProviderResult>>`. Every emitted list is a fresh immutable snapshot. The host does not call `updateQuery` until the query meets `minimumQueryLength`. A provider result uses `DisplayText`, one declared `SearchResultKind`, a typed `SearchResultAction`, and bounded relevance. Provider relevance is one input to host ranking, not a final global rank.

The provider cannot execute its action, mutate host search state, or return raw Android results.

## Host responsibilities

The [shared host rules](shared-contract-rules.md#host-responsibilities) apply. The host owns provider enablement, contextual permission flows, query debouncing, session fan-out, timeouts, stale-work cancellation, profile and private-space filtering, result normalization, deterministic ranking, history, presentation, and action execution.

The host validates every emitted result against the descriptor and current policy. Permission denial or revocation switches off only that provider. A slow or failing provider cannot delay or remove results from another provider.

## Contribution responsibilities

The [shared contribution rules](shared-contract-rules.md#contribution-responsibilities) apply. A provider returns only declared result kinds and typed actions, keeps result production bounded, handles query replacement, and stops on close. It must not persist raw contact, file, or web queries or retain host inputs after disposal.

A provider treats host filtering as authoritative. It does not infer profile access, reveal locked private data, request Android permissions, rank another provider's results, or perform an action.

## Invariants and invalid states

The [shared validation rules](shared-contract-rules.md#invariants-and-invalid-states) apply. Search-provider validation additionally rejects:

- metadata whose type is not `ContributionTypes.SEARCH_PROVIDER`;
- an empty result-kind set;
- a negative minimum query length;
- a query longer than 512 characters or containing a null character;
- provider relevance outside 0 through 1,000;
- an emitted kind absent from the descriptor;
- duplicate result IDs within one snapshot;
- output after query replacement that belongs to a stale query;
- output after session close;
- an `InvokeCommand` action that does not resolve to a registered launcher command.

The host may drop an invalid runtime result and isolate the provider. It never executes such a result.

## Errors and recovery

The [shared recovery rules](shared-contract-rules.md#errors-and-recovery) apply. Invalid provider metadata fails compilation. Configuration failure preserves the original document and prevents only that provider from opening.

A provider flow failure becomes a provider-local error state. The host cancels that session, retains results from healthy providers, and exposes retry or settings where appropriate. Permission denial and profile lock are ordinary isolated states. Cancellation and query replacement are not errors.

## Accessibility

The [shared accessibility rules](shared-contract-rules.md#accessibility) apply. Search providers do not own the result UI, but their `DisplayText`, `SearchResultKind`, and typed action must give the host enough data for a readable name, optional detail, role, state, and action.

Provider fixtures verify that titles remain meaningful at large text and that permission-denied, locked-profile, loading, empty, and error states can be announced without protected content. The host owns focus order, keyboard traversal, predictive Back, and result activation semantics.

## Performance

The [shared performance rules](shared-contract-rules.md#performance) apply. A provider contract declaration includes `FIRST_RESULT`, `QUERY_REPLACEMENT`, and `DISPOSAL` hooks.

The provider bounds result count and source work, cancels stale queries, and does not block other providers. Hook descriptions define the query, fixture source, first emission, replacement acknowledgement, and proof that close stopped work. Network providers remain subject to host timeouts and bounded response rules.

## Required preview fixtures

The [shared fixture rules](shared-contract-rules.md#required-preview-fixtures) apply. A provider supplies deterministic query and result-stream fixtures for `EMPTY`, `NORMAL`, `LOADING`, `PERMISSION_DENIED`, `PROFILE_LOCKED`, `LARGE_TEXT`, and `ERROR`.

The loading fixture begins without results and later emits a bounded snapshot. Permission-denied and profile-locked fixtures emit no protected results. Large text supplies long but valid `DisplayText` values for host preview. Error uses a controlled provider-local stream failure. Fixtures include a query replacement and close event.

## Black-box contract suite

The [shared suite rules](shared-contract-rules.md#black-box-contract-suite) apply. The provider suite opens the public session, verifies minimum-query handling, observes immutable snapshots, checks declared result kinds and relevance, replaces a query, closes twice, and proves no stale or post-close emissions.

It also covers descriptor and exact-major validation, default configuration, codec round trips, migrations and original preservation, all seven fixtures, typed action data, cancellation, and all three provider performance hooks.

## Registration

The [shared registration rules](shared-contract-rules.md#registration) apply. A source-retained search-provider registration names a target implementing `SearchProviderContribution<C>`, supplies the metadata from which KSP constructs a `SearchProviderDescriptor`, and references `ConfigurationCodec<C>` plus `SearchProviderContractTestDeclaration<C>` for that same concrete `C`. Migrations remain part of the referenced codec's supported configuration path.

The codec's `ConfigurationCodecSpec` ID must equal the registration's `configTypeId`. The declaration's `ContractTestSpec` must name the provider contribution, select the search-provider category, list exactly all scenarios, and include `FIRST_RESULT`, `QUERY_REPLACEMENT`, and `DISPOSAL`. KSP rejects a generic, manifest, category, ID, or required-hook mismatch, an empty result-kind set, or a negative minimum query length. It emits `RegisteredSearchProvider<C>` only after validation.

## Contract and category evolution

The [shared evolution rules](shared-contract-rules.md#contract-and-category-evolution) apply. A later provider contract major keeps the persisted search-provider type ID and stable provider/config identities. Provider enablement remains attached to the stable contribution ID.

Adding a result kind incompatibly requires a contract-major decision. Adding a configuration option normally advances only the schema or uses a compatible default. A later contribution category cannot renumber the provider category.
