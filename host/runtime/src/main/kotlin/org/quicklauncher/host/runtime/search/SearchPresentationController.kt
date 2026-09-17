package org.quicklauncher.host.runtime.search

import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import org.quicklauncher.contracts.ui.ActionDispatchResult
import org.quicklauncher.contracts.ui.ActionSink
import org.quicklauncher.contracts.ui.PreparedSearchResult
import org.quicklauncher.contracts.ui.SearchPresentation
import org.quicklauncher.contracts.ui.SearchPresentationAction
import org.quicklauncher.contracts.ui.SearchPresentationProviderState
import org.quicklauncher.contracts.ui.SearchPresentationState
import org.quicklauncher.contracts.ui.SearchPresentationToken

/** One controller is owned by one visible presentation and must close when it leaves composition. */
class SearchPresentationController(
    engine: SearchEngine,
    parentScope: CoroutineScope,
    private val dismiss: () -> Unit,
    private val activityChanged: () -> Unit = {},
) : AutoCloseable {
    private val closed = AtomicBoolean(false)
    private val ownedJob = SupervisorJob(parentScope.coroutineContext[Job])
    private val scope = CoroutineScope(parentScope.coroutineContext + ownedJob)
    private val session = engine.open()
    private val mutableState = MutableStateFlow(map(session.state.value, ""))
    private var query = ""
    private var focusRequestId = 0L

    val state: StateFlow<SearchPresentationState> = mutableState.asStateFlow()
    val hasActiveQuery: Boolean get() = query.isNotEmpty() && !closed.get()
    val presentation: SearchPresentation
        get() = SearchPresentation(state.value, ActionSink(::dispatch))

    init {
        scope.launch {
            session.state.collect { snapshot ->
                if (!closed.get()) mutableState.value = map(snapshot, query)
            }
        }
    }

    fun dispatch(action: SearchPresentationAction): ActionDispatchResult {
        if (closed.get()) return rejected("search-closed", "Search is closed")
        return when (action) {
            is SearchPresentationAction.QueryChanged -> {
                val value = action.value
                if (value.length > 512 || '\u0000' in value) {
                    rejected("search-query-invalid", "Search query is invalid")
                } else {
                    query = value
                    activityChanged()
                    when (session.submit(value, action.minimumCharacters)) {
                        is QuerySubmissionResult.Accepted -> {
                            mutableState.value = map(session.state.value, query)
                            ActionDispatchResult.Accepted
                        }
                        QuerySubmissionResult.Invalid ->
                            rejected("search-query-invalid", "Search query is invalid")
                        QuerySubmissionResult.Closed ->
                            rejected("search-closed", "Search is closed")
                    }
                }
            }
            is SearchPresentationAction.Activate -> {
                scope.launch { session.activate(action.token.toRuntimeToken()) }
                ActionDispatchResult.Accepted
            }
            SearchPresentationAction.Dismiss -> {
                clearQuery()
                dismiss()
                ActionDispatchResult.Accepted
            }
        }
    }

    override fun close() {
        if (!closed.compareAndSet(false, true)) return
        session.close()
        scope.cancel()
        activityChanged()
    }

    fun clearQuery() {
        if (closed.get() || query.isEmpty()) return
        query = ""
        session.submit("")
        mutableState.value = map(session.state.value, query)
        activityChanged()
    }

    fun requestFocus() {
        if (closed.get()) return
        focusRequestId += 1L
        mutableState.value = map(session.state.value, query)
    }

    private fun map(snapshot: SearchSnapshot, query: String): SearchPresentationState =
        SearchPresentationState(
            query = query,
            results = snapshot.results.map { item ->
                PreparedSearchResult(
                    token = SearchPresentationToken(
                        item.token.sessionId,
                        item.token.generation,
                        item.token.providerId,
                        item.token.resultId,
                    ),
                    providerLabel = item.providerLabel,
                    title = item.result.title.value,
                    subtitle = item.result.subtitle?.value,
                    workBadged = item.workBadged,
                )
            },
            providerStates = snapshot.providerStates.mapValues { (_, state) -> state.toPresentation() },
            providerLabels = snapshot.providerLabels,
            focusRequestId = focusRequestId,
        )

    private fun SearchPresentationToken.toRuntimeToken(): SearchActivationToken =
        SearchActivationToken(sessionId, generation, providerId, resultId)

    private fun SearchProviderState.toPresentation(): SearchPresentationProviderState = when (this) {
        SearchProviderState.IDLE -> SearchPresentationProviderState.IDLE
        SearchProviderState.LOADING -> SearchPresentationProviderState.LOADING
        SearchProviderState.READY -> SearchPresentationProviderState.READY
        SearchProviderState.DENIED -> SearchPresentationProviderState.DENIED
        SearchProviderState.UNAVAILABLE -> SearchPresentationProviderState.UNAVAILABLE
        SearchProviderState.TIMED_OUT -> SearchPresentationProviderState.TIMED_OUT
        SearchProviderState.FAILED -> SearchPresentationProviderState.FAILED
    }

    private fun rejected(code: String, message: String) = ActionDispatchResult.Rejected(
        org.quicklauncher.contracts.domain.StableKey.parse(code),
        message,
    )
}
