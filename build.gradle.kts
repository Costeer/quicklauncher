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
    alias(libs.plugins.ksp) apply false
    alias(libs.plugins.licensee) apply false
    alias(libs.plugins.paparazzi) apply false
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
            ":host:runtime",
            ":host:data",
            ":host:platform",
            ":host:editor",
            ":host:settings",
            ":host:backup",
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

        project(":testing:samples") {
            onUnusedDependencies {
                // The Paparazzi plugin injects this test dependency; the shared snapshot suite
                // consumes it through :testing:contracts, which dependency analysis cannot trace.
                exclude(libs.paparazzi)
            }
        }

        // JVM compilation selects Compose's desktop/JVM-stub variants. These generic coordinates
        // must remain variant-aware so Android consumers select Android artifacts instead of
        // receiving duplicate desktop classes. Dependency analysis reports the selected variants
        // as transitive and the declarations as unused, so only those false positives are excluded.
        project(":contracts:contribution") {
            onUnusedDependencies { exclude(libs.androidx.compose.runtime) }
            onUsedTransitiveDependencies { exclude("androidx.compose.runtime:runtime-desktop") }
        }
        project(":contracts:ui") {
            onUnusedDependencies {
                exclude(libs.androidx.compose.runtime, libs.androidx.compose.ui)
            }
            onUsedTransitiveDependencies {
                exclude(
                    "androidx.compose.runtime:runtime-desktop",
                    "androidx.compose.ui:ui-jvmstubs",
                )
            }
        }
        project(":testing:contracts") {
            onUnusedDependencies {
                exclude(libs.androidx.compose.runtime, libs.androidx.compose.foundation)
            }
            onUsedTransitiveDependencies {
                exclude(
                    "androidx.compose.foundation:foundation-jvmstubs",
                    "androidx.compose.foundation:foundation-layout-jvmstubs",
                    "androidx.compose.runtime:runtime-desktop",
                    "androidx.compose.ui:ui-graphics-jvmstubs",
                    "androidx.compose.ui:ui-jvmstubs",
                    "androidx.compose.ui:ui-text-jvmstubs",
                )
            }
        }
        project(":testing:fakes") {
            onUnusedDependencies {
                exclude(libs.androidx.compose.runtime, libs.androidx.compose.ui)
            }
            onUsedTransitiveDependencies {
                exclude(
                    "androidx.compose.runtime:runtime-desktop",
                    "androidx.compose.ui:ui-jvmstubs",
                )
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
                        val isKspDependency = configuration.name.contains("ksp", ignoreCase = true)
                        val allowed = when {
                            target == owner.path -> true
                            owner.path.startsWith(":modules:") && isKspDependency ->
                                target == ":registry:ksp"
                            owner.path.startsWith(":modules:") && isTestDependency ->
                                target.startsWith(":contracts:") || target.startsWith(":testing:")
                            owner.path.startsWith(":modules:") ->
                                target.startsWith(":contracts:") || target == ":registry:annotations"
                            owner.path.startsWith(":host:") ->
                                target.startsWith(":contracts:") || target.startsWith(":host:")
                            owner.path.startsWith(":contracts:") ->
                                target.startsWith(":contracts:")
                            owner.path == ":registry:annotations" ->
                                target.startsWith(":contracts:")
                            owner.path == ":registry:ksp" ->
                                target.startsWith(":contracts:") || target == ":registry:annotations"
                            owner.path == ":testing:contracts" || owner.path == ":testing:fakes" ->
                                target.startsWith(":contracts:")
                            owner.path == ":testing:samples" && isKspDependency ->
                                target == ":registry:ksp"
                            owner.path == ":testing:samples" ->
                                target.startsWith(":contracts:") ||
                                    target.startsWith(":testing:") ||
                                    target == ":registry:annotations"
                            owner.path == ":app" ->
                                target.startsWith(":contracts:") ||
                                    target.startsWith(":host:") ||
                                    target.startsWith(":modules:") ||
                                    target.startsWith(":registry:")
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
    description = "Rejects Google Play Services and Firebase dependencies."
    notCompatibleWithConfigurationCache("Inspects resolved dependency configurations across projects.")

    doLast {
        val bannedGroups = setOf("com.google.android.gms", "com.google.firebase")
        val directViolations = subprojects.flatMap { owner ->
            owner.configurations.flatMap { configuration ->
                configuration.dependencies.mapNotNull { dependency ->
                    val group = dependency.group
                    if (group in bannedGroups) {
                        "${owner.path}:${configuration.name} -> $group:${dependency.name}:${dependency.version}"
                    } else {
                        null
                    }
                }
            }
        }
        val resolvedViolations = subprojects.flatMap { owner ->
            owner.configurations
                .filter {
                    it.isCanBeResolved &&
                        (it.name.contains("classpath", ignoreCase = true) ||
                            it.name.startsWith("ksp", ignoreCase = true))
                }
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
        }
        val violations = (directViolations + resolvedViolations).distinct().sorted()

        check(violations.isEmpty()) {
            "Forbidden Google runtime dependencies:\n${violations.joinToString("\n") { " - $it" }}"
        }
    }
}
