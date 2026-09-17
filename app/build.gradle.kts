plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.licensee)
    alias(libs.plugins.paparazzi)
}

android {
    namespace = "org.quicklauncher.app"
    compileSdk = 36

    defaultConfig {
        applicationId = "org.quicklauncher"
        minSdk = 35
        targetSdk = 35
        versionCode = 1
        versionName = "0.1.0"
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    flavorDimensions += "channel"
    productFlavors {
        create("stable") {
            dimension = "channel"
            buildConfigField("String", "CHANNEL", "\"stable\"")
            resValue("string", "app_name", "Quicklauncher")
        }
        create("preview") {
            dimension = "channel"
            applicationIdSuffix = ".preview"
            versionNameSuffix = "-preview"
            buildConfigField("String", "CHANNEL", "\"preview\"")
            resValue("string", "app_name", "Quicklauncher Preview")
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
        }
    }

    buildFeatures {
        buildConfig = true
        compose = true
    }

    testOptions {
        unitTests.isIncludeAndroidResources = true
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}

dependencies {
    implementation(project(":contracts:domain"))
    implementation(project(":contracts:contribution"))
    implementation(project(":contracts:ui"))
    implementation(project(":host:data"))
    implementation(project(":host:editor"))
    implementation(project(":host:platform"))
    implementation(project(":host:runtime"))
    implementation(project(":host:settings"))
    implementation(project(":host:backup"))
    implementation(project(":modules:search:core"))
    implementation(project(":registry:production"))
    implementation(libs.androidx.activity)
    implementation(libs.androidx.activity.compose)
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.compose.foundation)
    implementation(libs.androidx.compose.foundation.layout)
    implementation(libs.androidx.compose.runtime)
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.geometry)
    implementation(libs.androidx.compose.ui.graphics)
    implementation(libs.androidx.compose.ui.text)
    implementation(libs.androidx.compose.ui.unit)
    implementation(libs.kotlinx.coroutines.core)

    testImplementation(libs.junit)
    testImplementation(libs.kotlinx.coroutines.test)
    testImplementation("com.android.tools.layoutlib:layoutlib-api:31.11.0-rc02")

    androidTestImplementation(libs.androidx.test.ext.junit)
    androidTestImplementation(libs.androidx.test.monitor)
    androidTestImplementation(libs.androidx.test.uiautomator)
    androidTestImplementation(platform(libs.androidx.compose.bom))
    androidTestImplementation(libs.androidx.compose.ui.test)
    androidTestImplementation(libs.androidx.compose.ui.test.junit4)
    androidTestImplementation(libs.junit)
    androidTestImplementation("org.jspecify:jspecify:1.0.0")
    androidTestImplementation(libs.androidx.test.runner)
}

licensee {
    allow("Apache-2.0")
    allow("BSD-3-Clause")
}
