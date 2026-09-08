plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.licensee)
}

description = "Android launcher, profile, and persistence-construction adapters."

dependencies {
    api(project(":host:data"))
    api(project(":host:runtime"))
    api(project(":contracts:domain"))
    api(libs.androidx.activity)
    api(libs.kotlinx.coroutines.core)

    testImplementation(libs.junit)
    testImplementation(libs.kotlinx.coroutines.test)
}

licensee {
    allow("Apache-2.0")
    allow("BSD-3-Clause")
}
