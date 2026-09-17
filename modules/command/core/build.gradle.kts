plugins {
    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.ksp)
    alias(libs.plugins.licensee)
}

description = "First-party typed launcher commands."

ksp {
    arg("quicklauncher.registry.fragment.package", "org.quicklauncher.modules.command.core.generated")
    arg("quicklauncher.registry.fragment.name", "CoreCommandRegistry")
    arg("quicklauncher.registry.fragment.id", "org.quicklauncher.registry.fragment/core-commands")
}

dependencies {
    api(project(":contracts:domain"))
    api(project(":contracts:contribution"))
    api(project(":contracts:ui"))
    compileOnly(project(":registry:annotations"))
    ksp(project(":registry:ksp"))
}

licensee { allow("Apache-2.0") }
