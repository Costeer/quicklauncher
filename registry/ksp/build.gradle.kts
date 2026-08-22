plugins {
    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.licensee)
    `java-library`
}

description = "Compile-time contribution registry validation and generation."

dependencies {
    api(project(":contracts:domain"))
    implementation(project(":contracts:contribution"))
    api(libs.ksp.api)

    testImplementation(libs.junit)
    testRuntimeOnly(project(":registry:annotations"))
    testImplementation(libs.kotlin.compiler.embeddable)
    testImplementation(libs.kotlin.compile.testing)
    testImplementation(libs.kotlin.compile.testing.ksp)
}

configurations.named("testRuntimeClasspath") {
    exclude(group = "org.jetbrains", module = "annotations")
}

licensee {
    allow("Apache-2.0")
}
