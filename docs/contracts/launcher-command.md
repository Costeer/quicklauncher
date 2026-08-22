# Launcher-command contribution contract

Persisted contribution type: `org.quicklauncher.contribution/launcher-command`

Supported code-contract major: `1`

## Purpose and the host-contribution seam

The [shared seam rules](shared-contract-rules.md#purpose-and-the-host-contribution-seam) apply. A launcher-command contribution computes a typed command result for host-owned surfaces such as search, gesture settings, item actions, recovery, or settings. It does not launch an intent, mutate launcher state, or hold a host implementation.

`LauncherCommandContribution<C>.execute` is a suspending interface from immutable `CommandInput<C>` to `CommandResult`. The host validates availability and performs any returned `LauncherCommandAction`.

## Stable identity and descriptor metadata

The [shared identity rules](shared-contract-rules.md#stable-identity-and-descriptor-metadata) apply. A `LauncherCommandDescriptor` has `ContributionMetadata` whose `typeId` is exactly `ContributionTypes.LAUNCHER_COMMAND`, a nonempty immutable set of `CommandContext`, and a nonempty immutable set of `CommandResultKind`.

Command contexts are `SEARCH`, `GESTURE`, `ITEM_ACTIONS`, `RECOVERY`, and `SETTINGS`. Result kinds are `HOST_ACTION` and `INFORMATION`. Each invocation has a distinct namespaced `CommandInvocationId`. Stable parameter keys use `StableKey`.

## Contract-major compatibility

The [shared compatibility rules](shared-contract-rules.md#contract-major-compatibility) apply. Launcher-command major 1 consists of `LauncherCommandContribution`, `CommandInput`, `CommandParameters`, `SettingValue`, `CommandResult`, `LauncherCommandAction`, `LauncherCommandDescriptor`, and cancellation behavior. The registry rejects any command major other than 1.

An incompatible parameter, result, action, context, or cancellation change requires a later launcher-command contract major. Existing command bindings keep their persisted contribution identity.

## Configuration documents and schema versions

The [shared configuration rules](shared-contract-rules.md#configuration-documents-and-schema-versions) apply. Command configuration contains contribution-owned defaults or behavior settings. A user gesture binding, current invocation ID, current parameter values, permission state, and destination state remain host-owned.

Every command registers a codec, including a command with no persistent settings. `CommandParameters` are invocation input and are not silently merged into the saved configuration document. Configuration migrations complete before execution.

## Lifecycle and cancellation

The [shared lifecycle rules](shared-contract-rules.md#lifecycle-and-cancellation) apply. A command owns work only for one call to `execute`. It receives a cancellation signal in `CommandInput` and must check it before and during work. Suspending dependencies must use structured cancellation.

Cancellation returns `CommandResult.Cancelled` only when the contribution observes a domain-level cancellation that has not already cancelled its coroutine. A thrown `CancellationException` propagates to the caller. The command must not catch it and return `RecoverableFailure`.

## Immutable inputs and typed outputs or actions

The [shared immutable-data rules](shared-contract-rules.md#immutable-inputs-and-typed-outputs-or-actions) apply. `CommandInput` contains invocation ID, decoded configuration, immutable `CommandParameters`, and cancellation signal. Parameter values are typed boolean, choice, integer, dimension, color, font role, or immutable app-selection values.

`CommandResult` is one of:

- `Succeeded`, with an optional typed host action;
- `Cancelled`;
- `MissingAccess`, naming a capability;
- `Unavailable`, with validated display text;
- `RecoverableFailure`, with a stable code and validated message.

Allowed host actions are opening launcher settings, opening search, navigating to a typed destination ID, and opening item actions for a typed content item ID. A null action on success represents an informational command whose work requires no host effect.

## Host responsibilities

The [shared host rules](shared-contract-rules.md#host-responsibilities) apply. The host resolves command bindings, confirms that the invocation context appears in the descriptor, validates parameters, checks current capabilities and policy, constructs the invocation ID, owns cancellation, and invokes the command.

After success, the host validates any action again and performs it. It owns UI, navigation, settings, item overlays, permissions, platform calls, result presentation, and audit-safe diagnostics.

## Contribution responsibilities

The [shared contribution rules](shared-contract-rules.md#contribution-responsibilities) apply. A command validates contribution-specific parameter combinations, honors cancellation, returns one typed result, and requests only an allowed host action. It handles access revocation between the host's precheck and execution with `MissingAccess` or `Unavailable`.

A command must not execute an Android intent, write persistence, retain invocation parameters, or claim success for an effect it cannot request through the contract.

## Invariants and invalid states

The [shared validation rules](shared-contract-rules.md#invariants-and-invalid-states) apply. Launcher-command validation additionally rejects:

- metadata whose type is not `ContributionTypes.LAUNCHER_COMMAND`;
- an empty context set;
- an empty result-kind set;
- a call from a context absent from the descriptor;
- a parameter key or value type that violates the command's declared settings or invocation rules;
- a `MissingAccess` capability unrelated to the contribution's declared capabilities;
- a host action whose referenced destination or content item is unavailable;
- a success form inconsistent with the descriptor's declared result kinds.

The host treats an invalid runtime result as a recoverable command failure and performs no action.

## Errors and recovery

The [shared recovery rules](shared-contract-rules.md#errors-and-recovery) apply. Invalid command metadata fails compilation. Configuration failure preserves the original document and makes that command unavailable without affecting other commands or bindings.

`MissingAccess`, `Unavailable`, and `RecoverableFailure` are expected typed outcomes. The host presents them without performing an action and may offer a permission or settings recovery route. A thrown unexpected exception is isolated to that invocation. Cancellation does not produce failure diagnostics.

## Accessibility

The [shared accessibility rules](shared-contract-rules.md#accessibility) apply. Commands do not own a render tree. Their validated display name, description, parameter labels, and result text must let host-owned search, gesture, item, recovery, and settings surfaces expose a meaningful name, state, and action.

Every command must be invokable without a drag when the host places it in an accessible surface. Large-text fixtures cover long names, parameter labels, and result messages. The host owns focus order, keyboard shortcuts, confirmation UI, and predictive Back.

## Performance

The [shared performance rules](shared-contract-rules.md#performance) apply. A launcher-command declaration includes `COMMAND_EXECUTION`. A command that owns cancellable work also includes a hook description that measures cancellation within the same metric.

Execution must not block a UI thread. Work is bounded by the immutable input. The hook records input, start and completion points, typed result, and cancellation behavior.

## Required preview fixtures

The [shared fixture rules](shared-contract-rules.md#required-preview-fixtures) apply. A command supplies deterministic invocation and expected-result fixtures for `EMPTY`, `NORMAL`, `LOADING`, `PERMISSION_DENIED`, `PROFILE_LOCKED`, `LARGE_TEXT`, and `ERROR`.

Empty uses an empty valid parameter map. Loading represents an in-flight suspended execution. Permission denial yields `MissingAccess`; locked profile yields `Unavailable` or `MissingAccess` without private text; error yields `RecoverableFailure`. Large text exercises host presentation using valid long `DisplayText`.

## Black-box contract suite

The [shared suite rules](shared-contract-rules.md#black-box-contract-suite) apply. The command suite invokes only the public suspending interface. It verifies parameter snapshots, every result variant, action data, host-side rejection, access-revocation behavior, coroutine cancellation, and absence of arbitrary executable intents.

It also covers descriptor and exact-major validation, configuration defaults and round trips, sequential migrations and original preservation, all fixtures, and the `COMMAND_EXECUTION` hook.

## Registration

The [shared registration rules](shared-contract-rules.md#registration) apply. A source-retained launcher-command registration names a target implementing `LauncherCommandContribution<C>`, supplies the metadata from which KSP constructs a `LauncherCommandDescriptor`, and references `ConfigurationCodec<C>` plus `LauncherCommandContractTestDeclaration<C>` for that same concrete `C`. Migrations remain part of the referenced codec's supported configuration path.

The codec's `ConfigurationCodecSpec` ID must equal the registration's `configTypeId`. The declaration's `ContractTestSpec` must name the command contribution, select the launcher-command category, list exactly all scenarios, and include `COMMAND_EXECUTION`. KSP rejects a generic, manifest, category, ID, or required-hook mismatch and missing contexts or result kinds. It emits `RegisteredLauncherCommand<C>` only after validation.

## Contract and category evolution

The [shared evolution rules](shared-contract-rules.md#contract-and-category-evolution) apply. Later command majors keep the persisted launcher-command type ID and stable contribution/config identities. Existing gesture or search bindings continue to refer to the contribution ID.

Adding an optional parameter with a safe default may be compatible. Removing or changing parameter or action meaning requires a major change. Configuration-only changes use schema migrations. A later category cannot renumber the command category.
