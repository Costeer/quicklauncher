# Register five versioned contribution types

First-release modules may contribute layouts, blocks, search providers, launcher commands, and destination templates. Code contracts and persisted configuration evolve independently: modules declare the supported contract major, while each configuration document carries its own schema version and sequential migrations. The generated registry rejects incompatible contract versions and invalid contribution metadata during the build.
