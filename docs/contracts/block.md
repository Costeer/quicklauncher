# Block contribution contract

Type `org.quicklauncher.contribution/block`, code-contract major `1`. The [shared rules](shared-contract-rules.md) apply.

## Interface

`BlockContribution<C>.open` returns a host-owned `BlockSession`. Its composable `Render` uses supplied child-slot and prepared-content renderers and emits only `OpenSettings`, `ActivateItem`, or `OpenItemActions` with identities from its input.

`BlockDescriptor` declares at least one compatible slot type, optional ordered child slots, and occupied scroll axes. Child slots use the layout slot fields. The static accepted-block graph and runtime placement tree must both be acyclic.

Render input contains immutable theme, window, contrast, status, editor, composition-role, placement, child-slot, and prepared-content snapshots. Only current composition is interactive.

## Ownership and validation

The host owns placement, content identity, app and profile policy, item actions, widget bindings, copy and clone rules, persistence, child lifecycle, and failure placeholders. Blocks query no Android service or host store. They pass prepared items and host-surface tokens back to the supplied renderer.

Validation rejects missing compatible slot types, duplicate child slots, incompatible slot type or capability edges, non-positive capacity, scroll conflicts, static or runtime cycles, and actions naming absent input data. A block is active only while its current, neighbor, or editor composition needs it.

## Evidence and evolution

The category declaration requires `FIRST_RENDER`, `ACTION_DISPATCH`, and `DISPOSAL`. Its suite renders all shared scenarios, records child-slot and prepared-content calls, checks semantics and goldens, and proves close stops owned work.

Breaking compatibility, nesting, render, action, or lifecycle semantics require a later block major. Configuration evolves separately; copies get independent documents and future clones may share one.
