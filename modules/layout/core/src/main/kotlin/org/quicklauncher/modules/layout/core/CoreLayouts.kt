package org.quicklauncher.modules.layout.core

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import org.quicklauncher.contracts.contribution.CodecResult
import org.quicklauncher.contracts.contribution.ConfigurationCodec
import org.quicklauncher.contracts.contribution.ContributionContext
import org.quicklauncher.contracts.contribution.EncodedConfiguration
import org.quicklauncher.contracts.contribution.LayoutContractTestDeclaration
import org.quicklauncher.contracts.contribution.LayoutContribution
import org.quicklauncher.contracts.contribution.LayoutSession
import org.quicklauncher.contracts.domain.ConfigTypeId
import org.quicklauncher.contracts.domain.ContributionId
import org.quicklauncher.contracts.domain.SchemaVersion
import org.quicklauncher.contracts.ui.LayoutRenderInput
import org.quicklauncher.contracts.ui.PerformanceHookDeclaration
import org.quicklauncher.contracts.ui.PerformanceMetric
import org.quicklauncher.contracts.ui.PreviewScenario
import org.quicklauncher.registry.annotations.ConfigurationCodecSpec
import org.quicklauncher.registry.annotations.ContractTestSpec
import org.quicklauncher.registry.annotations.ContributionCategorySpec
import org.quicklauncher.registry.annotations.PerformanceMetricSpec
import org.quicklauncher.registry.annotations.PreviewScenarioSpec
import org.quicklauncher.registry.annotations.RegisterLayout
import org.quicklauncher.registry.annotations.ScrollAxisSpec
import org.quicklauncher.registry.annotations.SettingKind
import org.quicklauncher.registry.annotations.SettingSpec
import org.quicklauncher.registry.annotations.SettingsSchemaSpec
import org.quicklauncher.registry.annotations.SlotSpec

@Serializable
data class GridLayoutConfiguration(val columns: Int) {
    init {
        require(columns in 1..12) { "Grid columns must be between 1 and 12" }
    }
}

@SettingsSchemaSpec(
    fields = [SettingSpec(
        key = "columns",
        label = "Columns",
        kind = SettingKind.NUMBER,
        defaultValue = "4",
        minimum = 1,
        maximum = 12,
    )],
)
object GridLayoutSettings

@ConfigurationCodecSpec(configTypeId = CoreLayoutIds.GRID_CONFIG)
object GridLayoutCodec : ConfigurationCodec<GridLayoutConfiguration> {
    override val configType = ConfigTypeId.parse(CoreLayoutIds.GRID_CONFIG)
    override val currentSchemaVersion = SchemaVersion.of(1)
    override val default = GridLayoutConfiguration(4)
    override fun encode(value: GridLayoutConfiguration) =
        EncodedConfiguration.of(ConfigurationJson.encodeToString(value))

    override fun decode(encoded: EncodedConfiguration): CodecResult<GridLayoutConfiguration> = try {
        CodecResult.Decoded(ConfigurationJson.decodeFromString<GridLayoutConfiguration>(encoded.value))
    } catch (failure: IllegalArgumentException) {
        CodecResult.Failed(failure.message ?: "grid configuration is invalid")
    }
}

@Serializable
data object SingleBlockLayoutConfiguration

@ConfigurationCodecSpec(configTypeId = CoreLayoutIds.SINGLE_CONFIG)
object SingleBlockLayoutCodec : ConfigurationCodec<SingleBlockLayoutConfiguration> {
    override val configType = ConfigTypeId.parse(CoreLayoutIds.SINGLE_CONFIG)
    override val currentSchemaVersion = SchemaVersion.of(1)
    override val default = SingleBlockLayoutConfiguration
    override fun encode(value: SingleBlockLayoutConfiguration) =
        EncodedConfiguration.of(ConfigurationJson.encodeToString(value))

    override fun decode(encoded: EncodedConfiguration): CodecResult<SingleBlockLayoutConfiguration> = try {
        CodecResult.Decoded(ConfigurationJson.decodeFromString<SingleBlockLayoutConfiguration>(encoded.value))
    } catch (failure: IllegalArgumentException) {
        CodecResult.Failed(failure.message ?: "single-block configuration is invalid")
    }
}

@RegisterLayout(
    id = CoreLayoutIds.GRID,
    contractMajor = 1,
    configTypeId = CoreLayoutIds.GRID_CONFIG,
    displayName = "Grid",
    description = "Arranges blocks in a configurable destination grid",
    settings = GridLayoutSettings::class,
    codec = GridLayoutCodec::class,
    contractTests = GridLayoutContract::class,
    slots = [SlotSpec(
        id = "content",
        type = CoreLayoutIds.GRID_SLOT,
        acceptedBlocks = [
            "org.quicklauncher.block/alphabetical-apps",
            "org.quicklauncher.block/app-grid",
            "org.quicklauncher.block/favorites",
            "org.quicklauncher.block/folder",
            "org.quicklauncher.block/clock-date",
        ],
        maximumChildren = 24,
        allowedScrollAxes = [ScrollAxisSpec.HORIZONTAL, ScrollAxisSpec.VERTICAL],
    )],
)
object GridLayout : LayoutContribution<GridLayoutConfiguration> {
    override fun open(context: ContributionContext<GridLayoutConfiguration>): LayoutSession {
        context.cancellation.ensureActive()
        return GridLayoutSession(context)
    }
}

@RegisterLayout(
    id = CoreLayoutIds.SINGLE,
    contractMajor = 1,
    configTypeId = CoreLayoutIds.SINGLE_CONFIG,
    displayName = "Single block",
    description = "Promotes one compatible block to the full destination viewport",
    codec = SingleBlockLayoutCodec::class,
    contractTests = SingleBlockLayoutContract::class,
    slots = [SlotSpec(
        id = "content",
        type = CoreLayoutIds.FULL_SLOT,
        acceptedBlocks = [
            "org.quicklauncher.block/alphabetical-apps",
            "org.quicklauncher.block/app-grid",
            "org.quicklauncher.block/favorites",
            "org.quicklauncher.block/folder",
            "org.quicklauncher.block/clock-date",
        ],
        allowedScrollAxes = [ScrollAxisSpec.HORIZONTAL, ScrollAxisSpec.VERTICAL],
    )],
)
object SingleBlockLayout : LayoutContribution<SingleBlockLayoutConfiguration> {
    override fun open(context: ContributionContext<SingleBlockLayoutConfiguration>): LayoutSession {
        context.cancellation.ensureActive()
        return SingleBlockLayoutSession(context)
    }
}

private class GridLayoutSession(
    private val context: ContributionContext<GridLayoutConfiguration>,
) : LayoutSession {
    override var isClosed = false
        private set

    @Composable
    override fun Render(input: LayoutRenderInput) {
        check(!isClosed) { "Layout session is closed" }
        context.cancellation.ensureActive()
        val slot = input.state.slots.singleOrNull()
        if (slot == null) {
            Box(Modifier.fillMaxSize().semantics { contentDescription = "Empty grid layout" })
            return
        }
        val placements = slot.placements.mapIndexed { fallbackIndex, child ->
            GridPlacement.decode(child.placement.encoded.value, fallbackIndex, context.configuration.columns)
        }
        Layout(
            content = {
                slot.placements.forEach { child ->
                    input.slots.RenderChild(slot, child, Modifier.semantics {
                        contentDescription = "Grid item ${child.index + 1}"
                    })
                }
            },
            modifier = Modifier.fillMaxSize().semantics { contentDescription = "Grid layout" },
        ) { measurables, constraints ->
            val width = constraints.maxWidth.coerceAtLeast(constraints.minWidth)
            val height = constraints.maxHeight.coerceAtLeast(constraints.minHeight)
            val cellWidth = (width / context.configuration.columns).coerceAtLeast(1)
            val maximumRow = placements.maxOfOrNull { it.row + it.rowSpan }?.coerceAtLeast(1) ?: 1
            val cellHeight = (height / maximumRow).coerceAtLeast(1)
            val placeables = measurables.mapIndexed { index, measurable ->
                val placement = placements[index]
                measurable.measure(
                    Constraints.fixed(
                        (cellWidth * placement.columnSpan).coerceAtMost(width),
                        (cellHeight * placement.rowSpan).coerceAtMost(height),
                    ),
                )
            }
            layout(width, height) {
                placeables.forEachIndexed { index, placeable ->
                    val placement = placements[index]
                    placeable.place(placement.column * cellWidth, placement.row * cellHeight)
                }
            }
        }
    }

    override fun close() {
        isClosed = true
    }
}

private class SingleBlockLayoutSession(
    private val context: ContributionContext<SingleBlockLayoutConfiguration>,
) : LayoutSession {
    override var isClosed = false
        private set

    @Composable
    override fun Render(input: LayoutRenderInput) {
        check(!isClosed) { "Layout session is closed" }
        context.cancellation.ensureActive()
        Box(Modifier.fillMaxSize().semantics { contentDescription = "Single block layout" }) {
            input.state.slots.singleOrNull()?.let { input.slots.Render(it, Modifier.fillMaxSize()) }
        }
    }

    override fun close() {
        isClosed = true
    }
}

private data class GridPlacement(
    val column: Int,
    val row: Int,
    val columnSpan: Int,
    val rowSpan: Int,
) {
    companion object {
        private val JsonShape = Regex(
            """\{\"column\":(\d+),\"row\":(\d+),\"columnSpan\":(\d+),\"rowSpan\":(\d+)}""",
        )

        fun decode(encoded: String, fallbackIndex: Int, columns: Int): GridPlacement {
            val values = JsonShape.matchEntire(encoded)?.groupValues?.drop(1)?.mapNotNull(String::toIntOrNull)
            if (values?.size == 4) {
                val candidate = GridPlacement(values[0], values[1], values[2], values[3])
                if (candidate.columnSpan > 0 && candidate.rowSpan > 0 && candidate.column >= 0 &&
                    candidate.row >= 0 && candidate.column + candidate.columnSpan <= columns
                ) return candidate
            }
            return GridPlacement(fallbackIndex % columns, fallbackIndex / columns, 1, 1)
        }
    }
}

abstract class CoreLayoutContract<C : Any>(id: String) : LayoutContractTestDeclaration<C> {
    final override val contributionId = ContributionId.parse(id)
    final override val scenarios = PreviewScenario.entries.toSet()
    final override val performanceHooks = listOf(
        PerformanceHookDeclaration(PerformanceMetric.FIRST_RENDER, "Measure the first layout render"),
        PerformanceHookDeclaration(PerformanceMetric.ACTION_DISPATCH, "Measure layout action dispatch"),
        PerformanceHookDeclaration(PerformanceMetric.DISPOSAL, "Measure layout session disposal"),
    )
}

@ContractTestSpec(
    contributionId = CoreLayoutIds.GRID,
    category = ContributionCategorySpec.LAYOUT,
    scenarios = [
        PreviewScenarioSpec.EMPTY, PreviewScenarioSpec.NORMAL, PreviewScenarioSpec.LOADING,
        PreviewScenarioSpec.PERMISSION_DENIED, PreviewScenarioSpec.PROFILE_LOCKED,
        PreviewScenarioSpec.LARGE_TEXT, PreviewScenarioSpec.ERROR,
    ],
    performanceHooks = [
        PerformanceMetricSpec.FIRST_RENDER,
        PerformanceMetricSpec.ACTION_DISPATCH,
        PerformanceMetricSpec.DISPOSAL,
    ],
)
object GridLayoutContract : CoreLayoutContract<GridLayoutConfiguration>(CoreLayoutIds.GRID)

@ContractTestSpec(
    contributionId = CoreLayoutIds.SINGLE,
    category = ContributionCategorySpec.LAYOUT,
    scenarios = [
        PreviewScenarioSpec.EMPTY, PreviewScenarioSpec.NORMAL, PreviewScenarioSpec.LOADING,
        PreviewScenarioSpec.PERMISSION_DENIED, PreviewScenarioSpec.PROFILE_LOCKED,
        PreviewScenarioSpec.LARGE_TEXT, PreviewScenarioSpec.ERROR,
    ],
    performanceHooks = [
        PerformanceMetricSpec.FIRST_RENDER,
        PerformanceMetricSpec.ACTION_DISPATCH,
        PerformanceMetricSpec.DISPOSAL,
    ],
)
object SingleBlockLayoutContract :
    CoreLayoutContract<SingleBlockLayoutConfiguration>(CoreLayoutIds.SINGLE)

object CoreLayoutIds {
    const val GRID = "org.quicklauncher.layout/grid"
    const val GRID_CONFIG = "org.quicklauncher.layout/grid-config"
    const val SINGLE = "org.quicklauncher.layout/single-block"
    const val SINGLE_CONFIG = "org.quicklauncher.layout/single-block-config"
    const val GRID_SLOT = "org.quicklauncher.slot/grid-content"
    const val FULL_SLOT = "org.quicklauncher.slot/full-content"
}

private val ConfigurationJson = Json {
    encodeDefaults = true
    explicitNulls = false
    ignoreUnknownKeys = false
}
