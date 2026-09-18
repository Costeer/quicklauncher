import org.gradle.api.artifacts.component.ModuleComponentIdentifier

plugins {
    alias(libs.plugins.android.test)
    alias(libs.plugins.kotlin.android)
}

description = "Release-mode Macrobenchmark coverage for Phase 9 performance contracts."

android {
    namespace = "org.quicklauncher.benchmark.macrobenchmark"
    compileSdk = 36

    defaultConfig {
        minSdk = 35
        targetSdk = 35
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        testInstrumentationRunnerArguments["androidx.benchmark.output.enable"] = "true"
        testInstrumentationRunnerArguments["androidx.benchmark.suppressErrors"] = "EMULATOR"
    }

    targetProjectPath = ":app"
    experimentalProperties["android.experimental.self-instrumenting"] = true

    flavorDimensions += "channel"
    productFlavors {
        create("stable") {
            dimension = "channel"
        }
    }

    buildTypes {
        create("benchmark") {
            isDebuggable = true
            matchingFallbacks += listOf("release")
            signingConfig = signingConfigs.getByName("debug")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}

dependencies {
    implementation("androidx.benchmark:benchmark-macro:1.5.0")
    implementation(libs.androidx.benchmark.macro.junit4)
    implementation(libs.androidx.test.ext.junit)
    implementation(libs.androidx.test.monitor)
    implementation(libs.androidx.test.runner)
    implementation(libs.androidx.test.uiautomator)
    implementation(libs.junit)
    implementation("org.jspecify:jspecify:1.0.0")
}

val writeBenchmarkDependencyInventory = tasks.register("writeBenchmarkDependencyInventory") {
    notCompatibleWithConfigurationCache("Resolves the live benchmark runtime graph for license audit.")
    val output = layout.buildDirectory.file("reports/licenses/stable-benchmark-dependencies.txt")
    outputs.file(output)
    doLast {
        val components = configurations.getByName("stableBenchmarkRuntimeClasspath")
            .incoming.resolutionResult.allComponents
            .mapNotNull { it.id as? ModuleComponentIdentifier }
            .map { "${it.group}:${it.module}:${it.version}" }
            .toSortedSet()
        val file = output.get().asFile
        file.parentFile.mkdirs()
        file.writeText(components.joinToString(separator = "\n", postfix = "\n"))
    }
}

tasks.register<Exec>("checkBenchmarkDependencyLicenses") {
    group = "verification"
    description = "Fail closed unless every resolved benchmark dependency has an approved license entry."
    dependsOn(writeBenchmarkDependencyInventory)
    commandLine(
        "python3",
        rootProject.file("tools/performance/audit_benchmark_dependencies.py"),
        "--resolved",
        layout.buildDirectory.file("reports/licenses/stable-benchmark-dependencies.txt").get().asFile,
        "--allowlist",
        rootProject.file("tools/performance/benchmark-dependency-licenses.txt"),
    )
}
