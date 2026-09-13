# Shared contribution contract rules

These rules apply to all five first-release contribution categories. Category files add only their deltas. Kotlin types in `:contracts:*` and validation in `:registry:ksp` are authoritative when prose and code differ.

## Ownership boundary

The host owns persistence, Android integration, permissions, profile and privacy policy, action validation, lifecycle, failure isolation, and recovery. Contributions receive decoded configuration and immutable prepared state, then render, return typed data, or emit typed actions.

Contributions never receive Room types, Android launcher objects, mutable host state, executable intents, or another contribution implementation. They do not use Google Play Services or Firebase.

## Identity and registration

Persisted IDs use `<lowercase reverse-DNS namespace>/<lowercase local name>`. Local descriptor keys use the same local-name grammar. Raw strings exist only at serialization boundaries.

Every registration binds one concrete configuration type `C` across its target, descriptor, codec, and category contract declaration. Metadata includes stable contribution, category, capability, and configuration IDs; contract major; display text; capability sets; and optional settings. KSP rejects malformed or duplicate IDs, type or generic mismatches, unsupported majors, invalid descriptors, capability cycles, missing codecs or suites, and invalid composition graphs.

The five persisted category IDs are listed in the [contract index](README.md). New categories add IDs without renumbering existing ones.

## Versions and configuration

Code-contract majors and configuration schemas evolve independently. Compatibility is exact by category and major; first release accepts only major 1. Breaking interface semantics require a new major. Compatible descriptor additions use defaults.

Every contribution has a `ConfigurationCodec`, including stateless contributions. Loading rejects a wrong config type or future schema, applies exactly one pure migration per version, then decodes. Cancellation propagates. Any other failure returns the original document byte-for-byte; partial migration output is never committed.

Declarative settings use validated typed fields. Configuration contains contribution-owned settings, not host identity, policy, placement, permission, or platform state.

## Lifecycle and data

The host owns contribution work and cancellation. Layout, block, and search sessions close idempotently, stop owned jobs, release inputs, and produce nothing after close. All instance jobs are children of the supplied scope. Commands and templates also propagate cancellation and create no process-wide work.

Inputs and emitted collections are immutable snapshots. Outputs and actions use category types. Contributions never return `Any`, Android objects, callbacks for later execution, or arbitrary intents. The host validates actions again against current state and may reject them without side effects.

## Failure and recovery

Build-time registration errors fail compilation without a partial registry. Runtime and configuration failures stay local to the affected contribution. Failed configuration and missing code preserve raw data. A failed layout selects the host-owned safe layout; other failures keep unrelated contributions running. Cancellation is control flow, not a diagnostic failure.

## UI, accessibility, and performance

Visual contributions publish Compose semantics from the rendered tree. Host and contribution semantics together must support logical focus, large text, reduced motion, keyboard or D-pad input, sufficient contrast, and a non-drag path for every operation. Nonvisual contributions provide display metadata the host can present accessibly.

Rendering and synchronous template creation perform no disk, network, database, package-manager, or other blocking platform work. Work and result sets remain bounded and cancellable. Category files name their required performance hooks.

## Contract evidence

Every contribution declares all scenarios: `EMPTY`, `NORMAL`, `LOADING`, `PERMISSION_DENIED`, `PROFILE_LOCKED`, `LARGE_TEXT`, and `ERROR`. Visual fixtures use the public Compose entry point and checked-in goldens. Locked fixtures contain no private data.

Reusable black-box suites exercise the public interface, exact-major and descriptor validation, configuration defaults and migrations, original-data preservation, immutable inputs and outputs, action rejection, cancellation, disposal, accessibility declarations, all scenarios, and category performance hooks. Registration succeeds only when the matching declaration is present.

## Evolution rule

Stable contribution, category, configuration, capability, slot, and instance identities do not change because Kotlin interfaces or configuration schemas change. Preserve unknown saved data until compatible code returns or the user deletes it.
