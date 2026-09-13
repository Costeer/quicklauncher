plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.ksp)
    alias(libs.plugins.licensee)
    alias(libs.plugins.paparazzi)
}

description = "First-party grid and single-block layouts."

android {
    buildFeatures { compose = true }
    testOptions.unitTests.isIncludeAndroidResources = true
}


tasks.withType<Test>().configureEach {
    forkEvery = 1
    maxParallelForks = 1
}

ksp {
    arg("quicklauncher.registry.fragment.package", "org.quicklauncher.modules.layout.core.generated")
    arg("quicklauncher.registry.fragment.name", "CoreLayoutRegistry")
    arg("quicklauncher.registry.fragment.id", "org.quicklauncher.registry.fragment/core-layouts")
}

dependencies {
    api(project(":contracts:domain"))
    api(project(":contracts:contribution"))
    api(project(":contracts:ui"))
    compileOnly(project(":registry:annotations"))
    ksp(project(":registry:ksp"))

    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.compose.runtime)
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.foundation)
    implementation(libs.androidx.compose.foundation.layout)
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.compose.ui.unit)
    implementation(libs.kotlinx.coroutines.core)
    implementation(libs.kotlinx.serialization.json)
    api(libs.kotlinx.serialization.core)

    testImplementation(libs.junit)
    testImplementation(project(":testing:fakes"))
    testImplementation(project(":testing:contracts"))
    testImplementation("com.android.tools.layoutlib:layoutlib-api:31.11.0-rc02")
    testImplementation(libs.androidx.compose.ui.graphics)
    testImplementation(libs.androidx.compose.ui.text)
    testImplementation(libs.paparazzi)
    testRuntimeOnly(libs.robolectric)
}

licensee { allow("Apache-2.0") }
