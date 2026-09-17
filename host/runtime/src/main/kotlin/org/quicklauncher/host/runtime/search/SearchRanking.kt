package org.quicklauncher.host.runtime.search

import java.util.Locale
import java.util.Collections
import kotlin.math.max
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.quicklauncher.contracts.contribution.ProviderResult
import org.quicklauncher.contracts.contribution.SearchResultKind
import org.quicklauncher.contracts.domain.ContributionId
import org.quicklauncher.host.data.store.SearchLaunchHistoryStore
import org.quicklauncher.host.data.store.SearchLaunchProfileKind
import org.quicklauncher.host.data.store.SearchLaunchTargetRecord
import org.quicklauncher.host.data.store.ShortcutTarget
import org.quicklauncher.host.data.preferences.HistoryPolicy
import org.quicklauncher.host.data.preferences.LauncherPreferencesStore

data class SearchHistoryEntry(
    val target: SearchHistoryTarget,
    val launchCount: Int,
    val lastLaunchedAtMillis: Long,
) {
    init {
        require(launchCount > 0) { "Launch count must be positive" }
    }
}

interface SearchHistory {
    suspend fun entries(): List<SearchHistoryEntry>
    suspend fun record(target: SearchHistoryTarget, nowMillis: Long)
}

data object DisabledSearchHistory : SearchHistory {
    override suspend fun entries(): List<SearchHistoryEntry> = emptyList()
    override suspend fun record(target: SearchHistoryTarget, nowMillis: Long) = Unit
}

class StoreSearchHistory(private val store: SearchLaunchHistoryStore) : SearchHistory {
    override suspend fun entries(): List<SearchHistoryEntry> = store.readSearchLaunchHistory().map { record ->
        SearchHistoryEntry(
            target = when (val target = record.target) {
                is SearchLaunchTargetRecord.App -> SearchHistoryTarget.App(
                    target.identity,
                    target.profileKind.toRuntime(),
                )
                is SearchLaunchTargetRecord.Shortcut -> SearchHistoryTarget.Shortcut(
                    org.quicklauncher.host.runtime.shortcuts.ShortcutTargetIdentity(
                        target.target.profile,
                        target.target.packageName,
                        target.target.shortcutId,
                    ),
                    target.profileKind.toRuntime(),
                )
            },
            launchCount = record.launchCount,
            lastLaunchedAtMillis = record.lastLaunchedAtMillis,
        )
    }

    override suspend fun record(target: SearchHistoryTarget, nowMillis: Long) {
        store.recordSearchLaunch(
            when (target) {
                is SearchHistoryTarget.App -> SearchLaunchTargetRecord.App(
                    target.identity,
                    target.profileKind.toStore(),
                )
                is SearchHistoryTarget.Shortcut -> SearchLaunchTargetRecord.Shortcut(
                    ShortcutTarget(
                        target.identity.profile,
                        target.identity.packageName,
                        target.identity.shortcutId,
                    ),
                    target.profileKind.toStore(),
                )
            },
            nowMillis,
        )
    }
}

class PreferenceControlledSearchHistory(
    private val preferences: LauncherPreferencesStore,
    private val delegate: SearchHistory,
) : SearchHistory {
    override suspend fun entries(): List<SearchHistoryEntry> =
        if (preferences.read().historyPolicy == HistoryPolicy.LOCAL) delegate.entries() else emptyList()

    override suspend fun record(target: SearchHistoryTarget, nowMillis: Long) {
        if (preferences.read().historyPolicy == HistoryPolicy.LOCAL) {
            delegate.record(target, nowMillis)
        }
    }
}

/** Bounded local history. Its type system cannot represent queries or non-launch targets. */
class BoundedSearchHistory(private val maximumEntries: Int = 256) : SearchHistory {
    private val mutex = Mutex()
    private val values = LinkedHashMap<SearchHistoryTarget, SearchHistoryEntry>()

    init {
        require(maximumEntries in 1..1_000) { "History limit must be between 1 and 1,000" }
    }

    override suspend fun entries(): List<SearchHistoryEntry> = mutex.withLock {
        Collections.unmodifiableList(ArrayList(values.values))
    }

    override suspend fun record(target: SearchHistoryTarget, nowMillis: Long) {
        mutex.withLock {
            val safeNow = nowMillis.coerceAtLeast(0L)
            val previous = values[target]
            values[target] = SearchHistoryEntry(
                target = target,
                launchCount = (previous?.launchCount ?: 0).coerceAtMost(Int.MAX_VALUE - 1) + 1,
                lastLaunchedAtMillis = safeNow,
            )
            if (values.size > maximumEntries) {
                val retained = values.values
                    .sortedWith(
                        compareByDescending<SearchHistoryEntry> { it.lastLaunchedAtMillis }
                            .thenBy { stableTargetKey(it.target) },
                    )
                    .take(maximumEntries)
                values.clear()
                retained.forEach { values[it.target] = it }
            }
        }
    }

    private fun stableTargetKey(target: SearchHistoryTarget): String = when (target) {
        is SearchHistoryTarget.App -> with(target.identity) {
            "app:${target.profileKind}:${profile.value}:${packageName.value}:${activityName.value}"
        }
        is SearchHistoryTarget.Shortcut -> with(target.identity) {
            "shortcut:${target.profileKind}:${profile.value}:${packageName.value}:${shortcutId.value}"
        }
    }
}

private fun SearchLaunchProfileKind.toRuntime(): SearchProfileKind = when (this) {
    SearchLaunchProfileKind.PERSONAL -> SearchProfileKind.PERSONAL
    SearchLaunchProfileKind.WORK -> SearchProfileKind.WORK
}

private fun SearchProfileKind.toStore(): SearchLaunchProfileKind = when (this) {
    SearchProfileKind.PERSONAL -> SearchLaunchProfileKind.PERSONAL
    SearchProfileKind.WORK -> SearchLaunchProfileKind.WORK
}

fun interface SearchClock {
    fun nowMillis(): Long
}

data class ValidatedProviderResult(
    val providerId: ContributionId,
    val result: ProviderResult,
    val target: ResolvedSearchTarget?,
)

class SearchRanker(
    private val history: SearchHistory = DisabledSearchHistory,
    private val clock: SearchClock = SearchClock(System::currentTimeMillis),
) {
    suspend fun record(target: SearchHistoryTarget) {
        history.record(target, clock.nowMillis().coerceAtLeast(0L))
    }

    suspend fun rank(query: String, values: Collection<ValidatedProviderResult>): List<RankedResult> {
        val now = clock.nowMillis().coerceAtLeast(0L)
        val historyScores = history.entries()
            .asSequence()
            .filter { it.lastLaunchedAtMillis >= 0L }
            .associate { entry -> entry.target to historyScore(entry, now) }
        val normalizedQuery = query.trim()
        val ranked = values.mapNotNull { value ->
            val match = matchScore(query, value.result.title.value)
            if (!isMatch(normalizedQuery, value.result.kind, match)) {
                return@mapNotNull null
            }
            val historyScore = value.target?.historyTarget?.let { historyScores[it] }.orZero()
            RankedResult(value, value.result.providerRelevance + match + historyScore)
        }
        return ranked
            .groupBy { it.value.target?.duplicateKey ?: uniqueKey(it.value) }
            .values
            .map { duplicates -> duplicates.sortedWith(order).first() }
            .sortedWith(order)
    }

    fun matches(query: String, result: ProviderResult): Boolean {
        val normalizedQuery = query.trim()
        return isMatch(normalizedQuery, result.kind, matchScore(query, result.title.value))
    }

    private fun isMatch(normalizedQuery: String, kind: SearchResultKind, score: Int): Boolean =
        normalizedQuery.isEmpty() || score > 0 ||
            kind == SearchResultKind.WEB || kind == SearchResultKind.INFORMATION

    private fun historyScore(entry: SearchHistoryEntry, nowMillis: Long): Int {
        // Future timestamps can result from a clock moving backwards. Clamp their age to zero.
        val age = max(0L, nowMillis - entry.lastLaunchedAtMillis)
        val halfLives = age / HISTORY_HALF_LIFE_MILLIS
        val decayedCount = entry.launchCount.coerceAtMost(MAX_HISTORY_COUNT) shr halfLives.coerceAtMost(30).toInt()
        return decayedCount * HISTORY_POINTS_PER_LAUNCH
    }

    private fun matchScore(query: String, title: String): Int {
        val normalizedQuery = query.trim().lowercase(Locale.ROOT)
        if (normalizedQuery.isEmpty()) return 0
        val normalizedTitle = title.trim().lowercase(Locale.ROOT)
        if (normalizedTitle == normalizedQuery) return EXACT_MATCH
        if (normalizedTitle.startsWith(normalizedQuery)) return PREFIX_MATCH
        val queryTokens = normalizedQuery.split(WHITESPACE).filter(String::isNotBlank)
        val titleTokens = normalizedTitle.split(WHITESPACE).filter(String::isNotBlank)
        if (queryTokens.isNotEmpty() && queryTokens.all { queryToken ->
                titleTokens.any { it.startsWith(queryToken) }
            }
        ) return TOKEN_MATCH
        if (isSubsequence(normalizedQuery.filterNot(Char::isWhitespace), normalizedTitle)) {
            return FUZZY_MATCH
        }
        return 0
    }

    private fun isSubsequence(needle: String, haystack: String): Boolean {
        if (needle.isEmpty()) return false
        var index = 0
        haystack.forEach { character ->
            if (index < needle.length && character == needle[index]) index += 1
        }
        return index == needle.length
    }

    private fun uniqueKey(value: ValidatedProviderResult): SearchTargetKey = SearchTargetKey.of(
        "${value.providerId.value}:${value.result.id.value}",
    )

    companion object {
        private val WHITESPACE = Regex("\\s+")
        private const val EXACT_MATCH = 4_000
        private const val PREFIX_MATCH = 3_000
        private const val TOKEN_MATCH = 2_000
        private const val FUZZY_MATCH = 1_000
        private const val HISTORY_POINTS_PER_LAUNCH = 25
        private const val MAX_HISTORY_COUNT = 20
        private const val HISTORY_HALF_LIFE_MILLIS = 30L * 24 * 60 * 60 * 1_000

        private val order = compareByDescending<RankedResult> { it.score }
            .thenBy { kindOrder(it.value.result.kind) }
            .thenBy { it.value.result.title.value.lowercase(Locale.ROOT) }
            .thenBy { it.value.result.title.value }
            .thenBy { it.value.providerId.value }
            .thenBy { it.value.result.id.value }

        private fun kindOrder(kind: SearchResultKind): Int = when (kind) {
            SearchResultKind.APP -> 0
            SearchResultKind.SHORTCUT -> 1
            SearchResultKind.COMMAND -> 2
            SearchResultKind.CONTACT -> 3
            SearchResultKind.FILE -> 4
            SearchResultKind.SETTING -> 5
            SearchResultKind.WEB -> 6
            SearchResultKind.INFORMATION -> 7
        }

        private fun Int?.orZero(): Int = this ?: 0
    }
}

data class RankedResult(val value: ValidatedProviderResult, val score: Int)
