package org.quicklauncher.host.runtime.composition

import java.util.concurrent.CancellationException
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.Job
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.Rule
import org.junit.runner.RunWith
import org.quicklauncher.contracts.contribution.ActiveCancellationSignal
import org.quicklauncher.contracts.contribution.BlockContractTestDeclaration
import org.quicklauncher.contracts.contribution.BlockContribution
import org.quicklauncher.contracts.contribution.BlockDescriptor
import org.quicklauncher.contracts.contribution.BlockSession
import org.quicklauncher.contracts.contribution.CodecResult
import org.quicklauncher.contracts.contribution.ConfigurationCodec
import org.quicklauncher.contracts.contribution.ConfigurationDocument
import org.quicklauncher.contracts.contribution.ConfigurationMigration
import org.quicklauncher.contracts.contribution.ContributionContext
import org.quicklauncher.contracts.contribution.ContributionMetadata
import org.quicklauncher.contracts.contribution.ContributionRegistry
import org.quicklauncher.contracts.contribution.ContributionTypes
import org.quicklauncher.contracts.contribution.DisplayText
import org.quicklauncher.contracts.contribution.EncodedConfiguration
import org.quicklauncher.contracts.contribution.MigrationResult
import org.quicklauncher.contracts.contribution.LayoutContractTestDeclaration
import org.quicklauncher.contracts.contribution.LayoutContribution
import org.quicklauncher.contracts.contribution.LayoutDescriptor
import org.quicklauncher.contracts.contribution.LayoutSession
import org.quicklauncher.contracts.contribution.RegisteredContribution
import org.quicklauncher.contracts.contribution.RegisteredBlock
import org.quicklauncher.contracts.contribution.RegisteredLayout
import org.quicklauncher.contracts.contribution.ScrollAxis
import org.quicklauncher.contracts.contribution.SlotDescriptor
import org.quicklauncher.contracts.contribution.contributionEntriesOf
import org.quicklauncher.contracts.contribution.contributionTypeIdsOf
import org.quicklauncher.contracts.domain.ArgbColor
import org.quicklauncher.contracts.domain.ConfigTypeId
import org.quicklauncher.contracts.domain.CrashMarkerId
import org.quicklauncher.contracts.domain.ConfigurationDocumentId
import org.quicklauncher.contracts.domain.ContractMajor
import org.quicklauncher.contracts.domain.ContributionId
import org.quicklauncher.contracts.domain.ModuleInstanceId
import org.quicklauncher.contracts.domain.PlacementId
import org.quicklauncher.contracts.domain.SchemaVersion
import org.quicklauncher.contracts.domain.SlotTypeId
import org.quicklauncher.contracts.domain.StableKey
import org.quicklauncher.contracts.ui.BackgroundContrast
import org.quicklauncher.contracts.ui.ActionDispatchResult
import org.quicklauncher.contracts.ui.ActionSink
import org.quicklauncher.contracts.ui.CompositionRole
import org.quicklauncher.contracts.ui.EditorMode
import org.quicklauncher.contracts.ui.LauncherTheme
import org.quicklauncher.contracts.ui.LayoutRenderInput
import org.quicklauncher.contracts.ui.LayoutAction
import org.quicklauncher.contracts.ui.BlockRenderInput
import org.quicklauncher.contracts.ui.PerformanceHookDeclaration
import org.quicklauncher.contracts.ui.PreviewScenario
import org.quicklauncher.contracts.ui.ThemeMode
import org.quicklauncher.contracts.ui.WindowInfo
import org.quicklauncher.contracts.ui.WindowOrientation
import org.quicklauncher.host.data.spatial.DestinationCoordinate
import org.quicklauncher.host.data.store.CommitResult
import org.quicklauncher.host.data.store.CrashMarkerRecord
import org.quicklauncher.host.data.store.DestinationInstall
import org.quicklauncher.host.data.store.DestinationLayoutRecord
import org.quicklauncher.host.data.store.DestinationRecord
import org.quicklauncher.host.data.store.InMemoryLauncherStore
import org.quicklauncher.host.data.store.EncodedPlacementData
import org.quicklauncher.host.data.store.LauncherEdit
import org.quicklauncher.host.data.store.LauncherTransaction
import org.quicklauncher.host.data.store.ModuleInstanceRecord
import org.quicklauncher.host.data.store.NewPlacedModule
import org.quicklauncher.host.data.store.PlacementRecord
import org.quicklauncher.host.data.store.RegistryPlacementPolicy
import org.quicklauncher.host.data.store.LauncherSnapshot
import org.quicklauncher.host.data.store.StoredConfigurationDocument
import org.quicklauncher.host.data.store.registryConfigurationResolver
import org.robolectric.annotation.Config

@RunWith(AndroidJUnit4::class)
@Config(sdk = [35])
class CompositionEngineTest {
    @get:Rule
    val compose = createComposeRule()

    @Test
    fun `prepare opens current and cardinal neighbors and only current is interactive`() = runTest {
        val layouts = RecordingLayouts()
        val registry = TestRegistry(listOf(layouts.registration))
        val store = InMemoryLauncherStore()
        val center = install(store, layouts, "center", 0, 0, bootstrap = true)
        install(store, layouts, "left", -1, 0)
        install(store, layouts, "right", 1, 0)
        install(store, layouts, "up", 0, -1)
        install(store, layouts, "down", 0, 1)
        install(store, layouts, "distant", 2, 0)
        val engine = DefaultCompositionEngine(registry, backgroundScope)

        val prepared = engine.prepare(
            CompositionRequest(store.read(), center, testEnvironment()),
        )

        assertEquals(5, prepared.destinations.size)
        assertEquals(5, layouts.opened.size)
        assertFalse(layouts.opened.any { it.value.endsWith("distant-layout") })
        assertEquals(
            setOf(center),
            prepared.destinations
                .filter { it.composition.role == CompositionRole.CURRENT }
                .map { it.destinationId }
                .toSet(),
        )
        assertTrue(prepared.destinations.single { it.destinationId == center }.composition.isInteractive)
        assertTrue(
            prepared.destinations
                .filterNot { it.destinationId == center }
                .none { it.composition.isInteractive },
        )
    }

    @Test
    fun `prepare migrates configuration before opening the typed session`() = runTest {
        val codec = TestCodec(
            currentVersion = 2,
            migrations = listOf(object : ConfigurationMigration {
                override val configType = TestCodec.Type
                override val fromVersion = SchemaVersion.of(1)
                override val toVersion = SchemaVersion.of(2)
                override fun migrate(encoded: EncodedConfiguration) =
                    MigrationResult.Migrated(EncodedConfiguration.of("${encoded.value}-migrated"))
            }),
        )
        val layouts = RecordingLayouts(codec)
        val registry = TestRegistry(listOf(layouts.registration))
        val store = InMemoryLauncherStore()
        val current = install(
            store,
            layouts,
            "current",
            0,
            0,
            bootstrap = true,
            document = ConfigurationDocument(
                codec.configType,
                SchemaVersion.of(1),
                EncodedConfiguration.of("old"),
            ),
        )

        DefaultCompositionEngine(registry, backgroundScope).prepare(
            CompositionRequest(store.read(), current, testEnvironment()),
        )

        assertEquals(TestConfig("old-migrated"), layouts.configurations.single())
    }

    @Test
    fun `configuration failure remains a placeholder and preserves the original document`() = runTest {
        val layouts = RecordingLayouts()
        val registry = TestRegistry(listOf(layouts.registration))
        val store = InMemoryLauncherStore()
        val original = ConfigurationDocument(
            layouts.registration.codec.configType,
            SchemaVersion.of(2),
            EncodedConfiguration.of("future-byte-exact"),
        )
        val current = install(store, layouts, "current", 0, 0, bootstrap = true, document = original)

        val prepared = DefaultCompositionEngine(registry, backgroundScope).prepare(
            CompositionRequest(store.read(), current, testEnvironment()),
        )

        assertTrue(layouts.opened.isEmpty())
        assertEquals(CompositionIssueKind.CONFIGURATION_FAILED, prepared.current.issues.single().kind)
        assertEquals(original, store.read().configurationDocuments.single().document)
    }

    @Test
    fun `missing and failed registrations become contained placeholders`() = runTest {
        val layouts = RecordingLayouts()
        val store = InMemoryLauncherStore()
        val current = install(store, layouts, "current", 0, 0, bootstrap = true)
        val missing = DefaultCompositionEngine(TestRegistry(emptyList()), backgroundScope).prepare(
            CompositionRequest(store.read(), current, testEnvironment()),
        )
        assertEquals(CompositionIssueKind.MISSING_CONTRIBUTION, missing.current.issues.single().kind)

        val throwing = RecordingLayouts(throwOnOpen = true)
        val failed = DefaultCompositionEngine(
            TestRegistry(listOf(throwing.registration)),
            backgroundScope,
        ).prepare(CompositionRequest(store.read(), current, testEnvironment()))
        assertEquals(CompositionIssueKind.SESSION_FAILED, failed.current.issues.single().kind)
    }

    @Test
    fun `moving across the map reuses neighbors and disposes distant instance scopes`() = runTest {
        val layouts = RecordingLayouts()
        val registry = TestRegistry(listOf(layouts.registration))
        val store = InMemoryLauncherStore()
        val left = install(store, layouts, "left", -1, 0, bootstrap = true)
        val center = install(store, layouts, "center", 0, 0)
        val right = install(store, layouts, "right", 1, 0)
        val engine = DefaultCompositionEngine(registry, backgroundScope)
        engine.prepare(CompositionRequest(store.read(), left, testEnvironment()))
        val leftInstance = ModuleInstanceId.parse("org.quicklauncher.test/left-layout")
        val centerInstance = ModuleInstanceId.parse("org.quicklauncher.test/center-layout")
        val firstCenterSession = layouts.sessions.getValue(centerInstance)

        engine.prepare(CompositionRequest(store.read(), right, testEnvironment()))

        assertTrue(layouts.sessions.getValue(leftInstance).isClosed)
        assertFalse(layouts.jobs.getValue(leftInstance).isActive)
        assertTrue(layouts.jobs.getValue(centerInstance).isActive)
        assertTrue(firstCenterSession === layouts.sessions.getValue(centerInstance))
        engine.close()
        assertTrue(layouts.sessions.values.all(LayoutSession::isClosed))
        assertTrue(layouts.jobs.values.none(Job::isActive))
    }

    @Test
    fun `cancelled preparation closes every session opened by the abandoned attempt`() = runTest {
        val layouts = RecordingLayouts()
        val registry = TestRegistry(listOf(layouts.registration))
        val store = InMemoryLauncherStore()
        val center = install(store, layouts, "center", 0, 0, bootstrap = true)
        install(store, layouts, "right", 1, 0)
        layouts.cancelOnOpen = ModuleInstanceId.parse("org.quicklauncher.test/right-layout")
        val engine = DefaultCompositionEngine(registry, backgroundScope)

        val failure = runCatching {
            engine.prepare(CompositionRequest(store.read(), center, testEnvironment()))
        }.exceptionOrNull()

        assertTrue(failure is CancellationException)
        val centerId = ModuleInstanceId.parse("org.quicklauncher.test/center-layout")
        assertTrue(layouts.sessions.getValue(centerId).isClosed)
        assertFalse(layouts.jobs.getValue(centerId).isActive)
        assertFalse(layouts.jobs.getValue(layouts.cancelOnOpen!!).isActive)
    }

    @Test
    fun `cold restore prepares the selected root from the installed request source`() = runTest {
        val layouts = RecordingLayouts()
        val registry = TestRegistry(listOf(layouts.registration))
        val store = InMemoryLauncherStore()
        val current = install(store, layouts, "current", 0, 0, bootstrap = true)
        var requests = 0
        val engine = DefaultCompositionEngine(
            registry,
            backgroundScope,
            CompositionRestoreRequestSource {
                requests += 1
                CompositionRequest(store.read(), current, testEnvironment())
            },
        )

        engine.restore(ModuleInstanceId.parse("org.quicklauncher.test/current-layout"))

        assertEquals(1, requests)
        assertEquals(1, layouts.opened.size)
    }

    @Test
    fun `selected layout restorer accepts only the prepared current root`() = runTest {
        val layouts = RecordingLayouts()
        val registry = TestRegistry(listOf(layouts.registration))
        val store = InMemoryLauncherStore()
        val current = install(store, layouts, "current", 0, 0, bootstrap = true)
        val engine = DefaultCompositionEngine(registry, backgroundScope)
        val prepared = engine.prepare(CompositionRequest(store.read(), current, testEnvironment()))

        engine.restore(checkNotNull(prepared.current.layoutInstanceId))
        val failure = runCatching {
            engine.restore(ModuleInstanceId.parse("org.quicklauncher.test/not-selected"))
        }.exceptionOrNull()
        assertTrue(failure is IllegalStateException)
    }

    @Test
    fun `neighbor render actions are rejected through the same public input seam`() = runTest {
        val layouts = RecordingLayouts()
        val registry = TestRegistry(listOf(layouts.registration))
        val store = InMemoryLauncherStore()
        val current = install(store, layouts, "current", 0, 0, bootstrap = true)
        install(store, layouts, "right", 1, 0)
        val accepted = mutableListOf<LayoutAction>()
        val prepared = DefaultCompositionEngine(registry, backgroundScope).prepare(
            CompositionRequest(
                store.read(),
                current,
                testEnvironment().copy(layoutActions = ActionSink {
                    accepted += it
                    ActionDispatchResult.Accepted
                }),
            ),
        )

        compose.setContent {
            prepared.destinations.forEach { it.Render() }
        }
        compose.waitForIdle()

        assertEquals(1, accepted.size)
        assertTrue(layouts.actionResults.getValue(ModuleInstanceId.parse("org.quicklauncher.test/current-layout")) is ActionDispatchResult.Accepted)
        val neighbor = layouts.actionResults.getValue(ModuleInstanceId.parse("org.quicklauncher.test/right-layout"))
        assertTrue(neighbor is ActionDispatchResult.Rejected)
        assertEquals("neighbor-not-interactive", (neighbor as ActionDispatchResult.Rejected).reason.value)
    }

    @Test
    fun `quarantined selected layout is never opened and restores as a placeholder`() = runTest {
        val layouts = RecordingLayouts()
        val registry = TestRegistry(listOf(layouts.registration))
        val store = InMemoryLauncherStore()
        val current = install(store, layouts, "current", 0, 0, bootstrap = true)
        val instanceId = ModuleInstanceId.parse("org.quicklauncher.test/current-layout")
        val before = store.read()
        val marker = CrashMarkerRecord(
            CrashMarkerId.parse("org.quicklauncher.test/current-marker"),
            instanceId,
            "test",
            1,
            10L,
            null,
        )
        check(
            store.commit(
                LauncherTransaction(
                    before.revision,
                    listOf(
                        LauncherEdit.BeginStartupRestore(marker),
                        LauncherEdit.QuarantineRenderer(
                            instanceId,
                            "renderer.failed",
                            "Renderer failed",
                            StableKey.parse("failed"),
                        ),
                    ),
                ),
            ) is CommitResult.Committed,
        )

        val prepared = DefaultCompositionEngine(registry, backgroundScope).prepare(
            CompositionRequest(store.read(), current, testEnvironment()),
        )

        assertTrue(layouts.opened.isEmpty())
        assertEquals(CompositionIssueKind.QUARANTINED, prepared.current.issues.single().kind)
    }

    @Test
    fun `changed configuration replaces and disposes the previous session`() = runTest {
        val layouts = RecordingLayouts()
        val registry = TestRegistry(listOf(layouts.registration))
        val store = InMemoryLauncherStore(configurationResolver = registryConfigurationResolver(registry))
        val current = install(store, layouts, "current", 0, 0, bootstrap = true)
        val instanceId = ModuleInstanceId.parse("org.quicklauncher.test/current-layout")
        val engine = DefaultCompositionEngine(registry, backgroundScope)
        engine.prepare(CompositionRequest(store.read(), current, testEnvironment()))
        val firstSession = layouts.sessions.getValue(instanceId)
        val firstJob = layouts.jobs.getValue(instanceId)
        val before = store.read()
        check(
            store.commit(
                LauncherTransaction(
                    before.revision,
                    listOf(
                        LauncherEdit.ReplaceConfiguration(
                            ConfigurationDocumentId.parse("org.quicklauncher.test/current-config"),
                            ConfigurationDocument(
                                layouts.registration.codec.configType,
                                layouts.registration.codec.currentSchemaVersion,
                                EncodedConfiguration.of("replacement"),
                            ),
                        ),
                    ),
                ),
            ) is CommitResult.Committed,
        )

        engine.prepare(CompositionRequest(store.read(), current, testEnvironment()))

        assertTrue(firstSession.isClosed)
        assertFalse(firstJob.isActive)
        assertEquals(TestConfig("replacement"), layouts.configurations.last())
        assertFalse(firstSession === layouts.sessions.getValue(instanceId))
    }

    @Test
    fun `configuration failure disposes the formerly live session without changing bytes`() = runTest {
        val layouts = RecordingLayouts()
        val registry = TestRegistry(listOf(layouts.registration))
        val store = InMemoryLauncherStore()
        val current = install(store, layouts, "current", 0, 0, bootstrap = true)
        val instanceId = ModuleInstanceId.parse("org.quicklauncher.test/current-layout")
        val engine = DefaultCompositionEngine(registry, backgroundScope)
        engine.prepare(CompositionRequest(store.read(), current, testEnvironment()))
        val firstSession = layouts.sessions.getValue(instanceId)
        val firstJob = layouts.jobs.getValue(instanceId)
        val valid = store.read()
        val future = ConfigurationDocument(
            TestCodec.Type,
            SchemaVersion.of(2),
            EncodedConfiguration.of("future-byte-exact"),
        )
        val corrupt = LauncherSnapshot(
            revision = valid.revision,
            startDestinationId = valid.startDestinationId,
            destinations = valid.destinations,
            destinationLayouts = valid.destinationLayouts,
            moduleInstances = valid.moduleInstances,
            configurationDocuments = valid.configurationDocuments.map { it.copy(document = future) },
            placements = valid.placements,
        )

        val prepared = engine.prepare(CompositionRequest(corrupt, current, testEnvironment()))

        assertEquals(CompositionIssueKind.CONFIGURATION_FAILED, prepared.current.issues.single().kind)
        assertTrue(firstSession.isClosed)
        assertFalse(firstJob.isActive)
        assertEquals("future-byte-exact", future.encoded.value)
    }

    @Test
    fun `typed slot tree opens a compatible block with prepared host content`() = runTest {
        val visual = VisualRegistrations()
        val registry = TestRegistry(listOf(visual.layout, visual.block))
        val store = InMemoryLauncherStore(
            configurationResolver = registryConfigurationResolver(registry),
            placementPolicy = RegistryPlacementPolicy(registry),
        )
        val current = installVisualTree(store, visual)
        val blockId = ModuleInstanceId.parse("org.quicklauncher.test/visual-block-instance")
        val content = org.quicklauncher.contracts.ui.PreparedHostContent(
            listOf(
                org.quicklauncher.contracts.ui.PreparedContentItem(
                    org.quicklauncher.contracts.domain.ContentItemId.parse("org.quicklauncher.test/item"),
                    "Prepared item",
                    null,
                    org.quicklauncher.contracts.ui.PreparedContentKind.TEXT,
                    true,
                ),
            ),
            emptyList(),
        )
        val environment = testEnvironment().copy(
            contentSource = PreparedContentSource { instanceId ->
                if (instanceId == blockId) content else org.quicklauncher.contracts.ui.PreparedHostContent.Empty
            },
        )

        val prepared = DefaultCompositionEngine(registry, backgroundScope).prepare(
            CompositionRequest(store.read(), current, environment),
        )

        assertTrue(prepared.current.issues.isEmpty())
        assertEquals(listOf(blockId), visual.openedBlocks)
        assertEquals(TestConfig("block"), visual.blockConfigurations.single())
    }

    @Test
    fun `prepared content failure disposes only the failed block session`() = runTest {
        val visual = VisualRegistrations()
        val registry = TestRegistry(listOf(visual.layout, visual.block))
        val store = InMemoryLauncherStore(
            configurationResolver = registryConfigurationResolver(registry),
            placementPolicy = RegistryPlacementPolicy(registry),
        )
        val current = installVisualTree(store, visual)
        val blockId = ModuleInstanceId.parse("org.quicklauncher.test/visual-block-instance")

        val prepared = DefaultCompositionEngine(registry, backgroundScope).prepare(
            CompositionRequest(
                store.read(),
                current,
                testEnvironment().copy(contentSource = PreparedContentSource { error("content failed") }),
            ),
        )

        assertEquals(CompositionIssueKind.PREPARED_CONTENT_FAILED, prepared.current.issues.single().kind)
        assertTrue(visual.blockSessions.getValue(blockId).isClosed)
        assertFalse(visual.blockJobs.getValue(blockId).isActive)
    }

    @Test
    fun `incompatible slot child is a placeholder and never opens`() = runTest {
        val visual = VisualRegistrations(blockSlotType = SlotTypeId.parse("org.quicklauncher.test/wrong-slot"))
        val registry = TestRegistry(listOf(visual.layout, visual.block))
        val store = InMemoryLauncherStore(
            configurationResolver = registryConfigurationResolver(registry),
            placementPolicy = org.quicklauncher.host.data.store.PlacementPolicy { null },
        )
        val current = installVisualTree(store, visual)

        val prepared = DefaultCompositionEngine(registry, backgroundScope).prepare(
            CompositionRequest(store.read(), current, testEnvironment()),
        )

        assertEquals(CompositionIssueKind.INCOMPATIBLE_PLACEMENT, prepared.current.issues.single().kind)
        assertTrue(visual.openedBlocks.isEmpty())
    }

    @Test
    fun `runtime placement cycle is contained even when persisted state is corrupt`() = runTest {
        val visual = VisualRegistrations(nested = true)
        val registry = TestRegistry(listOf(visual.layout, visual.block))
        val store = InMemoryLauncherStore(
            configurationResolver = registryConfigurationResolver(registry),
            placementPolicy = RegistryPlacementPolicy(registry),
        )
        val current = installVisualTree(store, visual)
        val valid = store.read()
        val blockId = ModuleInstanceId.parse("org.quicklauncher.test/visual-block-instance")
        val corrupt = LauncherSnapshot(
            revision = valid.revision,
            startDestinationId = valid.startDestinationId,
            destinations = valid.destinations,
            destinationLayouts = valid.destinationLayouts,
            moduleInstances = valid.moduleInstances,
            configurationDocuments = valid.configurationDocuments,
            placements = valid.placements + PlacementRecord(
                PlacementId.parse("org.quicklauncher.test/cycle-placement"),
                ModuleInstanceId.parse("org.quicklauncher.test/visual-layout-instance"),
                blockId,
                StableKey.parse("nested"),
                blockId,
                0,
            ),
        )

        val prepared = DefaultCompositionEngine(registry, backgroundScope).prepare(
            CompositionRequest(corrupt, current, testEnvironment()),
        )

        assertEquals(CompositionIssueKind.PLACEMENT_CYCLE, prepared.current.issues.single().kind)
    }

    @Test
    fun `mismatched selected layout identity is rejected before opening code`() = runTest {
        val visual = VisualRegistrations()
        val registry = TestRegistry(listOf(visual.layout, visual.block))
        val store = InMemoryLauncherStore(
            configurationResolver = registryConfigurationResolver(registry),
            placementPolicy = RegistryPlacementPolicy(registry),
        )
        val current = installVisualTree(store, visual)
        val valid = store.read()
        val corrupt = LauncherSnapshot(
            revision = valid.revision,
            startDestinationId = valid.startDestinationId,
            destinations = valid.destinations,
            destinationLayouts = valid.destinationLayouts.map {
                if (it.selected) it.copy(layoutContributionId = visual.block.descriptor.metadata.id) else it
            },
            moduleInstances = valid.moduleInstances,
            configurationDocuments = valid.configurationDocuments,
            placements = valid.placements,
        )

        val prepared = DefaultCompositionEngine(registry, backgroundScope).prepare(
            CompositionRequest(corrupt, current, testEnvironment()),
        )

        assertEquals(CompositionIssueKind.INCOMPATIBLE_PLACEMENT, prepared.current.issues.single().kind)
        assertEquals(0, visual.openedLayouts)
    }

    @Test
    fun `typed child rendering dispatches only the requested child`() = runTest {
        val visual = VisualRegistrations(renderFirstChildOnly = true)
        val registry = TestRegistry(listOf(visual.layout, visual.block))
        val store = InMemoryLauncherStore(
            configurationResolver = registryConfigurationResolver(registry),
            placementPolicy = RegistryPlacementPolicy(registry),
        )
        val current = installVisualTree(store, visual)
        addVisualBlock(store, visual, "second", 1)
        val prepared = DefaultCompositionEngine(registry, backgroundScope).prepare(
            CompositionRequest(store.read(), current, testEnvironment()),
        )

        compose.setContent { prepared.current.Render() }
        compose.waitForIdle()

        assertEquals(1, visual.renderCounts[ModuleInstanceId.parse("org.quicklauncher.test/visual-block-instance")])
        assertEquals(null, visual.renderCounts[ModuleInstanceId.parse("org.quicklauncher.test/visual-block-instance-second")])
        assertEquals(listOf(1, 1), visual.renderedPlacementSchemas)
    }
}

private data class TestConfig(val value: String)

private class TestCodec(
    currentVersion: Int = 1,
    override val migrations: List<ConfigurationMigration> = emptyList(),
) : ConfigurationCodec<TestConfig> {
    override val configType = Type
    override val currentSchemaVersion = SchemaVersion.of(currentVersion)
    override val default = TestConfig("default")
    override fun encode(value: TestConfig) = EncodedConfiguration.of(value.value)
    override fun decode(encoded: EncodedConfiguration) = CodecResult.Decoded(TestConfig(encoded.value))

    companion object {
        val Type = ConfigTypeId.parse("org.quicklauncher.test/layout-config")
    }
}

private class RecordingLayouts(
    private val codec: TestCodec = TestCodec(),
    private val throwOnOpen: Boolean = false,
) {
    var cancelOnOpen: ModuleInstanceId? = null
    val opened = mutableListOf<ModuleInstanceId>()
    val configurations = mutableListOf<TestConfig>()
    val sessions = linkedMapOf<ModuleInstanceId, LayoutSession>()
    val jobs = linkedMapOf<ModuleInstanceId, Job>()
    val actionResults = linkedMapOf<ModuleInstanceId, ActionDispatchResult>()
    private val id = ContributionId.parse("org.quicklauncher.test/layout")
    val registration = RegisteredLayout(
        descriptor = LayoutDescriptor(metadata(id, ContributionTypes.LAYOUT, codec.configType), emptyList()),
        target = object : LayoutContribution<TestConfig> {
            override fun open(context: ContributionContext<TestConfig>): LayoutSession {
                val job = checkNotNull(context.instanceScope.coroutineContext[Job])
                jobs[context.instanceId] = job
                if (context.instanceId == cancelOnOpen) throw CancellationException("cancelled by test")
                if (throwOnOpen) error("deliberate open failure")
                opened += context.instanceId
                configurations += context.configuration
                return object : LayoutSession {
                    override var isClosed = false
                    override fun close() { isClosed = true }
                    @androidx.compose.runtime.Composable
                    override fun Render(input: LayoutRenderInput) {
                        actionResults[input.instanceId] = input.actions.emit(LayoutAction.OpenSettings(input.instanceId))
                    }
                }.also { sessions[context.instanceId] = it }
            }
        },
        codec = codec,
        contractTests = object : LayoutContractTestDeclaration<TestConfig> {
            override val contributionId = id
            override val scenarios = PreviewScenario.entries.toSet()
            override val performanceHooks = emptyList<PerformanceHookDeclaration>()
        },
    )
}

private class TestRegistry(entries: List<RegisteredContribution<*>>) : ContributionRegistry {
    override val categoryIds = contributionTypeIdsOf(ContributionTypes.firstRelease)
    override val entries = contributionEntriesOf(entries)
}

private class VisualRegistrations(
    blockSlotType: SlotTypeId = SlotType,
    nested: Boolean = false,
    private val renderFirstChildOnly: Boolean = false,
) {
    private val layoutCodec = TestCodec()
    private val blockCodec = object : ConfigurationCodec<TestConfig> {
        override val configType = ConfigTypeId.parse("org.quicklauncher.test/block-config")
        override val currentSchemaVersion = SchemaVersion.of(1)
        override val default = TestConfig("block-default")
        override fun encode(value: TestConfig) = EncodedConfiguration.of(value.value)
        override fun decode(encoded: EncodedConfiguration) = CodecResult.Decoded(TestConfig(encoded.value))
    }
    private val layoutId = ContributionId.parse("org.quicklauncher.test/visual-layout")
    private val blockId = ContributionId.parse("org.quicklauncher.test/visual-block")
    val openedBlocks = mutableListOf<ModuleInstanceId>()
    var openedLayouts = 0
    val blockConfigurations = mutableListOf<TestConfig>()
    val blockSessions = linkedMapOf<ModuleInstanceId, BlockSession>()
    val blockJobs = linkedMapOf<ModuleInstanceId, Job>()
    val renderCounts = linkedMapOf<ModuleInstanceId, Int>()
    val renderedPlacementSchemas = mutableListOf<Int>()
    val layout = RegisteredLayout(
        LayoutDescriptor(
            metadata(layoutId, ContributionTypes.LAYOUT, layoutCodec.configType),
            listOf(
                SlotDescriptor(
                    StableKey.parse("body"),
                    SlotType,
                    setOf(blockId),
                    emptySet(),
                    if (renderFirstChildOnly) 2 else 1,
                    emptySet(),
                ),
            ),
        ),
        object : LayoutContribution<TestConfig> {
            override fun open(context: ContributionContext<TestConfig>): LayoutSession {
                openedLayouts += 1
                return object : LayoutSession {
                    override var isClosed = false
                    override fun close() { isClosed = true }
                    @androidx.compose.runtime.Composable
                    override fun Render(input: LayoutRenderInput) {
                        renderedPlacementSchemas.clear()
                        input.state.slots.forEach { slot ->
                            renderedPlacementSchemas += slot.placements.map { it.placement.schemaVersion }
                            if (renderFirstChildOnly) {
                                slot.placements.firstOrNull()?.let { input.slots.RenderChild(slot, it) }
                            } else {
                                input.slots.Render(slot)
                            }
                        }
                    }
                }
            }
        },
        layoutCodec,
        layoutContract(layoutId),
    )
    val block = RegisteredBlock(
        BlockDescriptor(
            metadata(blockId, ContributionTypes.BLOCK, blockCodec.configType),
            setOf(blockSlotType),
            if (nested) {
                listOf(
                    SlotDescriptor(
                        StableKey.parse("nested"),
                        SlotType,
                        setOf(blockId),
                        emptySet(),
                        1,
                        emptySet(),
                    ),
                )
            } else {
                emptyList()
            },
            emptySet(),
        ),
        object : BlockContribution<TestConfig> {
            override fun open(context: ContributionContext<TestConfig>): BlockSession {
                openedBlocks += context.instanceId
                blockConfigurations += context.configuration
                blockJobs[context.instanceId] = checkNotNull(context.instanceScope.coroutineContext[Job])
                return object : BlockSession {
                    override var isClosed = false
                    override fun close() { isClosed = true }
                    @androidx.compose.runtime.Composable
                    override fun Render(input: BlockRenderInput) {
                        renderCounts[input.instanceId] = renderCounts.getOrDefault(input.instanceId, 0) + 1
                    }
                }.also { blockSessions[context.instanceId] = it }
            }
        },
        blockCodec,
        object : BlockContractTestDeclaration<TestConfig> {
            override val contributionId = blockId
            override val scenarios = PreviewScenario.entries.toSet()
            override val performanceHooks = emptyList<PerformanceHookDeclaration>()
        },
    )

    private fun layoutContract(id: ContributionId) = object : LayoutContractTestDeclaration<TestConfig> {
        override val contributionId = id
        override val scenarios = PreviewScenario.entries.toSet()
        override val performanceHooks = emptyList<PerformanceHookDeclaration>()
    }

    companion object {
        val SlotType = SlotTypeId.parse("org.quicklauncher.test/content-slot")
    }
}

private fun metadata(
    id: ContributionId,
    type: org.quicklauncher.contracts.domain.ContributionTypeId,
    configType: ConfigTypeId,
) = ContributionMetadata(
    id = id,
    typeId = type,
    contractMajor = ContractMajor.of(1),
    displayName = DisplayText.of("Test"),
    description = DisplayText.of("Test contribution"),
    providedCapabilities = emptySet(),
    requiredCapabilities = emptySet(),
    settings = null,
    configType = configType,
)

private suspend fun install(
    store: InMemoryLauncherStore,
    layouts: RecordingLayouts,
    name: String,
    x: Long,
    y: Long,
    bootstrap: Boolean = false,
    document: ConfigurationDocument? = null,
): org.quicklauncher.contracts.domain.DestinationId {
    val destinationId = org.quicklauncher.contracts.domain.DestinationId.parse("org.quicklauncher.test/$name")
    val instanceId = ModuleInstanceId.parse("org.quicklauncher.test/$name-layout")
    val documentId = ConfigurationDocumentId.parse("org.quicklauncher.test/$name-config")
    val codec = layouts.registration.codec
    val install = DestinationInstall(
        destination = DestinationRecord(destinationId, name, DestinationCoordinate(x, y)),
        layout = DestinationLayoutRecord(destinationId, layouts.registration.descriptor.metadata.id, instanceId, true),
        layoutInstance = ModuleInstanceRecord(instanceId, layouts.registration.descriptor.metadata.id, documentId),
        configuration = StoredConfigurationDocument(
            documentId,
            document ?: ConfigurationDocument(
                codec.configType,
                codec.currentSchemaVersion,
                codec.encode(TestConfig(name)),
            ),
        ),
    )
    val before = store.read()
    val edit = if (bootstrap) LauncherEdit.Bootstrap(install) else LauncherEdit.InstallDestination(install)
    check(store.commit(LauncherTransaction(before.revision, listOf(edit))) is CommitResult.Committed)
    return destinationId
}

private suspend fun installVisualTree(
    store: InMemoryLauncherStore,
    visual: VisualRegistrations,
): org.quicklauncher.contracts.domain.DestinationId {
    val destinationId = org.quicklauncher.contracts.domain.DestinationId.parse("org.quicklauncher.test/visual")
    val layoutInstanceId = ModuleInstanceId.parse("org.quicklauncher.test/visual-layout-instance")
    val layoutDocumentId = ConfigurationDocumentId.parse("org.quicklauncher.test/visual-layout-config")
    val initial = store.read()
    check(
        store.commit(
            LauncherTransaction(
                initial.revision,
                listOf(
                    LauncherEdit.Bootstrap(
                        DestinationInstall(
                            DestinationRecord(destinationId, "Visual", DestinationCoordinate(0, 0)),
                            DestinationLayoutRecord(
                                destinationId,
                                visual.layout.descriptor.metadata.id,
                                layoutInstanceId,
                                true,
                            ),
                            ModuleInstanceRecord(
                                layoutInstanceId,
                                visual.layout.descriptor.metadata.id,
                                layoutDocumentId,
                            ),
                            StoredConfigurationDocument(
                                layoutDocumentId,
                                ConfigurationDocument(
                                    visual.layout.codec.configType,
                                    visual.layout.codec.currentSchemaVersion,
                                    visual.layout.codec.encode(TestConfig("layout")),
                                ),
                            ),
                        ),
                    ),
                ),
            ),
        ) is CommitResult.Committed,
    )
    addVisualBlock(store, visual, suffix = null, index = 0)
    return destinationId
}

private suspend fun addVisualBlock(
    store: InMemoryLauncherStore,
    visual: VisualRegistrations,
    suffix: String?,
    index: Int,
) {
    val idSuffix = suffix?.let { "-$it" }.orEmpty()
    val blockInstanceId = ModuleInstanceId.parse("org.quicklauncher.test/visual-block-instance$idSuffix")
    val blockDocumentId = ConfigurationDocumentId.parse("org.quicklauncher.test/visual-block-config$idSuffix")
    val before = store.read()
    check(
        store.commit(
            LauncherTransaction(
                before.revision,
                listOf(
                    LauncherEdit.CommitDrop(
                        NewPlacedModule(
                            ModuleInstanceRecord(
                                blockInstanceId,
                                visual.block.descriptor.metadata.id,
                                blockDocumentId,
                            ),
                            StoredConfigurationDocument(
                                blockDocumentId,
                                ConfigurationDocument(
                                    visual.block.codec.configType,
                                    visual.block.codec.currentSchemaVersion,
                                    visual.block.codec.encode(TestConfig("block")),
                                ),
                            ),
                            PlacementRecord(
                                PlacementId.parse("org.quicklauncher.test/visual-placement$idSuffix"),
                                layoutInstanceId,
                                layoutInstanceId,
                                StableKey.parse("body"),
                                blockInstanceId,
                                index,
                                EncodedPlacementData.of("{}"),
                                1,
                            ),
                        ),
                    ),
                ),
            ),
        ) is CommitResult.Committed,
    )
}

private fun testEnvironment() = CompositionEnvironment(
    theme = LauncherTheme(
        ThemeMode.LIGHT,
        ArgbColor.of(0xff000000),
        ArgbColor.of(0xffffffff),
        ArgbColor.of(0xff0000ff),
        1f,
        false,
    ),
    window = WindowInfo(400, 800, WindowOrientation.PORTRAIT),
    backgroundContrast = BackgroundContrast(21f, 21f, false),
    editorMode = EditorMode.BROWSING,
)
