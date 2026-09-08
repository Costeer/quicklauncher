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
    api(libs.androidx.compose.runtime)
    api(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.foundation)
    implementation(libs.androidx.compose.foundation.layout)
    implementation(libs.androidx.compose.material3)

    testImplementation(libs.junit)
    testImplementation(libs.kotlinx.coroutines.test)
    testImplementation("com.android.tools.layoutlib:layoutlib-api:31.11.0-rc02")
    testRuntimeOnly(libs.robolectric)
}

licensee {
    allow("Apache-2.0")
    allow("BSD-3-Clause")
}
