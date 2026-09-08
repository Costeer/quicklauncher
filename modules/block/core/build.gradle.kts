plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.ksp)
    alias(libs.plugins.licensee)
    alias(libs.plugins.paparazzi)
}

description = "First-party app, favorites, folder, and clock blocks."

android {
    buildFeatures { compose = true }
    testOptions.unitTests.isIncludeAndroidResources = true
}

ksp {
    arg("quicklauncher.registry.fragment.package", "org.quicklauncher.modules.block.core.generated")
    arg("quicklauncher.registry.fragment.name", "CoreBlockRegistry")
    arg("quicklauncher.registry.fragment.id", "org.quicklauncher.registry.fragment/core-blocks")
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
    implementation(libs.kotlinx.coroutines.core)
    implementation(libs.kotlinx.serialization.json)

    testImplementation(libs.junit)
    testImplementation(project(":testing:fakes"))
    testImplementation(libs.androidx.compose.ui.test.junit4)
    testImplementation(libs.androidx.test.ext.junit)
    testImplementation(libs.robolectric.annotations)
    testImplementation(libs.paparazzi)
    testRuntimeOnly(libs.robolectric)
    debugRuntimeOnly(libs.androidx.compose.ui.test.manifest)
    releaseRuntimeOnly(libs.androidx.compose.ui.test.manifest)
}

licensee { allow("Apache-2.0") }
