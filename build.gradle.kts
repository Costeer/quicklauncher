import com.android.build.api.dsl.LibraryExtension
import org.gradle.api.artifacts.ProjectDependency
import org.gradle.api.artifacts.component.ModuleComponentIdentifier
import org.gradle.api.plugins.JavaPluginExtension
import org.gradle.jvm.toolchain.JavaLanguageVersion
import org.jetbrains.kotlin.gradle.dsl.JvmTarget
import org.jetbrains.kotlin.gradle.tasks.KotlinCompile

plugins {
    alias(libs.plugins.android.application) apply false
    alias(libs.plugins.android.library) apply false
    alias(libs.plugins.kotlin.android) apply false
    alias(libs.plugins.kotlin.compose) apply false
    alias(libs.plugins.kotlin.jvm) apply false
    alias(libs.plugins.licensee) apply false
    alias(libs.plugins.dependency.analysis)
}

dependencyAnalysis {
    issues {
        all {
            onAny {
                severity("fail")
            }
        }

        // These modules intentionally have no source until their contract or host phase begins.
        // Remove each exception when that module gains its first source file.
        listOf(
            ":contracts:contribution",
            ":contracts:ui",
            ":registry:annotations",
            ":registry:ksp",
            ":host:runtime",
            ":host:data",
            ":host:platform",
            ":host:editor",
            ":host:settings",
            ":host:backup",
            ":testing:contracts",
            ":testing:fakes",
        ).forEach { placeholderPath ->
            project(placeholderPath) {
                onRedundantPlugins {
                    severity("ignore")
                }
                onModuleStructure {
                    severity("ignore")
                }
            }
        }
    }
}

subprojects {
    apply(plugin = "com.autonomousapps.dependency-analysis")

    plugins.withId("com.android.library") {
        extensions.configure<LibraryExtension> {
            namespace = "org.quicklauncher.${project.path.trim(':').replace(':', '.')}"
            compileSdk = 36

            defaultConfig {
                minSdk = 35
            }

            compileOptions {
                sourceCompatibility = JavaVersion.VERSION_17
                targetCompatibility = JavaVersion.VERSION_17
            }
        }
    }

    plugins.withId("java-library") {
        extensions.configure<JavaPluginExtension> {
            toolchain.languageVersion.set(JavaLanguageVersion.of(17))
        }
    }

    tasks.withType<KotlinCompile>().configureEach {
        compilerOptions {
            jvmTarget.set(JvmTarget.JVM_17)
            allWarningsAsErrors.set(true)
        }
    }
}

tasks.register("checkModuleBoundaries") {
    group = "verification"
    description = "Checks project dependencies against the architecture module rules."
    notCompatibleWithConfigurationCache("Inspects the live Gradle project dependency model.")

    doLast {
        val violations = subprojects.flatMap { owner ->
            owner.configurations.flatMap { configuration ->
                configuration.dependencies
                    .withType(ProjectDependency::class.java)
                    .mapNotNull { dependency ->
                        val target = dependency.path
                        val isTestDependency = configuration.name.contains("test", ignoreCase = true)
                        val allowed = when {
                            owner.path.startsWith(":modules:") && isTestDependency ->
                                target.startsWith(":contracts:") || target.startsWith(":testing:")
                            owner.path.startsWith(":modules:") -> target.startsWith(":contracts:")
                            owner.path.startsWith(":host:") ->
                                !target.startsWith(":modules:") && target != ":app"
                            owner.path.startsWith(":contracts:") ->
                                !target.startsWith(":host:") &&
                                    !target.startsWith(":modules:") &&
                                    target != ":app"
                            else -> true
                        }

                        if (allowed) {
                            null
                        } else {
                            "${owner.path}:${configuration.name} -> $target"
                        }
                    }
            }
        }.distinct().sorted()

        check(violations.isEmpty()) {
            "Module boundary violations:\n${violations.joinToString("\n") { " - $it" }}"
        }
    }
}

tasks.register("verifyNoGoogleDependencies") {
    group = "verification"
    description = "Rejects Google Play Services and Firebase runtime dependencies."
    notCompatibleWithConfigurationCache("Inspects resolved runtime configurations across projects.")

    doLast {
        val bannedGroups = setOf("com.google.android.gms", "com.google.firebase")
        val violations = subprojects.flatMap { owner ->
            owner.configurations
                .filter { it.isCanBeResolved && it.name.endsWith("RuntimeClasspath") }
                .flatMap { configuration ->
                    configuration.incoming.resolutionResult.allComponents.mapNotNull { component ->
                        val module = component.id as? ModuleComponentIdentifier ?: return@mapNotNull null
                        if (module.group in bannedGroups) {
                            "${owner.path}:${configuration.name} -> ${module.group}:${module.module}:${module.version}"
                        } else {
                            null
                        }
                    }
                }
        }.distinct().sorted()

        check(violations.isEmpty()) {
            "Forbidden Google runtime dependencies:\n${violations.joinToString("\n") { " - $it" }}"
        }
    }
}
