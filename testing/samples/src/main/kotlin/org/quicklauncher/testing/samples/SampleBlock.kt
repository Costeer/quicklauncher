package org.quicklauncher.testing.samples

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.semantics
import kotlinx.coroutines.Job
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.launch
import org.quicklauncher.contracts.contribution.BlockContribution
import org.quicklauncher.contracts.contribution.BlockDescriptor
import org.quicklauncher.contracts.contribution.BlockSession
import org.quicklauncher.contracts.contribution.ContributionContext
import org.quicklauncher.contracts.contribution.ContributionTypes
import org.quicklauncher.contracts.domain.CapabilityId
import org.quicklauncher.contracts.domain.SlotTypeId
import org.quicklauncher.contracts.ui.BlockAction
import org.quicklauncher.contracts.ui.BlockRenderInput
import org.quicklauncher.contracts.ui.ActionDispatchResult
import org.quicklauncher.contracts.ui.ActionSink
import org.quicklauncher.contracts.ui.PerformanceMetric
import org.quicklauncher.contracts.ui.PreviewScenario
import org.quicklauncher.contracts.ui.RenderStatus
import org.quicklauncher.registry.annotations.RegisterBlock
import org.quicklauncher.registry.annotations.SlotSpec
import org.quicklauncher.registry.annotations.ContractTestSpec
import org.quicklauncher.registry.annotations.ContributionCategorySpec
import org.quicklauncher.registry.annotations.PerformanceMetricSpec
import org.quicklauncher.registry.annotations.PreviewScenarioSpec
import org.quicklauncher.testing.contracts.BlockContractDeclaration
import org.quicklauncher.testing.contracts.BlockContractFixture
import org.quicklauncher.testing.fakes.FakeCancellationSignal
import org.quicklauncher.testing.fakes.FakeHostStates
import org.quicklauncher.testing.fakes.FakeInstanceLifecycle

private val blockCapability = CapabilityId.parse("org.quicklauncher.capability/sample-block")
private val compatibleContentSlot = SlotTypeId.parse("org.quicklauncher.slot/content")

@RegisterBlock(
    id = "org.quicklauncher.samples/block",
    contractMajor = 1,
    configTypeId = "org.quicklauncher.samples/block-config",
    displayName = "Sample block",
    description = "Skeletal Sample block contribution used by Phase 1 contract tests",
    providedCapabilities = ["org.quicklauncher.capability/sample-block"],
    settings = SampleSettings::class,
    codec = BlockConfigurationCodec::class,
    contractTests = SampleBlockContract::class,
    compatibleSlotTypes = ["org.quicklauncher.slot/content"],
    childSlots = [SlotSpec(id = "children", type = "org.quicklauncher.slot/leaf")],
)
object SampleBlock : BlockContribution<SampleConfiguration> {
    override fun open(context: ContributionContext<SampleConfiguration>): BlockSession {
        context.cancellation.ensureActive()
        return Session(context)
    }

    private class Session(
        private val context: ContributionContext<SampleConfiguration>,
    ) : BlockSession {
        private val ownedJob: Job = context.instanceScope.launch { awaitCancellation() }
        override var isClosed: Boolean = false
            private set

        @Composable
        override fun Render(input: BlockRenderInput) {
            check(!isClosed) { "Block session is closed" }
            context.cancellation.ensureActive()
            DisposableEffect(this) {
                onDispose(::close)
            }
            val action = BlockAction.OpenSettings(input.instanceId)
            val preparedItem = input.state.content.items.firstOrNull()
            val label = blockScenario(input.state.status, input.state.theme.textScale)
                .name.lowercase().replace('_', ' ') + " sample"
            Column(
                Modifier
                    .fillMaxSize()
                    .background(Color(input.state.theme.background.value))
                    .clickable(
                        enabled = context.configuration.enabled,
                        role = Role.Button,
                    ) { input.actions.emit(action) }
                    .semantics {
                        contentDescription = label
                        customActions = if (preparedItem == null) {
                            emptyList()
                        } else {
                            listOf(
                                CustomAccessibilityAction("Activate prepared item") {
                                    input.actions.emit(BlockAction.ActivateItem(preparedItem.id))
                                    true
                                },
                                CustomAccessibilityAction("Open prepared item actions") {
                                    input.actions.emit(BlockAction.OpenItemActions(preparedItem.id))
                                    true
                                },
                            )
                        }
                    },
            ) {
                BasicText(label, style = TextStyle(color = Color(input.state.theme.foreground.value)))
                input.state.content.items.forEach { item -> input.content.RenderItem(item) }
                input.state.content.surfaces.forEach { surface -> input.content.RenderSurface(surface) }
                input.state.childSlots.forEach { slot -> input.slots.Render(slot) }
            }
        }

        override fun close() {
            ownedJob.cancel()
            isClosed = true
        }
    }
}

@ContractTestSpec(
    contributionId = "org.quicklauncher.samples/block",
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
object SampleBlockContract : BlockContractDeclaration<SampleConfiguration> {
    override val contributionId = SampleIds.BLOCK
    override val target = SampleBlock
    override val codec = BlockConfigurationCodec
    override val descriptor = BlockDescriptor(
        metadata = sampleMetadata(
            SampleIds.BLOCK,
            ContributionTypes.BLOCK,
            codec.configType,
            "Sample block",
            setOf(blockCapability),
        ),
        compatibleSlotTypes = setOf(compatibleContentSlot),
        childSlots = listOf(
            org.quicklauncher.contracts.contribution.SlotDescriptor(
                org.quicklauncher.contracts.domain.StableKey.parse("children"),
                SlotTypeId.parse("org.quicklauncher.slot/leaf"),
                emptySet(),
                emptySet(),
                1,
                emptySet(),
            ),
        ),
        occupiedScrollAxes = emptySet(),
    )
    override val configurationCases = sampleConfigurationCases(codec)
    override val instanceId = SampleIds.BLOCK_INSTANCE
    override val fixtures = immutableSampleList(PreviewScenario.entries.map { scenario ->
        BlockContractFixture(
            sampleScreenshotId("block", scenario),
            scenario,
            FakeHostStates.block(
                scenario,
                sampleOrientation(scenario),
                sampleThemeMode(scenario),
                sampleReducedMotion(scenario),
            ),
            if (scenario == PreviewScenario.NORMAL) {
                val itemId = FakeHostStates.block(scenario).content.items.single().id
                listOf(
                    BlockAction.OpenSettings(instanceId),
                    BlockAction.ActivateItem(itemId),
                    BlockAction.OpenItemActions(itemId),
                )
            } else {
                emptyList()
            },
            if (scenario == PreviewScenario.NORMAL) {
                listOf("Activate prepared item", "Open prepared item actions")
            } else {
                emptyList()
            },
        )
    })
    override val scenarios = immutableSampleSet(PreviewScenario.entries)
    override val previewAccessibility = sampleAccessibilityFixtures()
    override val performanceChecks = immutableSampleList(listOf(
        samplePerformanceCheck(PerformanceMetric.FIRST_RENDER, "Render the empty block fixture") {
            val lifecycle = FakeInstanceLifecycle()
            val session = target.open(
                ContributionContext(instanceId, codec.default, FakeCancellationSignal(), lifecycle.scope),
            )
            val fixture = fixtures.single { it.scenario == PreviewScenario.EMPTY }
            check(fixture.state.status == RenderStatus.Empty)
            session.close()
            lifecycle.close()
        },
        samplePerformanceCheck(PerformanceMetric.ACTION_DISPATCH, "Dispatch a typed block action") {
            val lifecycle = FakeInstanceLifecycle()
            val session = target.open(
                ContributionContext(instanceId, codec.default, FakeCancellationSignal(), lifecycle.scope),
            )
            val fixture = fixtures.single { it.scenario == PreviewScenario.NORMAL }
            val sink = ActionSink<BlockAction> { action ->
                check(action == BlockAction.OpenSettings(instanceId))
                ActionDispatchResult.Accepted
            }
            check(sink.emit(fixture.expectedActions.first()) == ActionDispatchResult.Accepted)
            session.close()
            lifecycle.close()
        },
        samplePerformanceCheck(PerformanceMetric.DISPOSAL, "Dispose a block session") {
            val lifecycle = FakeInstanceLifecycle()
            val session = target.open(
                ContributionContext(instanceId, codec.default, FakeCancellationSignal(), lifecycle.scope),
            )
            session.close()
            check(session.isClosed)
            lifecycle.close()
        },
    ))
    override val performanceHooks = immutableSampleList(performanceChecks.map { it.declaration })
}

private fun blockScenario(status: RenderStatus, textScale: Float): PreviewScenario = when {
    textScale >= 2f -> PreviewScenario.LARGE_TEXT
    status == RenderStatus.Empty -> PreviewScenario.EMPTY
    status == RenderStatus.Ready -> PreviewScenario.NORMAL
    status == RenderStatus.Loading -> PreviewScenario.LOADING
    status is RenderStatus.PermissionDenied -> PreviewScenario.PERMISSION_DENIED
    status is RenderStatus.ProfileLocked -> PreviewScenario.PROFILE_LOCKED
    else -> PreviewScenario.ERROR
}
