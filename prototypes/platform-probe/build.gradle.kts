plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.licensee)
}

android {
    namespace = "org.quicklauncher.prototypes.platformprobe"
    compileSdk = 36

    defaultConfig {
        applicationId = "org.quicklauncher.prototypes.platformprobe"
        minSdk = 35
        targetSdk = 35
        versionCode = 1
        versionName = "0.1"
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}

dependencies {
    testImplementation(libs.junit)
}

licensee {
    allow("Apache-2.0")
}
