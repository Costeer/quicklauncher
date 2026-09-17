package org.quicklauncher.host.runtime.search

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertTrue
import org.junit.Test
import org.quicklauncher.contracts.contribution.ContributionMetadata
import org.quicklauncher.contracts.contribution.ContributionTypes
import org.quicklauncher.contracts.contribution.DisplayText
import org.quicklauncher.contracts.contribution.GenerationAwareSearchProviderSession
import org.quicklauncher.contracts.contribution.ProviderResult
import org.quicklauncher.contracts.contribution.ProviderResultSnapshot
import org.quicklauncher.contracts.contribution.SearchProviderDescriptor
import org.quicklauncher.contracts.contribution.SearchProviderRequest
import org.quicklauncher.contracts.contribution.SearchProviderResultIds
import org.quicklauncher.contracts.contribution.SearchQuery
import org.quicklauncher.contracts.contribution.SearchResultAction
import org.quicklauncher.contracts.contribution.SearchResultKind
import org.quicklauncher.contracts.domain.ConfigTypeId
import org.quicklauncher.contracts.domain.ContractMajor
import org.quicklauncher.contracts.domain.ContributionId
import org.quicklauncher.contracts.domain.SearchActionId

@OptIn(ExperimentalCoroutinesApi::class)
class DefaultSearchEngineTest {
    @Test
    fun `query replacement propagates cancellation and rejects stale and post-close emissions`() = runTest {
        val provider = FakeBinding("replace") { request ->
            delay(if (request.query.value == "old") 500 else 10)
            listOf(result("replace", request.query.value))
        }
        val targets = FakeTargetCatalog()
        val engine = engine(listOf(provider), targets)
        val session = engine.open()

        val first = session.submit("old") as QuerySubmissionResult.Accepted
        runCurrent()
        val second = session.submit("new") as QuerySubmissionResult.Accepted
        advanceTimeBy(20)
        runCurrent()

        assertTrue(provider.session.cancelledGenerations.contains(first.generation))
        assertEquals(second.generation, session.state.value.generation)
        assertEquals(listOf("new"), session.state.value.results.map { it.result.title.value })

        provider.session.emit(first.generation, listOf(result("replace", "stale")))
        runCurrent()
        assertEquals(listOf("new"), session.state.value.results.map { it.result.title.value })

        session.close()
        provider.session.emit(second.generation, listOf(result("replace", "late")))
        runCurrent()
        assertTrue(session.state.value.closed)
        assertTrue(session.state.value.results.isEmpty())
        assertTrue(provider.session.isClosed)
        assertEquals(listOf(second.generation), provider.preparedGenerations.takeLast(1))
        assertEquals(
            listOf(1L to id("replace"), 1L to id("replace")),
            targets.releasedProviders,
        )
        assertEquals(listOf(1L), targets.releasedSessions)
        engine.close()
    }

    @Test
    fun `slow timeout and broken providers do not delay healthy results`() = runTest {
        val fast = FakeBinding("fast") { request ->
            delay(10)
            listOf(result("fast", request.query.value))
        }
        val slow = FakeBinding("slow") { request ->
            delay(5_000)
            listOf(result("slow", request.query.value))
        }
        val broken = FakeBinding("broken", sessionFactory = { scope -> FailingSession(scope) })
        val engine = engine(listOf(fast, slow, broken), timeout = 100)
        val session = engine.open()

        session.submit("ready")
        advanceTimeBy(11)
        runCurrent()

        assertEquals(listOf("ready"), session.state.value.results.map { it.result.title.value })
        assertEquals(SearchProviderState.READY, session.state.value.providerStates[id("fast")])
        assertEquals(SearchProviderState.LOADING, session.state.value.providerStates[id("slow")])
        assertEquals(SearchProviderState.FAILED, session.state.value.providerStates[id("broken")])

        advanceTimeBy(100)
        runCurrent()
        assertEquals(SearchProviderState.TIMED_OUT, session.state.value.providerStates[id("slow")])
        assertEquals(listOf("ready"), session.state.value.results.map { it.result.title.value })
        session.close()
        engine.close()
    }

    @Test
    fun `provider timeout includes host candidate preparation`() = runTest {
        val fast = FakeBinding("fast-preparation") {
            listOf(result("fast-preparation", "ready"))
        }
        val slow = FakeBinding(
            "slow-preparation",
            preparation = {
                delay(5_000)
                SearchCandidatePreparation.Available(emptyList())
            },
        ) { emptyList() }
        val engine = engine(listOf(fast, slow), timeout = 100)
        val session = engine.open()

        session.submit("ready")
        runCurrent()
        assertEquals(listOf("ready"), session.state.value.results.map { it.result.title.value })

        advanceTimeBy(101)
        runCurrent()
        assertEquals(
            SearchProviderState.TIMED_OUT,
            session.state.value.providerStates[id("slow-preparation")],
        )
        assertEquals(listOf("ready"), session.state.value.results.map { it.result.title.value })
        session.close()
        engine.close()
    }

    @Test
    fun `candidate access outcomes remain provider local`() = runTest {
        val denied = FakeBinding(
            "prepared-denied",
            preparation = { SearchCandidatePreparation.Denied },
        ) { error("Denied preparation must not invoke the provider") }
        val unavailable = FakeBinding(
            "prepared-unavailable",
            preparation = { SearchCandidatePreparation.Unavailable },
        ) { error("Unavailable preparation must not invoke the provider") }
        val healthy = FakeBinding("prepared-healthy") {
            listOf(result("prepared-healthy", "healthy"))
        }
        val engine = engine(listOf(denied, unavailable, healthy))
        val session = engine.open()

        session.submit("healthy")
        advanceUntilIdle()

        assertEquals(SearchProviderState.DENIED, session.state.value.providerStates[id("prepared-denied")])
        assertEquals(
            SearchProviderState.UNAVAILABLE,
            session.state.value.providerStates[id("prepared-unavailable")],
        )
        assertEquals(listOf("healthy"), session.state.value.results.map { it.result.title.value })
        assertEquals(0, denied.session.subscriberCount)
        assertEquals(0, unavailable.session.subscriberCount)
        session.close()
        engine.close()
    }

    @Test
    fun `presentation minimum query length is enforced before provider preparation`() = runTest {
        val provider = FakeBinding("minimum") { request ->
            listOf(result("minimum", request.query.value))
        }
        val engine = engine(listOf(provider))
        val session = engine.open()

        session.submit("abc", minimumCharacters = 4)
        advanceUntilIdle()
        assertTrue(provider.preparedGenerations.isEmpty())
        assertEquals(SearchProviderState.IDLE, session.state.value.providerStates[id("minimum")])

        session.submit("abcd", minimumCharacters = 4)
        advanceUntilIdle()
        assertEquals(listOf("abcd"), session.state.value.results.map { it.result.title.value })
        session.close()
        engine.close()
    }

    @Test
    fun `host matching happens before a provider applies its output limit`() = runTest {
        val provider = FakeBinding(
            "prefilter",
            preparation = {
                SearchCandidatePreparation.Available(
                    (0 until 100).map { index ->
                        result(
                            "prefilter",
                            if (index == 99) "Needle application" else "Candidate $index",
                            suffix = "item-$index",
                        )
                    },
                )
            },
        ) { request -> request.candidates.take(50) }
        val engine = engine(listOf(provider))
        val session = engine.open()

        session.submit("needle")
        advanceUntilIdle()

        assertEquals(
            listOf("Needle application"),
            session.state.value.results.map { it.result.title.value },
        )
        session.close()
        engine.close()
    }

    @Test
    fun `malformed output is isolated and healthy provider remains visible`() = runTest {
        val healthy = FakeBinding("healthy") { listOf(result("healthy", "valid")) }
        val malformed = FakeBinding("malformed") {
            listOf(result("different", "outside namespace"))
        }
        val engine = engine(listOf(healthy, malformed))
        val session = engine.open()

        session.submit("v")
        advanceUntilIdle()

        assertEquals(listOf("valid"), session.state.value.results.map { it.result.title.value })
        assertEquals(SearchProviderState.READY, session.state.value.providerStates[id("healthy")])
        assertEquals(SearchProviderState.FAILED, session.state.value.providerStates[id("malformed")])
        session.close()
        engine.close()
    }

    @Test
    fun `current generation keeps collecting provider refinements`() = runTest {
        val provider = FakeBinding("refining") { request ->
            listOf(result("refining", "initial-${request.query.value}"))
        }
        val engine = engine(listOf(provider))
        val session = engine.open()
        val accepted = session.submit("value") as QuerySubmissionResult.Accepted
        runCurrent()
        assertEquals(listOf("initial-value"), session.state.value.results.map { it.result.title.value })

        provider.session.emit(
            accepted.generation,
            listOf(result("refining", "refined-value")),
        )
        runCurrent()

        assertEquals(listOf("refined-value"), session.state.value.results.map { it.result.title.value })
        session.close()
        engine.close()
    }

    @Test
    fun `flow failure after a healthy emission remains provider local`() = runTest {
        val healthy = FakeBinding("flow-healthy") { listOf(result("flow-healthy", "healthy")) }
        val failing = FakeBinding("flow-failing", failAfterFirstEmission = true) {
            listOf(result("flow-failing", "transient"))
        }
        val engine = engine(listOf(healthy, failing))
        val session = engine.open()

        session.submit("h")
        advanceUntilIdle()

        assertEquals(listOf("healthy"), session.state.value.results.map { it.result.title.value })
        assertEquals(SearchProviderState.READY, session.state.value.providerStates[id("flow-healthy")])
        assertEquals(SearchProviderState.FAILED, session.state.value.providerStates[id("flow-failing")])
        session.close()
        engine.close()
    }

    @Test
    fun `snapshots and provider inputs are immutable and bounded`() = runTest {
        val provider = FakeBinding("immutable") {
            listOf(result("immutable", "one"), result("immutable", "two", suffix = "two"))
        }
        val engine = engine(listOf(provider), maximumResults = 2)
        val session = engine.open()
        session.submit("o")
        advanceUntilIdle()

        val first = session.state.value
        assertEquals(2, first.results.size)
        assertThrowsUnsupported { (first.results as MutableList).clear() }
        assertThrowsUnsupported { (first.providerStates as MutableMap).clear() }
        assertEquals("immutable", first.providerLabels[id("immutable")])
        assertThrowsUnsupported { (first.providerLabels as MutableMap).clear() }
        assertThrowsUnsupported { (provider.session.lastRequest!!.candidates as MutableList).clear() }

        session.submit("t")
        advanceUntilIdle()
        assertNotSame(first, session.state.value)
        assertEquals(2, first.results.size)
        session.close()
        engine.close()
    }

    @Test
    fun `provider denial removes only its results and later enable recovers`() = runTest {
        val first = FakeBinding("first") { listOf(result("first", "first")) }
        val second = FakeBinding("second") { listOf(result("second", "second")) }
        val engine = engine(listOf(first, second))
        val session = engine.open()
        session.submit("")
        advanceUntilIdle()

        first.mutableAvailability.value = SearchProviderAvailability.DENIED
        runCurrent()
        assertEquals(listOf("second"), session.state.value.results.map { it.result.title.value })
        assertEquals(SearchProviderState.DENIED, session.state.value.providerStates[id("first")])

        first.mutableAvailability.value = SearchProviderAvailability.ENABLED
        advanceUntilIdle()
        assertEquals(setOf("first", "second"), session.state.value.results.map { it.result.title.value }.toSet())
        session.close()
        engine.close()
    }

    @Test
    fun `activation revalidates freshness target and command registration`() = runTest {
        val action = action("activation")
        val targetCatalog = FakeTargetCatalog(mutableMapOf(action to target(action)))
        val executions = mutableListOf<SearchResultAction>()
        val provider = FakeBinding("activation", kinds = setOf(SearchResultKind.INFORMATION)) {
            listOf(
                result(
                    "activation",
                    "activate",
                    kind = SearchResultKind.INFORMATION,
                    action = SearchResultAction.Execute(action),
                ),
            )
        }
        val engine = engine(
            listOf(provider),
            targetCatalog,
            SearchActionExecutor { value -> executions += value; SearchExecutionResult.Succeeded },
        )
        val session = engine.open()
        session.submit("activate")
        advanceUntilIdle()
        val token = session.state.value.results.single().token

        targetCatalog.values.remove(action)
        assertEquals(SearchExecutionResult.Rejected, session.activate(token))
        assertTrue(executions.isEmpty())

        targetCatalog.values[action] = target(action)
        targetCatalog.mutableInvalidations.emit(Unit)
        advanceUntilIdle()
        val currentToken = session.state.value.results.single().token
        assertEquals(SearchExecutionResult.Succeeded, session.activate(currentToken))
        assertEquals(1, executions.size)

        session.submit("replacement")
        assertEquals(SearchExecutionResult.Rejected, session.activate(currentToken))
        assertEquals(1, executions.size)
        session.close()
        engine.close()
    }

    @Test
    fun `query replacement cancels an in flight activation before its side effect`() = runTest {
        val action = action("activation-cancellation")
        val targetCatalog = FakeTargetCatalog(mutableMapOf(action to target(action)))
        var started = false
        var completed = false
        val provider = FakeBinding("activation-cancellation", kinds = setOf(SearchResultKind.INFORMATION)) {
            listOf(
                result(
                    "activation-cancellation",
                    "activate",
                    kind = SearchResultKind.INFORMATION,
                    action = SearchResultAction.Execute(action),
                ),
            )
        }
        val engine = engine(
            listOf(provider),
            targetCatalog,
            SearchActionExecutor {
                started = true
                awaitCancellation()
            },
        )
        val session = engine.open()
        session.submit("activate")
        advanceUntilIdle()
        val token = session.state.value.results.single().token
        val activation = async {
            session.activate(token)
            completed = true
        }
        runCurrent()
        assertTrue(started)

        session.submit("replacement")
        runCurrent()

        assertTrue(activation.isCancelled)
        assertTrue(!completed)
        session.close()
        engine.close()
    }

    @Test
    fun `work result without host badge is rejected`() = runTest {
        val action = action("work")
        val targetCatalog = FakeTargetCatalog(
            mutableMapOf(
                action to target(
                    action,
                    kind = SearchResultKind.APP,
                    profileKind = SearchProfileKind.WORK,
                    workBadged = false,
                ),
            ),
        )
        val provider = FakeBinding("work", kinds = setOf(SearchResultKind.APP)) {
            listOf(result("work", "work", SearchResultKind.APP, action = SearchResultAction.Execute(action)))
        }
        val engine = engine(listOf(provider), targetCatalog)
        val session = engine.open()

        session.submit("work")
        advanceUntilIdle()

        assertTrue(session.state.value.results.isEmpty())
        assertEquals(SearchProviderState.READY, session.state.value.providerStates[id("work")])
        session.close()
        engine.close()
    }

    @Test
    fun `invalid query is rejected without replacing current generation`() = runTest {
        val engine = engine(listOf(FakeBinding("query") { emptyList() }) )
        val session = engine.open()
        val accepted = session.submit("valid") as QuerySubmissionResult.Accepted

        assertEquals(QuerySubmissionResult.Invalid, session.submit("x".repeat(513)))
        assertEquals(QuerySubmissionResult.Invalid, session.submit("bad\u0000query"))
        assertEquals(accepted.generation, session.state.value.generation)
        session.close()
        engine.close()
    }

    private fun CoroutineScope.engine(
        providers: List<SearchProviderBinding>,
        targets: FakeTargetCatalog = FakeTargetCatalog(),
        actionExecutor: SearchActionExecutor = SearchActionExecutor { SearchExecutionResult.Succeeded },
        timeout: Long = 1_000,
        maximumResults: Int = 50,
    ): DefaultSearchEngine = DefaultSearchEngine(
        providers = providers,
        targets = targets,
        commands = SearchCommandCatalog { true },
        executor = actionExecutor,
        parentScope = this,
        debounceMillis = 0,
        providerTimeoutMillis = timeout,
        maximumProviderResults = maximumResults,
    )
}

private class FakeBinding(
    localId: String,
    kinds: Set<SearchResultKind> = setOf(SearchResultKind.COMMAND),
    private val sessionFactory: ((CoroutineScope) -> FakeSession)? = null,
    private val failAfterFirstEmission: Boolean = false,
    private val preparation: suspend (SearchQuery) -> SearchCandidatePreparation = {
        SearchCandidatePreparation.Available(emptyList())
    },
    private val response: suspend (SearchProviderRequest) -> List<ProviderResult> = { emptyList() },
) : SearchProviderBinding {
    override val descriptor = descriptor(localId, kinds)
    val mutableAvailability = MutableStateFlow(SearchProviderAvailability.ENABLED)
    override val availability: StateFlow<SearchProviderAvailability> = mutableAvailability
    lateinit var session: FakeSession
    val preparedGenerations = mutableListOf<Long>()

    override suspend fun preparedCandidates(
        sessionId: Long,
        generation: Long,
        query: SearchQuery,
    ): SearchCandidatePreparation {
        preparedGenerations += generation
        return preparation(query)
    }

    override fun open(scope: CoroutineScope): GenerationAwareSearchProviderSession =
        (sessionFactory?.invoke(scope) ?: FakeSession(scope, response, failAfterFirstEmission)).also { session = it }
}

private open class FakeSession(
    private val scope: CoroutineScope,
    private val response: suspend (SearchProviderRequest) -> List<ProviderResult> = { emptyList() },
    failAfterFirstEmission: Boolean = false,
) : GenerationAwareSearchProviderSession {
    private val mutableSnapshots = MutableSharedFlow<ProviderResultSnapshot>(extraBufferCapacity = 16)
    val subscriberCount: Int get() = mutableSnapshots.subscriptionCount.value
    private var activeJob: Job? = null
    val cancelledGenerations = mutableListOf<Long>()
    var lastRequest: SearchProviderRequest? = null
        private set
    final override var isClosed: Boolean = false
        private set
    override val snapshots: Flow<ProviderResultSnapshot> = if (failAfterFirstEmission) {
        flow {
            mutableSnapshots.collect { snapshot ->
                emit(snapshot)
                throw IllegalStateException("provider flow failure without private data")
            }
        }
    } else {
        mutableSnapshots
    }
    override val results: Flow<List<ProviderResult>> = snapshots.map { it.results }

    override fun update(request: SearchProviderRequest) {
        check(!isClosed)
        activeJob?.let { previous ->
            if (previous.isActive) {
                lastRequest?.let { cancelledGenerations += it.generation }
                previous.cancel()
            }
        }
        lastRequest = request
        activeJob = scope.launch {
            val values = response(request)
            mutableSnapshots.emit(ProviderResultSnapshot(request.generation, values))
        }
    }

    override fun updateQuery(query: SearchQuery) {
        update(SearchProviderRequest((lastRequest?.generation ?: 0L) + 1L, query, emptyList()))
    }

    suspend fun emit(generation: Long, results: List<ProviderResult>) {
        mutableSnapshots.emit(ProviderResultSnapshot(generation, results))
    }

    override fun close() {
        if (isClosed) return
        isClosed = true
        activeJob?.cancel()
    }
}

private class FailingSession(scope: CoroutineScope) : FakeSession(scope) {
    override val snapshots: Flow<ProviderResultSnapshot> = flow {
        throw IllegalStateException("provider failure without private data")
    }
}

private class FakeTargetCatalog(
    val values: MutableMap<SearchActionId, ResolvedSearchTarget> = mutableMapOf(),
) : SearchTargetCatalog {
    val mutableInvalidations = MutableSharedFlow<Unit>(extraBufferCapacity = 1)
    val releasedProviders = mutableListOf<Pair<Long, ContributionId>>()
    val releasedSessions = mutableListOf<Long>()
    override val invalidations: Flow<Unit> = mutableInvalidations
    override fun resolve(id: SearchActionId): ResolvedSearchTarget? = values[id]
    override fun releaseProvider(sessionId: Long, providerId: ContributionId) {
        releasedProviders += sessionId to providerId
    }
    override fun releaseSession(sessionId: Long) {
        releasedSessions += sessionId
    }
}

private fun descriptor(localId: String, kinds: Set<SearchResultKind>): SearchProviderDescriptor {
    val id = id(localId)
    return SearchProviderDescriptor(
        metadata = ContributionMetadata(
            id = id,
            typeId = ContributionTypes.SEARCH_PROVIDER,
            contractMajor = ContractMajor.of(1),
            displayName = DisplayText.of(localId),
            description = DisplayText.of("Test provider"),
            providedCapabilities = emptySet(),
            requiredCapabilities = emptySet(),
            settings = null,
            configType = ConfigTypeId.parse("org.quicklauncher.test/$localId-config"),
        ),
        resultKinds = kinds,
        minimumQueryLength = 0,
    )
}

private fun result(
    provider: String,
    title: String,
    kind: SearchResultKind = SearchResultKind.COMMAND,
    suffix: String = "item",
    action: SearchResultAction = SearchResultAction.InvokeCommand(id("command")),
    relevance: Int = 500,
): ProviderResult = ProviderResult(
    id = SearchProviderResultIds.create(id(provider), suffix),
    title = DisplayText.of(title),
    subtitle = null,
    kind = kind,
    action = action,
    providerRelevance = relevance,
)

private fun id(local: String): ContributionId = ContributionId.parse("org.quicklauncher.test/$local")

private fun action(local: String): SearchActionId =
    SearchActionId.parse("org.quicklauncher.test/$local")

private fun target(
    action: SearchActionId,
    kind: SearchResultKind = SearchResultKind.INFORMATION,
    profileKind: SearchProfileKind? = null,
    workBadged: Boolean = false,
): ResolvedSearchTarget = ResolvedSearchTarget(
    actionId = action,
    kind = kind,
    duplicateKey = SearchTargetKey.of(action.value),
    profile = null,
    profileKind = profileKind,
    workBadged = workBadged,
    historyTarget = null,
)

private fun assertThrowsUnsupported(block: () -> Unit) {
    var threw = false
    try {
        block()
    } catch (_: UnsupportedOperationException) {
        threw = true
    }
    assertTrue(threw)
}
