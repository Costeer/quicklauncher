pluginManagement {
    repositories {
        google()
        mavenCentral()
        gradlePluginPortal()
    }
}

dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google()
        mavenCentral()
    }
}

rootProject.name = "quicklauncher"

include(
    ":app",
    ":prototypes:nested-scroll",
    ":prototypes:platform-probe",
    ":prototypes:widget-neighbors",
    ":contracts:domain",
    ":contracts:contribution",
    ":contracts:ui",
    ":registry:annotations",
    ":registry:ksp",
    ":registry:production",
    ":host:runtime",
    ":host:data",
    ":host:platform",
    ":host:editor",
    ":host:settings",
    ":host:backup",
    ":modules:layout:core",
    ":modules:block:core",
    ":modules:command:core",
    ":modules:search:core",
    ":modules:templates:core",
    ":testing:contracts",
    ":testing:fakes",
    ":testing:samples",
)
