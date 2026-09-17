package org.quicklauncher.modules.block.core

import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusDirection
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.platform.LocalFocusManager
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
import org.quicklauncher.contracts.ui.SearchPresentationAction
import org.quicklauncher.contracts.ui.SearchPresentationProviderState
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
data class FolderConfiguration(
    val title: String = "Folder",
    val columns: Int = 3,
) {
    init {
        require(title.isNotBlank()) { "Folder title must not be blank" }
        require(title.length <= 200) { "Folder title must not exceed 200 characters" }
        require(columns in 1..12) { "Folder columns must be between 1 and 12" }
    }
}

@Serializable
data class WidgetConfiguration(val showUnavailableExplanation: Boolean = true)

@Serializable
data class ClockDateConfiguration(val showDate: Boolean = true)

@Serializable
data class SearchConfiguration(val minimumCharacters: Int = 0) {
    init {
        require(minimumCharacters in 0..32) { "Minimum search characters must be between 0 and 32" }
    }
}

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
    key = "show-unavailable-explanation",
    label = "Show unavailable explanation",
    kind = SettingKind.BOOLEAN,
    defaultValue = "true",
)])
object WidgetSettings

@SettingsSchemaSpec(fields = [SettingSpec(
    key = "show-date",
    label = "Show date",
    kind = SettingKind.BOOLEAN,
    defaultValue = "true",
)])
object ClockDateSettings

@SettingsSchemaSpec(fields = [SettingSpec(
    key = "minimum-characters",
    label = "Minimum characters",
    kind = SettingKind.NUMBER,
    defaultValue = "0",
    minimum = 0,
    maximum = 32,
)])
object SearchSettings

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

@ConfigurationCodecSpec(configTypeId = CoreBlockIds.WIDGET_CONFIG)
object WidgetCodec : JsonBlockCodec<WidgetConfiguration>(
    CoreBlockIds.WIDGET_CONFIG,
    WidgetConfiguration(),
    WidgetConfiguration.serializer(),
)

@ConfigurationCodecSpec(configTypeId = CoreBlockIds.CLOCK_CONFIG)
object ClockDateCodec : JsonBlockCodec<ClockDateConfiguration>(
    CoreBlockIds.CLOCK_CONFIG,
    ClockDateConfiguration(),
    ClockDateConfiguration.serializer(),
)

@ConfigurationCodecSpec(configTypeId = CoreBlockIds.SEARCH_CONFIG)
object SearchCodec : JsonBlockCodec<SearchConfiguration>(
    CoreBlockIds.SEARCH_CONFIG,
    SearchConfiguration(),
    SearchConfiguration.serializer(),
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
    providedCapabilities = ["org.quicklauncher.capability/theme-accent"],
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
    id = CoreBlockIds.WIDGET,
    contractMajor = 1,
    configTypeId = CoreBlockIds.WIDGET_CONFIG,
    displayName = "Widget",
    description = "Delegates a host-owned widget surface to the prepared content renderer",
    settings = WidgetSettings::class,
    codec = WidgetCodec::class,
    contractTests = WidgetContract::class,
    compatibleSlotTypes = [CoreBlockIds.GRID_SLOT, CoreBlockIds.FULL_SLOT],
)
object WidgetBlock : BlockContribution<WidgetConfiguration> {
    override fun open(context: ContributionContext<WidgetConfiguration>): BlockSession =
        CoreBlockSession(context, "Widget") { input ->
            Widget(input, context.configuration)
        }
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

@RegisterBlock(
    id = CoreBlockIds.SEARCH,
    contractMajor = 1,
    configTypeId = CoreBlockIds.SEARCH_CONFIG,
    displayName = "Search",
    description = "Renders host-prepared search state and emits typed presentation actions",
    providedCapabilities = ["org.quicklauncher.capability/theme-accent"],
    settings = SearchSettings::class,
    codec = SearchCodec::class,
    contractTests = SearchContract::class,
    compatibleSlotTypes = [CoreBlockIds.GRID_SLOT, CoreBlockIds.FULL_SLOT],
    occupiedScrollAxes = [ScrollAxisSpec.VERTICAL],
    requiresSearchPresentation = true,
)
object SearchBlock : BlockContribution<SearchConfiguration> {
    override fun open(context: ContributionContext<SearchConfiguration>): BlockSession =
        CoreBlockSession(context, "Search apps") { input -> SearchApps(input, context.configuration) }
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
    StatusOrContent(input, configuration.title) {
        val folders = input.state.content.items.filter {
            it.kind == org.quicklauncher.contracts.ui.PreparedContentKind.FOLDER
        }
        if (folders.isEmpty()) {
            StatusText("Folder unavailable")
        } else {
            LazyVerticalGrid(
                columns = GridCells.Fixed(minOf(configuration.columns, folders.size)),
                modifier = Modifier.fillMaxSize(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                items(folders, key = { it.id.value }) { folder ->
                    Card(
                        Modifier.fillMaxWidth().combinedClickable(
                            enabled = input.state.composition.isInteractive && folder.enabled,
                            onClick = { input.actions.emit(BlockAction.ActivateItem(folder.id)) },
                            onClickLabel = "Open ${folder.label}",
                        ).semantics {
                            contentDescription = "${folder.label}, folder"
                        },
                    ) {
                        Column(Modifier.padding(16.dp)) {
                            Text(folder.label, style = MaterialTheme.typography.titleLarge)
                            folder.supportingText?.let { Text(it) }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun Widget(input: BlockRenderInput, configuration: WidgetConfiguration) {
    StatusOrContent(input, "Widget") {
        val surface = input.state.content.surfaces.singleOrNull {
            it.kind == org.quicklauncher.contracts.ui.PreparedSurfaceKind.WIDGET
        }
        if (surface == null) {
            if (configuration.showUnavailableExplanation) StatusText("Widget unavailable")
        } else {
            input.content.RenderSurface(surface, Modifier.fillMaxSize())
        }
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
private fun SearchApps(input: BlockRenderInput, configuration: SearchConfiguration) {
    StatusOrContent(input, "search") {
        val presentation = input.search
        if (presentation == null) {
            StatusText("Search unavailable")
            return@StatusOrContent
        }
        val state = presentation.state
        val focusManager = LocalFocusManager.current
        val visibleResults = state.results
        val searchFocus = remember { FocusRequester() }
        val resultFocus = remember(visibleResults.map { it.token }) {
            visibleResults.map { FocusRequester() }
        }
        LaunchedEffect(state.focusRequestId) {
            if (state.focusRequestId > 0L) searchFocus.requestFocus()
        }
        Column(Modifier.fillMaxSize()) {
            OutlinedTextField(
                value = state.query,
                onValueChange = { value ->
                    presentation.actions.emit(
                        SearchPresentationAction.QueryChanged(value, configuration.minimumCharacters),
                    )
                },
                enabled = input.state.composition.isInteractive,
                label = { Text("Search") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth().semantics {
                    contentDescription = "Search input"
                }.focusRequester(searchFocus).onPreviewKeyEvent { event ->
                    if (event.type != KeyEventType.KeyDown) return@onPreviewKeyEvent false
                    when (event.key) {
                        Key.Enter, Key.NumPadEnter -> visibleResults.firstOrNull()?.let { first ->
                            presentation.actions.emit(SearchPresentationAction.Activate(first.token))
                            true
                        } ?: false
                        Key.Escape -> {
                            presentation.actions.emit(SearchPresentationAction.Dismiss)
                            true
                        }
                        Key.DirectionDown -> resultFocus.firstOrNull()?.let {
                            it.requestFocus()
                            true
                        } ?: focusManager.moveFocus(FocusDirection.Down)
                        Key.DirectionUp -> focusManager.moveFocus(FocusDirection.Up)
                        else -> false
                    }
                },
            )
            state.providerStates.entries
                .filter { (_, providerState) ->
                    providerState != SearchPresentationProviderState.IDLE &&
                        providerState != SearchPresentationProviderState.READY
                }
                .sortedBy { (providerId, _) -> providerId.value }
                .forEach { (providerId, providerState) ->
                    val providerName = state.providerLabels[providerId] ?: "Search provider"
                    val status = when (providerState) {
                        SearchPresentationProviderState.LOADING -> "loading"
                        SearchPresentationProviderState.DENIED -> "access denied"
                        SearchPresentationProviderState.UNAVAILABLE -> "unavailable"
                        SearchPresentationProviderState.TIMED_OUT -> "timed out"
                        SearchPresentationProviderState.FAILED -> "failed"
                        SearchPresentationProviderState.IDLE,
                        SearchPresentationProviderState.READY,
                        -> null
                    }
                    if (status != null) {
                        Text("$providerName: $status", style = MaterialTheme.typography.labelMedium)
                    }
                }
            if (visibleResults.isEmpty()) {
                val providerStates = state.providerStates.values
                val message = when {
                    providerStates.any { it == SearchPresentationProviderState.LOADING } -> "Searching"
                    providerStates.any { it == SearchPresentationProviderState.DENIED } -> "Search access required"
                    providerStates.isNotEmpty() && providerStates.all {
                        it == SearchPresentationProviderState.UNAVAILABLE ||
                            it == SearchPresentationProviderState.TIMED_OUT ||
                            it == SearchPresentationProviderState.FAILED
                    } -> "Search providers unavailable"
                    state.query.isBlank() -> "Type to search"
                    else -> "No results"
                }
                StatusText(message)
            } else {
                LazyColumn(Modifier.weight(1f)) {
                    itemsIndexed(
                        visibleResults,
                        key = { _, result ->
                            "${result.token.providerId.value}:${result.token.resultId.value}"
                        },
                    ) { index, result ->
                        if (index == 0 || visibleResults[index - 1].providerLabel != result.providerLabel) {
                            Text(
                                result.providerLabel,
                                style = MaterialTheme.typography.labelLarge,
                                modifier = Modifier.semantics { heading() },
                            )
                        }
                        Card(
                            Modifier.fillMaxWidth().focusRequester(resultFocus[index]).onPreviewKeyEvent { event ->
                                if (event.type != KeyEventType.KeyDown) return@onPreviewKeyEvent false
                                when (event.key) {
                                    Key.Enter, Key.NumPadEnter -> {
                                        presentation.actions.emit(
                                            SearchPresentationAction.Activate(result.token),
                                        )
                                        true
                                    }
                                    Key.Escape -> {
                                        presentation.actions.emit(SearchPresentationAction.Dismiss)
                                        true
                                    }
                                    Key.DirectionDown -> resultFocus.getOrNull(index + 1)?.let {
                                        it.requestFocus()
                                        true
                                    } ?: false
                                    Key.DirectionUp -> {
                                        (resultFocus.getOrNull(index - 1) ?: searchFocus).requestFocus()
                                        true
                                    }
                                    else -> false
                                }
                            }.combinedClickable(
                                enabled = input.state.composition.isInteractive,
                                onClick = {
                                    presentation.actions.emit(SearchPresentationAction.Activate(result.token))
                                },
                            ).semantics {
                                contentDescription = if (result.workBadged) {
                                    "${result.title}, Work"
                                } else {
                                    result.title
                                }
                            },
                        ) {
                            Column(Modifier.padding(12.dp)) {
                                Text(result.title)
                                if (result.workBadged) {
                                    Text(
                                        "Work",
                                        style = MaterialTheme.typography.labelSmall,
                                        maxLines = 1,
                                        modifier = Modifier.semantics {
                                            contentDescription = "Work profile"
                                        },
                                    )
                                }
                                result.subtitle
                                    ?.takeUnless { result.workBadged && it.equals("Work", ignoreCase = true) }
                                    ?.let { Text(it) }
                            }
                        }
                    }
                }
            }
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
    contributionId = CoreBlockIds.WIDGET,
    category = ContributionCategorySpec.BLOCK,
    scenarios = [PreviewScenarioSpec.EMPTY, PreviewScenarioSpec.NORMAL, PreviewScenarioSpec.LOADING, PreviewScenarioSpec.PERMISSION_DENIED, PreviewScenarioSpec.PROFILE_LOCKED, PreviewScenarioSpec.LARGE_TEXT, PreviewScenarioSpec.ERROR],
    performanceHooks = [PerformanceMetricSpec.FIRST_RENDER, PerformanceMetricSpec.ACTION_DISPATCH, PerformanceMetricSpec.DISPOSAL],
)
object WidgetContract : CoreBlockContract<WidgetConfiguration>(CoreBlockIds.WIDGET)

@ContractTestSpec(
    contributionId = CoreBlockIds.CLOCK,
    category = ContributionCategorySpec.BLOCK,
    scenarios = [PreviewScenarioSpec.EMPTY, PreviewScenarioSpec.NORMAL, PreviewScenarioSpec.LOADING, PreviewScenarioSpec.PERMISSION_DENIED, PreviewScenarioSpec.PROFILE_LOCKED, PreviewScenarioSpec.LARGE_TEXT, PreviewScenarioSpec.ERROR],
    performanceHooks = [PerformanceMetricSpec.FIRST_RENDER, PerformanceMetricSpec.ACTION_DISPATCH, PerformanceMetricSpec.DISPOSAL],
)
object ClockDateContract : CoreBlockContract<ClockDateConfiguration>(CoreBlockIds.CLOCK)

@ContractTestSpec(
    contributionId = CoreBlockIds.SEARCH,
    category = ContributionCategorySpec.BLOCK,
    scenarios = [PreviewScenarioSpec.EMPTY, PreviewScenarioSpec.NORMAL, PreviewScenarioSpec.LOADING, PreviewScenarioSpec.PERMISSION_DENIED, PreviewScenarioSpec.PROFILE_LOCKED, PreviewScenarioSpec.LARGE_TEXT, PreviewScenarioSpec.ERROR],
    performanceHooks = [PerformanceMetricSpec.FIRST_RENDER, PerformanceMetricSpec.ACTION_DISPATCH, PerformanceMetricSpec.DISPOSAL],
)
object SearchContract : CoreBlockContract<SearchConfiguration>(CoreBlockIds.SEARCH)

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
    const val WIDGET = "org.quicklauncher.block/widget"
    const val WIDGET_CONFIG = "org.quicklauncher.block/widget-config"
    const val CLOCK = "org.quicklauncher.block/clock-date"
    const val CLOCK_CONFIG = "org.quicklauncher.block/clock-date-config"
    const val SEARCH = "org.quicklauncher.block/search"
    const val SEARCH_CONFIG = "org.quicklauncher.block/search-config"
}

private val ConfigurationJson = Json {
    encodeDefaults = true
    explicitNulls = false
    ignoreUnknownKeys = false
}
