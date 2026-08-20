plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.licensee)
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

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}

dependencies {
    implementation(libs.androidx.activity)
    implementation(libs.androidx.activity.compose)
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.compose.runtime)

    testImplementation(libs.junit)
}

licensee {
    allow("Apache-2.0")
}
