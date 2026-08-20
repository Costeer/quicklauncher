# Isolate module data and failures

The host stores destination, content, placement, and version-tagged module configuration in one transactional persistence system. Each module supplies typed codecs and sequential migrations. A failed migration or renderer isolates the affected instance, preserves its raw data, and presents a recoverable placeholder; if the active layout fails, the launcher uses a built-in safe layout rather than blocking startup or deleting user data.
