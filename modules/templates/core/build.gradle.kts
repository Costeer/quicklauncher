plugins {
    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.ksp)
    alias(libs.plugins.licensee)
}

description = "Modular, traditional, and blank first-run destination templates."

ksp {
    arg("quicklauncher.registry.fragment.package", "org.quicklauncher.modules.templates.core.generated")
    arg("quicklauncher.registry.fragment.name", "CoreTemplateRegistry")
    arg("quicklauncher.registry.fragment.id", "org.quicklauncher.registry.fragment/core-templates")
}

dependencies {
    implementation(project(":contracts:domain"))
    implementation(project(":contracts:contribution"))
    compileOnly(project(":registry:annotations"))
    ksp(project(":registry:ksp"))
    testImplementation(libs.junit)
}

licensee { allow("Apache-2.0") }
