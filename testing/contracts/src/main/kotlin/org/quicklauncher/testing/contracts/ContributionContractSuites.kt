package org.quicklauncher.testing.contracts

import java.util.concurrent.CancellationException
import java.util.ArrayDeque
import kotlin.coroutines.CoroutineContext
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.yield
import org.junit.Assert.assertEquals
import org.junit.Assert.fail
import org.junit.Assert.assertSame
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import org.quicklauncher.contracts.contribution.BlockSession
import org.quicklauncher.contracts.contribution.BlockDescriptor
import org.quicklauncher.contracts.contribution.ConfigurationLoadResult
import org.quicklauncher.contracts.contribution.ConfigurationCodec
import org.quicklauncher.contracts.contribution.ConfigurationDocument
import org.quicklauncher.contracts.contribution.ConfigurationMigration
import org.quicklauncher.contracts.contribution.ConfigurationPipeline
import org.quicklauncher.contracts.contribution.CodecResult
import org.quicklauncher.contracts.contribution.ContractCompatibility
import org.quicklauncher.contracts.contribution.ContributionContext
import org.quicklauncher.contracts.contribution.DescriptorValidator
import org.quicklauncher.contracts.contribution.LayoutSession
import org.quicklauncher.contracts.contribution.LayoutDescriptor
import org.quicklauncher.contracts.contribution.SearchProviderDescriptor
import org.quicklauncher.contracts.contribution.LauncherCommandDescriptor
import org.quicklauncher.contracts.contribution.DestinationTemplateDescriptor
import org.quicklauncher.contracts.contribution.ChoiceSetting
import org.quicklauncher.contracts.contribution.FontSetting
import org.quicklauncher.contracts.contribution.SettingValue
import org.quicklauncher.contracts.contribution.CommandResult
import org.quicklauncher.contracts.contribution.TemplateResult
import org.quicklauncher.contracts.contribution.EncodedConfiguration
import org.quicklauncher.contracts.contribution.MigrationResult
import org.quicklauncher.contracts.contribution.ValidationResult
import org.quicklauncher.contracts.ui.AccessibilityDeclarationValidator
import org.quicklauncher.contracts.ui.PerformanceMetric
import org.quicklauncher.contracts.ui.PreviewScenario
import org.quicklauncher.contracts.ui.ThemeMode
import org.quicklauncher.contracts.ui.WindowOrientation
import org.quicklauncher.contracts.ui.CompositionRole
import org.quicklauncher.contracts.ui.PlacementMode
import org.quicklauncher.contracts.domain.ConfigTypeId
import org.quicklauncher.contracts.domain.SchemaVersion

abstract class CommonContributionContractSuite<C : Any> {
    protected abstract val contract: BlackBoxContractDeclaration<C>
    protected abstract val requiredPerformanceMetrics: Set<PerformanceMetric>

    @Test
    fun `descriptor and contract major are valid`() {
        assertEquals(contract.contributionId, contract.descriptor.metadata.id)
        assertEquals(contract.codec.configType, contract.descriptor.metadata.configType)
        assertEquals(ValidationResult.Valid, DescriptorValidator.validate(contract.descriptor))
        assertTrue(
            ContractCompatibility.isSupported(
                contract.descriptor.metadata.typeId,
                contract.descriptor.metadata.contractMajor,
            ),
        )
    }

    @Test
    fun `configuration defaults decode and codec round trips`() {
        val defaultDocument = ConfigurationPipeline.defaultDocument(contract.codec)
        val defaultResult = ConfigurationPipeline.load(defaultDocument, contract.codec, emptyList())
            as ConfigurationLoadResult.Loaded
        assertEquals(contract.codec.default, defaultResult.value)

        val encoded = ConfigurationPipeline.encode(contract.configurationCases.roundTripValue, contract.codec)
        val roundTrip = ConfigurationPipeline.load(encoded, contract.codec, emptyList())
            as ConfigurationLoadResult.Loaded
        assertEquals(contract.configurationCases.roundTripValue, roundTrip.value)
    }

    @Test
    fun `configuration migrations are sequential deterministic and preserve the original`() {
        val cases = contract.configurationCases
        assertImmutableList(cases.migrations)
        assertImmutableList(cases.failingMigrations)
        val migrated = ConfigurationPipeline.load(cases.migrationDocument, contract.codec, cases.migrations)
            as ConfigurationLoadResult.Loaded
        val repeated = ConfigurationPipeline.load(cases.migrationDocument, contract.codec, cases.migrations)
            as ConfigurationLoadResult.Loaded
        assertEquals(cases.expectedMigratedValue, migrated.value)
        assertEquals(contract.codec.currentSchemaVersion, migrated.document.schemaVersion)
        assertEquals(migrated, repeated)

        assertConfigurationFailure(
            "configuration.migration-failed",
            cases.failingDocument,
            contract.codec,
            cases.failingMigrations,
        )
    }

    @Test
    fun `complete configuration failure matrix preserves byte exact original data`() {
        val cases = contract.configurationCases
        val codec = contract.codec
        val firstMigration = cases.migrations.first()

        assertConfigurationFailure(
            "configuration.type-mismatch",
            cases.migrationDocument.copy(
                configType = ConfigTypeId.parse("org.quicklauncher.testing/wrong-config-type"),
            ),
            codec,
            cases.migrations,
        )
        assertConfigurationFailure(
            "configuration.future-schema",
            cases.migrationDocument.copy(
                schemaVersion = SchemaVersion.of(codec.currentSchemaVersion.value + 1),
            ),
            codec,
            cases.migrations,
        )
        assertConfigurationFailure(
            "configuration.migration-missing",
            cases.migrationDocument,
            codec,
            emptyList(),
        )
        assertConfigurationFailure(
            "configuration.migration-ambiguous",
            cases.migrationDocument,
            codec,
            cases.migrations + firstMigration,
        )
        assertConfigurationFailure(
            "configuration.migration-non-sequential",
            cases.migrationDocument,
            codec,
            listOf(
                migrationLike(firstMigration, SchemaVersion.of(firstMigration.fromVersion.value + 2)) {
                    MigrationResult.Migrated(it)
                },
            ),
        )
        assertConfigurationFailure(
            "configuration.migration-threw",
            cases.migrationDocument,
            codec,
            listOf(
                migrationLike(firstMigration, firstMigration.toVersion) {
                    throw IllegalStateException("deliberate contract migration exception")
                },
            ),
        )
        assertConfigurationFailure(
            "configuration.decode-failed",
            cases.decodeFailureDocument,
            codec,
            emptyList(),
        )
        val throwingCodec = object : ConfigurationCodec<C> {
            override val configType = codec.configType
            override val currentSchemaVersion = codec.currentSchemaVersion
            override val default = codec.default
            override fun encode(value: C): EncodedConfiguration = codec.encode(value)
            override fun decode(encoded: EncodedConfiguration): CodecResult<C> =
                throw IllegalStateException("deliberate contract codec exception")
        }
        assertConfigurationFailure(
            "configuration.decode-threw",
            ConfigurationPipeline.defaultDocument(codec),
            throwingCodec,
            emptyList(),
        )

        val cancelledMigration = migrationLike(firstMigration, firstMigration.toVersion) {
            throw CancellationException("deliberate contract cancellation")
        }
        assertThrows(CancellationException::class.java) {
            ConfigurationPipeline.load(cases.migrationDocument, codec, listOf(cancelledMigration))
        }
        val cancellingCodec = object : ConfigurationCodec<C> {
            override val configType = codec.configType
            override val currentSchemaVersion = codec.currentSchemaVersion
            override val default = codec.default
            override fun encode(value: C): EncodedConfiguration = codec.encode(value)
            override fun decode(encoded: EncodedConfiguration): CodecResult<C> =
                throw CancellationException("deliberate codec cancellation")
        }
        assertThrows(CancellationException::class.java) {
            ConfigurationPipeline.load(
                ConfigurationPipeline.defaultDocument(codec),
                cancellingCodec,
                emptyList(),
            )
        }
    }

    @Test
    fun `all preview and accessibility scenarios are declared`() {
        assertEquals(PreviewScenario.entries.toSet(), contract.scenarios)
        assertEquals(PreviewScenario.entries.toSet(), contract.previewAccessibility.keys)
        contract.previewAccessibility.values.forEach { declaration ->
            assertTrue(AccessibilityDeclarationValidator.validate(declaration).isEmpty())
        }
    }

    @Test
    fun `contribution performance hooks execute through the public contract`() {
        assertTrue(contract.performanceChecks.isNotEmpty())
        assertTrue(
            contract.performanceHooks.map { it.metric }.toSet().containsAll(requiredPerformanceMetrics),
        )
        assertEquals(
            contract.performanceHooks.toSet(),
            contract.performanceChecks.map { it.declaration }.toSet(),
        )
        contract.performanceChecks.forEach { check ->
            val observation = check.run()
            assertEquals(check.declaration.metric, observation.metric)
            assertTrue(observation.probeId.value.isNotBlank())
            assertTrue(observation.observedOutcome.isNotBlank())
        }
    }

    @Test
    fun `public descriptor declaration and accessibility collections are immutable snapshots`() {
        assertImmutableSet(contract.scenarios)
        assertImmutableList(contract.performanceHooks)
        assertImmutableList(contract.performanceChecks)
        assertImmutableMap(contract.previewAccessibility)
        contract.previewAccessibility.values.forEach { declaration ->
            assertImmutableList(declaration.semantics)
            assertImmutableList(declaration.focusOrder)
            declaration.semantics.forEach { assertImmutableList(it.actions) }
        }

        val metadata = contract.descriptor.metadata
        assertImmutableSet(metadata.providedCapabilities)
        assertImmutableSet(metadata.requiredCapabilities)
        metadata.settings?.fields?.let { fields ->
            assertImmutableList(fields)
            fields.forEach { field ->
                when (field) {
                    is ChoiceSetting -> assertImmutableList(field.options)
                    is FontSetting -> assertImmutableSet(field.allowedRoles)
                    else -> Unit
                }
            }
        }

        when (val descriptor = contract.descriptor) {
            is LayoutDescriptor -> {
                assertImmutableList(descriptor.slots)
                descriptor.slots.forEach(::assertImmutableSlot)
            }
            is BlockDescriptor -> {
                assertImmutableSet(descriptor.compatibleSlotTypes)
                assertImmutableSet(descriptor.occupiedScrollAxes)
                assertImmutableList(descriptor.childSlots)
                descriptor.childSlots.forEach(::assertImmutableSlot)
            }
            is SearchProviderDescriptor -> assertImmutableSet(descriptor.resultKinds)
            is LauncherCommandDescriptor -> {
                assertImmutableSet(descriptor.contexts)
                assertImmutableSet(descriptor.resultKinds)
            }
            is DestinationTemplateDescriptor -> assertImmutableSet(descriptor.requiredContributions)
        }
    }

    private fun assertConfigurationFailure(
        expectedCode: String,
        document: ConfigurationDocument,
        codec: ConfigurationCodec<C>,
        migrations: Collection<ConfigurationMigration>,
    ) {
        val failure = ConfigurationPipeline.load(document, codec, migrations)
            as ConfigurationLoadResult.Failed
        assertSame(document, failure.original)
        assertEquals(expectedCode, failure.error.code)
    }

    private fun migrationLike(
        source: ConfigurationMigration,
        toVersion: SchemaVersion,
        migrate: (EncodedConfiguration) -> MigrationResult,
    ): ConfigurationMigration = object : ConfigurationMigration {
        override val configType: ConfigTypeId = source.configType
        override val fromVersion: SchemaVersion = source.fromVersion
        override val toVersion: SchemaVersion = toVersion
        override fun migrate(encoded: EncodedConfiguration): MigrationResult = migrate(encoded)
    }

    private fun assertImmutableSlot(slot: org.quicklauncher.contracts.contribution.SlotDescriptor) {
        assertImmutableSet(slot.acceptedBlocks)
        assertImmutableSet(slot.requiredCapabilities)
        assertImmutableSet(slot.allowedScrollAxes)
    }
}

abstract class LayoutContributionContractSuite<C : Any> : CommonContributionContractSuite<C>() {
    protected abstract override val contract: LayoutContractDeclaration<C>
    final override val requiredPerformanceMetrics = setOf(
        PerformanceMetric.FIRST_RENDER,
        PerformanceMetric.ACTION_DISPATCH,
        PerformanceMetric.DISPOSAL,
    )

    @Test
    fun `layout fixtures are immutable accessible visual snapshots`() {
        assertImmutableList(contract.fixtures)
        assertEquals(PreviewScenario.entries.toSet(), contract.fixtures.map { it.scenario }.toSet())
        contract.fixtures.forEach { fixture ->
            assertImmutableList(fixture.state.slots)
            fixture.state.slots.forEach { slot -> assertImmutableList(slot.placements) }
            assertImmutableList(fixture.expectedActions)
            assertImmutableList(fixture.expectedCustomActionLabels)
            assertTrue(fixture.state.backgroundContrast.textContrastRatio >= 4.5f)
            assertTrue(fixture.state.backgroundContrast.nonTextContrastRatio >= 3f)
            assertTrue(
                contrastRatio(
                    fixture.state.theme.foreground.value,
                    fixture.state.theme.background.value,
                ) >= fixture.state.backgroundContrast.textContrastRatio,
            )
            assertTrue(
                contrastRatio(
                    fixture.state.theme.accent.value,
                    fixture.state.theme.background.value,
                ) >= fixture.state.backgroundContrast.nonTextContrastRatio,
            )
        }
        val largeText = contract.fixtures.single { it.scenario == PreviewScenario.LARGE_TEXT }
        assertTrue(largeText.state.theme.textScale >= 2f)
        assertTrue(!largeText.state.theme.reducedMotion)
        assertEquals(WindowOrientation.entries.toSet(), contract.fixtures.map { it.state.window.orientation }.toSet())
        assertEquals(ThemeMode.entries.toSet(), contract.fixtures.map { it.state.theme.mode }.toSet())
        assertEquals(CompositionRole.entries.toSet(), contract.fixtures.map { it.state.composition.role }.toSet())
        assertEquals(PlacementMode.entries.toSet(), contract.fixtures.map { it.state.placement.mode }.toSet())
        assertTrue(contract.fixtures.any { it.state.theme.reducedMotion })
        assertEquals(contract.fixtures.size, contract.fixtures.map { it.screenshotId }.toSet().size)
    }

    @Test
    fun `layout disposal is idempotent and cancellation stops work`() {
        val fixture = contract.fixtures.single { it.scenario == PreviewScenario.NORMAL }
        assertTrue(fixture.state.slots.isNotEmpty())
        val lifecycle = ContractInstanceLifecycle()
        val session = openLayoutSession(ContractCancellationSignal(), lifecycle)
        assertTrue(lifecycle.hasActiveChildren)
        session.close()
        session.close()
        assertTrue(session.isClosed)
        runBlocking {
            withTimeout(1_000) {
                while (lifecycle.hasActiveChildren) yield()
            }
        }
        assertTrue(!lifecycle.hasActiveChildren)
        lifecycle.close()

        val cancelled = ContractCancellationSignal().apply { cancel() }
        assertThrows(CancellationException::class.java) {
            openLayoutSession(cancelled, ContractInstanceLifecycle())
        }
    }

    private fun openLayoutSession(
        signal: ContractCancellationSignal,
        lifecycle: ContractInstanceLifecycle,
    ): LayoutSession = contract.target.open(
        ContributionContext(contract.instanceId, contract.codec.default, signal, lifecycle.scope),
    )
}

abstract class BlockContributionContractSuite<C : Any> : CommonContributionContractSuite<C>() {
    protected abstract override val contract: BlockContractDeclaration<C>
    final override val requiredPerformanceMetrics = setOf(
        PerformanceMetric.FIRST_RENDER,
        PerformanceMetric.ACTION_DISPATCH,
        PerformanceMetric.DISPOSAL,
    )

    @Test
    fun `block fixtures are immutable accessible visual snapshots`() {
        assertImmutableList(contract.fixtures)
        assertEquals(PreviewScenario.entries.toSet(), contract.fixtures.map { it.scenario }.toSet())
        contract.fixtures.forEach { fixture ->
            assertImmutableList(fixture.state.childSlots)
            fixture.state.childSlots.forEach { slot -> assertImmutableList(slot.placements) }
            assertImmutableList(fixture.state.content.items)
            assertImmutableList(fixture.state.content.surfaces)
            assertImmutableList(fixture.expectedActions)
            assertImmutableList(fixture.expectedCustomActionLabels)
            assertTrue(fixture.state.backgroundContrast.textContrastRatio >= 4.5f)
            assertTrue(fixture.state.backgroundContrast.nonTextContrastRatio >= 3f)
            assertTrue(
                contrastRatio(
                    fixture.state.theme.foreground.value,
                    fixture.state.theme.background.value,
                ) >= fixture.state.backgroundContrast.textContrastRatio,
            )
            assertTrue(
                contrastRatio(
                    fixture.state.theme.accent.value,
                    fixture.state.theme.background.value,
                ) >= fixture.state.backgroundContrast.nonTextContrastRatio,
            )
        }
        val largeText = contract.fixtures.single { it.scenario == PreviewScenario.LARGE_TEXT }
        assertTrue(largeText.state.theme.textScale >= 2f)
        assertTrue(!largeText.state.theme.reducedMotion)
        assertEquals(WindowOrientation.entries.toSet(), contract.fixtures.map { it.state.window.orientation }.toSet())
        assertEquals(ThemeMode.entries.toSet(), contract.fixtures.map { it.state.theme.mode }.toSet())
        assertEquals(CompositionRole.entries.toSet(), contract.fixtures.map { it.state.composition.role }.toSet())
        assertEquals(PlacementMode.entries.toSet(), contract.fixtures.map { it.state.placement.mode }.toSet())
        assertTrue(contract.fixtures.any { it.state.theme.reducedMotion })
        assertTrue(contract.fixtures.any { it.state.content.surfaces.isNotEmpty() })
        assertEquals(contract.fixtures.size, contract.fixtures.map { it.screenshotId }.toSet().size)
    }

    @Test
    fun `block disposal is idempotent and cancellation stops work`() {
        val fixture = contract.fixtures.single { it.scenario == PreviewScenario.NORMAL }
        assertTrue(fixture.state.childSlots.isNotEmpty())
        val lifecycle = ContractInstanceLifecycle()
        val session = openBlockSession(ContractCancellationSignal(), lifecycle)
        assertTrue(lifecycle.hasActiveChildren)
        session.close()
        session.close()
        assertTrue(session.isClosed)
        runBlocking {
            withTimeout(1_000) {
                while (lifecycle.hasActiveChildren) yield()
            }
        }
        assertTrue(!lifecycle.hasActiveChildren)
        lifecycle.close()

        val cancelled = ContractCancellationSignal().apply { cancel() }
        assertThrows(CancellationException::class.java) {
            openBlockSession(cancelled, ContractInstanceLifecycle())
        }
    }

    private fun openBlockSession(
        signal: ContractCancellationSignal,
        lifecycle: ContractInstanceLifecycle,
    ): BlockSession = contract.target.open(
        ContributionContext(contract.instanceId, contract.codec.default, signal, lifecycle.scope),
    )
}

abstract class SearchProviderContributionContractSuite<C : Any> : CommonContributionContractSuite<C>() {
    protected abstract override val contract: SearchProviderContractDeclaration<C>
    final override val requiredPerformanceMetrics = setOf(
        PerformanceMetric.FIRST_RESULT,
        PerformanceMetric.QUERY_REPLACEMENT,
        PerformanceMetric.DISPOSAL,
    )

    @Test
    fun `queries produce typed results for every preview scenario`() = runBlocking {
        assertImmutableList(contract.fixtures)
        assertEquals(PreviewScenario.entries.toSet(), contract.fixtures.map { it.scenario }.toSet())
        contract.fixtures.forEach { fixture ->
            val lifecycle = ContractInstanceLifecycle()
            val session = contract.target.open(
                ContributionContext(
                    contract.instanceId,
                    contract.codec.default,
                    ContractCancellationSignal(),
                    lifecycle.scope,
                ),
            )
            session.updateQuery(fixture.query)
            val results = session.results.first()
            assertEquals(fixture.expectedResults, results)
            assertImmutableList(results)
            assertImmutableList(fixture.expectedResults)
            session.close()
            lifecycle.close()
        }
    }

    @Test
    fun `search close is idempotent and cancellation stops work`() {
        val lifecycle = ContractInstanceLifecycle()
        val session = contract.target.open(
            ContributionContext(
                contract.instanceId,
                contract.codec.default,
                ContractCancellationSignal(),
                lifecycle.scope,
            ),
        )
        session.close()
        session.close()
        assertTrue(session.isClosed)
        assertThrows(IllegalStateException::class.java) {
            session.updateQuery(contract.fixtures.first().query)
        }
        val cancelled = ContractCancellationSignal().apply { cancel() }
        assertThrows(CancellationException::class.java) {
            contract.target.open(
                ContributionContext(contract.instanceId, contract.codec.default, cancelled, lifecycle.scope),
            )
        }
        lifecycle.close()
    }

    @Test
    fun `replacement cancels stale search work and close produces no later result`() = runBlocking {
        val stale = contract.fixtures.single { it.scenario == PreviewScenario.NORMAL }
        val replacement = contract.fixtures.single { it.scenario == PreviewScenario.ERROR }
        assertTrue(stale.expectedResults.isNotEmpty())
        assertTrue(replacement.expectedResults != stale.expectedResults)

        val dispatcher = ContractQueuedDispatcher()
        val lifecycle = ContractInstanceLifecycle(dispatcher)
        val signal = ContractCancellationSignal()
        val session = contract.target.open(
            ContributionContext(contract.instanceId, contract.codec.default, signal, lifecycle.scope),
        )
        val emissions = mutableListOf<List<org.quicklauncher.contracts.contribution.ProviderResult>>()
        val collector = launch(start = CoroutineStart.UNDISPATCHED) {
            session.results.collect { result -> emissions += result }
        }

        session.updateQuery(stale.query)
        session.updateQuery(replacement.query)
        dispatcher.runUntilIdle()
        yield()

        assertEquals(listOf(replacement.expectedResults), emissions)
        assertTrue(emissions.none { it == stale.expectedResults })
        emissions.forEach(::assertImmutableList)

        val emissionCountBeforeClose = emissions.size
        session.close()
        dispatcher.runUntilIdle()
        collector.join()
        assertEquals(emissionCountBeforeClose, emissions.size)
        assertTrue(!lifecycle.hasActiveChildren)
        lifecycle.close()
    }

    @Test
    fun `cancelling an active search suppresses its pending result`() = runBlocking {
        val normal = contract.fixtures.single { it.scenario == PreviewScenario.NORMAL }
        val dispatcher = ContractQueuedDispatcher()
        val lifecycle = ContractInstanceLifecycle(dispatcher)
        val signal = ContractCancellationSignal()
        val session = contract.target.open(
            ContributionContext(contract.instanceId, contract.codec.default, signal, lifecycle.scope),
        )
        val emissions = mutableListOf<List<org.quicklauncher.contracts.contribution.ProviderResult>>()
        val collector = launch(start = CoroutineStart.UNDISPATCHED) {
            session.results.collect { result -> emissions += result }
        }

        session.updateQuery(normal.query)
        signal.cancel()
        dispatcher.runUntilIdle()
        yield()

        assertTrue(emissions.isEmpty())
        session.close()
        dispatcher.runUntilIdle()
        collector.join()
        assertTrue(!lifecycle.hasActiveChildren)
        lifecycle.close()
    }
}

abstract class LauncherCommandContributionContractSuite<C : Any> : CommonContributionContractSuite<C>() {
    protected abstract override val contract: LauncherCommandContractDeclaration<C>
    final override val requiredPerformanceMetrics = setOf(PerformanceMetric.COMMAND_EXECUTION)

    @Test
    fun `command fixtures return typed results`() = runBlocking {
        assertImmutableList(contract.fixtures)
        assertEquals(PreviewScenario.entries.toSet(), contract.fixtures.map { it.scenario }.toSet())
        contract.fixtures.forEach { fixture ->
            assertImmutableMap(fixture.input.parameters.values)
            fixture.input.parameters.values.values.filterIsInstance<SettingValue.AppSelectionValue>()
                .forEach { assertImmutableList(it.values) }
            assertEquals(fixture.expectedResult, contract.target.execute(fixture.input))
        }
        val results = contract.fixtures.map { it.expectedResult }
        assertTrue(results.any { it is CommandResult.Succeeded })
        assertTrue(results.any { it is CommandResult.Cancelled })
        assertTrue(results.any { it is CommandResult.MissingAccess })
        assertTrue(results.any { it is CommandResult.Unavailable })
        assertTrue(results.any { it is CommandResult.RecoverableFailure })
    }

    @Test
    fun `command cancellation propagates`() {
        val source = contract.fixtures.first().input
        val cancelled = ContractCancellationSignal().apply { cancel() }
        val input = source.copy(cancellation = cancelled)
        assertThrows(CancellationException::class.java) { runBlocking { contract.target.execute(input) } }
    }
}

abstract class DestinationTemplateContributionContractSuite<C : Any> : CommonContributionContractSuite<C>() {
    protected abstract override val contract: DestinationTemplateContractDeclaration<C>
    final override val requiredPerformanceMetrics = setOf(PerformanceMetric.DRAFT_CREATION)

    @Test
    fun `template fixtures return deterministic typed drafts or failures`() {
        assertImmutableList(contract.fixtures)
        assertEquals(PreviewScenario.entries.toSet(), contract.fixtures.map { it.scenario }.toSet())
        contract.fixtures.forEach { fixture ->
            assertImmutableList(fixture.input.availableInstanceIds)
            val first = contract.target.create(fixture.input)
            assertEquals(fixture.expectedResult, first)
            assertEquals(fixture.expectedResult, contract.target.create(fixture.input))
            if (first is TemplateResult.Created) {
                val descriptor = contract.descriptor as DestinationTemplateDescriptor
                val draft = first.draft
                assertEquals(fixture.input.destinationId, draft.id)
                assertEquals(fixture.input.name, draft.name)
                assertImmutableList(draft.blocks)
                assertTrue(draft.blocks.size <= descriptor.maximumBlocks)
                val modules = listOf(draft.layout) + draft.blocks
                assertEquals(modules.size, modules.map { it.instanceId }.toSet().size)
                assertTrue(modules.all { it.instanceId in fixture.input.availableInstanceIds })
                assertTrue(modules.all { it.contributionId in descriptor.requiredContributions })
            }
        }
    }

    @Test
    fun `template cancellation propagates`() {
        val source = contract.fixtures.first().input
        val cancelled = ContractCancellationSignal().apply { cancel() }
        val input = org.quicklauncher.contracts.contribution.TemplateInput(
            source.destinationId,
            source.name,
            source.availableInstanceIds,
            source.configuration,
            cancelled,
        )
        assertThrows(CancellationException::class.java) { contract.target.create(input) }
    }
}

private fun <T> assertImmutableList(values: List<T>) {
    try {
        @Suppress("UNCHECKED_CAST")
        val mutable = values as MutableList<T>
        if (mutable.isEmpty()) {
            mutable.clear()
        } else {
            val index = mutable.lastIndex
            val removed = mutable.removeAt(index)
            mutable.add(index, removed)
        }
        fail("Expected an immutable list snapshot")
    } catch (_: UnsupportedOperationException) {
        Unit
    } catch (_: ClassCastException) {
        Unit
    }
}

private fun <T> assertImmutableSet(values: Set<T>) {
    try {
        @Suppress("UNCHECKED_CAST")
        val mutable = values as MutableSet<T>
        if (mutable.isEmpty()) {
            mutable.clear()
        } else {
            val removed = mutable.first()
            mutable.remove(removed)
            mutable.add(removed)
        }
        fail("Expected an immutable set snapshot")
    } catch (_: UnsupportedOperationException) {
        Unit
    } catch (_: ClassCastException) {
        Unit
    }
}

private fun <K, V> assertImmutableMap(values: Map<K, V>) {
    try {
        @Suppress("UNCHECKED_CAST")
        val mutable = values as MutableMap<K, V>
        if (mutable.isEmpty()) {
            mutable.clear()
        } else {
            val (key, value) = mutable.entries.first()
            mutable.remove(key)
            mutable[key] = value
        }
        fail("Expected an immutable map snapshot")
    } catch (_: UnsupportedOperationException) {
        Unit
    } catch (_: ClassCastException) {
        Unit
    }
}

private fun contrastRatio(first: Long, second: Long): Float {
    val firstLuminance = relativeLuminance(first)
    val secondLuminance = relativeLuminance(second)
    return ((max(firstLuminance, secondLuminance) + 0.05) /
        (min(firstLuminance, secondLuminance) + 0.05)).toFloat()
}

private fun relativeLuminance(argb: Long): Double {
    fun channel(shift: Int): Double {
        val encoded = ((argb shr shift) and 0xff).toDouble() / 255.0
        return if (encoded <= 0.03928) encoded / 12.92 else ((encoded + 0.055) / 1.055).pow(2.4)
    }
    return 0.2126 * channel(16) + 0.7152 * channel(8) + 0.0722 * channel(0)
}

private class ContractCancellationSignal : org.quicklauncher.contracts.contribution.CancellationSignal {
    private var cancelled = false
    override val isCancelled: Boolean get() = cancelled

    fun cancel() {
        cancelled = true
    }
}

private class ContractInstanceLifecycle(
    dispatcher: CoroutineDispatcher = Dispatchers.Default,
) : AutoCloseable {
    private val job = SupervisorJob()
    val scope = CoroutineScope(job + dispatcher)
    val hasActiveChildren: Boolean get() = job.children.any { child -> child.isActive }

    override fun close() {
        scope.cancel()
    }
}

private class ContractQueuedDispatcher : CoroutineDispatcher() {
    private val queued = ArrayDeque<Runnable>()

    override fun dispatch(context: CoroutineContext, block: Runnable) {
        queued.addLast(block)
    }

    fun runUntilIdle() {
        while (queued.isNotEmpty()) queued.removeFirst().run()
    }
}
