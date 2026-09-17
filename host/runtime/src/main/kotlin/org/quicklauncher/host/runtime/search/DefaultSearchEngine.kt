package org.quicklauncher.host.runtime.search

import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeout
import org.quicklauncher.contracts.contribution.ProviderResult
import org.quicklauncher.contracts.contribution.SearchProviderRequest
import org.quicklauncher.contracts.contribution.SearchProviderResultIds
import org.quicklauncher.contracts.contribution.SearchQuery
import org.quicklauncher.contracts.contribution.SearchResultAction
import org.quicklauncher.contracts.contribution.SearchResultKind
import org.quicklauncher.contracts.domain.ContributionId

class DefaultSearchEngine(
    providers: Collection<SearchProviderBinding>,
    private val targets: SearchTargetCatalog,
    private val commands: SearchCommandCatalog,
    private val executor: SearchActionExecutor,
    private val ranker: SearchRanker = SearchRanker(),
    parentScope: CoroutineScope,
    private val debounceMillis: Long = 120L,
    private val providerTimeoutMillis: Long = 1_500L,
    private val maximumProviderResults: Int = 50,
    private val maximumPreparedCandidates: Int = 1_000,
) : SearchEngine, AutoCloseable {
    private val closed = AtomicBoolean(false)
    private val nextSessionId = AtomicLong(0L)
    private val ownedJob = SupervisorJob(parentScope.coroutineContext[Job])
    private val scope = CoroutineScope(parentScope.coroutineContext + ownedJob)
    private val bindings = immutableList(providers)

    init {
        require(debounceMillis >= 0L) { "Search debounce must not be negative" }
        require(providerTimeoutMillis > 0L) { "Provider timeout must be positive" }
        require(maximumProviderResults in 1..1_000) { "Provider result limit must be between 1 and 1,000" }
        require(maximumPreparedCandidates in 1..10_000) {
            "Prepared candidate limit must be between 1 and 10,000"
        }
        require(bindings.map { it.descriptor.metadata.id }.distinct().size == bindings.size) {
            "Search provider identities must be unique"
        }
    }

    override fun open(): SearchSession {
        check(!closed.get()) { "Search engine is closed" }
        return DefaultSearchSession(nextSessionId.incrementAndGet(), scope)
    }

    override fun close() {
        if (!closed.compareAndSet(false, true)) return
        scope.cancel()
    }

    private inner class DefaultSearchSession(
        private val sessionId: Long,
        parentScope: CoroutineScope,
    ) : SearchSession {
        private val closed = AtomicBoolean(false)
        private val generation = AtomicLong(0L)
        private val ownedJob = SupervisorJob(parentScope.coroutineContext[Job])
        private val scope = CoroutineScope(parentScope.coroutineContext + ownedJob)
        private val updateMutex = Mutex()
        private val stateLock = Any()
        private val mutableState = MutableStateFlow(
            SearchSnapshot.initial(
                sessionId,
                bindings.associate { it.descriptor.metadata.id to it.descriptor.metadata.displayName.value },
            ),
        )
        private val providerSessions = LinkedHashMap<ContributionId, OpenProvider>()
        private val activeProviderJobs = ConcurrentHashMap<ContributionId, Job>()
        private val activationJobs = ConcurrentHashMap.newKeySet<Job>()
        private var activeQuery: SearchQuery? = null
        private var activeMinimumCharacters = 0
        private var activeJob: Job? = null

        override val state: StateFlow<SearchSnapshot> = mutableState.asStateFlow()

        init {
            bindings.forEach { binding ->
                val id = binding.descriptor.metadata.id
                providerSessions[id] = try {
                    OpenProvider.Available(binding.open(scope))
                } catch (_: RuntimeException) {
                    OpenProvider.Failed
                }
                scope.launch(start = CoroutineStart.UNDISPATCHED) {
                    binding.availability.collect { availability ->
                        if (closed.get()) return@collect
                        if (availability != SearchProviderAvailability.ENABLED) {
                            activeProviderJobs[id]?.cancel()
                            runCatching { targets.releaseProvider(sessionId, id) }
                        }
                        val state = availability.toProviderState()
                        removeProviderResults(id, state)
                        if (activeQuery != null && availability == SearchProviderAvailability.ENABLED) {
                            replaceCurrentQuery()
                        }
                    }
                }
            }
            scope.launch(start = CoroutineStart.UNDISPATCHED) {
                targets.invalidations.collect {
                    if (closed.get()) return@collect
                    activeJob?.cancel()
                    removeIneligibleResults()
                    replaceCurrentQuery()
                }
            }
        }

        override fun submit(rawQuery: String): QuerySubmissionResult = submit(rawQuery, 0)

        override fun submit(rawQuery: String, minimumCharacters: Int): QuerySubmissionResult {
            if (closed.get()) return QuerySubmissionResult.Closed
            if (minimumCharacters !in 0..32) return QuerySubmissionResult.Invalid
            val query = try {
                SearchQuery.of(rawQuery)
            } catch (_: IllegalArgumentException) {
                return QuerySubmissionResult.Invalid
            }
            activeQuery = query
            activeMinimumCharacters = minimumCharacters
            val nextGeneration = generation.incrementAndGet()
            activationJobs.forEach(Job::cancel)
            activeJob?.cancel()
            activeProviderJobs.values.forEach(Job::cancel)
            activeProviderJobs.clear()
            bindings.forEach { binding ->
                runCatching { targets.releaseProvider(sessionId, binding.descriptor.metadata.id) }
            }
            synchronized(stateLock) {
                if (closed.get()) return QuerySubmissionResult.Closed
                mutableState.value = SearchSnapshot(
                    sessionId,
                    nextGeneration,
                    emptyList(),
                    initialStates(),
                    closed = false,
                    providerLabels = mutableState.value.providerLabels,
                )
            }
            activeJob = scope.launch { executeGeneration(nextGeneration, query) }
            return QuerySubmissionResult.Accepted(nextGeneration)
        }

        override suspend fun activate(token: SearchActivationToken): SearchExecutionResult = coroutineScope {
            val activationJob = currentCoroutineContext()[Job]
                ?: return@coroutineScope SearchExecutionResult.Rejected
            activationJobs += activationJob
            try {
                if (closed.get() || token.sessionId != sessionId || token.generation != generation.get()) {
                    return@coroutineScope SearchExecutionResult.Rejected
                }
                val current = mutableState.value.results.singleOrNull { it.token == token }
                    ?: return@coroutineScope SearchExecutionResult.Rejected
                val validated = validateAction(current.providerId, current.result)
                    ?: return@coroutineScope SearchExecutionResult.Rejected
                currentCoroutineContext().ensureActive()
                if (closed.get() || token.generation != generation.get()) {
                    return@coroutineScope SearchExecutionResult.Rejected
                }
                try {
                    executor.execute(current.result.action).also { result ->
                        if (result == SearchExecutionResult.Succeeded) {
                            validated.target?.historyTarget?.let { ranker.record(it) }
                        }
                    }
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (_: RuntimeException) {
                    SearchExecutionResult.RecoverableFailure
                }
            } finally {
                activationJobs -= activationJob
            }
        }

        override fun close() {
            if (!closed.compareAndSet(false, true)) return
            activeJob?.cancel()
            activeProviderJobs.values.forEach(Job::cancel)
            activeProviderJobs.clear()
            activationJobs.forEach(Job::cancel)
            activationJobs.clear()
            providerSessions.values.forEach { opened ->
                runCatching { (opened as? OpenProvider.Available)?.session?.close() }
            }
            runCatching { targets.releaseSession(sessionId) }
            synchronized(stateLock) {
                mutableState.value = SearchSnapshot(
                    sessionId,
                    generation.get(),
                    emptyList(),
                    mutableState.value.providerStates,
                    closed = true,
                    providerLabels = mutableState.value.providerLabels,
                )
            }
            scope.cancel()
        }

        private suspend fun executeGeneration(generation: Long, query: SearchQuery) {
            if (debounceMillis > 0L) delay(debounceMillis)
            if (!isCurrent(generation)) return
            coroutineScope {
                bindings.forEach { binding ->
                    val id = binding.descriptor.metadata.id
                    launch(start = CoroutineStart.LAZY) {
                        try {
                            queryProvider(binding, generation, query)
                        } finally {
                            coroutineContext[Job]?.let { job -> activeProviderJobs.remove(id, job) }
                        }
                    }.also { job ->
                        activeProviderJobs[id] = job
                        job.start()
                    }
                }
            }
        }

        private suspend fun queryProvider(
            binding: SearchProviderBinding,
            queryGeneration: Long,
            query: SearchQuery,
        ) {
            val id = binding.descriptor.metadata.id
            if (binding.availability.value != SearchProviderAvailability.ENABLED) return
            if (
                query.value.trim().length <
                maxOf(binding.descriptor.minimumQueryLength, activeMinimumCharacters)
            ) {
                updateProvider(id, SearchProviderState.IDLE, emptyList(), queryGeneration, query.value)
                return
            }
            val opened = providerSessions[id]
            if (opened !is OpenProvider.Available) {
                updateProvider(id, SearchProviderState.FAILED, emptyList(), queryGeneration, query.value)
                return
            }
            updateProvider(id, SearchProviderState.LOADING, emptyList(), queryGeneration, query.value)
            try {
                coroutineScope providerScope@{
                    val firstEmission = CompletableDeferred<Unit>()
                    var collector: Job? = null
                    var preparationTerminal = false
                    try {
                        withTimeout(providerTimeoutMillis) {
                            val preparation = binding.preparedCandidates(
                                sessionId,
                                queryGeneration,
                                query,
                            )
                            val prepared = when (preparation) {
                                is SearchCandidatePreparation.Available -> preparation.results
                                SearchCandidatePreparation.Denied -> {
                                    updateProvider(
                                        id,
                                        SearchProviderState.DENIED,
                                        emptyList(),
                                        queryGeneration,
                                        query.value,
                                    )
                                    preparationTerminal = true
                                    return@withTimeout
                                }
                                SearchCandidatePreparation.Unavailable -> {
                                    updateProvider(
                                        id,
                                        SearchProviderState.UNAVAILABLE,
                                        emptyList(),
                                        queryGeneration,
                                        query.value,
                                    )
                                    preparationTerminal = true
                                    return@withTimeout
                                }
                            }.take(maximumPreparedCandidates)
                            collector = this@providerScope.launch(start = CoroutineStart.UNDISPATCHED) {
                                opened.session.snapshots.collect { snapshot ->
                                    if (!isCurrent(queryGeneration) || snapshot.generation != queryGeneration) {
                                        return@collect
                                    }
                                    val validated = validateResults(binding, snapshot.results)
                                    updateProvider(
                                        id,
                                        SearchProviderState.READY,
                                        validated,
                                        queryGeneration,
                                        query.value,
                                    )
                                    firstEmission.complete(Unit)
                                }
                            }
                            val candidates = validateResults(
                                binding,
                                prepared,
                                maximumPreparedCandidates,
                            ).map { it.result }
                                .filter { result -> ranker.matches(query.value, result) }
                            opened.session.update(
                                SearchProviderRequest(queryGeneration, query, candidates),
                            )
                            firstEmission.await()
                        }
                        if (!preparationTerminal) collector?.join()
                    } finally {
                        collector?.cancel()
                    }
                }
            } catch (_: TimeoutCancellationException) {
                updateProvider(id, SearchProviderState.TIMED_OUT, emptyList(), queryGeneration, query.value)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: RuntimeException) {
                updateProvider(id, SearchProviderState.FAILED, emptyList(), queryGeneration, query.value)
            }
        }

        private fun validateResults(
            binding: SearchProviderBinding,
            results: Collection<ProviderResult>,
            maximumSize: Int = maximumProviderResults,
        ): List<ValidatedProviderResult> {
            check(results.size <= maximumSize) { "Provider result limit exceeded" }
            check(results.map { it.id }.distinct().size == results.size) {
                "Provider returned duplicate result identities"
            }
            return results.mapNotNull { result ->
                check(binding.descriptor.resultKinds.contains(result.kind)) {
                    "Provider returned an undeclared result kind"
                }
                check(SearchProviderResultIds.belongsTo(result.id, binding.descriptor.metadata.id)) {
                    "Provider returned a result outside its namespace"
                }
                when (val action = result.action) {
                    is SearchResultAction.Execute -> {
                        val target = targets.resolve(action.id) ?: return@mapNotNull null
                        check(target.kind == result.kind) { "Provider result action kind does not match" }
                        if (
                            target.profileKind == SearchProfileKind.WORK &&
                            !target.workBadged &&
                            result.kind.isProfileProtected()
                        ) {
                            return@mapNotNull null
                        }
                        ValidatedProviderResult(binding.descriptor.metadata.id, result, target)
                    }
                    is SearchResultAction.InvokeCommand -> {
                        if (result.kind != SearchResultKind.COMMAND || !commands.contains(action.contributionId)) {
                            return@mapNotNull null
                        }
                        ValidatedProviderResult(binding.descriptor.metadata.id, result, null)
                    }
                }
            }
        }

        private suspend fun updateProvider(
            providerId: ContributionId,
            providerState: SearchProviderState,
            results: List<ValidatedProviderResult>,
            queryGeneration: Long,
            query: String,
        ) {
            if (!isCurrent(queryGeneration)) return
            if (
                results.isEmpty() && providerState in setOf(
                    SearchProviderState.IDLE,
                    SearchProviderState.DENIED,
                    SearchProviderState.UNAVAILABLE,
                    SearchProviderState.TIMED_OUT,
                    SearchProviderState.FAILED,
                )
            ) {
                runCatching { targets.releaseProvider(sessionId, providerId) }
            }
            updateMutex.withLock {
                if (!isCurrent(queryGeneration)) return@withLock
                val previous = mutableState.value
                val retained = previous.results
                    .filterNot { it.providerId == providerId }
                    .mapNotNull { item -> revalidateExisting(item) }
                val ranked = ranker.rank(
                    query,
                    retained.map { it.validated } + results,
                )
                val states = LinkedHashMap(previous.providerStates)
                states[providerId] = providerState
                synchronized(stateLock) {
                    if (!isCurrent(queryGeneration)) return@withLock
                    mutableState.value = SearchSnapshot(
                        sessionId,
                        queryGeneration,
                        ranked.map { rankedResult -> rankedResult.toItem(queryGeneration) },
                        states,
                        closed = false,
                        providerLabels = previous.providerLabels,
                    )
                }
            }
        }

        private suspend fun removeProviderResults(id: ContributionId, providerState: SearchProviderState) {
            updateMutex.withLock {
                synchronized(stateLock) {
                    if (closed.get()) return@withLock
                    val previous = mutableState.value
                    val states = LinkedHashMap(previous.providerStates)
                    states[id] = providerState
                    mutableState.value = SearchSnapshot(
                        sessionId,
                        previous.generation,
                        previous.results.filterNot { it.providerId == id },
                        states,
                        closed = false,
                        providerLabels = previous.providerLabels,
                    )
                }
            }
        }

        private suspend fun removeIneligibleResults() {
            updateMutex.withLock {
                synchronized(stateLock) {
                    if (closed.get()) return@withLock
                    val previous = mutableState.value
                    mutableState.value = SearchSnapshot(
                        sessionId,
                        previous.generation,
                        previous.results.filter { item ->
                            validateAction(item.providerId, item.result) != null
                        },
                        previous.providerStates,
                        closed = false,
                        providerLabels = previous.providerLabels,
                    )
                }
            }
        }

        private fun replaceCurrentQuery() {
            val query = activeQuery ?: return
            submit(query.value, activeMinimumCharacters)
        }

        private fun validateAction(
            providerId: ContributionId,
            result: ProviderResult,
        ): ValidatedProviderResult? {
            val binding = bindings.singleOrNull { it.descriptor.metadata.id == providerId } ?: return null
            return validateResults(binding, listOf(result)).singleOrNull()
        }

        private fun revalidateExisting(item: SearchResultItem): ExistingResult? {
            val binding = bindings.singleOrNull { it.descriptor.metadata.id == item.providerId } ?: return null
            val value = validateResults(binding, listOf(item.result)).singleOrNull() ?: return null
            return ExistingResult(value)
        }

        private fun RankedResult.toItem(queryGeneration: Long): SearchResultItem = SearchResultItem(
            token = SearchActivationToken(
                sessionId,
                queryGeneration,
                value.providerId,
                value.result.id,
            ),
            providerId = value.providerId,
            providerLabel = requireNotNull(
                bindings.singleOrNull { it.descriptor.metadata.id == value.providerId },
            ).descriptor.metadata.displayName.value,
            result = value.result,
            score = score,
            workBadged = value.target?.workBadged == true,
        )

        private fun initialStates(): Map<ContributionId, SearchProviderState> = bindings.associate { binding ->
            val id = binding.descriptor.metadata.id
            id to when {
                providerSessions[id] is OpenProvider.Failed -> SearchProviderState.FAILED
                else -> binding.availability.value.toProviderState()
            }
        }

        private fun isCurrent(queryGeneration: Long): Boolean =
            !closed.get() && generation.get() == queryGeneration
    }

    private sealed interface OpenProvider {
        data class Available(val session: org.quicklauncher.contracts.contribution.GenerationAwareSearchProviderSession) : OpenProvider
        data object Failed : OpenProvider
    }

    private data class ExistingResult(val validated: ValidatedProviderResult)

    private fun SearchResultKind.isProfileProtected(): Boolean =
        this == SearchResultKind.APP || this == SearchResultKind.SHORTCUT || this == SearchResultKind.CONTACT

    private fun SearchProviderAvailability.toProviderState(): SearchProviderState = when (this) {
        SearchProviderAvailability.ENABLED -> SearchProviderState.IDLE
        SearchProviderAvailability.DENIED -> SearchProviderState.DENIED
        SearchProviderAvailability.UNAVAILABLE -> SearchProviderState.UNAVAILABLE
    }
}
