package org.quicklauncher.host.editor

import androidx.compose.runtime.Composable
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.quicklauncher.contracts.contribution.ActiveCancellationSignal
import org.quicklauncher.contracts.contribution.BlockContractTestDeclaration
import org.quicklauncher.contracts.contribution.BlockContribution
import org.quicklauncher.contracts.contribution.BlockDescriptor
import org.quicklauncher.contracts.contribution.BlockSession
import org.quicklauncher.contracts.contribution.CodecResult
import org.quicklauncher.contracts.contribution.ConfigurationCodec
import org.quicklauncher.contracts.contribution.ConfigurationDocument
import org.quicklauncher.contracts.contribution.ContributionContext
import org.quicklauncher.contracts.contribution.ContributionMetadata
import org.quicklauncher.contracts.contribution.ContributionRegistry
import org.quicklauncher.contracts.contribution.ContributionTypes
import org.quicklauncher.contracts.contribution.DestinationDraft
import org.quicklauncher.contracts.contribution.DestinationTemplateContractTestDeclaration
import org.quicklauncher.contracts.contribution.DestinationTemplateContribution
import org.quicklauncher.contracts.contribution.DestinationTemplateDescriptor
import org.quicklauncher.contracts.contribution.DisplayText
import org.quicklauncher.contracts.contribution.EncodedConfiguration
import org.quicklauncher.contracts.contribution.EncodedPlacementData
import org.quicklauncher.contracts.contribution.LayoutContractTestDeclaration
import org.quicklauncher.contracts.contribution.LayoutContribution
import org.quicklauncher.contracts.contribution.LayoutDescriptor
import org.quicklauncher.contracts.contribution.LayoutSession
import org.quicklauncher.contracts.contribution.ModuleDraft
import org.quicklauncher.contracts.contribution.ModuleDraftIdentity
import org.quicklauncher.contracts.contribution.PlacementData
import org.quicklauncher.contracts.contribution.PlacementDraft
import org.quicklauncher.contracts.contribution.PositionedDestinationDraft
import org.quicklauncher.contracts.contribution.RegisteredBlock
import org.quicklauncher.contracts.contribution.RegisteredContribution
import org.quicklauncher.contracts.contribution.RegisteredDestinationTemplate
import org.quicklauncher.contracts.contribution.RegisteredLayout
import org.quicklauncher.contracts.contribution.ScrollAxis
import org.quicklauncher.contracts.contribution.SlotDescriptor
import org.quicklauncher.contracts.contribution.TemplateCoordinate
import org.quicklauncher.contracts.contribution.TemplateInput
import org.quicklauncher.contracts.contribution.TemplatePlan
import org.quicklauncher.contracts.contribution.TemplateResult
import org.quicklauncher.contracts.domain.CapabilityId
import org.quicklauncher.contracts.domain.ConfigTypeId
import org.quicklauncher.contracts.domain.ConfigurationDocumentId
import org.quicklauncher.contracts.domain.ContractMajor
import org.quicklauncher.contracts.domain.ContributionId
import org.quicklauncher.contracts.domain.ContributionTypeId
import org.quicklauncher.contracts.domain.DestinationId
import org.quicklauncher.contracts.domain.ModuleInstanceId
import org.quicklauncher.contracts.domain.PlacementId
import org.quicklauncher.contracts.domain.SchemaVersion
import org.quicklauncher.contracts.domain.SlotTypeId
import org.quicklauncher.contracts.domain.StableKey
import org.quicklauncher.contracts.ui.BlockRenderInput
import org.quicklauncher.contracts.ui.LayoutRenderInput
import org.quicklauncher.contracts.ui.PerformanceHookDeclaration
import org.quicklauncher.contracts.ui.PreviewScenario
import org.quicklauncher.host.data.spatial.DestinationCoordinate
import org.quicklauncher.host.data.store.CommitResult
import org.quicklauncher.host.data.store.DestinationInstall
import org.quicklauncher.host.data.store.DestinationLayoutRecord
import org.quicklauncher.host.data.store.DestinationRecord
import org.quicklauncher.host.data.store.InMemoryLauncherStore
import org.quicklauncher.host.data.store.LauncherEdit
import org.quicklauncher.host.data.store.LauncherTransaction
import org.quicklauncher.host.data.store.ModuleInstanceRecord
import org.quicklauncher.host.data.store.PlacementPolicy
import org.quicklauncher.host.data.store.StoredConfigurationDocument

class TemplateOnboardingTest {
    @Test
    fun `production identity source emits valid stable local ids`() {
        repeat(100) {
            val localId = randomEditorLocalId()

            DestinationId.parse("org.quicklauncher.destination/$localId")
            ModuleInstanceId.parse("org.quicklauncher.instance/$localId")
            ConfigurationDocumentId.parse("org.quicklauncher.configuration/$localId")
            PlacementId.parse("org.quicklauncher.placement/$localId")
        }
    }

    @Test
    fun `valid plan previews before one atomic installation with safe fallback per destination`() = runTest {
        val store = InMemoryLauncherStore(placementPolicy = PlacementPolicy { null })
        val onboarding = onboarding(store, registry(ValidTemplate))

        val result = onboarding.preview(TEMPLATE, input())
        assertTrue(result is OnboardingResult.Previewed)
        val preview = (result as OnboardingResult.Previewed).preview
        assertTrue(preview.canInstall)
        assertTrue(store.read().destinations.isEmpty())
        assertEquals(listOf(HOME), preview.snapshot?.destinations?.map { it.id })

        assertEquals(OnboardingResult.Installed, onboarding.install(preview))
        val installed = store.read()
        assertEquals(1L, installed.revision.value)
        assertEquals(listOf(HOME), installed.destinations.map { it.id })
        assertEquals(HOME, installed.startDestinationId)
        assertEquals(2, installed.destinationLayouts.size)
        assertTrue(installed.destinationLayouts.single { it.layoutContributionId == LAYOUT }.selected)
        assertFalse(installed.destinationLayouts.single { it.layoutContributionId == SAFE_LAYOUT }.selected)
        assertEquals("{\"cell\":3}", installed.placements.single().data.value)
        assertEquals(preview.snapshot?.destinations, installed.destinations)
        assertEquals(preview.snapshot?.moduleInstances, installed.moduleInstances)
        assertEquals(preview.snapshot?.placements, installed.placements)
    }

    @Test
    fun `invalid preview cannot mutate the store`() = runTest {
        val store = InMemoryLauncherStore(placementPolicy = PlacementPolicy { null })
        val onboarding = onboarding(store, registry(IncompatibleTemplate))
        val before = store.read()

        val result = onboarding.preview(TEMPLATE, input())

        assertTrue(result is OnboardingResult.Previewed)
        val preview = (result as OnboardingResult.Previewed).preview
        assertFalse(preview.canInstall)
        assertTrue(preview.issues.any { it.contains("unavailable") })
        assertTrue(onboarding.install(preview) is OnboardingResult.Rejected)
        assertEquals(before, store.read())
    }

    @Test
    fun `template output cannot invent module or configuration identities`() {
        val store = InMemoryLauncherStore(placementPolicy = PlacementPolicy { null })
        val template = object : DestinationTemplateContribution<String> {
            override fun create(input: TemplateInput<String>): TemplateResult {
                val inventedRoot = ModuleInstanceId.parse("org.quicklauncher.instance/invented-root")
                val inventedDocument = ConfigurationDocumentId.parse(
                    "org.quicklauncher.configuration/invented-root",
                )
                return TemplateResult.Created(
                    TemplatePlan(
                        listOf(
                            PositionedDestinationDraft(
                                TemplateCoordinate(0, 0),
                                DestinationDraft(
                                    HOME,
                                    DisplayText.of("Home"),
                                    ModuleDraft(inventedRoot, LAYOUT, inventedDocument, document("layout")),
                                    emptyList(),
                                    emptyList(),
                                ),
                                isStart = true,
                            ),
                        ),
                    ),
                )
            }
        }
        val onboarding = onboarding(store, registry(template))

        val preview = (onboarding.preview(TEMPLATE, input()) as OnboardingResult.Previewed).preview

        assertFalse(preview.canInstall)
        assertTrue(preview.issues.any { it.contains("supplied module identity") })
    }

    @Test
    fun `destination editor discovers typed drop targets recursively and adds to the actual parent`() = runTest {
        val store = InMemoryLauncherStore(placementPolicy = PlacementPolicy { null })
        val contributionRegistry = registry(ValidTemplate)
        val onboarding = onboarding(store, contributionRegistry)
        val preview = (onboarding.preview(TEMPLATE, input()) as OnboardingResult.Previewed).preview
        assertEquals(OnboardingResult.Installed, onboarding.install(preview))
        val ids = ArrayDeque(listOf("nested", "nested-config", "nested-placement"))
        val editor = DefaultLauncherEditor(
            store = store,
            parentScope = backgroundScope,
            configurationDefaults = registryEditorConfigurationDefaults(contributionRegistry),
            identities = EditorIdentitySource { ids.removeFirst() },
            contributionRegistry = contributionRegistry,
        )
        editor.start()
        editor.dispatch(EditorAction.OpenDestination(HOME))

        val nested = editor.state.value.dropTargets.single { it.parentInstanceId == BLOCK_INSTANCE }
        assertEquals(NESTED_SLOT, nested.parentSlotId)
        assertTrue(NESTED_BLOCK in nested.acceptedBlocks)
        assertEquals(
            EditorResult.Applied,
            editor.dispatch(
                EditorAction.AddBlock(
                    nested.layoutInstanceId,
                    nested.parentInstanceId,
                    nested.parentSlotId,
                    NESTED_BLOCK,
                    document("nested"),
                    nested.nextIndex,
                ),
            ),
        )
        assertEquals(
            BLOCK_INSTANCE,
            store.read().placements.single { it.parentSlotId == NESTED_SLOT }.parentInstanceId,
        )
        editor.close()
    }

    @Test
    fun `store rejection rolls back the complete installation`() = runTest {
        val store = InMemoryLauncherStore(placementPolicy = PlacementPolicy { null })
        val existing = destinationInstall("existing", LAYOUT)
        check(
            store.commit(
                LauncherTransaction(store.read().revision, listOf(LauncherEdit.Bootstrap(existing))),
            ) is CommitResult.Committed,
        )
        val before = store.read()
        val onboarding = onboarding(store, registry(ValidTemplate))
        val preview = (onboarding.preview(TEMPLATE, input()) as OnboardingResult.Previewed).preview

        assertTrue(onboarding.install(preview) is OnboardingResult.Rejected)
        assertEquals(before, store.read())
    }

    @Test
    fun `template discovery is deterministic and missing template has a typed result`() {
        val store = InMemoryLauncherStore(placementPolicy = PlacementPolicy { null })
        val onboarding = onboarding(store, registry(ValidTemplate))

        assertEquals(listOf(TEMPLATE), onboarding.availableTemplates().map { it.id })
        assertEquals(
            "template.missing",
            (onboarding.preview(ContributionId.parse("org.quicklauncher.test/missing"), input()) as OnboardingResult.Invalid).code,
        )
    }

    private fun onboarding(store: InMemoryLauncherStore, registry: ContributionRegistry): TemplateOnboarding {
        val ids = ArrayDeque(listOf("safe-instance", "safe-document", "placement"))
        return TemplateOnboarding(
            registry,
            store,
            OnboardingSafeLayoutFactory { destinationId ->
                val instance = ModuleInstanceId.parse("org.quicklauncher.instance/${ids.removeFirst()}")
                val documentId = ConfigurationDocumentId.parse("org.quicklauncher.configuration/${ids.removeFirst()}")
                SafeLayoutDraft(
                    DestinationLayoutRecord(destinationId, SAFE_LAYOUT, instance, selected = false),
                    ModuleInstanceRecord(instance, SAFE_LAYOUT, documentId),
                    StoredConfigurationDocument(documentId, document("safe")),
                )
            },
            EditorIdentitySource { ids.removeFirst() },
        )
    }

    private fun input() = TemplateInput(
        listOf(org.quicklauncher.contracts.contribution.TemplateDestinationInput(HOME, DisplayText.of("Home"))),
        listOf(
            ModuleDraftIdentity(ROOT, ROOT_DOCUMENT),
            ModuleDraftIdentity(BLOCK_INSTANCE, BLOCK_DOCUMENT),
        ),
        "template",
        ActiveCancellationSignal,
    )

    private fun registry(template: DestinationTemplateContribution<String>): ContributionRegistry {
        val entries: List<RegisteredContribution<*>> = listOf(
            RegisteredLayout(layoutDescriptor(), NoOpLayout, TestCodec, LayoutContract),
            RegisteredBlock(blockDescriptor(), NoOpBlock, TestCodec, BlockContract),
            RegisteredBlock(nestedBlockDescriptor(), NoOpBlock, TestCodec, BlockContract),
            RegisteredDestinationTemplate(templateDescriptor(), template, TestCodec, TemplateContract),
        )
        return object : ContributionRegistry {
            override val categoryIds: Set<ContributionTypeId> = entries.mapTo(linkedSetOf()) { it.descriptor.metadata.typeId }
            override val entries: List<RegisteredContribution<*>> = entries
        }
    }

    private fun layoutDescriptor() = LayoutDescriptor(
        metadata(LAYOUT, ContributionTypes.LAYOUT),
        listOf(
            SlotDescriptor(
                SLOT,
                SLOT_TYPE,
                acceptedBlocks = setOf(BLOCK),
                requiredCapabilities = emptySet(),
                maximumChildren = 4,
                allowedScrollAxes = setOf(ScrollAxis.VERTICAL),
            ),
        ),
    )

    private fun blockDescriptor() = BlockDescriptor(
        metadata(BLOCK, ContributionTypes.BLOCK),
        compatibleSlotTypes = setOf(SLOT_TYPE),
        childSlots = listOf(
            SlotDescriptor(
                NESTED_SLOT,
                NESTED_SLOT_TYPE,
                acceptedBlocks = setOf(NESTED_BLOCK),
                requiredCapabilities = emptySet(),
                maximumChildren = 2,
                allowedScrollAxes = setOf(ScrollAxis.VERTICAL),
            ),
        ),
        occupiedScrollAxes = setOf(ScrollAxis.VERTICAL),
    )

    private fun nestedBlockDescriptor() = BlockDescriptor(
        metadata(NESTED_BLOCK, ContributionTypes.BLOCK),
        compatibleSlotTypes = setOf(NESTED_SLOT_TYPE),
        childSlots = emptyList(),
        occupiedScrollAxes = setOf(ScrollAxis.VERTICAL),
    )

    private fun templateDescriptor() = DestinationTemplateDescriptor(
        metadata(TEMPLATE, ContributionTypes.DESTINATION_TEMPLATE),
        requiredContributions = setOf(LAYOUT, BLOCK),
        maximumBlocks = 1,
    )

    private fun metadata(id: ContributionId, type: ContributionTypeId) = ContributionMetadata(
        id,
        type,
        ContractMajor.of(1),
        DisplayText.of(id.value.substringAfterLast('/')),
        DisplayText.of("Test contribution"),
        providedCapabilities = emptySet<CapabilityId>(),
        requiredCapabilities = emptySet(),
        settings = null,
        configType = CONFIG,
    )

    private fun destinationInstall(suffix: String, contribution: ContributionId): DestinationInstall {
        val destination = DestinationId.parse("org.quicklauncher.destination/$suffix")
        val instance = ModuleInstanceId.parse("org.quicklauncher.instance/$suffix")
        val configuration = ConfigurationDocumentId.parse("org.quicklauncher.configuration/$suffix")
        return DestinationInstall(
            DestinationRecord(destination, suffix, DestinationCoordinate(0, 0)),
            DestinationLayoutRecord(destination, contribution, instance, selected = true),
            ModuleInstanceRecord(instance, contribution, configuration),
            StoredConfigurationDocument(configuration, document(suffix)),
        )
    }

    companion object {
        val TEMPLATE = ContributionId.parse("org.quicklauncher.test/template")
        val LAYOUT = ContributionId.parse("org.quicklauncher.test/layout")
        val SAFE_LAYOUT = ContributionId.parse("org.quicklauncher.core/safe-layout")
        val BLOCK = ContributionId.parse("org.quicklauncher.test/block")
        val NESTED_BLOCK = ContributionId.parse("org.quicklauncher.test/nested-block")
        val CONFIG = ConfigTypeId.parse("org.quicklauncher.test/config")
        val SLOT = StableKey.parse("content")
        val SLOT_TYPE = SlotTypeId.parse("org.quicklauncher.slot/content")
        val NESTED_SLOT = StableKey.parse("nested")
        val NESTED_SLOT_TYPE = SlotTypeId.parse("org.quicklauncher.slot/nested-content")
        val HOME = DestinationId.parse("org.quicklauncher.destination/home")
        val ROOT = ModuleInstanceId.parse("org.quicklauncher.instance/root")
        val ROOT_DOCUMENT = ConfigurationDocumentId.parse("org.quicklauncher.configuration/root")
        val BLOCK_INSTANCE = ModuleInstanceId.parse("org.quicklauncher.instance/block")
        val BLOCK_DOCUMENT = ConfigurationDocumentId.parse("org.quicklauncher.configuration/block")

        fun document(value: String) = ConfigurationDocument(
            CONFIG,
            SchemaVersion.of(1),
            EncodedConfiguration.of(value),
        )

        fun plan(blockContribution: ContributionId): TemplatePlan {
            val layout = ModuleDraft(ROOT, LAYOUT, ROOT_DOCUMENT, document("layout"))
            val block = ModuleDraft(BLOCK_INSTANCE, blockContribution, BLOCK_DOCUMENT, document("block"))
            return TemplatePlan(
                listOf(
                    PositionedDestinationDraft(
                        TemplateCoordinate(0, 0),
                        DestinationDraft(
                            HOME,
                            DisplayText.of("Home"),
                            layout,
                            listOf(block),
                            listOf(
                                PlacementDraft(
                                    ROOT,
                                    SLOT,
                                    BLOCK_INSTANCE,
                                    0,
                                    PlacementData(
                                        SchemaVersion.of(1),
                                        EncodedPlacementData.of("{\"cell\":3}"),
                                    ),
                                ),
                            ),
                        ),
                        isStart = true,
                    ),
                ),
            )
        }
    }
}

private object ValidTemplate : DestinationTemplateContribution<String> {
    override fun create(input: TemplateInput<String>) = TemplateResult.Created(TemplateOnboardingTest.plan(
        ContributionId.parse("org.quicklauncher.test/block"),
    ))
}

private object IncompatibleTemplate : DestinationTemplateContribution<String> {
    override fun create(input: TemplateInput<String>) = TemplateResult.Created(TemplateOnboardingTest.plan(
        ContributionId.parse("org.quicklauncher.test/unregistered-block"),
    ))
}

private object TestCodec : ConfigurationCodec<String> {
    override val configType = ConfigTypeId.parse("org.quicklauncher.test/config")
    override val currentSchemaVersion = SchemaVersion.of(1)
    override val default = "default"
    override fun encode(value: String) = EncodedConfiguration.of(value)
    override fun decode(encoded: EncodedConfiguration) = CodecResult.Decoded(encoded.value)
}

private object NoOpLayout : LayoutContribution<String> {
    override fun open(context: ContributionContext<String>): LayoutSession = object : LayoutSession {
        override val isClosed: Boolean get() = false
        @Composable override fun Render(input: LayoutRenderInput) = Unit
        override fun close() = Unit
    }
}

private object NoOpBlock : BlockContribution<String> {
    override fun open(context: ContributionContext<String>): BlockSession = object : BlockSession {
        override val isClosed: Boolean get() = false
        @Composable override fun Render(input: BlockRenderInput) = Unit
        override fun close() = Unit
    }
}

private object LayoutContract : LayoutContractTestDeclaration<String> {
    override val contributionId = ContributionId.parse("org.quicklauncher.test/layout")
    override val scenarios = emptySet<PreviewScenario>()
    override val performanceHooks = emptyList<PerformanceHookDeclaration>()
}

private object BlockContract : BlockContractTestDeclaration<String> {
    override val contributionId = ContributionId.parse("org.quicklauncher.test/block")
    override val scenarios = emptySet<PreviewScenario>()
    override val performanceHooks = emptyList<PerformanceHookDeclaration>()
}

private object TemplateContract : DestinationTemplateContractTestDeclaration<String> {
    override val contributionId = ContributionId.parse("org.quicklauncher.test/template")
    override val scenarios = emptySet<PreviewScenario>()
    override val performanceHooks = emptyList<PerformanceHookDeclaration>()
}
