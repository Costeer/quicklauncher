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

    override fun close() {
        database.close()
    }

    companion object {
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

fun openRoomLauncherStore(
    file: File,
    configurationResolver: ConfigurationResolver = EmptyConfigurationResolver,
    placementPolicy: PlacementPolicy = RejectUnresolvedPlacementPolicy,
): CloseableLauncherStore = RoomLauncherStore.open(file, configurationResolver, placementPolicy)
