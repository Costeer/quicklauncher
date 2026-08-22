plugins {
    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.licensee)
    `java-library`
}

licensee {
    allow("Apache-2.0")
}

description = "Platform-neutral launcher identities and value types."

dependencies {
    testImplementation(libs.junit)
}
