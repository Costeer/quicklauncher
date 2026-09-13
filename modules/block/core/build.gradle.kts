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

tasks.withType<Test>().configureEach {
    forkEvery = 1
    maxParallelForks = 1
}

ksp {
    arg("quicklauncher.registry.fragment.package", "org.quicklauncher.modules.block.core.generated")
    arg("quicklauncher.registry.fragment.name", "CoreBlockRegistry")
    arg("quicklauncher.registry.fragment.id", "org.quicklauncher.registry.fragment/core-blocks")
}

dependencies {
    api(project(":contracts:domain"))
    api(project(":contracts:contribution"))
    api(project(":contracts:ui"))
    compileOnly(project(":registry:annotations"))
    ksp(project(":registry:ksp"))

    implementation(platform(libs.androidx.compose.bom))
    api(libs.androidx.compose.runtime)
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.foundation)
    implementation(libs.androidx.compose.foundation.layout)
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.compose.ui.graphics)
    implementation(libs.androidx.compose.ui.text)
    implementation(libs.androidx.compose.ui.unit)
    implementation(libs.kotlinx.coroutines.core)
    implementation(libs.kotlinx.serialization.json)
    api(libs.kotlinx.serialization.core)

    testImplementation(libs.junit)
    testImplementation(project(":testing:fakes"))
    testImplementation(project(":testing:contracts"))
    testImplementation(libs.androidx.compose.ui.test.junit4)
    testImplementation(libs.androidx.compose.ui.graphics)
    testImplementation(libs.androidx.compose.ui.test)
    testImplementation(libs.androidx.compose.ui.text)
    testImplementation(libs.androidx.test.ext.junit)
    testImplementation("com.android.tools.layoutlib:layoutlib-api:31.11.0-rc02")
    testCompileOnly(libs.robolectric.annotations)
    testImplementation(libs.paparazzi)
    testRuntimeOnly(libs.robolectric)
    debugRuntimeOnly(libs.androidx.compose.ui.test.manifest)
    releaseRuntimeOnly(libs.androidx.compose.ui.test.manifest)
}

licensee { allow("Apache-2.0") }
