package org.quicklauncher.host.runtime.search

import java.util.Collections
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow
import org.quicklauncher.contracts.contribution.GenerationAwareSearchProviderSession
import org.quicklauncher.contracts.contribution.ProviderResult
import org.quicklauncher.contracts.contribution.SearchProviderDescriptor
import org.quicklauncher.contracts.contribution.SearchQuery
import org.quicklauncher.contracts.contribution.SearchResultAction
import org.quicklauncher.contracts.contribution.SearchResultKind
import org.quicklauncher.contracts.domain.AppActivityIdentity
import org.quicklauncher.contracts.domain.ContributionId
import org.quicklauncher.contracts.domain.ProfileSerial
import org.quicklauncher.contracts.domain.SearchActionId
import org.quicklauncher.contracts.domain.SearchResultId
import org.quicklauncher.host.runtime.shortcuts.ShortcutTargetIdentity

enum class SearchProviderAvailability {
    ENABLED,
    DENIED,
    UNAVAILABLE,
}

enum class SearchProviderState {
    IDLE,
    LOADING,
    READY,
    DENIED,
    UNAVAILABLE,
    TIMED_OUT,
    FAILED,
}

/** Host entry for one registered provider. No Android type may cross this seam. */
interface SearchProviderBinding {
    val descriptor: SearchProviderDescriptor
    val availability: StateFlow<SearchProviderAvailability>

    /** Returns only candidates already admitted by current host permission and profile policy. */
    suspend fun preparedCandidates(
        sessionId: Long,
        generation: Long,
        query: SearchQuery,
    ): SearchCandidatePreparation

    /** Called exactly once for each visible search presentation. */
    fun open(scope: CoroutineScope): GenerationAwareSearchProviderSession
}

sealed interface SearchCandidatePreparation {
    class Available(results: Collection<ProviderResult>) : SearchCandidatePreparation {
        val results: List<ProviderResult> = immutableList(results)
    }

    data object Denied : SearchCandidatePreparation
    data object Unavailable : SearchCandidatePreparation
}

@JvmInline
value class SearchTargetKey private constructor(val value: String) {
    override fun toString(): String = "SearchTargetKey(redacted)"

    companion object {
        fun of(value: String): SearchTargetKey {
            require(value.isNotBlank()) { "Search target key must not be blank" }
            require(value.length <= 1_024) { "Search target key must not exceed 1,024 characters" }
            require('\u0000' !in value) { "Search target key must not contain a null character" }
            return SearchTargetKey(value)
        }
    }
}

sealed interface SearchHistoryTarget {
    data class App(
        val identity: AppActivityIdentity,
        val profileKind: SearchProfileKind,
    ) : SearchHistoryTarget
    data class Shortcut(
        val identity: ShortcutTargetIdentity,
        val profileKind: SearchProfileKind,
    ) : SearchHistoryTarget
}

data class ResolvedSearchTarget(
    val actionId: SearchActionId,
    val kind: SearchResultKind,
    val duplicateKey: SearchTargetKey,
    val profile: ProfileSerial?,
    val profileKind: SearchProfileKind?,
    val workBadged: Boolean,
    val historyTarget: SearchHistoryTarget?,
) {
    override fun toString(): String =
        "ResolvedSearchTarget(kind=$kind, profileKind=$profileKind, workBadged=$workBadged, content=redacted)"
}

enum class SearchProfileKind {
    PERSONAL,
    WORK,
}

/** Resolves opaque actions without exposing platform objects or protected metadata. */
interface SearchTargetCatalog {
    val invalidations: Flow<Unit>

    /** Returns null unless the target is currently eligible and callable. */
    fun resolve(id: SearchActionId): ResolvedSearchTarget?

    /** Releases ephemeral targets owned by one provider within one presentation. */
    fun releaseProvider(sessionId: Long, providerId: ContributionId) = Unit

    /** Releases all ephemeral targets, including raw web queries, owned by one presentation. */
    fun releaseSession(sessionId: Long) = Unit
}

fun interface SearchCommandCatalog {
    fun contains(id: ContributionId): Boolean
}

sealed interface SearchExecutionResult {
    data object Succeeded : SearchExecutionResult
    data object Cancelled : SearchExecutionResult
    data object MissingAccess : SearchExecutionResult
    data object Unavailable : SearchExecutionResult
    data object RecoverableFailure : SearchExecutionResult
    data object Rejected : SearchExecutionResult
}

fun interface SearchActionExecutor {
    suspend fun execute(action: SearchResultAction): SearchExecutionResult
}

data class SearchActivationToken(
    val sessionId: Long,
    val generation: Long,
    val providerId: ContributionId,
    val resultId: SearchResultId,
)

class SearchResultItem(
    val token: SearchActivationToken,
    val providerId: ContributionId,
    val providerLabel: String,
    val result: ProviderResult,
    val score: Int,
    val workBadged: Boolean,
) {
    init {
        require(providerLabel.isNotBlank()) { "Search provider label must not be blank" }
        require(score >= 0) { "Search score must not be negative" }
    }
}

class SearchSnapshot(
    val sessionId: Long,
    val generation: Long,
    results: Collection<SearchResultItem>,
    providerStates: Map<ContributionId, SearchProviderState>,
    val closed: Boolean,
    providerLabels: Map<ContributionId, String> = emptyMap(),
) {
    val results: List<SearchResultItem> = immutableList(results)
    val providerStates: Map<ContributionId, SearchProviderState> =
        Collections.unmodifiableMap(LinkedHashMap(providerStates))
    val providerLabels: Map<ContributionId, String> =
        Collections.unmodifiableMap(LinkedHashMap(providerLabels))

    companion object {
        fun initial(sessionId: Long, providers: Collection<ContributionId>): SearchSnapshot =
            initial(sessionId, providers.associateWith { "Search provider" })

        fun initial(sessionId: Long, providers: Map<ContributionId, String>): SearchSnapshot =
            SearchSnapshot(
                sessionId = sessionId,
                generation = 0L,
                results = emptyList(),
                providerStates = providers.keys.associateWith { SearchProviderState.IDLE },
                closed = false,
                providerLabels = providers,
            )
    }
}

sealed interface QuerySubmissionResult {
    data class Accepted(val generation: Long) : QuerySubmissionResult
    data object Invalid : QuerySubmissionResult
    data object Closed : QuerySubmissionResult
}

interface SearchSession : AutoCloseable {
    val state: StateFlow<SearchSnapshot>

    fun submit(rawQuery: String): QuerySubmissionResult
    fun submit(rawQuery: String, minimumCharacters: Int): QuerySubmissionResult = submit(rawQuery)
    suspend fun activate(token: SearchActivationToken): SearchExecutionResult
    override fun close()
}

fun interface SearchEngine {
    fun open(): SearchSession
}

internal fun <T> immutableList(values: Collection<T>): List<T> =
    Collections.unmodifiableList(ArrayList(values))
