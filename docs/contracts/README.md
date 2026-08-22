# Contribution contracts

These specifications define the five contribution interfaces accepted for Quicklauncher's first release. The [shared contract rules](shared-contract-rules.md) are authoritative for behavior common to every contribution. Each dedicated specification applies those rules to one contribution type and defines its type-specific interface.

| Contribution type | Persisted type ID | Supported code-contract major | Specification |
| --- | --- | --- | --- |
| Layout | `org.quicklauncher.contribution/layout` | `1` | [Layout](layout.md) |
| Block | `org.quicklauncher.contribution/block` | `1` | [Block](block.md) |
| Search provider | `org.quicklauncher.contribution/search-provider` | `1` | [Search provider](search-provider.md) |
| Launcher command | `org.quicklauncher.contribution/launcher-command` | `1` | [Launcher command](launcher-command.md) |
| Destination template | `org.quicklauncher.contribution/destination-template` | `1` | [Destination template](destination-template.md) |

The persisted type IDs above are data, not enum ordinals. A later contribution category receives a new stable string. Adding it must not change an existing ID.

These documents specify the interface at the seam between the launcher host and an in-repository contribution. They do not authorize runtime plugins, Android framework access from contributions, or a public binary compatibility promise.

Layout and block major 1 are Compose interfaces: their sessions expose `@Composable Render`, and child composition crosses host-owned typed renderer seams. Registry registration binds each target, codec, and category-specific contract suite to one concrete configuration type. The [shared registration rules](shared-contract-rules.md#registration) define the compile-time manifests and rejection rules.
