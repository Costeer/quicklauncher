# Layout contribution contract

Type `org.quicklauncher.contribution/layout`, code-contract major `1`. The [shared rules](shared-contract-rules.md) apply.

## Interface

`LayoutContribution<C>.open` receives decoded configuration and returns a host-owned `LayoutSession`. Its composable `Render` arranges one destination, invokes the supplied `SlotRenderer`, and emits only `OpenSettings(instanceId)` or `SelectSlot(slotId)` actions.

`LayoutDescriptor` contains an ordered slot list. Each slot has a stable local ID, namespaced type, accepted block IDs, required capabilities, positive child limit, and allowed scroll axes. Empty slot lists and empty accepted-block sets are legal.

Render input contains immutable theme, window, contrast, status, editor, composition-role, placement, and slot snapshots. Only current composition is interactive. A layout never receives stored records, block implementations, or Android objects.

## Ownership and validation

The host owns destination identity, layout selection, dormant trees, placement commits, block lifecycle, navigation, quarantine, and safe fallback. A dormant layout has no session or live work.

Validation rejects duplicate slot IDs, unknown or incompatible accepted blocks, non-positive capacity, invalid capability guards, scroll-axis conflicts, mismatched slot renders, and actions naming another instance or undeclared slot. A layout delegates child rendering and never invokes a block target itself.

## Evidence and evolution

The category declaration requires `FIRST_RENDER`, `ACTION_DISPATCH`, and `DISPOSAL`. Its black-box suite renders every shared scenario, records slot calls and actions, checks semantics and goldens, and proves idempotent close and job termination.

Breaking render, slot, action, or lifecycle semantics require a later layout major. Configuration schema changes remain independent and preserve dormant state and stable IDs.
