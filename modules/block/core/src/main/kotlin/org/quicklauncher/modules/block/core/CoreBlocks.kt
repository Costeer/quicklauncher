package org.quicklauncher.modules.block.core

import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Job
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.launch
import kotlinx.serialization.KSerializer
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import org.quicklauncher.contracts.contribution.BlockContractTestDeclaration
import org.quicklauncher.contracts.contribution.BlockContribution
import org.quicklauncher.contracts.contribution.BlockSession
import org.quicklauncher.contracts.contribution.CodecResult
import org.quicklauncher.contracts.contribution.ConfigurationCodec
import org.quicklauncher.contracts.contribution.ContributionContext
import org.quicklauncher.contracts.contribution.EncodedConfiguration
import org.quicklauncher.contracts.domain.ConfigTypeId
import org.quicklauncher.contracts.domain.ContributionId
import org.quicklauncher.contracts.domain.SchemaVersion
import org.quicklauncher.contracts.ui.ActionDispatchResult
import org.quicklauncher.contracts.ui.BlockAction
import org.quicklauncher.contracts.ui.BlockRenderInput
import org.quicklauncher.contracts.ui.PerformanceHookDeclaration
import org.quicklauncher.contracts.ui.PerformanceMetric
import org.quicklauncher.contracts.ui.PreparedContentItem
import org.quicklauncher.contracts.ui.PreviewScenario
import org.quicklauncher.contracts.ui.RenderStatus
import org.quicklauncher.registry.annotations.ConfigurationCodecSpec
import org.quicklauncher.registry.annotations.ContractTestSpec
import org.quicklauncher.registry.annotations.ContributionCategorySpec
import org.quicklauncher.registry.annotations.PerformanceMetricSpec
import org.quicklauncher.registry.annotations.PreviewScenarioSpec
import org.quicklauncher.registry.annotations.RegisterBlock
import org.quicklauncher.registry.annotations.ScrollAxisSpec
import org.quicklauncher.registry.annotations.SettingKind
import org.quicklauncher.registry.annotations.SettingSpec
import org.quicklauncher.registry.annotations.SettingsSchemaSpec

@Serializable
data class AlphabeticalAppsConfiguration(val showSectionHeaders: Boolean = true)

@Serializable
data class AppGridConfiguration(val columns: Int = 4) {
    init {
        require(columns in 1..12) { "App grid columns must be between 1 and 12" }
    }
}

@Serializable
data class FavoritesConfiguration(val maximumItems: Int = 5) {
    init {
        require(maximumItems in 1..100) { "Maximum favorites must be between 1 and 100" }
    }
}

@Serializable
data class FolderConfiguration(val title: String = "Folder", val columns: Int = 3) {
    init {
        require(title.isNotBlank()) { "Folder title must not be blank" }
        require(title.length <= 200) { "Folder title must not exceed 200 characters" }
        require(columns in 1..12) { "Folder columns must be between 1 and 12" }
    }
}

@Serializable
data class ClockDateConfiguration(val showDate: Boolean = true)

@SettingsSchemaSpec(fields = [SettingSpec(
    key = "show-section-headers",
    label = "Show section headers",
    kind = SettingKind.BOOLEAN,
    defaultValue = "true",
)])
object AlphabeticalAppsSettings

@SettingsSchemaSpec(fields = [SettingSpec(
    key = "columns",
    label = "Columns",
    kind = SettingKind.NUMBER,
    defaultValue = "4",
    minimum = 1,
    maximum = 12,
)])
object AppGridSettings

@SettingsSchemaSpec(fields = [SettingSpec(
    key = "maximum-items",
    label = "Maximum items",
    kind = SettingKind.NUMBER,
    defaultValue = "5",
    minimum = 1,
    maximum = 100,
)])
object FavoritesSettings

@SettingsSchemaSpec(fields = [
    SettingSpec(key = "columns", label = "Columns", kind = SettingKind.NUMBER, defaultValue = "3", minimum = 1, maximum = 12),
])
object FolderSettings

@SettingsSchemaSpec(fields = [SettingSpec(
    key = "show-date",
    label = "Show date",
    kind = SettingKind.BOOLEAN,
    defaultValue = "true",
)])
object ClockDateSettings

abstract class JsonBlockCodec<C : Any>(
    typeId: String,
    final override val default: C,
    private val serializer: KSerializer<C>,
) : ConfigurationCodec<C> {
    final override val configType = ConfigTypeId.parse(typeId)
    final override val currentSchemaVersion = SchemaVersion.of(1)

    final override fun encode(value: C): EncodedConfiguration =
        EncodedConfiguration.of(ConfigurationJson.encodeToString(serializer, value))

    final override fun decode(encoded: EncodedConfiguration): CodecResult<C> = try {
        CodecResult.Decoded(ConfigurationJson.decodeFromString(serializer, encoded.value))
    } catch (failure: IllegalArgumentException) {
        CodecResult.Failed(failure.message ?: "Configuration is invalid")
    }
}

@ConfigurationCodecSpec(configTypeId = CoreBlockIds.ALPHABETICAL_CONFIG)
object AlphabeticalAppsCodec : JsonBlockCodec<AlphabeticalAppsConfiguration>(
    CoreBlockIds.ALPHABETICAL_CONFIG,
    AlphabeticalAppsConfiguration(),
    AlphabeticalAppsConfiguration.serializer(),
)

@ConfigurationCodecSpec(configTypeId = CoreBlockIds.APP_GRID_CONFIG)
object AppGridCodec : JsonBlockCodec<AppGridConfiguration>(
    CoreBlockIds.APP_GRID_CONFIG,
    AppGridConfiguration(),
    AppGridConfiguration.serializer(),
)

@ConfigurationCodecSpec(configTypeId = CoreBlockIds.FAVORITES_CONFIG)
object FavoritesCodec : JsonBlockCodec<FavoritesConfiguration>(
    CoreBlockIds.FAVORITES_CONFIG,
    FavoritesConfiguration(),
    FavoritesConfiguration.serializer(),
)

@ConfigurationCodecSpec(configTypeId = CoreBlockIds.FOLDER_CONFIG)
object FolderCodec : JsonBlockCodec<FolderConfiguration>(
    CoreBlockIds.FOLDER_CONFIG,
    FolderConfiguration(),
    FolderConfiguration.serializer(),
)

@ConfigurationCodecSpec(configTypeId = CoreBlockIds.CLOCK_CONFIG)
object ClockDateCodec : JsonBlockCodec<ClockDateConfiguration>(
    CoreBlockIds.CLOCK_CONFIG,
    ClockDateConfiguration(),
    ClockDateConfiguration.serializer(),
)

@RegisterBlock(
    id = CoreBlockIds.ALPHABETICAL,
    contractMajor = 1,
    configTypeId = CoreBlockIds.ALPHABETICAL_CONFIG,
    displayName = "Alphabetical apps",
    description = "Shows prepared apps in an alphabetical list",
    settings = AlphabeticalAppsSettings::class,
    codec = AlphabeticalAppsCodec::class,
    contractTests = AlphabeticalAppsContract::class,
    compatibleSlotTypes = [CoreBlockIds.GRID_SLOT, CoreBlockIds.FULL_SLOT],
    occupiedScrollAxes = [ScrollAxisSpec.VERTICAL],
)
object AlphabeticalAppsBlock : BlockContribution<AlphabeticalAppsConfiguration> {
    override fun open(context: ContributionContext<AlphabeticalAppsConfiguration>): BlockSession =
        CoreBlockSession(context, "Alphabetical apps") { input -> AlphabeticalApps(input, context.configuration) }
}

@RegisterBlock(
    id = CoreBlockIds.APP_GRID,
    contractMajor = 1,
    configTypeId = CoreBlockIds.APP_GRID_CONFIG,
    displayName = "App grid",
    description = "Shows prepared apps in a configurable grid",
    settings = AppGridSettings::class,
    codec = AppGridCodec::class,
    contractTests = AppGridContract::class,
    compatibleSlotTypes = [CoreBlockIds.GRID_SLOT, CoreBlockIds.FULL_SLOT],
    occupiedScrollAxes = [ScrollAxisSpec.VERTICAL],
)
object AppGridBlock : BlockContribution<AppGridConfiguration> {
    override fun open(context: ContributionContext<AppGridConfiguration>): BlockSession =
        CoreBlockSession(context, "App grid") { input -> ItemGrid(input, context.configuration.columns, "App grid") }
}

@RegisterBlock(
    id = CoreBlockIds.FAVORITES,
    contractMajor = 1,
    configTypeId = CoreBlockIds.FAVORITES_CONFIG,
    displayName = "Favorites",
    description = "Shows a bounded ordered row of prepared favorites",
    settings = FavoritesSettings::class,
    codec = FavoritesCodec::class,
    contractTests = FavoritesContract::class,
    compatibleSlotTypes = [CoreBlockIds.GRID_SLOT, CoreBlockIds.FULL_SLOT],
    occupiedScrollAxes = [ScrollAxisSpec.HORIZONTAL],
)
object FavoritesBlock : BlockContribution<FavoritesConfiguration> {
    override fun open(context: ContributionContext<FavoritesConfiguration>): BlockSession =
        CoreBlockSession(context, "Favorites") { input -> Favorites(input, context.configuration) }
}

@RegisterBlock(
    id = CoreBlockIds.FOLDER,
    contractMajor = 1,
    configTypeId = CoreBlockIds.FOLDER_CONFIG,
    displayName = "Folder",
    description = "Shows host-prepared folder members and surfaces",
    settings = FolderSettings::class,
    codec = FolderCodec::class,
    contractTests = FolderContract::class,
    compatibleSlotTypes = [CoreBlockIds.GRID_SLOT, CoreBlockIds.FULL_SLOT],
    occupiedScrollAxes = [ScrollAxisSpec.VERTICAL],
)
object FolderBlock : BlockContribution<FolderConfiguration> {
    override fun open(context: ContributionContext<FolderConfiguration>): BlockSession =
        CoreBlockSession(context, context.configuration.title) { input -> Folder(input, context.configuration) }
}

@RegisterBlock(
    id = CoreBlockIds.CLOCK,
    contractMajor = 1,
    configTypeId = CoreBlockIds.CLOCK_CONFIG,
    displayName = "Clock and date",
    description = "Shows host-prepared clock and optional date content",
    settings = ClockDateSettings::class,
    codec = ClockDateCodec::class,
    contractTests = ClockDateContract::class,
    compatibleSlotTypes = [CoreBlockIds.GRID_SLOT, CoreBlockIds.FULL_SLOT],
)
object ClockDateBlock : BlockContribution<ClockDateConfiguration> {
    override fun open(context: ContributionContext<ClockDateConfiguration>): BlockSession =
        CoreBlockSession(context, "Clock and date") { input -> ClockDate(input, context.configuration) }
}

private class CoreBlockSession<C : Any>(
    private val context: ContributionContext<C>,
    private val accessibilityLabel: String,
    private val content: @Composable (BlockRenderInput) -> Unit,
) : BlockSession {
    private val ownedJob: Job
    override var isClosed = false
        private set

    init {
        context.cancellation.ensureActive()
        ownedJob = context.instanceScope.launch { awaitCancellation() }
    }

    @Composable
    override fun Render(input: BlockRenderInput) {
        check(!isClosed) { "Block session is closed" }
        context.cancellation.ensureActive()
        DisposableEffect(this) { onDispose(::close) }
        Box(
            Modifier.fillMaxSize().semantics {
                contentDescription = accessibilityLabel
                customActions = if (input.state.composition.isInteractive) {
                    listOf(CustomAccessibilityAction("Open block settings") {
                        input.actions.emit(BlockAction.OpenSettings(input.instanceId)) ==
                            ActionDispatchResult.Accepted
                    })
                } else {
                    emptyList()
                }
            },
        ) {
            content(input)
        }
    }

    override fun close() {
        if (isClosed) return
        isClosed = true
        ownedJob.cancel()
    }
}

@Composable
private fun AlphabeticalApps(input: BlockRenderInput, configuration: AlphabeticalAppsConfiguration) {
    StatusOrContent(input, "Alphabetical apps") {
        LazyColumn(Modifier.fillMaxSize()) {
            var section: Char? = null
            input.state.content.items.sortedWith(
                compareBy<PreparedContentItem> { it.label.lowercase() }.thenBy { it.id.value },
            ).forEach { item ->
                val nextSection = item.label.first().uppercaseChar()
                if (configuration.showSectionHeaders && nextSection != section) {
                    section = nextSection
                    item(key = "section-$nextSection") {
                        Text(nextSection.toString(), Modifier.padding(horizontal = 16.dp, vertical = 8.dp).semantics { heading() })
                    }
                }
                item(key = item.id.value) { PreparedItem(input, item, Modifier.fillMaxWidth()) }
            }
        }
    }
}

@Composable
private fun ItemGrid(input: BlockRenderInput, columns: Int, label: String) {
    StatusOrContent(input, label) {
        LazyVerticalGrid(
            columns = GridCells.Fixed(columns),
            modifier = Modifier.fillMaxSize(),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            items(input.state.content.items, key = { it.id.value }) { item ->
                Card { PreparedItem(input, item, Modifier.padding(8.dp)) }
            }
        }
    }
}

@Composable
private fun Favorites(input: BlockRenderInput, configuration: FavoritesConfiguration) {
    StatusOrContent(input, "Favorites") {
        LazyRow(
            Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            items(input.state.content.items.take(configuration.maximumItems), key = { it.id.value }) { item ->
                PreparedItem(input, item)
            }
        }
    }
}

@Composable
private fun Folder(input: BlockRenderInput, configuration: FolderConfiguration) {
    Column(Modifier.fillMaxSize()) {
        Text(configuration.title, style = MaterialTheme.typography.titleLarge)
        StatusOrContent(input, configuration.title) {
            ItemGrid(input, configuration.columns, "${configuration.title} contents")
        }
        input.state.content.surfaces.forEach { surface -> input.content.RenderSurface(surface) }
    }
}

@Composable
private fun ClockDate(input: BlockRenderInput, configuration: ClockDateConfiguration) {
    StatusOrContent(input, "Clock and date") {
        Column(Modifier.fillMaxWidth()) {
            input.state.content.items
                .take(if (configuration.showDate) 2 else 1)
                .forEach { item -> PreparedItem(input, item) }
            input.state.content.surfaces.forEach { surface -> input.content.RenderSurface(surface) }
        }
    }
}

@Composable
private fun StatusOrContent(input: BlockRenderInput, emptyLabel: String, content: @Composable () -> Unit) {
    when (val status = input.state.status) {
        RenderStatus.Ready -> content()
        RenderStatus.Empty -> StatusText("No $emptyLabel content")
        RenderStatus.Loading -> StatusText("Loading $emptyLabel")
        is RenderStatus.PermissionDenied -> StatusText("Permission required")
        is RenderStatus.ProfileLocked -> StatusText("Profile locked")
        is RenderStatus.Error -> StatusText(status.message)
    }
}

@Composable
private fun StatusText(text: String) {
    Box(Modifier.fillMaxWidth().semantics { contentDescription = text }) { Text(text) }
}

@Composable
private fun PreparedItem(input: BlockRenderInput, item: PreparedContentItem, modifier: Modifier = Modifier) {
    val interactive = input.state.composition.isInteractive && item.enabled
    val itemModifier = modifier
        .combinedClickable(
            enabled = interactive,
            onClick = { input.actions.emit(BlockAction.ActivateItem(item.id)) },
            onLongClick = { input.actions.emit(BlockAction.OpenItemActions(item.id)) },
        )
        .semantics {
            contentDescription = item.label
            customActions = if (interactive) {
                listOf(CustomAccessibilityAction("Open item actions") {
                    input.actions.emit(BlockAction.OpenItemActions(item.id)) == ActionDispatchResult.Accepted
                })
            } else {
                emptyList()
            }
        }
    input.content.RenderItem(item, itemModifier)
}

abstract class CoreBlockContract<C : Any>(id: String) : BlockContractTestDeclaration<C> {
    final override val contributionId = ContributionId.parse(id)
    final override val scenarios = PreviewScenario.entries.toSet()
    final override val performanceHooks = listOf(
        PerformanceHookDeclaration(PerformanceMetric.FIRST_RENDER, "Measure the first block render"),
        PerformanceHookDeclaration(PerformanceMetric.ACTION_DISPATCH, "Measure typed item action dispatch"),
        PerformanceHookDeclaration(PerformanceMetric.DISPOSAL, "Measure block session disposal"),
    )
}

@ContractTestSpec(
    contributionId = CoreBlockIds.ALPHABETICAL,
    category = ContributionCategorySpec.BLOCK,
    scenarios = [PreviewScenarioSpec.EMPTY, PreviewScenarioSpec.NORMAL, PreviewScenarioSpec.LOADING, PreviewScenarioSpec.PERMISSION_DENIED, PreviewScenarioSpec.PROFILE_LOCKED, PreviewScenarioSpec.LARGE_TEXT, PreviewScenarioSpec.ERROR],
    performanceHooks = [PerformanceMetricSpec.FIRST_RENDER, PerformanceMetricSpec.ACTION_DISPATCH, PerformanceMetricSpec.DISPOSAL],
)
object AlphabeticalAppsContract : CoreBlockContract<AlphabeticalAppsConfiguration>(CoreBlockIds.ALPHABETICAL)

@ContractTestSpec(
    contributionId = CoreBlockIds.APP_GRID,
    category = ContributionCategorySpec.BLOCK,
    scenarios = [PreviewScenarioSpec.EMPTY, PreviewScenarioSpec.NORMAL, PreviewScenarioSpec.LOADING, PreviewScenarioSpec.PERMISSION_DENIED, PreviewScenarioSpec.PROFILE_LOCKED, PreviewScenarioSpec.LARGE_TEXT, PreviewScenarioSpec.ERROR],
    performanceHooks = [PerformanceMetricSpec.FIRST_RENDER, PerformanceMetricSpec.ACTION_DISPATCH, PerformanceMetricSpec.DISPOSAL],
)
object AppGridContract : CoreBlockContract<AppGridConfiguration>(CoreBlockIds.APP_GRID)

@ContractTestSpec(
    contributionId = CoreBlockIds.FAVORITES,
    category = ContributionCategorySpec.BLOCK,
    scenarios = [PreviewScenarioSpec.EMPTY, PreviewScenarioSpec.NORMAL, PreviewScenarioSpec.LOADING, PreviewScenarioSpec.PERMISSION_DENIED, PreviewScenarioSpec.PROFILE_LOCKED, PreviewScenarioSpec.LARGE_TEXT, PreviewScenarioSpec.ERROR],
    performanceHooks = [PerformanceMetricSpec.FIRST_RENDER, PerformanceMetricSpec.ACTION_DISPATCH, PerformanceMetricSpec.DISPOSAL],
)
object FavoritesContract : CoreBlockContract<FavoritesConfiguration>(CoreBlockIds.FAVORITES)

@ContractTestSpec(
    contributionId = CoreBlockIds.FOLDER,
    category = ContributionCategorySpec.BLOCK,
    scenarios = [PreviewScenarioSpec.EMPTY, PreviewScenarioSpec.NORMAL, PreviewScenarioSpec.LOADING, PreviewScenarioSpec.PERMISSION_DENIED, PreviewScenarioSpec.PROFILE_LOCKED, PreviewScenarioSpec.LARGE_TEXT, PreviewScenarioSpec.ERROR],
    performanceHooks = [PerformanceMetricSpec.FIRST_RENDER, PerformanceMetricSpec.ACTION_DISPATCH, PerformanceMetricSpec.DISPOSAL],
)
object FolderContract : CoreBlockContract<FolderConfiguration>(CoreBlockIds.FOLDER)

@ContractTestSpec(
    contributionId = CoreBlockIds.CLOCK,
    category = ContributionCategorySpec.BLOCK,
    scenarios = [PreviewScenarioSpec.EMPTY, PreviewScenarioSpec.NORMAL, PreviewScenarioSpec.LOADING, PreviewScenarioSpec.PERMISSION_DENIED, PreviewScenarioSpec.PROFILE_LOCKED, PreviewScenarioSpec.LARGE_TEXT, PreviewScenarioSpec.ERROR],
    performanceHooks = [PerformanceMetricSpec.FIRST_RENDER, PerformanceMetricSpec.ACTION_DISPATCH, PerformanceMetricSpec.DISPOSAL],
)
object ClockDateContract : CoreBlockContract<ClockDateConfiguration>(CoreBlockIds.CLOCK)

object CoreBlockIds {
    const val GRID_SLOT = "org.quicklauncher.slot/grid-content"
    const val FULL_SLOT = "org.quicklauncher.slot/full-content"
    const val ALPHABETICAL = "org.quicklauncher.block/alphabetical-apps"
    const val ALPHABETICAL_CONFIG = "org.quicklauncher.block/alphabetical-apps-config"
    const val APP_GRID = "org.quicklauncher.block/app-grid"
    const val APP_GRID_CONFIG = "org.quicklauncher.block/app-grid-config"
    const val FAVORITES = "org.quicklauncher.block/favorites"
    const val FAVORITES_CONFIG = "org.quicklauncher.block/favorites-config"
    const val FOLDER = "org.quicklauncher.block/folder"
    const val FOLDER_CONFIG = "org.quicklauncher.block/folder-config"
    const val CLOCK = "org.quicklauncher.block/clock-date"
    const val CLOCK_CONFIG = "org.quicklauncher.block/clock-date-config"
}

private val ConfigurationJson = Json {
    encodeDefaults = true
    explicitNulls = false
    ignoreUnknownKeys = false
}
