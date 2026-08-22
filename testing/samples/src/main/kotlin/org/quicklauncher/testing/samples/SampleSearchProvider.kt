package org.quicklauncher.testing.samples

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.filterIsInstance
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.takeWhile
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.yield
import org.quicklauncher.contracts.contribution.ContributionContext
import org.quicklauncher.contracts.contribution.ContributionTypes
import org.quicklauncher.contracts.contribution.DisplayText
import org.quicklauncher.contracts.contribution.ProviderResult
import org.quicklauncher.contracts.contribution.SearchProviderContribution
import org.quicklauncher.contracts.contribution.SearchProviderDescriptor
import org.quicklauncher.contracts.contribution.SearchProviderSession
import org.quicklauncher.contracts.contribution.SearchQuery
import org.quicklauncher.contracts.contribution.SearchResultAction
import org.quicklauncher.contracts.contribution.SearchResultKind
import org.quicklauncher.contracts.domain.CapabilityId
import org.quicklauncher.contracts.domain.SearchActionId
import org.quicklauncher.contracts.domain.SearchResultId
import org.quicklauncher.contracts.ui.PerformanceMetric
import org.quicklauncher.contracts.ui.PreviewScenario
import org.quicklauncher.registry.annotations.RegisterSearchProvider
import org.quicklauncher.registry.annotations.SearchResultKindSpec
import org.quicklauncher.registry.annotations.ContractTestSpec
import org.quicklauncher.registry.annotations.ContributionCategorySpec
import org.quicklauncher.registry.annotations.PerformanceMetricSpec
import org.quicklauncher.registry.annotations.PreviewScenarioSpec
import org.quicklauncher.testing.contracts.SearchContractFixture
import org.quicklauncher.testing.contracts.SearchProviderContractDeclaration
import org.quicklauncher.testing.fakes.FakeCancellationSignal
import org.quicklauncher.testing.fakes.FakeInstanceLifecycle

private val searchCapability = CapabilityId.parse("org.quicklauncher.capability/sample-search")
private val normalSearchResult = ProviderResult(
    id = SearchResultId.parse("org.quicklauncher.result/sample"),
    title = DisplayText.of("Sample result"),
    subtitle = DisplayText.of("Typed search action"),
    kind = SearchResultKind.INFORMATION,
    action = SearchResultAction.Execute(SearchActionId.parse("org.quicklauncher.action/open-sample")),
    providerRelevance = 500,
)

@RegisterSearchProvider(
    id = "org.quicklauncher.samples/search",
    contractMajor = 1,
    configTypeId = "org.quicklauncher.samples/search-config",
    displayName = "Sample search provider",
    description = "Skeletal Sample search provider contribution used by Phase 1 contract tests",
    providedCapabilities = ["org.quicklauncher.capability/sample-search"],
    settings = SampleSettings::class,
    codec = SearchConfigurationCodec::class,
    contractTests = SampleSearchContract::class,
    resultKinds = [SearchResultKindSpec.INFORMATION],
)
object SampleSearchProvider : SearchProviderContribution<SampleConfiguration> {
    override fun open(context: ContributionContext<SampleConfiguration>): SearchProviderSession {
        context.cancellation.ensureActive()
        return Session(context)
    }

    private class Session(
        private val context: ContributionContext<SampleConfiguration>,
    ) : SearchProviderSession {
        private val lock = Any()
        private val sessionJob = SupervisorJob(
            checkNotNull(context.instanceScope.coroutineContext[Job]) {
                "ContributionContext.instanceScope must own a lifecycle Job"
            },
        )
        private val sessionScope = CoroutineScope(context.instanceScope.coroutineContext + sessionJob)
        private val events = MutableStateFlow<ResultEvent>(ResultEvent.Initial)
        private var generation = 0L
        private var queryJob: Job? = null

        override val results: Flow<List<ProviderResult>> = flow {
            val minimumGeneration = synchronized(lock) { generation }
            emitAll(
                events
                    .takeWhile { event -> event !== ResultEvent.Closed }
                    .filterIsInstance<ResultEvent.Published>()
                    .filter { event -> event.generation >= minimumGeneration }
                    .map { event -> event.results },
            )
        }

        @Volatile
        override var isClosed: Boolean = false
            private set

        override fun updateQuery(query: SearchQuery) {
            synchronized(lock) {
                check(!isClosed) { "Search session is closed" }
                context.cancellation.ensureActive()
                generation += 1
                val requestedGeneration = generation
                queryJob?.cancel()
                queryJob = sessionScope.launch {
                    yield()
                    context.cancellation.ensureActive()
                    val queryResults = if (context.configuration.enabled && query.value == "normal") {
                        immutableSampleList(listOf(normalSearchResult))
                    } else {
                        immutableSampleList(emptyList())
                    }
                    synchronized(lock) {
                        if (
                            !isClosed &&
                            requestedGeneration == generation &&
                            sessionJob.isActive &&
                            !context.cancellation.isCancelled
                        ) {
                            events.value = ResultEvent.Published(requestedGeneration, queryResults)
                        }
                    }
                }
            }
        }

        override fun close() {
            synchronized(lock) {
                if (isClosed) return
                isClosed = true
                generation += 1
                queryJob?.cancel()
                events.value = ResultEvent.Closed
                sessionJob.cancel()
            }
        }

        private sealed interface ResultEvent {
            data object Initial : ResultEvent
            data class Published(
                val generation: Long,
                val results: List<ProviderResult>,
            ) : ResultEvent
            data object Closed : ResultEvent
        }
    }
}

@ContractTestSpec(
    contributionId = "org.quicklauncher.samples/search",
    category = ContributionCategorySpec.SEARCH_PROVIDER,
    scenarios = [PreviewScenarioSpec.EMPTY, PreviewScenarioSpec.NORMAL, PreviewScenarioSpec.LOADING,
        PreviewScenarioSpec.PERMISSION_DENIED, PreviewScenarioSpec.PROFILE_LOCKED,
        PreviewScenarioSpec.LARGE_TEXT, PreviewScenarioSpec.ERROR],
    performanceHooks = [PerformanceMetricSpec.FIRST_RESULT, PerformanceMetricSpec.QUERY_REPLACEMENT,
        PerformanceMetricSpec.DISPOSAL],
)
object SampleSearchContract : SearchProviderContractDeclaration<SampleConfiguration> {
    override val contributionId = SampleIds.SEARCH
    override val target = SampleSearchProvider
    override val codec = SearchConfigurationCodec
    override val descriptor = SearchProviderDescriptor(
        metadata = sampleMetadata(
            SampleIds.SEARCH,
            ContributionTypes.SEARCH_PROVIDER,
            codec.configType,
            "Sample search provider",
            setOf(searchCapability),
        ),
        resultKinds = setOf(SearchResultKind.INFORMATION),
        minimumQueryLength = 0,
    )
    override val configurationCases = sampleConfigurationCases(codec)
    override val instanceId = SampleIds.SEARCH_INSTANCE
    override val fixtures = immutableSampleList(
        PreviewScenario.entries.map { scenario ->
            SearchContractFixture(
                scenario,
                SearchQuery.of(scenario.name.lowercase()),
                immutableSampleList(
                    if (scenario == PreviewScenario.NORMAL) listOf(normalSearchResult) else emptyList(),
                ),
            )
        },
    )
    override val scenarios = immutableSampleSet(PreviewScenario.entries)
    override val previewAccessibility = sampleAccessibilityFixtures()
    override val performanceChecks = immutableSampleList(
        listOf(
            samplePerformanceCheck(PerformanceMetric.FIRST_RESULT, "Publish the normal result fixture") {
                runBlocking {
                    val lifecycle = FakeInstanceLifecycle()
                    val session = target.open(
                        ContributionContext(instanceId, codec.default, FakeCancellationSignal(), lifecycle.scope),
                    )
                    val fixture = fixtures.single { it.scenario == PreviewScenario.NORMAL }
                    session.updateQuery(fixture.query)
                    check(session.results.first() == fixture.expectedResults)
                    session.close()
                    lifecycle.close()
                }
            },
            samplePerformanceCheck(PerformanceMetric.QUERY_REPLACEMENT, "Replace an active sample query") {
                runBlocking {
                    val lifecycle = FakeInstanceLifecycle()
                    val session = target.open(
                        ContributionContext(instanceId, codec.default, FakeCancellationSignal(), lifecycle.scope),
                    )
                    session.updateQuery(fixtures.single { it.scenario == PreviewScenario.NORMAL }.query)
                    session.updateQuery(fixtures.single { it.scenario == PreviewScenario.ERROR }.query)
                    check(session.results.first().isEmpty())
                    session.close()
                    lifecycle.close()
                }
            },
            samplePerformanceCheck(PerformanceMetric.DISPOSAL, "Dispose a search provider session") {
                val lifecycle = FakeInstanceLifecycle()
                val session = target.open(
                    ContributionContext(instanceId, codec.default, FakeCancellationSignal(), lifecycle.scope),
                )
                session.close()
                check(session.isClosed)
                lifecycle.close()
            },
        ),
    )
    override val performanceHooks = immutableSampleList(performanceChecks.map { it.declaration })
}
