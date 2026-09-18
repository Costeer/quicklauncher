plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.licensee)
}

description = "Backup archive, restore, and support bundle implementation."

dependencies {
    api(project(":contracts:domain"))
    api(project(":host:data"))
    implementation(project(":contracts:contribution"))
    implementation(libs.kotlinx.coroutines.core)
    implementation(libs.kotlinx.serialization.core)
    implementation(libs.kotlinx.serialization.json)

    testImplementation(libs.junit)
    testImplementation(libs.kotlinx.coroutines.test)
}

licensee {
    allow("Apache-2.0")
    allow("BSD-3-Clause")
}
