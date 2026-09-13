# Launcher-command contribution contract

Type `org.quicklauncher.contribution/launcher-command`, code-contract major `1`. The [shared rules](shared-contract-rules.md) apply.

## Interface

`LauncherCommandContribution<C>.execute` maps immutable `CommandInput<C>` to one typed `CommandResult`. A descriptor declares nonempty context and result-kind sets. Contexts are search, gesture, item actions, recovery, and settings; result kinds are host action and information.

Results are `Succeeded`, `Cancelled`, `MissingAccess`, `Unavailable`, or `RecoverableFailure`. Success may request opening launcher settings or search, navigating to a destination, or opening actions for a content item. The host performs the action after validating current state.

Saved command configuration contains contribution-owned defaults and behavior. Invocation ID, parameters, gesture binding, permissions, and destination state remain host input. A thrown `CancellationException` propagates; domain-level cancellation may return `Cancelled`.

## Ownership and validation

The host resolves bindings, checks context and policy, validates parameters and results, owns cancellation and presentation, and executes accepted actions. Commands perform no persistence or Android intent and retain no invocation data.

Validation rejects empty or mismatched descriptor sets, invalid parameters, unrelated missing-access capabilities, unavailable action targets, and results inconsistent with the declared kinds. Invalid output performs no action and becomes a recoverable invocation failure.

## Evidence and evolution

The category declaration requires `COMMAND_EXECUTION`, including cancellation where relevant. Its suite exercises every result, context, parameter snapshot, action rejection, access revocation, cancellation, and all shared scenarios.

Breaking parameter, result, action, context, or cancellation semantics require a later command major. Optional parameters with safe defaults and saved configuration changes may evolve without one.
