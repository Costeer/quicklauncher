# Contribution contracts

[Shared rules](shared-contract-rules.md) apply to every contribution. Category files contain only category-specific behavior.

| Category | Persisted type ID | Major | Specification |
| --- | --- | --- | --- |
| Layout | `org.quicklauncher.contribution/layout` | `1` | [Layout](layout.md) |
| Block | `org.quicklauncher.contribution/block` | `1` | [Block](block.md) |
| Search provider | `org.quicklauncher.contribution/search-provider` | `1` | [Search provider](search-provider.md) |
| Launcher command | `org.quicklauncher.contribution/launcher-command` | `1` | [Launcher command](launcher-command.md) |
| Destination template | `org.quicklauncher.contribution/destination-template` | `1` | [Destination template](destination-template.md) |

These are build-time, in-repository extension seams. They do not define runtime plugins or public binary compatibility. Kotlin types and validators under `:contracts:*` and `:registry:ksp` are the executable source of truth.
