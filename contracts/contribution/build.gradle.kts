plugins {
    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.licensee)
    `java-library`
}

licensee {
    allow("Apache-2.0")
}

description = "Contribution descriptors and lifecycle contracts."

dependencies {
    api(project(":contracts:domain"))
    api(project(":contracts:ui"))
    api(platform(libs.androidx.compose.bom))
    api(libs.androidx.compose.runtime)
    api(libs.kotlinx.coroutines.core)

    testImplementation(libs.junit)
}
