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
    ":host:runtime",
    ":host:data",
    ":host:platform",
    ":host:editor",
    ":host:settings",
    ":host:backup",
    ":testing:contracts",
    ":testing:fakes",
)
