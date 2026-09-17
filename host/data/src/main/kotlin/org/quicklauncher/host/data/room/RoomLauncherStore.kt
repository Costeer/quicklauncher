package org.quicklauncher.host.data.room

import androidx.room3.Room
import androidx.room3.withReadTransaction
import androidx.room3.withWriteTransaction
import androidx.sqlite.driver.AndroidSQLiteDriver
import java.io.File
import java.util.Collections
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.quicklauncher.host.data.store.CloseableLauncherStore
import org.quicklauncher.host.data.store.CommitResult
import org.quicklauncher.host.data.store.ConfigurationReconciliation
import org.quicklauncher.host.data.store.ConfigurationResolver
import org.quicklauncher.host.data.store.EmptyConfigurationResolver
import org.quicklauncher.host.data.store.InMemoryLauncherStore
import org.quicklauncher.host.data.store.LauncherSnapshot
import org.quicklauncher.host.data.store.LauncherTransaction
import org.quicklauncher.host.data.store.PlacementPolicy
import org.quicklauncher.host.data.store.RejectUnresolvedPlacementPolicy
import org.quicklauncher.host.data.store.SearchLaunchHistoryRecord
import org.quicklauncher.host.data.store.SearchLaunchTargetRecord
import org.quicklauncher.host.data.store.ShortcutTarget
import org.quicklauncher.host.data.store.immutableSearchHistory
import org.quicklauncher.contracts.domain.ActivityName
import org.quicklauncher.contracts.domain.AppActivityIdentity
import org.quicklauncher.contracts.domain.PackageName
import org.quicklauncher.contracts.domain.ProfileSerial
import org.quicklauncher.contracts.domain.ShortcutId

internal class RoomLauncherStore private constructor(
    private val database: LauncherDatabase,
    private val configurationResolver: ConfigurationResolver,
    private val placementPolicy: PlacementPolicy,
    private val afterRowsCleared: suspend () -> Unit,
) : CloseableLauncherStore {
    private val writer = Mutex()
    private val dao = database.launcherDao()

    override suspend fun read(): LauncherSnapshot = database.withReadTransaction {
        dao.readRows().toLauncherSnapshot()
    }

    override suspend fun readAppOverrides() = database.withReadTransaction {
        Collections.unmodifiableList(dao.appOverrides().map(AppOverrideEntity::toRecord))
    }

    override suspend fun commit(transaction: LauncherTransaction): CommitResult =
        writer.withLock {
            database.withWriteTransaction {
                val current = dao.readRows().toLauncherSnapshot()
                val reducer = InMemoryLauncherStore(
                    initialState = current,
                    configurationResolver = configurationResolver,
                    placementPolicy = placementPolicy,
                )
                when (val result = reducer.commit(transaction)) {
                    is CommitResult.Committed -> {
                        dao.replaceRows(result.state.toLauncherRows(), afterRowsCleared)
                        result
                    }
                    is CommitResult.Rejected -> result
                }
            }
        }

    override suspend fun reconcileConfigurations(): ConfigurationReconciliation =
        writer.withLock {
            database.withWriteTransaction {
                val current = dao.readRows().toLauncherSnapshot()
                val reducer = InMemoryLauncherStore(
                    initialState = current,
                    configurationResolver = configurationResolver,
                    placementPolicy = placementPolicy,
                )
                val reconciliation = reducer.reconcileConfigurations()
                if (reconciliation.state != current) {
                    dao.replaceRows(reconciliation.state.toLauncherRows(), afterRowsCleared)
                }
                reconciliation
            }
        }

    override suspend fun readSearchLaunchHistory(): List<SearchLaunchHistoryRecord> = writer.withLock {
        database.withWriteTransaction {
            val storedCount = dao.searchLaunchHistoryCount()
            val retained = dao.searchLaunchHistory(MAX_SEARCH_HISTORY_SCAN)
                .mapNotNull { it.toSearchHistoryRecord() }
                .take(MAX_SEARCH_HISTORY_ENTRIES)
            if (storedCount != retained.size) {
                dao.deleteSearchLaunchHistory()
                dao.insertSearchLaunchHistory(retained.map { it.toSearchHistoryEntity() })
            }
            immutableSearchHistory(retained)
        }
    }

    override suspend fun recordSearchLaunch(target: SearchLaunchTargetRecord, nowMillis: Long) {
        val timestamp = nowMillis.coerceAtLeast(0L)
        writer.withLock {
            database.withWriteTransaction {
                val current = dao.searchLaunchHistory(MAX_SEARCH_HISTORY_SCAN)
                    .mapNotNull { it.toSearchHistoryRecord() }
                    .take(MAX_SEARCH_HISTORY_ENTRIES)
                val previous = current.firstOrNull { it.target == target }
                val updated = SearchLaunchHistoryRecord(
                    target = target,
                    launchCount = (previous?.launchCount ?: 0).coerceAtMost(Int.MAX_VALUE - 1) + 1,
                    lastLaunchedAtMillis = timestamp,
                )
                val retained = (current.filterNot { it.target == target } + updated)
                    .sortedWith(
                        compareByDescending<SearchLaunchHistoryRecord> { it.lastLaunchedAtMillis }
                            .thenBy { it.target.stableKey() },
                    )
                    .take(MAX_SEARCH_HISTORY_ENTRIES)
                dao.deleteSearchLaunchHistory()
                dao.insertSearchLaunchHistory(retained.map { it.toSearchHistoryEntity() })
            }
        }
    }

    override fun close() {
        database.close()
    }

    companion object {
        private const val MAX_SEARCH_HISTORY_ENTRIES = 256
        private const val MAX_SEARCH_HISTORY_SCAN = 1_024

        internal fun open(
            file: File,
            configurationResolver: ConfigurationResolver = EmptyConfigurationResolver,
            placementPolicy: PlacementPolicy = RejectUnresolvedPlacementPolicy,
        ): RoomLauncherStore = create(file, configurationResolver, placementPolicy) {}

        internal fun openForTesting(
            file: File,
            configurationResolver: ConfigurationResolver = EmptyConfigurationResolver,
            placementPolicy: PlacementPolicy = RejectUnresolvedPlacementPolicy,
            afterRowsCleared: suspend () -> Unit,
        ): RoomLauncherStore = create(
            file,
            configurationResolver,
            placementPolicy,
            afterRowsCleared,
        )

        private fun create(
            file: File,
            configurationResolver: ConfigurationResolver,
            placementPolicy: PlacementPolicy,
            afterRowsCleared: suspend () -> Unit,
        ): RoomLauncherStore {
            require(file.isAbsolute) { "Launcher database file must use an absolute path" }
            val database = Room.databaseBuilder<LauncherDatabase>(file.absolutePath) {
                LauncherDatabase_Impl()
            }
                .setDriver(AndroidSQLiteDriver())
                .addMigrations(
                    LauncherMigration2To3,
                    LauncherMigration3To4,
                    LauncherMigration4To5,
                    LauncherMigration5To6,
                    LauncherMigration6To7,
                )
                .build()
            return RoomLauncherStore(
                database,
                configurationResolver,
                placementPolicy,
                afterRowsCleared,
            )
        }
    }

}

private fun SearchLaunchHistoryEntity.toSearchHistoryRecord(): SearchLaunchHistoryRecord? {
    if (launchCount <= 0 || lastLaunchedAtMillis < 0L) return null
    return try {
        val profile = ProfileSerial.of(profileSerial)
        val parsedPackageName = PackageName.parse(packageName)
        val target = when (targetKind) {
            "PERSONAL_APP" -> SearchLaunchTargetRecord.App(
                AppActivityIdentity(profile, parsedPackageName, ActivityName.parse(targetName)),
                org.quicklauncher.host.data.store.SearchLaunchProfileKind.PERSONAL,
            )
            "WORK_APP" -> SearchLaunchTargetRecord.App(
                AppActivityIdentity(profile, parsedPackageName, ActivityName.parse(targetName)),
                org.quicklauncher.host.data.store.SearchLaunchProfileKind.WORK,
            )
            "PERSONAL_SHORTCUT" -> SearchLaunchTargetRecord.Shortcut(
                ShortcutTarget(profile, parsedPackageName, ShortcutId.parse(targetName)),
                org.quicklauncher.host.data.store.SearchLaunchProfileKind.PERSONAL,
            )
            "WORK_SHORTCUT" -> SearchLaunchTargetRecord.Shortcut(
                ShortcutTarget(profile, parsedPackageName, ShortcutId.parse(targetName)),
                org.quicklauncher.host.data.store.SearchLaunchProfileKind.WORK,
            )
            else -> return null
        }
        SearchLaunchHistoryRecord(target, launchCount, lastLaunchedAtMillis)
    } catch (_: IllegalArgumentException) {
        null
    }
}

private fun SearchLaunchHistoryRecord.toSearchHistoryEntity(): SearchLaunchHistoryEntity = when (val value = target) {
    is SearchLaunchTargetRecord.App -> SearchLaunchHistoryEntity(
        "${value.profileKind}_APP",
        value.identity.profile.value,
        value.identity.packageName.value,
        value.identity.activityName.value,
        launchCount,
        lastLaunchedAtMillis,
    )
    is SearchLaunchTargetRecord.Shortcut -> SearchLaunchHistoryEntity(
        "${value.profileKind}_SHORTCUT",
        value.target.profile.value,
        value.target.packageName.value,
        value.target.shortcutId.value,
        launchCount,
        lastLaunchedAtMillis,
    )
}

private fun SearchLaunchTargetRecord.stableKey(): String = when (this) {
    is SearchLaunchTargetRecord.App ->
        "${profileKind}_APP:${identity.profile.value}:${identity.packageName.value}:${identity.activityName.value}"
    is SearchLaunchTargetRecord.Shortcut ->
        "${profileKind}_SHORTCUT:${target.profile.value}:${target.packageName.value}:${target.shortcutId.value}"
}

fun openRoomLauncherStore(
    file: File,
    configurationResolver: ConfigurationResolver = EmptyConfigurationResolver,
    placementPolicy: PlacementPolicy = RejectUnresolvedPlacementPolicy,
): CloseableLauncherStore = RoomLauncherStore.open(file, configurationResolver, placementPolicy)
