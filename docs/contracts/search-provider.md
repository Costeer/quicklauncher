# Search-provider contribution contract

Type `org.quicklauncher.contribution/search-provider`, code-contract major `1`. The [shared rules](shared-contract-rules.md) apply.

## Interface

`SearchProviderContribution<C>.open` creates one session for one visible host search session. `updateQuery` replaces prior work; `results` emits fresh immutable snapshots; `close` is idempotent and prevents later output.

`SearchProviderDescriptor` declares a nonempty set of result kinds and a nonnegative minimum query length. Supported kinds are app, shortcut, contact, file, setting, web, command, and information. Queries are at most 512 characters and contain no null character.

Each result has a stable ID, display text, a declared kind, relevance from 0 through 1,000, and a typed action. Actions either name a `SearchActionId` or a registered launcher command. They contain no intent or callback.

## Ownership and validation

The host owns enablement, permissions, query debouncing, fan-out, timeouts, profile filtering, ranking, history, presentation, and action execution. Providers retain no raw query history and cannot delay healthy providers.

Validation rejects undeclared kinds, duplicate result IDs, invalid relevance, stale or post-close output, and command actions that do not resolve. Configuration failure disables only that provider. Flow failure becomes provider-local error state; cancellation and query replacement are not failures.

## Evidence and evolution

The category declaration requires `FIRST_RESULT`, `QUERY_REPLACEMENT`, and `DISPOSAL`. Its suite covers every shared scenario, bounded immutable results, typed actions, stale-query cancellation, and absence of post-close emissions.

Breaking query, result, action, stream, or disposal semantics require a later provider major. Enablement stays attached to the stable contribution ID; configuration evolves separately.
