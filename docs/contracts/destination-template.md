# Destination-template contribution contract

Type `org.quicklauncher.contribution/destination-template`, code-contract major `1`. The [shared rules](shared-contract-rules.md) apply.

## Interface and plan

`DestinationTemplateContribution<C>.create` is a synchronous, pure transformation from immutable `TemplateInput<C>` to `TemplateResult`. It performs no persistence, platform work, registry discovery, or background work.

Input supplies unique destination identities and names, unique pairs of module-instance and configuration-document identities, decoded configuration, and cancellation. A template consumes only these identities.

`Created` contains one nonempty `TemplatePlan`. The plan has unique destination IDs and coordinates, exactly one start destination, and one cardinally connected map. Every destination has one layout root and an acyclic placement tree in which each block appears once. Sibling indexes are contiguous per parent slot. Parent-owned placement data has a positive schema and opaque bytes.

## Ownership and validation

The descriptor declares the exact required layout and block contributions plus a nonnegative per-destination block limit. Template configuration affects plan creation but leaves no marker on installed destinations.

The host validates all identities against the input, resolves every contribution and codec, checks the declared block limit, decodes configurations, and checks slot, capability, scroll, capacity, placement-schema, tree, and map invariants. It previews that exact plan, then installs destinations, retained safe layouts, modules, configurations, placements, and the start destination in one `LauncherStore` transaction. Failure or cancellation leaves prior state unchanged. The Home-role request is a later explicit step.

Templates are deterministic, honor cancellation, and retain no input. They do not create IDs, inspect the registry, instantiate targets, persist, or choose external actions.

## Evidence and evolution

The category declaration includes all shared scenarios and `DRAFT_CREATION`. Preview renders the validated plan through production composition with actions disabled. The suite checks determinism, identity use, configuration validity, graph and map invariants, cancellation, and atomic rejection.

Major 1 includes multi-destination plans, typed positions and start selection, explicit module/configuration identity pairs, and recursive placement trees. Incompatible changes require a later major. Template settings evolve through ordinary configuration schemas and never rewrite installed destinations.
