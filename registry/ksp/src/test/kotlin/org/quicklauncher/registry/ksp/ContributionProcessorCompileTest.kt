package org.quicklauncher.registry.ksp

import com.tschuchort.compiletesting.JvmCompilationResult
import com.tschuchort.compiletesting.KotlinCompilation
import com.tschuchort.compiletesting.SourceFile
import com.tschuchort.compiletesting.configureKsp
import com.tschuchort.compiletesting.sourcesGeneratedBySymbolProcessor
import java.io.ByteArrayOutputStream
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.jetbrains.kotlin.compiler.plugin.ExperimentalCompilerApi

@OptIn(ExperimentalCompilerApi::class)
class ContributionProcessorCompileTest {
    @Test
    fun `fragment generation options produce a unique named manifest`() {
        val outcome = compileWithOptions(
            mapOf(
                "quicklauncher.registry.fragment.package" to "compiletest.unique",
                "quicklauncher.registry.fragment.name" to "UniqueFixtureFragment",
                "quicklauncher.registry.fragment.id" to "org.quicklauncher.fragment/unique-fixture",
            ),
            candidateSource(
                declarations = layout(
                    "UniqueLayout",
                    "org.quicklauncher.samples/unique-layout",
                    "unique-layout-config",
                ),
            ),
        )

        assertEquals(outcome.messages, KotlinCompilation.ExitCode.OK, outcome.result.exitCode)
        val generated = outcome.generated("UniqueFixtureFragment.kt")
        assertTrue(generated.startsWith("package compiletest.unique"))
        assertTrue(generated.contains("fragmentId = \"org.quicklauncher.fragment/unique-fixture\""))
        assertTrue(generated.contains("object UniqueFixtureFragment"))
    }

    @Test
    fun `binary fragment manifests aggregate from compiled dependency modules`() {
        val fragmentModule = compile(compiledFragmentSource())
        assertEquals(fragmentModule.messages, KotlinCompilation.ExitCode.OK, fragmentModule.result.exitCode)

        val application = compileWithClasspaths(
            listOf(fragmentModule.result.outputDirectory),
            emptyMap(),
            SourceFile.kotlin(
                "ApplicationAggregation.kt",
                """
                package application

                import compiledfragment.CompiledFragment
                import org.quicklauncher.registry.annotations.AggregateContributionRegistry

                @AggregateContributionRegistry(
                    fragments = [CompiledFragment::class],
                    packageName = "application.generated",
                    registryName = "CompiledApplicationRegistry",
                )
                object ApplicationAggregation
                """.trimIndent(),
            ),
        )

        assertEquals(application.messages, KotlinCompilation.ExitCode.OK, application.result.exitCode)
        val generated = application.generated("CompiledApplicationRegistry.kt")
        assertTrue(generated.contains("compiledfragment.CompiledFragment.entries[0]"))
        assertFalse(generated.contains("reflect"))
    }

    @Test
    fun `explicit fragments aggregate in stable category and contribution order without reflection`() {
        val forward = compile(aggregationSource(reverseFragments = false))
        val reverse = compile(aggregationSource(reverseFragments = true))

        assertEquals(forward.messages, KotlinCompilation.ExitCode.OK, forward.result.exitCode)
        assertEquals(reverse.messages, KotlinCompilation.ExitCode.OK, reverse.result.exitCode)
        val generated = forward.generated("ApplicationContributionRegistry.kt")
        assertEquals(generated, reverse.generated("ApplicationContributionRegistry.kt"))
        assertTrue(generated.indexOf("BlockFragment.entries[0]") < generated.indexOf("LayoutFragment.entries[0]"))
        assertTrue(generated.indexOf("LayoutFragment.entries[0]") < generated.indexOf("LayoutFragment.entries[1]"))
        assertFalse(generated.contains("Class.forName"))
        assertFalse(generated.contains("ServiceLoader"))
        assertFalse(generated.contains("kotlin.reflect"))
    }

    @Test
    fun `duplicate contribution IDs across fragments fail aggregation`() {
        val outcome = compile(aggregationSource(duplicateContributionId = true))

        assertFailure(
            outcome,
            "registry.duplicate-contribution-id",
            "compiletest.AggregateApplicationRegistry",
            "org.quicklauncher.samples/layout-a",
        )
    }

    @Test
    fun `duplicate configuration type IDs across fragments fail aggregation`() {
        val outcome = compile(aggregationSource(duplicateConfigTypeId = true))

        assertFailure(
            outcome,
            "registry.duplicate-config-type",
            "compiletest.AggregateApplicationRegistry",
            "org.quicklauncher.samples/layout-a-config",
        )
    }

    @Test
    fun `slot incompatibility across fragments fails aggregation`() {
        val outcome = compile(aggregationSource(incompatibleSlot = true))

        assertFailure(
            outcome,
            "registry.slot-compatibility",
            "compiletest.AggregateApplicationRegistry",
            "org.quicklauncher.slot/content",
        )
    }

    @Test
    fun `unsupported contract majors in fragments fail aggregation`() {
        val outcome = compile(aggregationSource(unsupportedMajor = true))

        assertFailure(
            outcome,
            "registry.unsupported-contract-major",
            "compiletest.AggregateApplicationRegistry",
            "major 2",
        )
    }

    @Test
    fun `capability cycles across fragments fail aggregation`() {
        val outcome = compile(aggregationSource(capabilityCycle = true))

        assertFailure(
            outcome,
            "registry.capability-cycle",
            "compiletest.AggregateApplicationRegistry",
            "org.quicklauncher.samples/block-a",
        )
    }

    @Test
    fun `block nesting cycles across fragments fail aggregation`() {
        val outcome = compile(aggregationSource(blockCycle = true))

        assertFailure(
            outcome,
            "registry.block-nesting-cycle",
            "compiletest.AggregateApplicationRegistry",
            "block-a",
        )
    }

    @Test
    fun `one valid sample of every contribution type compiles in deterministic registry order`() {
        val compilation = compile(validFiveSource())

        assertEquals(compilation.messages, KotlinCompilation.ExitCode.OK, compilation.result.exitCode)
        val generated = compilation.generatedRegistry()
        val orderedTargets = listOf("SampleBlock", "SampleTemplate", "SampleCommand", "SampleLayout", "SampleSearch")
        orderedTargets.zipWithNext().forEach { (first, second) ->
            assertTrue("Expected $first before $second in:\n$generated", generated.indexOf(first) < generated.indexOf(second))
        }
        assertTrue(generated.contains("SettingsSchema"))
        listOf("LayoutCodec", "BlockCodec", "SearchCodec", "CommandCodec", "TemplateCodec").forEach { codec ->
            assertTrue(generated.contains("codec = compiletest.$codec"))
        }
        assertFalse(generated.contains("Class.forName"))
        assertFalse(generated.contains("ServiceLoader"))
        assertFalse(generated.contains("kotlin.reflect"))
    }

    @Test
    fun `generated source is byte stable when source declaration order changes`() {
        val forward = compile(validFiveSource(reverse = false)).generatedRegistry()
        val reverse = compile(validFiveSource(reverse = true)).generatedRegistry()

        assertEquals(forward, reverse)
    }

    @Test
    fun `duplicate contribution IDs fail compilation with the offending target`() {
        val outcome = compile(
            candidateSource(
                declarations = layout("First", "org.quicklauncher.samples/duplicate", "first-config") +
                    "\n" + layout("Second", "org.quicklauncher.samples/duplicate", "second-config"),
            ),
        )

        assertFailure(outcome, "registry.duplicate-contribution-id", "compiletest.First", "registered more than once")
    }

    @Test
    fun `duplicate persisted configuration type IDs fail compilation`() {
        val outcome = compile(
            candidateSource(
                declarations = layout("First", "org.quicklauncher.samples/first", "shared-config") +
                    "\n" + layout("Second", "org.quicklauncher.samples/second", "shared-config"),
            ),
        )

        assertFailure(outcome, "registry.duplicate-config-type", "compiletest.Second", "shared-config")
    }

    @Test
    fun `future contract major fails compilation`() {
        val outcome = compile(
            candidateSource(
                declarations = layout(
                    target = "FutureLayout",
                    id = "org.quicklauncher.samples/future-layout",
                    config = "future-layout-config",
                    contractMajor = 2,
                ),
            ),
        )

        assertFailure(outcome, "registry.unsupported-contract-major", "FutureLayout", "major 2")
    }

    @Test
    fun `invalid contribution ID fails compilation`() {
        val outcome = compile(
            candidateSource(
                declarations = layout("InvalidId", "not-namespaced", "invalid-id-config"),
            ),
        )

        assertFailure(outcome, "registry.invalid-contribution-id", "InvalidId", "not-namespaced")
    }

    @Test
    fun `invalid persisted configuration type ID fails compilation`() {
        val outcome = compile(
            candidateSource(
                declarations = layout(
                    target = "InvalidConfigType",
                    id = "org.quicklauncher.samples/invalid-config-type",
                    config = "unused",
                    configTypeId = "not-namespaced",
                ),
            ),
        )

        assertFailure(outcome, "registry.invalid-config-type", "InvalidConfigType", "not-namespaced")
    }

    @Test
    fun `host owned safe layout contribution ID fails compilation`() {
        val outcome = compile(
            candidateSource(
                declarations = layout(
                    target = "ReservedContribution",
                    id = "org.quicklauncher.core/safe-layout",
                    config = "reserved-contribution-config",
                ),
            ),
        )

        assertFailure(
            outcome,
            "registry.reserved-safe-layout-identity",
            "ReservedContribution",
            "contribution ID 'org.quicklauncher.core/safe-layout'",
        )
    }

    @Test
    fun `host owned safe layout configuration type ID fails compilation`() {
        val outcome = compile(
            candidateSource(
                declarations = layout(
                    target = "ReservedConfiguration",
                    id = "org.quicklauncher.samples/reserved-configuration",
                    config = "unused",
                    configTypeId = "org.quicklauncher.core/safe-layout",
                ),
            ),
        )

        assertFailure(
            outcome,
            "registry.reserved-safe-layout-identity",
            "ReservedConfiguration",
            "configuration type ID 'org.quicklauncher.core/safe-layout'",
        )
    }

    @Test
    fun `invalid capability ID fails compilation`() {
        val outcome = compile(
            candidateSource(
                declarations = layout(
                    target = "InvalidCapability",
                    id = "org.quicklauncher.samples/invalid-capability",
                    config = "invalid-capability-config",
                    provided = "not-namespaced",
                ),
            ),
        )

        assertFailure(outcome, "registry.invalid-capability-id", "InvalidCapability", "not-namespaced")
    }

    @Test
    fun `invalid descriptor fails compilation`() {
        val outcome = compile(
            candidateSource(
                declarations = layout(
                    target = "BlankDescriptor",
                    id = "org.quicklauncher.samples/blank",
                    config = "blank-config",
                    displayName = "",
                ),
            ),
        )

        assertFailure(outcome, "registry.blank-display-name", "BlankDescriptor", "must not be blank")
    }

    @Test
    fun `missing configuration codec fails compilation`() {
        val outcome = compile(
            candidateSource(
                declarations = layout(
                    target = "MissingCodec",
                    id = "org.quicklauncher.samples/missing-codec",
                    config = "missing-codec-config",
                    codec = null,
                ),
            ),
        )

        assertFailure(outcome, "registry.missing-codec", "MissingCodec", "configuration codec")
    }

    @Test
    fun `configuration codec with the wrong contract fails compilation`() {
        val outcome = compile(
            candidateSource(
                extra = "object WrongCodec",
                declarations = layout(
                    target = "WrongCodecContribution",
                    id = "org.quicklauncher.samples/wrong-codec",
                    config = "wrong-codec-config",
                    codec = "WrongCodec",
                ),
            ),
        )

        assertFailure(outcome, "registry.codec-contract", "WrongCodecContribution", "does not implement ConfigurationCodec")
    }

    @Test
    fun `invalid slot type ID fails compilation`() {
        val outcome = compile(
            candidateSource(
                declarations = layout(
                    target = "InvalidSlot",
                    id = "org.quicklauncher.samples/invalid-slot",
                    config = "invalid-slot-config",
                    slotType = "not-namespaced",
                ),
            ),
        )

        assertFailure(outcome, "registry.invalid-slot-type", "InvalidSlot", "not-namespaced")
    }

    @Test
    fun `invalid settings schema fails compilation`() {
        val outcome = compile(
            candidateSource(
                extra = """
                    @SettingsSchemaSpec(
                        fields = [
                            SettingSpec(
                                key = "columns",
                                label = "Columns",
                                kind = SettingKind.NUMBER,
                                defaultValue = "20",
                                minimum = 2,
                                maximum = 8,
                            ),
                        ],
                    )
                    object InvalidSettings
                """.trimIndent(),
                declarations = layout(
                    target = "InvalidSchema",
                    id = "org.quicklauncher.samples/invalid-schema",
                    config = "invalid-schema-config",
                    settings = "InvalidSettings",
                ),
            ),
        )

        assertFailure(outcome, "registry.invalid-settings-schema", "InvalidSchema", "outside its range")
    }

    @Test
    fun `capability dependency cycle fails compilation`() {
        val outcome = compile(
            candidateSource(
                declarations = layout(
                    "FirstCycle",
                    "org.quicklauncher.samples/first-cycle",
                    "first-cycle-config",
                    provided = "org.quicklauncher.capability/first",
                    required = "org.quicklauncher.capability/second",
                ) + "\n" + layout(
                    "SecondCycle",
                    "org.quicklauncher.samples/second-cycle",
                    "second-cycle-config",
                    provided = "org.quicklauncher.capability/second",
                    required = "org.quicklauncher.capability/first",
                ),
            ),
        )

        assertFailure(outcome, "registry.capability-cycle", "FirstCycle", "first -> org.quicklauncher.capability/second")
    }

    @Test
    fun `missing mandatory contract tests fail compilation`() {
        val outcome = compile(
            candidateSource(
                declarations = layout(
                    target = "MissingTests",
                    id = "org.quicklauncher.samples/missing-tests",
                    config = "missing-tests-config",
                    tests = null,
                ),
            ),
        )

        assertFailure(outcome, "registry.missing-contract-tests", "MissingTests", "mandatory contract tests")
    }

    @Test
    fun `registration target must implement its declared contribution contract`() {
        val outcome = compile(
            candidateSource(
                declarations = layout(
                    target = "WrongTarget",
                    id = "org.quicklauncher.samples/wrong-target",
                    config = "wrong-target-config",
                    targetContract = false,
                ),
            ),
        )

        assertFailure(outcome, "registry.target-contract", "WrongTarget", "LayoutContribution")
    }

    @Test
    fun `target and codec configuration generic mismatch fails compilation`() {
        val outcome = compile(
            candidateSource(
                declarations = layout(
                    target = "GenericMismatch",
                    id = "org.quicklauncher.samples/generic-mismatch",
                    config = "generic-mismatch-config",
                    codecConfigurationType = "OtherConfig",
                ),
            ),
        )

        assertFailure(
            outcome,
            "registry.configuration-type-mismatch",
            "GenericMismatch",
            "CandidateConfig",
        )
    }

    @Test
    fun `codec and descriptor configuration IDs mismatch fails compilation`() {
        val outcome = compile(
            candidateSource(
                declarations = layout(
                    target = "CodecIdMismatch",
                    id = "org.quicklauncher.samples/codec-id-mismatch",
                    config = "codec-id-mismatch-config",
                    codecManifestConfigTypeId = "org.quicklauncher.samples/different-codec-id",
                ),
            ),
        )

        assertFailure(outcome, "registry.codec-config-type-mismatch", "CodecIdMismatch", "different-codec-id")
    }

    @Test
    fun `contract declaration and descriptor contribution IDs mismatch fails compilation`() {
        val outcome = compile(
            candidateSource(
                declarations = layout(
                    target = "ContractIdMismatch",
                    id = "org.quicklauncher.samples/contract-id-mismatch",
                    config = "contract-id-mismatch-config",
                    testsContributionId = "org.quicklauncher.samples/different-contract-id",
                ),
            ),
        )

        assertFailure(outcome, "registry.contract-tests-id-mismatch", "ContractIdMismatch", "different-contract-id")
    }

    @Test
    fun `missing required scenarios fail compilation`() {
        val outcome = compile(
            candidateSource(
                declarations = layout(
                    target = "MissingScenario",
                    id = "org.quicklauncher.samples/missing-scenario",
                    config = "missing-scenario-config",
                    testScenarios = listOf("EMPTY", "NORMAL"),
                ),
            ),
        )

        assertFailure(outcome, "registry.contract-tests-missing-scenarios", "MissingScenario", "exactly")
    }

    @Test
    fun `missing category performance hooks fail compilation`() {
        val outcome = compile(
            candidateSource(
                declarations = layout(
                    target = "MissingHook",
                    id = "org.quicklauncher.samples/missing-hook",
                    config = "missing-hook-config",
                    testMetrics = listOf("FIRST_RENDER", "ACTION_DISPATCH"),
                ),
            ),
        )

        assertFailure(outcome, "registry.contract-tests-missing-performance-hooks", "MissingHook", "DISPOSAL")
    }

    @Test
    fun `wrong contract suite category fails compilation`() {
        val outcome = compile(
            candidateSource(
                declarations = layout(
                    target = "WrongSuiteCategory",
                    id = "org.quicklauncher.samples/wrong-suite-category",
                    config = "wrong-suite-category-config",
                    tests = "BlockSuite",
                ),
                extra = """
                    @ContractTestSpec(
                        contributionId = "org.quicklauncher.samples/wrong-suite-category",
                        category = ContributionCategorySpec.BLOCK,
                        scenarios = [
                            PreviewScenarioSpec.EMPTY,
                            PreviewScenarioSpec.NORMAL,
                            PreviewScenarioSpec.LOADING,
                            PreviewScenarioSpec.PERMISSION_DENIED,
                            PreviewScenarioSpec.PROFILE_LOCKED,
                            PreviewScenarioSpec.LARGE_TEXT,
                            PreviewScenarioSpec.ERROR,
                        ],
                        performanceHooks = [
                            PerformanceMetricSpec.FIRST_RENDER,
                            PerformanceMetricSpec.ACTION_DISPATCH,
                            PerformanceMetricSpec.DISPOSAL,
                        ],
                    )
                    object BlockSuite : BlockContractTestDeclaration<CandidateConfig> {
                        override val contributionId = ContributionId.parse(
                            "org.quicklauncher.samples/wrong-suite-category",
                        )
                        override val scenarios = PreviewScenario.entries.toSet()
                        override val performanceHooks = emptyList<PerformanceHookDeclaration>()
                    }
                """.trimIndent(),
            ),
        )

        assertFailure(outcome, "registry.contract-tests-category", "WrongSuiteCategory", "LayoutContractTestDeclaration")
    }

    @Test
    fun `contract suite configuration generic mismatch fails compilation`() {
        val outcome = compile(
            candidateSource(
                declarations = layout(
                    target = "SuiteGenericMismatch",
                    id = "org.quicklauncher.samples/suite-generic-mismatch",
                    config = "suite-generic-mismatch-config",
                    testsConfigurationType = "OtherConfig",
                ),
            ),
        )

        assertFailure(
            outcome,
            "registry.contract-tests-configuration-type",
            "SuiteGenericMismatch",
            "OtherConfig",
        )
    }

    @Test
    fun `slot type incompatibility fails compilation`() {
        val outcome = compile(
            candidateSource(
                declarations = layout(
                    target = "TypeParent",
                    id = "org.quicklauncher.samples/type-parent",
                    config = "type-parent-config",
                    slotType = "org.quicklauncher.slot/content",
                    acceptedBlocks = listOf("org.quicklauncher.samples/type-child"),
                ) + "\n" + block(
                    target = "TypeChild",
                    id = "org.quicklauncher.samples/type-child",
                    config = "type-child-config",
                    compatibleSlotTypes = listOf("org.quicklauncher.slot/other"),
                ),
            ),
        )

        assertFailure(outcome, "registry.unsupported-slot-nesting", "TypeParent", "incompatible")
    }

    @Test
    fun `slot capability incompatibility fails compilation`() {
        val outcome = compile(
            candidateSource(
                declarations = layout(
                    target = "CapabilityParent",
                    id = "org.quicklauncher.samples/capability-parent",
                    config = "capability-parent-config",
                    slotType = "org.quicklauncher.slot/content",
                    slotRequiredCapabilities = listOf("org.quicklauncher.capability/needed"),
                    acceptedBlocks = listOf("org.quicklauncher.samples/capability-child"),
                ) + "\n" + block(
                    target = "CapabilityChild",
                    id = "org.quicklauncher.samples/capability-child",
                    config = "capability-child-config",
                    compatibleSlotTypes = listOf("org.quicklauncher.slot/content"),
                ),
            ),
        )

        assertFailure(outcome, "registry.unsupported-slot-nesting", "CapabilityParent", "incompatible")
    }

    @Test
    fun `slot scroll-axis incompatibility fails compilation`() {
        val outcome = compile(
            candidateSource(
                declarations = layout(
                    target = "ScrollParent",
                    id = "org.quicklauncher.samples/scroll-parent",
                    config = "scroll-parent-config",
                    slotType = "org.quicklauncher.slot/content",
                    slotAllowedScrollAxes = listOf("VERTICAL"),
                    acceptedBlocks = listOf("org.quicklauncher.samples/scroll-child"),
                ) + "\n" + block(
                    target = "ScrollChild",
                    id = "org.quicklauncher.samples/scroll-child",
                    config = "scroll-child-config",
                    compatibleSlotTypes = listOf("org.quicklauncher.slot/content"),
                    occupiedScrollAxes = listOf("HORIZONTAL"),
                ),
            ),
        )

        assertFailure(outcome, "registry.unsupported-slot-nesting", "ScrollParent", "incompatible")
    }

    @Test
    fun `block self nesting cycle fails compilation`() {
        val outcome = compile(
            candidateSource(
                declarations = block(
                    target = "RecursiveBlock",
                    id = "org.quicklauncher.samples/recursive-block",
                    config = "recursive-block-config",
                    compatibleSlotTypes = listOf("org.quicklauncher.slot/nested"),
                    childSlotType = "org.quicklauncher.slot/nested",
                    acceptedBlocks = listOf("org.quicklauncher.samples/recursive-block"),
                ),
            ),
        )

        assertFailure(outcome, "registry.block-nesting-cycle", "RecursiveBlock", "recursive-block ->")
    }

    @Test
    fun `two-node block nesting cycle fails compilation`() {
        val outcome = compile(
            candidateSource(
                declarations = block(
                    target = "FirstNestedBlock",
                    id = "org.quicklauncher.samples/first-nested-block",
                    config = "first-nested-block-config",
                    compatibleSlotTypes = listOf("org.quicklauncher.slot/first"),
                    childSlotType = "org.quicklauncher.slot/second",
                    acceptedBlocks = listOf("org.quicklauncher.samples/second-nested-block"),
                ) + "\n" + block(
                    target = "SecondNestedBlock",
                    id = "org.quicklauncher.samples/second-nested-block",
                    config = "second-nested-block-config",
                    compatibleSlotTypes = listOf("org.quicklauncher.slot/second"),
                    childSlotType = "org.quicklauncher.slot/first",
                    acceptedBlocks = listOf("org.quicklauncher.samples/first-nested-block"),
                ),
            ),
        )

        assertFailure(
            outcome,
            "registry.block-nesting-cycle",
            "FirstNestedBlock",
            "first-nested-block -> org.quicklauncher.samples/second-nested-block",
        )
    }

    @Test
    fun `valid acyclic block nesting compiles`() {
        val outcome = compile(
            candidateSource(
                declarations = layout(
                    target = "AcyclicLayout",
                    id = "org.quicklauncher.samples/acyclic-layout",
                    config = "acyclic-layout-config",
                    slotType = "org.quicklauncher.slot/root",
                    acceptedBlocks = listOf("org.quicklauncher.samples/acyclic-parent"),
                ) + "\n" + block(
                    target = "AcyclicParent",
                    id = "org.quicklauncher.samples/acyclic-parent",
                    config = "acyclic-parent-config",
                    compatibleSlotTypes = listOf("org.quicklauncher.slot/root"),
                    childSlotType = "org.quicklauncher.slot/nested",
                    acceptedBlocks = listOf("org.quicklauncher.samples/acyclic-leaf"),
                ) + "\n" + block(
                    target = "AcyclicLeaf",
                    id = "org.quicklauncher.samples/acyclic-leaf",
                    config = "acyclic-leaf-config",
                    compatibleSlotTypes = listOf("org.quicklauncher.slot/nested"),
                ),
            ),
        )

        assertEquals(outcome.messages, KotlinCompilation.ExitCode.OK, outcome.result.exitCode)
        val generated = outcome.generatedRegistry()
        assertTrue(generated.contains("target = compiletest.AcyclicLayout"))
        assertTrue(generated.contains("target = compiletest.AcyclicParent"))
        assertTrue(generated.contains("target = compiletest.AcyclicLeaf"))
    }

    @Test
    fun `generic configuration bindings resolve through inherited contracts`() {
        val outcome = compile(inheritedGenericSource())

        assertEquals(outcome.messages, KotlinCompilation.ExitCode.OK, outcome.result.exitCode)
        assertTrue(outcome.generatedRegistry().contains("target = compiletest.InheritedLayout"))
    }

    private fun compile(vararg sources: SourceFile): CompilationOutcome {
        return compileWithOptions(emptyMap(), *sources)
    }

    private fun compileWithOptions(
        processorOptions: Map<String, String>,
        vararg sources: SourceFile,
    ): CompilationOutcome {
        return compileWithClasspaths(emptyList(), processorOptions, *sources)
    }

    private fun compileWithClasspaths(
        additionalClasspaths: List<java.io.File>,
        processorOptions: Map<String, String> = emptyMap(),
        vararg sources: SourceFile,
    ): CompilationOutcome {
        val messages = ByteArrayOutputStream()
        val compilation = KotlinCompilation().apply {
            this.sources = sources.toList()
            inheritClassPath = true
            classpaths = additionalClasspaths
            messageOutputStream = messages
            configureKsp {
                symbolProcessorProviders += ContributionRegistryProcessorProvider()
                this.processorOptions.putAll(processorOptions)
                withCompilation = true
            }
        }
        return CompilationOutcome(compilation.compile(), messages.toString())
    }

    private fun compiledFragmentSource(): SourceFile = SourceFile.kotlin(
        "CompiledFragment.kt",
        """
        package compiledfragment

        import org.quicklauncher.contracts.contribution.*
        import org.quicklauncher.contracts.domain.*
        import org.quicklauncher.registry.annotations.*

        @ContributionRegistryFragmentManifest(
            fragmentId = "org.quicklauncher.fragment/compiled",
            entries = [
                ContributionRegistryFragmentEntry(
                    index = 0,
                    contributionId = "org.quicklauncher.samples/compiled",
                    configTypeId = "org.quicklauncher.samples/compiled-config",
                    categoryTypeId = "org.quicklauncher.contribution/layout",
                ),
            ],
        )
        object CompiledFragment : ContributionRegistry {
            override val categoryIds = emptySet<ContributionTypeId>()
            override val entries = emptyList<RegisteredContribution<*>>()
        }
        """.trimIndent(),
    )

    private fun aggregationSource(
        reverseFragments: Boolean = false,
        duplicateContributionId: Boolean = false,
        duplicateConfigTypeId: Boolean = false,
        capabilityCycle: Boolean = false,
        blockCycle: Boolean = false,
        incompatibleSlot: Boolean = false,
        unsupportedMajor: Boolean = false,
    ): SourceFile {
        val fragments = if (reverseFragments) "LayoutFragment::class, BlockFragment::class" else
            "BlockFragment::class, LayoutFragment::class"
        val blockId = if (duplicateContributionId) "org.quicklauncher.samples/layout-a" else
            "org.quicklauncher.samples/block-a"
        val blockConfig = if (duplicateConfigTypeId) "org.quicklauncher.samples/layout-a-config" else
            "org.quicklauncher.samples/block-a-config"
        val layoutProvided = if (capabilityCycle) "[\"org.quicklauncher.capability/first\"]" else "[]"
        val layoutRequired = if (capabilityCycle) "[\"org.quicklauncher.capability/second\"]" else "[]"
        val blockProvided = if (capabilityCycle) "[\"org.quicklauncher.capability/second\"]" else "[]"
        val blockRequired = if (capabilityCycle) "[\"org.quicklauncher.capability/first\"]" else "[]"
        val layoutChildren = if (blockCycle) {
            "[ContributionRegistryFragmentSlot(type = \"org.quicklauncher.slot/content\", " +
                "acceptedBlocks = [\"org.quicklauncher.samples/block-a\"])]"
        } else "[]"
        val blockChildren = if (blockCycle) {
            "[ContributionRegistryFragmentSlot(type = \"org.quicklauncher.slot/content\", " +
                "acceptedBlocks = [\"org.quicklauncher.samples/layout-a\"])]"
        } else "[]"
        val layoutCategory = if (blockCycle) "org.quicklauncher.contribution/block" else
            "org.quicklauncher.contribution/layout"
        val layoutCompatible = if (blockCycle) "[\"org.quicklauncher.slot/content\"]" else "[]"
        val blockCompatible = if (incompatibleSlot) "[\"org.quicklauncher.slot/other\"]" else
            "[\"org.quicklauncher.slot/content\"]"
        val blockMajor = if (unsupportedMajor) 2 else 1
        val layoutSlots = if (incompatibleSlot) {
            "[ContributionRegistryFragmentSlot(type = \"org.quicklauncher.slot/content\", " +
                "acceptedBlocks = [\"org.quicklauncher.samples/block-a\"])]"
        } else layoutChildren
        return SourceFile.kotlin(
            "Aggregation.kt",
            """
            package compiletest

            import org.quicklauncher.contracts.contribution.*
            import org.quicklauncher.contracts.domain.*
            import org.quicklauncher.registry.annotations.*

            @ContributionRegistryFragmentManifest(
                fragmentId = "org.quicklauncher.fragment/layout",
                entries = [
                    ContributionRegistryFragmentEntry(
                        index = 1,
                        contributionId = "org.quicklauncher.samples/layout-z",
                        configTypeId = "org.quicklauncher.samples/layout-z-config",
                        categoryTypeId = "org.quicklauncher.contribution/layout",
                    ),
                    ContributionRegistryFragmentEntry(
                        index = 0,
                        contributionId = "org.quicklauncher.samples/layout-a",
                        configTypeId = "org.quicklauncher.samples/layout-a-config",
                        categoryTypeId = "$layoutCategory",
                        providedCapabilities = $layoutProvided,
                        requiredCapabilities = $layoutRequired,
                        compatibleSlotTypes = $layoutCompatible,
                        childSlots = $layoutSlots,
                    ),
                ],
            )
            object LayoutFragment : ContributionRegistry {
                override val categoryIds = emptySet<ContributionTypeId>()
                override val entries = emptyList<RegisteredContribution<*>>()
            }

            @ContributionRegistryFragmentManifest(
                fragmentId = "org.quicklauncher.fragment/block",
                entries = [
                    ContributionRegistryFragmentEntry(
                        index = 0,
                        contributionId = "$blockId",
                        configTypeId = "$blockConfig",
                        categoryTypeId = "org.quicklauncher.contribution/block",
                        contractMajor = $blockMajor,
                        providedCapabilities = $blockProvided,
                        requiredCapabilities = $blockRequired,
                        compatibleSlotTypes = $blockCompatible,
                        childSlots = $blockChildren,
                    ),
                ],
            )
            object BlockFragment : ContributionRegistry {
                override val categoryIds = emptySet<ContributionTypeId>()
                override val entries = emptyList<RegisteredContribution<*>>()
            }

            @AggregateContributionRegistry(
                fragments = [$fragments],
                packageName = "compiletest.generated",
                registryName = "ApplicationContributionRegistry",
            )
            object AggregateApplicationRegistry
            """.trimIndent(),
        )
    }

    private fun assertFailure(
        outcome: CompilationOutcome,
        code: String,
        target: String,
        reason: String,
    ) {
        assertEquals(outcome.messages, KotlinCompilation.ExitCode.COMPILATION_ERROR, outcome.result.exitCode)
        assertTrue(outcome.messages, outcome.messages.contains("[$code]"))
        assertTrue(outcome.messages, outcome.messages.contains(target))
        assertTrue(outcome.messages, outcome.messages.contains(reason))
    }

    private fun candidateSource(
        declarations: String,
        extra: String = "",
    ): SourceFile = SourceFile.kotlin(
        "Candidate.kt",
        """
        package compiletest

        import org.quicklauncher.contracts.contribution.*
        import org.quicklauncher.contracts.domain.*
        import org.quicklauncher.contracts.ui.*
        import org.quicklauncher.registry.annotations.*

        data class CandidateConfig(val enabled: Boolean)
        data class OtherConfig(val enabled: Boolean)

        $extra

        $declarations
        """.trimIndent(),
    )

    private fun layout(
        target: String,
        id: String,
        config: String,
        configTypeId: String = "org.quicklauncher.samples/$config",
        contractMajor: Int = 1,
        displayName: String = target,
        codec: String? = "CandidateCodec",
        tests: String? = "CandidateTests",
        settings: String? = null,
        provided: String? = null,
        required: String? = null,
        targetContract: Boolean = true,
        slotType: String? = null,
        targetConfigurationType: String = "CandidateConfig",
        codecConfigurationType: String = "CandidateConfig",
        codecManifestConfigTypeId: String = configTypeId,
        testsConfigurationType: String = "CandidateConfig",
        testsContributionId: String = id,
        testsCategory: String = "LAYOUT",
        testScenarios: List<String> = listOf(
            "EMPTY",
            "NORMAL",
            "LOADING",
            "PERMISSION_DENIED",
            "PROFILE_LOCKED",
            "LARGE_TEXT",
            "ERROR",
        ),
        testMetrics: List<String> = listOf("FIRST_RENDER", "ACTION_DISPATCH", "DISPOSAL"),
        acceptedBlocks: List<String> = emptyList(),
        slotRequiredCapabilities: List<String> = emptyList(),
        slotAllowedScrollAxes: List<String> = emptyList(),
    ): String {
        val resolvedCodec = if (codec == "CandidateCodec") "${target}Codec" else codec
        val resolvedTests = if (tests == "CandidateTests") "${target}Tests" else tests
        val optionalArgumentValues = buildList {
            resolvedCodec?.let { add("codec = $it::class") }
            resolvedTests?.let { add("contractTests = $it::class") }
            settings?.let { add("settings = $it::class") }
            provided?.let { add("providedCapabilities = [\"$it\"]") }
            required?.let { add("requiredCapabilities = [\"$it\"]") }
            slotType?.let {
                val accepted = acceptedBlocks.joinToString(", ") { id -> "\"$id\"" }
                val slotCapabilities = slotRequiredCapabilities.joinToString(", ") { id -> "\"$id\"" }
                val scrollAxes = slotAllowedScrollAxes.joinToString(", ") { axis -> "ScrollAxisSpec.$axis" }
                add(
                    "slots = [SlotSpec(id = \"main\", type = \"$it\", " +
                        "acceptedBlocks = [$accepted], requiredCapabilities = [$slotCapabilities], " +
                        "allowedScrollAxes = [$scrollAxes])]",
                )
            }
        }
        val optionalArguments = optionalArgumentValues.joinToString(
            separator = ",\n        ",
            postfix = if (optionalArgumentValues.isNotEmpty()) "," else "",
        )
        val contract = if (targetContract) {
            ": LayoutContribution<$targetConfigurationType> {\n" +
                "    override fun open(context: ContributionContext<$targetConfigurationType>): LayoutSession = error(\"fixture\")\n}"
        } else {
            ""
        }
        val codecDeclaration = if (codec == "CandidateCodec") {
            """
            @ConfigurationCodecSpec(configTypeId = "$codecManifestConfigTypeId")
            object ${target}Codec : ConfigurationCodec<$codecConfigurationType> {
                override val configType = ConfigTypeId.parse("$codecManifestConfigTypeId")
                override val currentSchemaVersion = SchemaVersion.of(1)
                override val default = $codecConfigurationType(true)
                override fun encode(value: $codecConfigurationType) = EncodedConfiguration.of(value.enabled.toString())
                override fun decode(encoded: EncodedConfiguration): CodecResult<$codecConfigurationType> =
                    CodecResult.Decoded($codecConfigurationType(encoded.value.toBooleanStrict()))
            }
            """.trimIndent()
        } else {
            ""
        }
        val testsDeclaration = if (tests == "CandidateTests") {
            val scenarios = testScenarios.joinToString(", ") { "PreviewScenarioSpec.$it" }
            val metrics = testMetrics.joinToString(", ") { "PerformanceMetricSpec.$it" }
            """
            @ContractTestSpec(
                contributionId = "$testsContributionId",
                category = ContributionCategorySpec.$testsCategory,
                scenarios = [$scenarios],
                performanceHooks = [$metrics],
            )
            object ${target}Tests : LayoutContractTestDeclaration<$testsConfigurationType> {
                override val contributionId = ContributionId.parse("$testsContributionId")
                override val scenarios = PreviewScenario.entries.toSet()
                override val performanceHooks = listOf(
                    PerformanceHookDeclaration(PerformanceMetric.FIRST_RENDER, "Measures candidate work"),
                    PerformanceHookDeclaration(PerformanceMetric.ACTION_DISPATCH, "Measures candidate work"),
                    PerformanceHookDeclaration(PerformanceMetric.DISPOSAL, "Measures candidate work"),
                )
            }
            """.trimIndent()
        } else {
            ""
        }
        return """
        $codecDeclaration

        $testsDeclaration

        @RegisterLayout(
            id = "$id",
            contractMajor = $contractMajor,
            configTypeId = "$configTypeId",
            displayName = "$displayName",
            description = "Candidate layout",
            $optionalArguments
        )
        object $target $contract
        """.trimIndent()
    }

    private fun block(
        target: String,
        id: String,
        config: String,
        compatibleSlotTypes: List<String>,
        childSlotType: String? = null,
        acceptedBlocks: List<String> = emptyList(),
        providedCapabilities: List<String> = emptyList(),
        childRequiredCapabilities: List<String> = emptyList(),
        childAllowedScrollAxes: List<String> = emptyList(),
        occupiedScrollAxes: List<String> = emptyList(),
    ): String {
        val configTypeId = "org.quicklauncher.samples/$config"
        val compatible = compatibleSlotTypes.joinToString(", ") { "\"$it\"" }
        val provided = providedCapabilities.joinToString(", ") { "\"$it\"" }
        val occupied = occupiedScrollAxes.joinToString(", ") { "ScrollAxisSpec.$it" }
        val childSlot = childSlotType?.let { type ->
            val accepted = acceptedBlocks.joinToString(", ") { "\"$it\"" }
            val required = childRequiredCapabilities.joinToString(", ") { "\"$it\"" }
            val allowed = childAllowedScrollAxes.joinToString(", ") { "ScrollAxisSpec.$it" }
            "[SlotSpec(id = \"nested\", type = \"$type\", acceptedBlocks = [$accepted], " +
                "requiredCapabilities = [$required], allowedScrollAxes = [$allowed])]"
        } ?: "[]"
        return """
        @ConfigurationCodecSpec(configTypeId = "$configTypeId")
        object ${target}Codec : ConfigurationCodec<CandidateConfig> {
            override val configType = ConfigTypeId.parse("$configTypeId")
            override val currentSchemaVersion = SchemaVersion.of(1)
            override val default = CandidateConfig(true)
            override fun encode(value: CandidateConfig) = EncodedConfiguration.of(value.enabled.toString())
            override fun decode(encoded: EncodedConfiguration): CodecResult<CandidateConfig> =
                CodecResult.Decoded(CandidateConfig(encoded.value.toBooleanStrict()))
        }

        @ContractTestSpec(
            contributionId = "$id",
            category = ContributionCategorySpec.BLOCK,
            scenarios = [
                PreviewScenarioSpec.EMPTY,
                PreviewScenarioSpec.NORMAL,
                PreviewScenarioSpec.LOADING,
                PreviewScenarioSpec.PERMISSION_DENIED,
                PreviewScenarioSpec.PROFILE_LOCKED,
                PreviewScenarioSpec.LARGE_TEXT,
                PreviewScenarioSpec.ERROR,
            ],
            performanceHooks = [
                PerformanceMetricSpec.FIRST_RENDER,
                PerformanceMetricSpec.ACTION_DISPATCH,
                PerformanceMetricSpec.DISPOSAL,
            ],
        )
        object ${target}Tests : BlockContractTestDeclaration<CandidateConfig> {
            override val contributionId = ContributionId.parse("$id")
            override val scenarios = PreviewScenario.entries.toSet()
            override val performanceHooks = listOf(
                PerformanceHookDeclaration(PerformanceMetric.FIRST_RENDER, "Measures block work"),
                PerformanceHookDeclaration(PerformanceMetric.ACTION_DISPATCH, "Measures block work"),
                PerformanceHookDeclaration(PerformanceMetric.DISPOSAL, "Measures block work"),
            )
        }

        @RegisterBlock(
            id = "$id",
            contractMajor = 1,
            configTypeId = "$configTypeId",
            displayName = "$target",
            description = "Candidate block",
            providedCapabilities = [$provided],
            codec = ${target}Codec::class,
            contractTests = ${target}Tests::class,
            compatibleSlotTypes = [$compatible],
            childSlots = $childSlot,
            occupiedScrollAxes = [$occupied],
        )
        object $target : BlockContribution<CandidateConfig> {
            override fun open(context: ContributionContext<CandidateConfig>): BlockSession = error("fixture")
        }
        """.trimIndent()
    }

    private fun inheritedGenericSource(): SourceFile = candidateSource(
        declarations = """
            abstract class GenericLayout<C : Any> : LayoutContribution<C>

            abstract class GenericCodec<C : Any> : ConfigurationCodec<C>

            abstract class GenericLayoutTests<C : Any> : LayoutContractTestDeclaration<C>

            @ConfigurationCodecSpec(configTypeId = "org.quicklauncher.samples/inherited-config")
            object InheritedCodec : GenericCodec<CandidateConfig>() {
                override val configType = ConfigTypeId.parse("org.quicklauncher.samples/inherited-config")
                override val currentSchemaVersion = SchemaVersion.of(1)
                override val default = CandidateConfig(true)
                override fun encode(value: CandidateConfig) = EncodedConfiguration.of(value.enabled.toString())
                override fun decode(encoded: EncodedConfiguration): CodecResult<CandidateConfig> =
                    CodecResult.Decoded(CandidateConfig(encoded.value.toBooleanStrict()))
            }

            @ContractTestSpec(
                contributionId = "org.quicklauncher.samples/inherited-layout",
                category = ContributionCategorySpec.LAYOUT,
                scenarios = [
                    PreviewScenarioSpec.EMPTY,
                    PreviewScenarioSpec.NORMAL,
                    PreviewScenarioSpec.LOADING,
                    PreviewScenarioSpec.PERMISSION_DENIED,
                    PreviewScenarioSpec.PROFILE_LOCKED,
                    PreviewScenarioSpec.LARGE_TEXT,
                    PreviewScenarioSpec.ERROR,
                ],
                performanceHooks = [
                    PerformanceMetricSpec.FIRST_RENDER,
                    PerformanceMetricSpec.ACTION_DISPATCH,
                    PerformanceMetricSpec.DISPOSAL,
                ],
            )
            object InheritedTests : GenericLayoutTests<CandidateConfig>() {
                override val contributionId = ContributionId.parse("org.quicklauncher.samples/inherited-layout")
                override val scenarios = PreviewScenario.entries.toSet()
                override val performanceHooks = listOf(
                    PerformanceHookDeclaration(PerformanceMetric.FIRST_RENDER, "Measures inherited work"),
                    PerformanceHookDeclaration(PerformanceMetric.ACTION_DISPATCH, "Measures inherited work"),
                    PerformanceHookDeclaration(PerformanceMetric.DISPOSAL, "Measures inherited work"),
                )
            }

            @RegisterLayout(
                id = "org.quicklauncher.samples/inherited-layout",
                contractMajor = 1,
                configTypeId = "org.quicklauncher.samples/inherited-config",
                displayName = "Inherited layout",
                description = "Inherited generic contracts",
                codec = InheritedCodec::class,
                contractTests = InheritedTests::class,
            )
            object InheritedLayout : GenericLayout<CandidateConfig>() {
                override fun open(context: ContributionContext<CandidateConfig>): LayoutSession = error("fixture")
            }
        """.trimIndent(),
    )

    private fun validFiveSource(reverse: Boolean = false): SourceFile {
        val registrations = listOf(
            """
            @RegisterLayout(
                id = "org.quicklauncher.samples/layout",
                contractMajor = 1,
                configTypeId = "org.quicklauncher.samples/layout-config",
                displayName = "Layout",
                description = "Sample layout",
                settings = SampleSettings::class,
                codec = LayoutCodec::class,
                contractTests = LayoutTests::class,
                slots = [SlotSpec(
                    id = "main",
                    type = "org.quicklauncher.slot/content",
                    acceptedBlocks = ["org.quicklauncher.samples/block"],
                )],
            )
            object SampleLayout : LayoutContribution<SampleConfig> {
                override fun open(context: ContributionContext<SampleConfig>): LayoutSession = error("fixture")
            }
            """.trimIndent(),
            """
            @RegisterBlock(
                id = "org.quicklauncher.samples/block",
                contractMajor = 1,
                configTypeId = "org.quicklauncher.samples/block-config",
                displayName = "Block",
                description = "Sample block",
                codec = BlockCodec::class,
                contractTests = BlockTests::class,
                compatibleSlotTypes = ["org.quicklauncher.slot/content"],
            )
            object SampleBlock : BlockContribution<SampleConfig> {
                override fun open(context: ContributionContext<SampleConfig>): BlockSession = error("fixture")
            }
            """.trimIndent(),
            """
            @RegisterSearchProvider(
                id = "org.quicklauncher.samples/search",
                contractMajor = 1,
                configTypeId = "org.quicklauncher.samples/search-config",
                displayName = "Search",
                description = "Sample search provider",
                codec = SearchCodec::class,
                contractTests = SearchTests::class,
                resultKinds = [SearchResultKindSpec.APP],
            )
            object SampleSearch : SearchProviderContribution<SampleConfig> {
                override fun open(context: ContributionContext<SampleConfig>): SearchProviderSession = error("fixture")
            }
            """.trimIndent(),
            """
            @RegisterLauncherCommand(
                id = "org.quicklauncher.samples/command",
                contractMajor = 1,
                configTypeId = "org.quicklauncher.samples/command-config",
                displayName = "Command",
                description = "Sample command",
                codec = CommandCodec::class,
                contractTests = CommandTests::class,
                contexts = [CommandContextSpec.SEARCH],
                resultKinds = [CommandResultKindSpec.HOST_ACTION],
            )
            object SampleCommand : LauncherCommandContribution<SampleConfig> {
                override suspend fun execute(input: CommandInput<SampleConfig>): CommandResult = CommandResult.Cancelled
            }
            """.trimIndent(),
            """
            @RegisterDestinationTemplate(
                id = "org.quicklauncher.samples/template",
                contractMajor = 1,
                configTypeId = "org.quicklauncher.samples/template-config",
                displayName = "Template",
                description = "Sample destination template",
                codec = TemplateCodec::class,
                contractTests = TemplateTests::class,
                maximumBlocks = 1,
            )
            object SampleTemplate : DestinationTemplateContribution<SampleConfig> {
                override fun create(input: TemplateInput<SampleConfig>): TemplateResult = error("fixture")
            }
            """.trimIndent(),
        ).let { if (reverse) it.reversed() else it }
        return SourceFile.kotlin(
            "Samples.kt",
            """
            package compiletest

            import org.quicklauncher.contracts.contribution.*
            import org.quicklauncher.contracts.domain.*
            import org.quicklauncher.contracts.ui.*
            import org.quicklauncher.registry.annotations.*

            data class SampleConfig(val enabled: Boolean)

            abstract class SampleCodec(configTypeId: String) : ConfigurationCodec<SampleConfig> {
                final override val configType = ConfigTypeId.parse(configTypeId)
                override val currentSchemaVersion = SchemaVersion.of(2)
                override val default = SampleConfig(true)
                override fun encode(value: SampleConfig) = EncodedConfiguration.of(value.enabled.toString())
                override fun decode(encoded: EncodedConfiguration): CodecResult<SampleConfig> =
                    CodecResult.Decoded(SampleConfig(encoded.value.toBooleanStrict()))
            }

            @ConfigurationCodecSpec(configTypeId = "org.quicklauncher.samples/layout-config")
            object LayoutCodec : SampleCodec("org.quicklauncher.samples/layout-config")
            @ConfigurationCodecSpec(configTypeId = "org.quicklauncher.samples/block-config")
            object BlockCodec : SampleCodec("org.quicklauncher.samples/block-config")
            @ConfigurationCodecSpec(configTypeId = "org.quicklauncher.samples/search-config")
            object SearchCodec : SampleCodec("org.quicklauncher.samples/search-config")
            @ConfigurationCodecSpec(configTypeId = "org.quicklauncher.samples/command-config")
            object CommandCodec : SampleCodec("org.quicklauncher.samples/command-config")
            @ConfigurationCodecSpec(configTypeId = "org.quicklauncher.samples/template-config")
            object TemplateCodec : SampleCodec("org.quicklauncher.samples/template-config")

            abstract class SampleTests<C : Any>(
                id: String,
                metrics: Set<PerformanceMetric>,
            ) : ContributionContractDeclaration<C> {
                final override val contributionId = ContributionId.parse(id)
                override val scenarios = PreviewScenario.entries.toSet()
                override val performanceHooks = metrics.map {
                    PerformanceHookDeclaration(it, "Measures sample work")
                }
            }

            @ContractTestSpec(
                contributionId = "org.quicklauncher.samples/layout",
                category = ContributionCategorySpec.LAYOUT,
                scenarios = [PreviewScenarioSpec.EMPTY, PreviewScenarioSpec.NORMAL, PreviewScenarioSpec.LOADING,
                    PreviewScenarioSpec.PERMISSION_DENIED, PreviewScenarioSpec.PROFILE_LOCKED,
                    PreviewScenarioSpec.LARGE_TEXT, PreviewScenarioSpec.ERROR],
                performanceHooks = [PerformanceMetricSpec.FIRST_RENDER, PerformanceMetricSpec.ACTION_DISPATCH,
                    PerformanceMetricSpec.DISPOSAL],
            )
            object LayoutTests : SampleTests<SampleConfig>(
                "org.quicklauncher.samples/layout",
                setOf(PerformanceMetric.FIRST_RENDER, PerformanceMetric.ACTION_DISPATCH, PerformanceMetric.DISPOSAL),
            ), LayoutContractTestDeclaration<SampleConfig>
            @ContractTestSpec(
                contributionId = "org.quicklauncher.samples/block",
                category = ContributionCategorySpec.BLOCK,
                scenarios = [PreviewScenarioSpec.EMPTY, PreviewScenarioSpec.NORMAL, PreviewScenarioSpec.LOADING,
                    PreviewScenarioSpec.PERMISSION_DENIED, PreviewScenarioSpec.PROFILE_LOCKED,
                    PreviewScenarioSpec.LARGE_TEXT, PreviewScenarioSpec.ERROR],
                performanceHooks = [PerformanceMetricSpec.FIRST_RENDER, PerformanceMetricSpec.ACTION_DISPATCH,
                    PerformanceMetricSpec.DISPOSAL],
            )
            object BlockTests : SampleTests<SampleConfig>(
                "org.quicklauncher.samples/block",
                setOf(PerformanceMetric.FIRST_RENDER, PerformanceMetric.ACTION_DISPATCH, PerformanceMetric.DISPOSAL),
            ), BlockContractTestDeclaration<SampleConfig>
            @ContractTestSpec(
                contributionId = "org.quicklauncher.samples/search",
                category = ContributionCategorySpec.SEARCH_PROVIDER,
                scenarios = [PreviewScenarioSpec.EMPTY, PreviewScenarioSpec.NORMAL, PreviewScenarioSpec.LOADING,
                    PreviewScenarioSpec.PERMISSION_DENIED, PreviewScenarioSpec.PROFILE_LOCKED,
                    PreviewScenarioSpec.LARGE_TEXT, PreviewScenarioSpec.ERROR],
                performanceHooks = [PerformanceMetricSpec.FIRST_RESULT, PerformanceMetricSpec.QUERY_REPLACEMENT,
                    PerformanceMetricSpec.DISPOSAL],
            )
            object SearchTests : SampleTests<SampleConfig>(
                "org.quicklauncher.samples/search",
                setOf(PerformanceMetric.FIRST_RESULT, PerformanceMetric.QUERY_REPLACEMENT, PerformanceMetric.DISPOSAL),
            ), SearchProviderContractTestDeclaration<SampleConfig>
            @ContractTestSpec(
                contributionId = "org.quicklauncher.samples/command",
                category = ContributionCategorySpec.LAUNCHER_COMMAND,
                scenarios = [PreviewScenarioSpec.EMPTY, PreviewScenarioSpec.NORMAL, PreviewScenarioSpec.LOADING,
                    PreviewScenarioSpec.PERMISSION_DENIED, PreviewScenarioSpec.PROFILE_LOCKED,
                    PreviewScenarioSpec.LARGE_TEXT, PreviewScenarioSpec.ERROR],
                performanceHooks = [PerformanceMetricSpec.COMMAND_EXECUTION],
            )
            object CommandTests : SampleTests<SampleConfig>(
                "org.quicklauncher.samples/command",
                setOf(PerformanceMetric.COMMAND_EXECUTION),
            ), LauncherCommandContractTestDeclaration<SampleConfig>
            @ContractTestSpec(
                contributionId = "org.quicklauncher.samples/template",
                category = ContributionCategorySpec.DESTINATION_TEMPLATE,
                scenarios = [PreviewScenarioSpec.EMPTY, PreviewScenarioSpec.NORMAL, PreviewScenarioSpec.LOADING,
                    PreviewScenarioSpec.PERMISSION_DENIED, PreviewScenarioSpec.PROFILE_LOCKED,
                    PreviewScenarioSpec.LARGE_TEXT, PreviewScenarioSpec.ERROR],
                performanceHooks = [PerformanceMetricSpec.DRAFT_CREATION],
            )
            object TemplateTests : SampleTests<SampleConfig>(
                "org.quicklauncher.samples/template",
                setOf(PerformanceMetric.DRAFT_CREATION),
            ), DestinationTemplateContractTestDeclaration<SampleConfig>

            @SettingsSchemaSpec(
                fields = [
                    SettingSpec(
                        key = "enabled",
                        label = "Enabled",
                        kind = SettingKind.BOOLEAN,
                        defaultValue = "true",
                    ),
                ],
            )
            object SampleSettings

            ${registrations.joinToString("\n\n")}
            """.trimIndent(),
        )
    }

    private data class CompilationOutcome(
        val result: JvmCompilationResult,
        val messages: String,
    ) {
        fun generatedRegistry(): String = result.sourcesGeneratedBySymbolProcessor
            .single { it.name == "GeneratedContributionRegistry.kt" }
            .readText()

        fun generated(name: String): String = result.sourcesGeneratedBySymbolProcessor
            .single { it.name == name }
            .readText()
    }
}
