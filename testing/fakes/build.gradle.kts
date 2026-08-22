plugins {
    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.licensee)
    `java-library`
}

description = "Fake host state and platform adapters for tests."

dependencies {
    implementation(project(":contracts:domain"))
    api(project(":contracts:contribution"))
    api(project(":contracts:ui"))
    api(platform(libs.androidx.compose.bom))
    api(libs.androidx.compose.runtime)
    api(libs.androidx.compose.ui)
    api(libs.kotlinx.coroutines.core)
    testImplementation(libs.junit)
}

licensee {
    allow("Apache-2.0")
}
