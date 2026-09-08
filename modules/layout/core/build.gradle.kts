plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.ksp)
    alias(libs.plugins.licensee)
}

description = "First-party grid and single-block layouts."

android { buildFeatures { compose = true } }

ksp {
    arg("quicklauncher.registry.fragment.package", "org.quicklauncher.modules.layout.core.generated")
    arg("quicklauncher.registry.fragment.name", "CoreLayoutRegistry")
    arg("quicklauncher.registry.fragment.id", "org.quicklauncher.registry.fragment/core-layouts")
}

dependencies {
    implementation(project(":contracts:domain"))
    implementation(project(":contracts:contribution"))
    implementation(project(":contracts:ui"))
    compileOnly(project(":registry:annotations"))
    ksp(project(":registry:ksp"))

    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.compose.runtime)
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.foundation)
    implementation(libs.androidx.compose.foundation.layout)
    implementation(libs.androidx.compose.material3)
    implementation(libs.kotlinx.serialization.json)

    testImplementation(libs.junit)
}

licensee { allow("Apache-2.0") }
