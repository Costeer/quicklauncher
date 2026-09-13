plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.licensee)
    alias(libs.plugins.paparazzi)
}

description = "Host-owned destination and map editing flows."

android {
    buildFeatures {
        compose = true
    }
    defaultConfig {
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }
    testOptions {
        unitTests.isIncludeAndroidResources = true
    }
}

tasks.withType<Test>().configureEach {
    forkEvery = 1
    maxParallelForks = 1
}

dependencies {
    api(project(":contracts:domain"))
    api(project(":contracts:contribution"))
    api(project(":host:data"))
    api(libs.kotlinx.coroutines.core)

    api(platform(libs.androidx.compose.bom))
    api(libs.androidx.compose.foundation.layout)
    api(libs.androidx.compose.runtime)
    api(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.foundation)
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.compose.ui.geometry)
    implementation(libs.androidx.compose.ui.graphics)
    implementation(libs.androidx.compose.ui.text)
    implementation(libs.androidx.compose.ui.unit)

    testImplementation(libs.junit)
    testImplementation(libs.kotlinx.coroutines.test)
    testImplementation(libs.androidx.compose.ui.test)
    testImplementation(libs.androidx.compose.ui.test.junit4)
    testImplementation(libs.androidx.compose.ui.geometry)
    testImplementation(libs.androidx.compose.ui.graphics)
    testImplementation(libs.androidx.test.ext.junit)
    testImplementation(project(":contracts:ui"))
    testImplementation(libs.robolectric.annotations)
    testImplementation("com.android.tools.layoutlib:layoutlib-api:31.11.0-rc02")
    testRuntimeOnly(libs.robolectric)
    androidTestImplementation(libs.androidx.compose.ui.test)
    androidTestImplementation(libs.androidx.compose.ui.test.junit4)
    androidTestImplementation(libs.androidx.compose.ui.geometry)
    androidTestImplementation(libs.androidx.compose.ui.unit)
    androidTestImplementation(libs.androidx.test.ext.junit)
    androidTestImplementation(libs.junit)
    androidTestImplementation(libs.androidx.test.runner)
    debugRuntimeOnly(libs.androidx.compose.ui.test.manifest)
    releaseRuntimeOnly(libs.androidx.compose.ui.test.manifest)
}

licensee {
    allow("Apache-2.0")
    allow("BSD-3-Clause")
}
