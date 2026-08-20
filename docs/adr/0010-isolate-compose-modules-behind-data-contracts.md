# Isolate Compose modules behind data contracts

Layout and block bundles live in separate Gradle modules and render with Jetpack Compose; the host alone bridges Android widget views. Modules depend on approved contracts rather than host storage or implementations. The host supplies immutable state and scoped services, modules emit typed actions, registered namespaced IDs describe slot compatibility, and each instance's state holder and coroutine scope end when that instance leaves composition.
