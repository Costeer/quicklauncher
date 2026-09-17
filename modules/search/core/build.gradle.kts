plugins {
    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.ksp)
    alias(libs.plugins.licensee)
}

description = "First-party Phase 6 search-provider contributions."

ksp {
    arg("quicklauncher.registry.fragment.package", "org.quicklauncher.modules.search.core.generated")
    arg("quicklauncher.registry.fragment.name", "CoreSearchRegistry")
    arg("quicklauncher.registry.fragment.id", "org.quicklauncher.registry.fragment/core-search")
}

dependencies {
    api(project(":contracts:domain"))
    api(project(":contracts:contribution"))
    api(project(":contracts:ui"))
    compileOnly(project(":registry:annotations"))
    ksp(project(":registry:ksp"))
    implementation(libs.kotlinx.coroutines.core)

    testImplementation(libs.junit)
    testImplementation(libs.kotlinx.coroutines.test)
}

licensee { allow("Apache-2.0") }
