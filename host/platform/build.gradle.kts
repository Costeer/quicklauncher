plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.licensee)
}

description = "Android launcher, profile, and persistence-construction adapters."

android {
    defaultConfig {
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }
}

dependencies {
    api(project(":contracts:contribution"))
    api(project(":host:data"))
    api(project(":host:runtime"))
    api(project(":contracts:domain"))
    api(project(":contracts:ui"))
    api(libs.androidx.activity)
    api(libs.androidx.compose.runtime)
    api(libs.androidx.compose.ui)
    api(libs.kotlinx.coroutines.core)
    api(project(":host:backup"))
    implementation(libs.androidx.activity.compose)
    implementation(platform(libs.androidx.compose.bom))

    testImplementation(libs.junit)
    testImplementation(libs.kotlinx.coroutines.test)
    testImplementation(libs.androidx.test.ext.junit)
    testImplementation(libs.androidx.test.monitor)
    testImplementation(libs.robolectric.annotations)
    testRuntimeOnly(libs.robolectric)

    androidTestImplementation(libs.androidx.test.ext.junit)
    androidTestImplementation(libs.androidx.test.monitor)
    androidTestImplementation(libs.junit)
    androidTestImplementation(libs.kotlinx.coroutines.test)
    androidTestRuntimeOnly(libs.androidx.test.runner)
}

licensee {
    allow("Apache-2.0")
    allow("BSD-3-Clause")
}
