plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.ksp)
    alias(libs.plugins.licensee)
    alias(libs.plugins.paparazzi)
}

description = "Compiled skeletal contributions and generated-registry fixtures."

android {
    testOptions.unitTests.isIncludeAndroidResources = true
}

tasks.withType<Test>().configureEach {
    // Paparazzi's native layoutlib and Robolectric's instrumented framework must not share a JVM.
    forkEvery = 1
    maxParallelForks = 1
}

dependencies {
    api(project(":contracts:domain"))
    api(project(":contracts:contribution"))
    api(project(":contracts:ui"))
    api(project(":testing:contracts"))
    implementation(project(":testing:fakes"))
    compileOnly(project(":registry:annotations"))
    implementation(libs.kotlinx.coroutines.core)
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.compose.foundation)
    implementation(libs.androidx.compose.foundation.layout)
    implementation(libs.androidx.compose.runtime)
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.graphics)
    implementation(libs.androidx.compose.ui.text)

    ksp(project(":registry:ksp"))

    testImplementation(libs.junit)
    testImplementation(libs.androidx.compose.ui.test.junit4)
    testImplementation(libs.androidx.test.ext.junit)
    testImplementation(libs.robolectric.annotations)
    testRuntimeOnly(libs.robolectric)
    debugRuntimeOnly(libs.androidx.compose.ui.test.manifest)
    releaseRuntimeOnly(libs.androidx.compose.ui.test.manifest)
}

licensee {
    allow("Apache-2.0")
}
