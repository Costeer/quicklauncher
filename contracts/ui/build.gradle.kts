plugins {
    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.licensee)
    `java-library`
}

licensee {
    allow("Apache-2.0")
}

description = "Shared immutable UI inputs, actions, and theme contracts."

dependencies {
    api(project(":contracts:domain"))
    api(platform(libs.androidx.compose.bom))
    api(libs.androidx.compose.runtime)
    api(libs.androidx.compose.ui)

    testImplementation(libs.junit)
}
