plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.ksp)
    alias(libs.plugins.androidx.room3)
    alias(libs.plugins.protobuf)
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.licensee)
}

description = "Launcher persistence and migration implementation."

android {
    defaultConfig {
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }
}

room3 {
    schemaDirectory("$projectDir/schemas")
}

protobuf {
    protoc {
        artifact = "com.google.protobuf:protoc:${libs.protobuf.java.lite.get().versionConstraint.requiredVersion}"
    }
    generateProtoTasks {
        all().configureEach {
            builtins {
                create("java") { option("lite") }
            }
        }
    }
}

dependencies {
    api(project(":contracts:domain"))
    api(project(":contracts:contribution"))
    api(libs.kotlinx.coroutines.core)

    implementation(libs.androidx.datastore.core)
    implementation(libs.androidx.room3.common)
    implementation(libs.androidx.room3.runtime)
    implementation(libs.androidx.sqlite.core)
    implementation(libs.androidx.sqlite.framework)
    implementation(libs.kotlinx.serialization.core)
    implementation(libs.kotlinx.serialization.json)
    implementation(libs.protobuf.java.lite)
    ksp(libs.androidx.room3.compiler)

    testImplementation(libs.junit)
    testImplementation(libs.kotlinx.coroutines.test)
    testImplementation(project(":contracts:ui"))
    testRuntimeOnly(libs.androidx.datastore.core)

    androidTestImplementation(libs.androidx.room3.testing)
    androidTestImplementation(libs.androidx.sqlite.core)
    androidTestImplementation(libs.androidx.test.ext.junit)
    androidTestImplementation(libs.androidx.test.monitor)
    androidTestImplementation(libs.junit)
    androidTestImplementation(libs.kotlinx.coroutines.test)
    androidTestRuntimeOnly(libs.androidx.datastore.core)
    androidTestRuntimeOnly(libs.androidx.test.runner)
}

licensee {
    allow("Apache-2.0")
    allow("BSD-3-Clause")
}
