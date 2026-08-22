plugins {
    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.licensee)
    `java-library`
}

description = "Source-retained annotations for generated contribution registration."

licensee {
    allow("Apache-2.0")
}
