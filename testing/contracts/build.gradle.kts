plugins {
    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.licensee)
    `java-library`
}

description = "Black-box contribution contract test suites."

dependencies {
    implementation(project(":contracts:domain"))
    api(project(":contracts:contribution"))
    api(project(":contracts:ui"))
    api(platform(libs.androidx.compose.bom))
    api(libs.androidx.compose.runtime)
    implementation(libs.androidx.compose.foundation)
    compileOnlyApi(libs.junit)
    compileOnlyApi(libs.paparazzi)
    compileOnlyApi(libs.androidx.compose.ui.test.junit4)
    api(libs.kotlinx.coroutines.core)
}

licensee {
    allow("Apache-2.0")
}
