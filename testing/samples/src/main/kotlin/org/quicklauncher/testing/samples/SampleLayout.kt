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
import org.quicklauncher.contracts.contribution.ContributionContext
import org.quicklauncher.contracts.contribution.ContributionTypes
import org.quicklauncher.contracts.contribution.LayoutContribution
import org.quicklauncher.contracts.contribution.LayoutDescriptor
import org.quicklauncher.contracts.contribution.LayoutSession
import org.quicklauncher.contracts.contribution.ScrollAxis
import org.quicklauncher.contracts.contribution.SlotDescriptor
import org.quicklauncher.contracts.domain.CapabilityId
import org.quicklauncher.contracts.domain.SlotTypeId
import org.quicklauncher.contracts.domain.StableKey
import org.quicklauncher.contracts.ui.LayoutAction
import org.quicklauncher.contracts.ui.LayoutRenderInput
import org.quicklauncher.contracts.ui.ActionDispatchResult
import org.quicklauncher.contracts.ui.ActionSink
import org.quicklauncher.contracts.ui.PerformanceMetric
import org.quicklauncher.contracts.ui.PreviewScenario
import org.quicklauncher.contracts.ui.RenderStatus
import org.quicklauncher.registry.annotations.RegisterLayout
import org.quicklauncher.registry.annotations.SlotSpec
import org.quicklauncher.registry.annotations.ContractTestSpec
import org.quicklauncher.registry.annotations.ContributionCategorySpec
import org.quicklauncher.registry.annotations.PerformanceMetricSpec
import org.quicklauncher.registry.annotations.PreviewScenarioSpec
import org.quicklauncher.testing.contracts.LayoutContractDeclaration
import org.quicklauncher.testing.contracts.LayoutContractFixture
import org.quicklauncher.testing.fakes.FakeCancellationSignal
import org.quicklauncher.testing.fakes.FakeHostStates
import org.quicklauncher.testing.fakes.FakeInstanceLifecycle

private val layoutCapability = CapabilityId.parse("org.quicklauncher.capability/sample-layout")
private val contentSlotType = SlotTypeId.parse("org.quicklauncher.slot/content")

@RegisterLayout(
    id = "org.quicklauncher.samples/layout",
    contractMajor = 1,
    configTypeId = "org.quicklauncher.samples/layout-config",
    displayName = "Sample layout",
    description = "Skeletal Sample layout contribution used by Phase 1 contract tests",
    providedCapabilities = ["org.quicklauncher.capability/sample-layout"],
    settings = SampleSettings::class,
    codec = LayoutConfigurationCodec::class,
    contractTests = SampleLayoutContract::class,
    slots = [SlotSpec(
        id = "main",
        type = "org.quicklauncher.slot/content",
        acceptedBlocks = ["org.quicklauncher.samples/block"],
    )],
)
object SampleLayout : LayoutContribution<SampleConfiguration> {
    override fun open(context: ContributionContext<SampleConfiguration>): LayoutSession {
        context.cancellation.ensureActive()
        return Session(context)
    }

    private class Session(
        private val context: ContributionContext<SampleConfiguration>,
    ) : LayoutSession {
        private val ownedJob: Job = context.instanceScope.launch { awaitCancellation() }
        override var isClosed: Boolean = false
            private set

        @Composable
        override fun Render(input: LayoutRenderInput) {
            check(!isClosed) { "Layout session is closed" }
            context.cancellation.ensureActive()
            DisposableEffect(this) {
                onDispose(::close)
            }
            val action = LayoutAction.OpenSettings(input.instanceId)
            val label = scenarioFor(input.state.status, input.state.theme.textScale)
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
                        customActions = listOf(
                            CustomAccessibilityAction("Select main slot") {
                                input.actions.emit(LayoutAction.SelectSlot(StableKey.parse("main")))
                                true
                            },
                        )
                    },
            ) {
                BasicText(label, style = TextStyle(color = Color(input.state.theme.foreground.value)))
                input.state.slots.forEach { slot -> input.slots.Render(slot) }
            }
        }

        override fun close() {
            ownedJob.cancel()
            isClosed = true
        }
    }
}

@ContractTestSpec(
    contributionId = "org.quicklauncher.samples/layout",
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
object SampleLayoutContract : LayoutContractDeclaration<SampleConfiguration> {
    override val contributionId = SampleIds.LAYOUT
    override val target = SampleLayout
    override val codec = LayoutConfigurationCodec
    override val descriptor = LayoutDescriptor(
        metadata = sampleMetadata(
            SampleIds.LAYOUT,
            ContributionTypes.LAYOUT,
            codec.configType,
            "Sample layout",
            setOf(layoutCapability),
        ),
        slots = listOf(
            SlotDescriptor(
                StableKey.parse("main"),
                contentSlotType,
                setOf(SampleIds.BLOCK),
                emptySet(),
                1,
                emptySet<ScrollAxis>(),
            ),
        ),
    )
    override val configurationCases = sampleConfigurationCases(codec)
    override val instanceId = SampleIds.LAYOUT_INSTANCE
    override val fixtures = immutableSampleList(PreviewScenario.entries.map { scenario ->
        LayoutContractFixture(
            sampleScreenshotId("layout", scenario),
            scenario,
            FakeHostStates.layout(
                scenario,
                sampleOrientation(scenario),
                sampleThemeMode(scenario),
                sampleReducedMotion(scenario),
            ),
            if (scenario == PreviewScenario.NORMAL) {
                listOf(
                    LayoutAction.OpenSettings(instanceId),
                    LayoutAction.SelectSlot(StableKey.parse("main")),
                )
            } else {
                emptyList()
            },
            if (scenario == PreviewScenario.NORMAL) listOf("Select main slot") else emptyList(),
        )
    })
    override val scenarios = immutableSampleSet(PreviewScenario.entries)
    override val previewAccessibility = sampleAccessibilityFixtures()
    override val performanceChecks = immutableSampleList(listOf(
        samplePerformanceCheck(PerformanceMetric.FIRST_RENDER, "Render the empty layout fixture") {
            val lifecycle = FakeInstanceLifecycle()
            val session = target.open(
                ContributionContext(instanceId, codec.default, FakeCancellationSignal(), lifecycle.scope),
            )
            val fixture = fixtures.single { it.scenario == PreviewScenario.EMPTY }
            check(fixture.state.status == RenderStatus.Empty)
            session.close()
            lifecycle.close()
        },
        samplePerformanceCheck(PerformanceMetric.ACTION_DISPATCH, "Dispatch a typed layout action") {
            val lifecycle = FakeInstanceLifecycle()
            val session = target.open(
                ContributionContext(instanceId, codec.default, FakeCancellationSignal(), lifecycle.scope),
            )
            val fixture = fixtures.single { it.scenario == PreviewScenario.NORMAL }
            val sink = ActionSink<LayoutAction> { action ->
                check(action == LayoutAction.OpenSettings(instanceId))
                ActionDispatchResult.Accepted
            }
            check(sink.emit(fixture.expectedActions.first()) == ActionDispatchResult.Accepted)
            session.close()
            lifecycle.close()
        },
        samplePerformanceCheck(PerformanceMetric.DISPOSAL, "Dispose a layout session") {
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

private fun scenarioFor(status: RenderStatus, textScale: Float): PreviewScenario = when {
    textScale >= 2f -> PreviewScenario.LARGE_TEXT
    status == RenderStatus.Empty -> PreviewScenario.EMPTY
    status == RenderStatus.Ready -> PreviewScenario.NORMAL
    status == RenderStatus.Loading -> PreviewScenario.LOADING
    status is RenderStatus.PermissionDenied -> PreviewScenario.PERMISSION_DENIED
    status is RenderStatus.ProfileLocked -> PreviewScenario.PROFILE_LOCKED
    else -> PreviewScenario.ERROR
}
