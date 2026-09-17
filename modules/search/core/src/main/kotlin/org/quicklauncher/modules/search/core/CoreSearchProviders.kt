package org.quicklauncher.modules.search.core

import java.util.Collections
import java.util.Locale
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import org.quicklauncher.contracts.contribution.CodecResult
import org.quicklauncher.contracts.contribution.ConfigurationCodec
import org.quicklauncher.contracts.contribution.ContributionContext
import org.quicklauncher.contracts.contribution.EncodedConfiguration
import org.quicklauncher.contracts.contribution.GenerationAwareSearchProviderSession
import org.quicklauncher.contracts.contribution.ProviderResult
import org.quicklauncher.contracts.contribution.ProviderResultSnapshot
import org.quicklauncher.contracts.contribution.SearchProviderContractTestDeclaration
import org.quicklauncher.contracts.contribution.SearchProviderContribution
import org.quicklauncher.contracts.contribution.SearchProviderRequest
import org.quicklauncher.contracts.contribution.SearchProviderSession
import org.quicklauncher.contracts.contribution.SearchQuery
import org.quicklauncher.contracts.contribution.SearchResultKind
import org.quicklauncher.contracts.domain.ConfigTypeId
import org.quicklauncher.contracts.domain.ContributionId
import org.quicklauncher.contracts.domain.SchemaVersion
import org.quicklauncher.contracts.ui.PerformanceHookDeclaration
import org.quicklauncher.contracts.ui.PerformanceMetric
import org.quicklauncher.contracts.ui.PreviewScenario
import org.quicklauncher.registry.annotations.ConfigurationCodecSpec
import org.quicklauncher.registry.annotations.ContractTestSpec
import org.quicklauncher.registry.annotations.ContributionCategorySpec
import org.quicklauncher.registry.annotations.PerformanceMetricSpec
import org.quicklauncher.registry.annotations.PreviewScenarioSpec
import org.quicklauncher.registry.annotations.RegisterSearchProvider
import org.quicklauncher.registry.annotations.SearchResultKindSpec

data class CoreSearchConfiguration(val maximumResults: Int = 25) {
    init {
        require(maximumResults in 1..50) { "Search provider result limit must be between 1 and 50" }
    }
}

abstract class CoreSearchCodec(configTypeId: String) : ConfigurationCodec<CoreSearchConfiguration> {
    final override val configType = ConfigTypeId.parse(configTypeId)
    override val currentSchemaVersion = SchemaVersion.of(1)
    override val default = CoreSearchConfiguration()

    override fun encode(value: CoreSearchConfiguration): EncodedConfiguration =
        EncodedConfiguration.of(value.maximumResults.toString())

    override fun decode(encoded: EncodedConfiguration): CodecResult<CoreSearchConfiguration> = try {
        CodecResult.Decoded(CoreSearchConfiguration(encoded.value.toInt()))
    } catch (_: IllegalArgumentException) {
        CodecResult.Failed("Search provider configuration is invalid")
    }
}

@ConfigurationCodecSpec(configTypeId = CoreSearchIds.APPS_CONFIG)
object AppsSearchCodec : CoreSearchCodec(CoreSearchIds.APPS_CONFIG)
@ConfigurationCodecSpec(configTypeId = CoreSearchIds.SHORTCUTS_CONFIG)
object ShortcutsSearchCodec : CoreSearchCodec(CoreSearchIds.SHORTCUTS_CONFIG)
@ConfigurationCodecSpec(configTypeId = CoreSearchIds.COMMANDS_CONFIG)
object CommandsSearchCodec : CoreSearchCodec(CoreSearchIds.COMMANDS_CONFIG)
@ConfigurationCodecSpec(configTypeId = CoreSearchIds.CONTACTS_CONFIG)
object ContactsSearchCodec : CoreSearchCodec(CoreSearchIds.CONTACTS_CONFIG)
@ConfigurationCodecSpec(configTypeId = CoreSearchIds.FILES_CONFIG)
object FilesSearchCodec : CoreSearchCodec(CoreSearchIds.FILES_CONFIG)
@ConfigurationCodecSpec(configTypeId = CoreSearchIds.SETTINGS_CONFIG)
object SettingsSearchCodec : CoreSearchCodec(CoreSearchIds.SETTINGS_CONFIG)
@ConfigurationCodecSpec(configTypeId = CoreSearchIds.GRAPHENE_SETTINGS_CONFIG)
object GrapheneSettingsSearchCodec : CoreSearchCodec(CoreSearchIds.GRAPHENE_SETTINGS_CONFIG)
@ConfigurationCodecSpec(configTypeId = CoreSearchIds.WEB_CONFIG)
object WebSearchCodec : CoreSearchCodec(CoreSearchIds.WEB_CONFIG)
@ConfigurationCodecSpec(configTypeId = CoreSearchIds.INFORMATION_CONFIG)
object InformationSearchCodec : CoreSearchCodec(CoreSearchIds.INFORMATION_CONFIG)

abstract class CoreSearchProvider(
    private val resultKinds: Set<SearchResultKind>,
) : SearchProviderContribution<CoreSearchConfiguration> {
    override fun open(context: ContributionContext<CoreSearchConfiguration>): SearchProviderSession {
        context.cancellation.ensureActive()
        return CoreSearchSession(context, resultKinds)
    }
}

private class CoreSearchSession(
    private val context: ContributionContext<CoreSearchConfiguration>,
    private val resultKinds: Set<SearchResultKind>,
) : GenerationAwareSearchProviderSession {
    private val closed = AtomicBoolean(false)
    private val ownedJob = SupervisorJob(context.instanceScope.coroutineContext[Job])
    private val scope = CoroutineScope(context.instanceScope.coroutineContext + ownedJob)
    private val mutableSnapshots = MutableSharedFlow<ProviderResultSnapshot>(extraBufferCapacity = 1)
    private var generation = 0L
    private var queryJob: Job? = null

    override val snapshots: Flow<ProviderResultSnapshot> = mutableSnapshots
    override val results: Flow<List<ProviderResult>> = snapshots.map { it.results }
    override val isClosed: Boolean get() = closed.get()

    override fun update(request: SearchProviderRequest) {
        check(!closed.get()) { "Search provider session is closed" }
        context.cancellation.ensureActive()
        require(request.generation > generation) { "Search generation must increase" }
        generation = request.generation
        queryJob?.cancel()
        queryJob = scope.launch {
            val requestedGeneration = request.generation
            val results = request.candidates.asSequence()
                .filter { it.kind in resultKinds }
                .sortedWith(
                    compareByDescending<ProviderResult> { it.providerRelevance }
                        .thenBy { it.title.value.lowercase(Locale.ROOT) }
                        .thenBy { it.id.value },
                )
                .take(context.configuration.maximumResults)
                .toList()
            if (!closed.get() && generation == requestedGeneration) {
                mutableSnapshots.emit(ProviderResultSnapshot(requestedGeneration, results))
            }
        }
    }

    override fun updateQuery(query: SearchQuery) {
        update(SearchProviderRequest(generation + 1L, query, emptyList()))
    }

    override fun close() {
        if (!closed.compareAndSet(false, true)) return
        generation += 1
        scope.cancel()
    }
}

@RegisterSearchProvider(
    id = CoreSearchIds.APPS, contractMajor = 1, configTypeId = CoreSearchIds.APPS_CONFIG,
    displayName = "Applications", description = "Profile-aware applications prepared by the host",
    codec = AppsSearchCodec::class, contractTests = AppsSearchContract::class,
    resultKinds = [SearchResultKindSpec.APP], minimumQueryLength = 0,
)
object AppsSearchProvider : CoreSearchProvider(setOf(SearchResultKind.APP))

@RegisterSearchProvider(
    id = CoreSearchIds.SHORTCUTS, contractMajor = 1, configTypeId = CoreSearchIds.SHORTCUTS_CONFIG,
    displayName = "Shortcuts", description = "Profile-aware dynamic and pinned shortcuts prepared by the host",
    codec = ShortcutsSearchCodec::class, contractTests = ShortcutsSearchContract::class,
    resultKinds = [SearchResultKindSpec.SHORTCUT], minimumQueryLength = 1,
)
object ShortcutsSearchProvider : CoreSearchProvider(setOf(SearchResultKind.SHORTCUT))

@RegisterSearchProvider(
    id = CoreSearchIds.COMMANDS, contractMajor = 1, configTypeId = CoreSearchIds.COMMANDS_CONFIG,
    displayName = "Launcher commands", description = "Registered launcher commands prepared by the host",
    codec = CommandsSearchCodec::class, contractTests = CommandsSearchContract::class,
    resultKinds = [SearchResultKindSpec.COMMAND], minimumQueryLength = 0,
)
object CommandsSearchProvider : CoreSearchProvider(setOf(SearchResultKind.COMMAND))

@RegisterSearchProvider(
    id = CoreSearchIds.CONTACTS, contractMajor = 1, configTypeId = CoreSearchIds.CONTACTS_CONFIG,
    displayName = "Contacts", description = "Explicitly authorized contacts prepared by the host",
    codec = ContactsSearchCodec::class, contractTests = ContactsSearchContract::class,
    resultKinds = [SearchResultKindSpec.CONTACT, SearchResultKindSpec.INFORMATION], minimumQueryLength = 2,
)
object ContactsSearchProvider : CoreSearchProvider(setOf(SearchResultKind.CONTACT, SearchResultKind.INFORMATION))

@RegisterSearchProvider(
    id = CoreSearchIds.FILES, contractMajor = 1, configTypeId = CoreSearchIds.FILES_CONFIG,
    displayName = "Files", description = "Files within explicit Storage Access Framework grants",
    codec = FilesSearchCodec::class, contractTests = FilesSearchContract::class,
    resultKinds = [SearchResultKindSpec.FILE, SearchResultKindSpec.INFORMATION], minimumQueryLength = 2,
)
object FilesSearchProvider : CoreSearchProvider(setOf(SearchResultKind.FILE, SearchResultKind.INFORMATION))

@RegisterSearchProvider(
    id = CoreSearchIds.SETTINGS, contractMajor = 1, configTypeId = CoreSearchIds.SETTINGS_CONFIG,
    displayName = "Android Settings", description = "Callable public Android Settings routes prepared by the host",
    codec = SettingsSearchCodec::class, contractTests = SettingsSearchContract::class,
    resultKinds = [SearchResultKindSpec.SETTING], minimumQueryLength = 1,
)
object SettingsSearchProvider : CoreSearchProvider(setOf(SearchResultKind.SETTING))

@RegisterSearchProvider(
    id = CoreSearchIds.GRAPHENE_SETTINGS, contractMajor = 1, configTypeId = CoreSearchIds.GRAPHENE_SETTINGS_CONFIG,
    displayName = "GrapheneOS Settings", description = "Versioned callable GrapheneOS routes prepared by the host",
    codec = GrapheneSettingsSearchCodec::class, contractTests = GrapheneSettingsSearchContract::class,
    resultKinds = [SearchResultKindSpec.SETTING, SearchResultKindSpec.INFORMATION], minimumQueryLength = 1,
)
object GrapheneSettingsSearchProvider : CoreSearchProvider(setOf(SearchResultKind.SETTING, SearchResultKind.INFORMATION))

@RegisterSearchProvider(
    id = CoreSearchIds.WEB, contractMajor = 1, configTypeId = CoreSearchIds.WEB_CONFIG,
    displayName = "Web", description = "Typed safe external HTTPS search actions prepared by the host",
    codec = WebSearchCodec::class, contractTests = WebSearchContract::class,
    resultKinds = [SearchResultKindSpec.WEB], minimumQueryLength = 1,
)
object WebSearchProvider : CoreSearchProvider(setOf(SearchResultKind.WEB))

@RegisterSearchProvider(
    id = CoreSearchIds.INFORMATION, contractMajor = 1, configTypeId = CoreSearchIds.INFORMATION_CONFIG,
    displayName = "Information", description = "Host-prepared recovery and explanatory search results",
    codec = InformationSearchCodec::class, contractTests = InformationSearchContract::class,
    resultKinds = [SearchResultKindSpec.INFORMATION], minimumQueryLength = 0,
)
object InformationSearchProvider : CoreSearchProvider(setOf(SearchResultKind.INFORMATION))

abstract class CoreSearchContract(id: String) :
    SearchProviderContractTestDeclaration<CoreSearchConfiguration> {
    final override val contributionId = ContributionId.parse(id)
    final override val scenarios: Set<PreviewScenario> = immutableSet(PreviewScenario.entries)
    final override val performanceHooks: List<PerformanceHookDeclaration> = immutableList(
        listOf(
            PerformanceHookDeclaration(PerformanceMetric.FIRST_RESULT, "Publish a bounded provider result"),
            PerformanceHookDeclaration(PerformanceMetric.QUERY_REPLACEMENT, "Replace the provider generation"),
            PerformanceHookDeclaration(PerformanceMetric.DISPOSAL, "Close the provider session"),
        ),
    )
}

@ContractTestSpec(contributionId = CoreSearchIds.APPS, category = ContributionCategorySpec.SEARCH_PROVIDER, scenarios = [PreviewScenarioSpec.EMPTY, PreviewScenarioSpec.NORMAL, PreviewScenarioSpec.LOADING, PreviewScenarioSpec.PERMISSION_DENIED, PreviewScenarioSpec.PROFILE_LOCKED, PreviewScenarioSpec.LARGE_TEXT, PreviewScenarioSpec.ERROR], performanceHooks = [PerformanceMetricSpec.FIRST_RESULT, PerformanceMetricSpec.QUERY_REPLACEMENT, PerformanceMetricSpec.DISPOSAL])
object AppsSearchContract : CoreSearchContract(CoreSearchIds.APPS)
@ContractTestSpec(contributionId = CoreSearchIds.SHORTCUTS, category = ContributionCategorySpec.SEARCH_PROVIDER, scenarios = [PreviewScenarioSpec.EMPTY, PreviewScenarioSpec.NORMAL, PreviewScenarioSpec.LOADING, PreviewScenarioSpec.PERMISSION_DENIED, PreviewScenarioSpec.PROFILE_LOCKED, PreviewScenarioSpec.LARGE_TEXT, PreviewScenarioSpec.ERROR], performanceHooks = [PerformanceMetricSpec.FIRST_RESULT, PerformanceMetricSpec.QUERY_REPLACEMENT, PerformanceMetricSpec.DISPOSAL])
object ShortcutsSearchContract : CoreSearchContract(CoreSearchIds.SHORTCUTS)
@ContractTestSpec(contributionId = CoreSearchIds.COMMANDS, category = ContributionCategorySpec.SEARCH_PROVIDER, scenarios = [PreviewScenarioSpec.EMPTY, PreviewScenarioSpec.NORMAL, PreviewScenarioSpec.LOADING, PreviewScenarioSpec.PERMISSION_DENIED, PreviewScenarioSpec.PROFILE_LOCKED, PreviewScenarioSpec.LARGE_TEXT, PreviewScenarioSpec.ERROR], performanceHooks = [PerformanceMetricSpec.FIRST_RESULT, PerformanceMetricSpec.QUERY_REPLACEMENT, PerformanceMetricSpec.DISPOSAL])
object CommandsSearchContract : CoreSearchContract(CoreSearchIds.COMMANDS)
@ContractTestSpec(contributionId = CoreSearchIds.CONTACTS, category = ContributionCategorySpec.SEARCH_PROVIDER, scenarios = [PreviewScenarioSpec.EMPTY, PreviewScenarioSpec.NORMAL, PreviewScenarioSpec.LOADING, PreviewScenarioSpec.PERMISSION_DENIED, PreviewScenarioSpec.PROFILE_LOCKED, PreviewScenarioSpec.LARGE_TEXT, PreviewScenarioSpec.ERROR], performanceHooks = [PerformanceMetricSpec.FIRST_RESULT, PerformanceMetricSpec.QUERY_REPLACEMENT, PerformanceMetricSpec.DISPOSAL])
object ContactsSearchContract : CoreSearchContract(CoreSearchIds.CONTACTS)
@ContractTestSpec(contributionId = CoreSearchIds.FILES, category = ContributionCategorySpec.SEARCH_PROVIDER, scenarios = [PreviewScenarioSpec.EMPTY, PreviewScenarioSpec.NORMAL, PreviewScenarioSpec.LOADING, PreviewScenarioSpec.PERMISSION_DENIED, PreviewScenarioSpec.PROFILE_LOCKED, PreviewScenarioSpec.LARGE_TEXT, PreviewScenarioSpec.ERROR], performanceHooks = [PerformanceMetricSpec.FIRST_RESULT, PerformanceMetricSpec.QUERY_REPLACEMENT, PerformanceMetricSpec.DISPOSAL])
object FilesSearchContract : CoreSearchContract(CoreSearchIds.FILES)
@ContractTestSpec(contributionId = CoreSearchIds.SETTINGS, category = ContributionCategorySpec.SEARCH_PROVIDER, scenarios = [PreviewScenarioSpec.EMPTY, PreviewScenarioSpec.NORMAL, PreviewScenarioSpec.LOADING, PreviewScenarioSpec.PERMISSION_DENIED, PreviewScenarioSpec.PROFILE_LOCKED, PreviewScenarioSpec.LARGE_TEXT, PreviewScenarioSpec.ERROR], performanceHooks = [PerformanceMetricSpec.FIRST_RESULT, PerformanceMetricSpec.QUERY_REPLACEMENT, PerformanceMetricSpec.DISPOSAL])
object SettingsSearchContract : CoreSearchContract(CoreSearchIds.SETTINGS)
@ContractTestSpec(contributionId = CoreSearchIds.GRAPHENE_SETTINGS, category = ContributionCategorySpec.SEARCH_PROVIDER, scenarios = [PreviewScenarioSpec.EMPTY, PreviewScenarioSpec.NORMAL, PreviewScenarioSpec.LOADING, PreviewScenarioSpec.PERMISSION_DENIED, PreviewScenarioSpec.PROFILE_LOCKED, PreviewScenarioSpec.LARGE_TEXT, PreviewScenarioSpec.ERROR], performanceHooks = [PerformanceMetricSpec.FIRST_RESULT, PerformanceMetricSpec.QUERY_REPLACEMENT, PerformanceMetricSpec.DISPOSAL])
object GrapheneSettingsSearchContract : CoreSearchContract(CoreSearchIds.GRAPHENE_SETTINGS)
@ContractTestSpec(contributionId = CoreSearchIds.WEB, category = ContributionCategorySpec.SEARCH_PROVIDER, scenarios = [PreviewScenarioSpec.EMPTY, PreviewScenarioSpec.NORMAL, PreviewScenarioSpec.LOADING, PreviewScenarioSpec.PERMISSION_DENIED, PreviewScenarioSpec.PROFILE_LOCKED, PreviewScenarioSpec.LARGE_TEXT, PreviewScenarioSpec.ERROR], performanceHooks = [PerformanceMetricSpec.FIRST_RESULT, PerformanceMetricSpec.QUERY_REPLACEMENT, PerformanceMetricSpec.DISPOSAL])
object WebSearchContract : CoreSearchContract(CoreSearchIds.WEB)
@ContractTestSpec(contributionId = CoreSearchIds.INFORMATION, category = ContributionCategorySpec.SEARCH_PROVIDER, scenarios = [PreviewScenarioSpec.EMPTY, PreviewScenarioSpec.NORMAL, PreviewScenarioSpec.LOADING, PreviewScenarioSpec.PERMISSION_DENIED, PreviewScenarioSpec.PROFILE_LOCKED, PreviewScenarioSpec.LARGE_TEXT, PreviewScenarioSpec.ERROR], performanceHooks = [PerformanceMetricSpec.FIRST_RESULT, PerformanceMetricSpec.QUERY_REPLACEMENT, PerformanceMetricSpec.DISPOSAL])
object InformationSearchContract : CoreSearchContract(CoreSearchIds.INFORMATION)

object CoreSearchIds {
    const val APPS = "org.quicklauncher.search/apps"
    const val SHORTCUTS = "org.quicklauncher.search/shortcuts"
    const val COMMANDS = "org.quicklauncher.search/commands"
    const val CONTACTS = "org.quicklauncher.search/contacts"
    const val FILES = "org.quicklauncher.search/files"
    const val SETTINGS = "org.quicklauncher.search/settings"
    const val GRAPHENE_SETTINGS = "org.quicklauncher.search/graphene-settings"
    const val WEB = "org.quicklauncher.search/web"
    const val INFORMATION = "org.quicklauncher.search/information"
    const val APPS_CONFIG = "org.quicklauncher.search/apps-config"
    const val SHORTCUTS_CONFIG = "org.quicklauncher.search/shortcuts-config"
    const val COMMANDS_CONFIG = "org.quicklauncher.search/commands-config"
    const val CONTACTS_CONFIG = "org.quicklauncher.search/contacts-config"
    const val FILES_CONFIG = "org.quicklauncher.search/files-config"
    const val SETTINGS_CONFIG = "org.quicklauncher.search/settings-config"
    const val GRAPHENE_SETTINGS_CONFIG = "org.quicklauncher.search/graphene-settings-config"
    const val WEB_CONFIG = "org.quicklauncher.search/web-config"
    const val INFORMATION_CONFIG = "org.quicklauncher.search/information-config"
}

private fun <T> immutableList(values: Collection<T>): List<T> =
    Collections.unmodifiableList(ArrayList(values))

private fun <T> immutableSet(values: Collection<T>): Set<T> =
    Collections.unmodifiableSet(LinkedHashSet(values))
