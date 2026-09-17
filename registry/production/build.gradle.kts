plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.ksp)
    alias(libs.plugins.licensee)
}

description = "Deterministic aggregate of production contribution registry fragments."

dependencies {
    api(project(":contracts:contribution"))
    api(project(":contracts:domain"))
    implementation(project(":modules:layout:core"))
    implementation(project(":modules:block:core"))
    implementation(project(":modules:command:core"))
    implementation(project(":modules:search:core"))
    implementation(project(":modules:templates:core"))
    compileOnly(project(":registry:annotations"))
    ksp(project(":registry:ksp"))
    testImplementation(libs.junit)
}

licensee { allow("Apache-2.0") }
