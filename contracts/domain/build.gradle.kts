plugins {
    alias(libs.plugins.kotlin.jvm)
    `java-library`
}

description = "Platform-neutral launcher identities and value types."

dependencies {
    testImplementation(libs.junit)
}
