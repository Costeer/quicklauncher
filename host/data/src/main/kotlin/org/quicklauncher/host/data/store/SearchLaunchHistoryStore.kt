package org.quicklauncher.host.data.store

import java.util.Collections
import org.quicklauncher.contracts.domain.AppActivityIdentity

enum class SearchLaunchProfileKind {
    PERSONAL,
    WORK,
}

sealed interface SearchLaunchTargetRecord {
    data class App(
        val identity: AppActivityIdentity,
        val profileKind: SearchLaunchProfileKind,
    ) : SearchLaunchTargetRecord
    data class Shortcut(
        val target: ShortcutTarget,
        val profileKind: SearchLaunchProfileKind,
    ) : SearchLaunchTargetRecord
}

data class SearchLaunchHistoryRecord(
    val target: SearchLaunchTargetRecord,
    val launchCount: Int,
    val lastLaunchedAtMillis: Long,
) {
    init {
        require(launchCount > 0) { "Search launch count must be positive" }
        require(lastLaunchedAtMillis >= 0L) { "Search launch timestamp must not be negative" }
    }
}

interface SearchLaunchHistoryStore {
    suspend fun readSearchLaunchHistory(): List<SearchLaunchHistoryRecord>
    suspend fun recordSearchLaunch(target: SearchLaunchTargetRecord, nowMillis: Long)
}

internal fun immutableSearchHistory(
    values: Collection<SearchLaunchHistoryRecord>,
): List<SearchLaunchHistoryRecord> = Collections.unmodifiableList(ArrayList(values))
