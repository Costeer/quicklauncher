package org.quicklauncher.host.data.room

import androidx.sqlite.driver.AndroidSQLiteDriver
import androidx.sqlite.execSQL
import androidx.sqlite.SQLiteConnection
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.room3.testing.MigrationTestHelper
import java.util.concurrent.CancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.quicklauncher.contracts.contribution.ConfigurationDocument
import org.quicklauncher.contracts.contribution.EncodedConfiguration
import org.quicklauncher.contracts.domain.ConfigTypeId
import org.quicklauncher.contracts.domain.ConfigurationDocumentId
import org.quicklauncher.contracts.domain.ContributionId
import org.quicklauncher.contracts.domain.DestinationId
import org.quicklauncher.contracts.domain.ModuleInstanceId
import org.quicklauncher.contracts.domain.ContentItemId
import org.quicklauncher.contracts.domain.PackageName
import org.quicklauncher.contracts.domain.PlacementId
import org.quicklauncher.contracts.domain.ProfileSerial
import org.quicklauncher.contracts.domain.SchemaVersion
import org.quicklauncher.contracts.domain.ShortcutId
import org.quicklauncher.contracts.domain.StableKey
import org.quicklauncher.host.data.spatial.DestinationCoordinate
import org.quicklauncher.host.data.store.CommitResult
import org.quicklauncher.host.data.store.ContentItemKind
import org.quicklauncher.host.data.store.ContentItemRecord
import org.quicklauncher.host.data.store.DestinationInstall
import org.quicklauncher.host.data.store.DestinationLayoutRecord
import org.quicklauncher.host.data.store.DestinationRecord
import org.quicklauncher.host.data.store.LauncherEdit
import org.quicklauncher.host.data.store.LauncherTransaction
import org.quicklauncher.host.data.store.ModuleInstanceRecord
import org.quicklauncher.host.data.store.ModuleInstanceStatus
import org.quicklauncher.host.data.store.ModuleQuarantineOrigin
import org.quicklauncher.host.data.store.NewPlacedModule
import org.quicklauncher.host.data.store.EncodedPlacementData
import org.quicklauncher.host.data.store.PlacementRecord
import org.quicklauncher.host.data.store.PlacementPolicy
import org.quicklauncher.host.data.store.StoreRevision
import org.quicklauncher.host.data.store.StoreRejectionCode
import org.quicklauncher.host.data.store.StoredConfigurationDocument
import org.quicklauncher.host.data.store.ShortcutTarget
import org.quicklauncher.host.data.store.SearchLaunchTargetRecord
import org.quicklauncher.host.data.store.WidgetBindState
import org.quicklauncher.host.data.store.WidgetCleanupState
import org.quicklauncher.host.data.store.WidgetPlacementRecord
import org.quicklauncher.host.data.store.WidgetRestoreState

@RunWith(AndroidJUnit4::class)
class RoomLauncherStoreInstrumentedTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context = instrumentation.targetContext
    private val databaseFile = context.getDatabasePath(DATABASE_NAME)

    @get:Rule
    val migrationHelper = MigrationTestHelper(
        instrumentation = instrumentation,
        file = databaseFile,
        driver = AndroidSQLiteDriver(),
        databaseClass = LauncherDatabase::class,
    )

    @Before
    fun deleteDatabaseBeforeTest() {
        context.deleteDatabase(DATABASE_NAME)
    }

    @After
    fun deleteDatabaseAfterTest() {
        context.deleteDatabase(DATABASE_NAME)
    }

    @Test
    fun versionOneDatabaseMigratesEveryDurableTableWithoutChangingOpaqueBytes() = runTest {
        migrationHelper.createDatabase(1).closing(::insertVersionOneState)

        migrationHelper.runMigrationsAndValidate(
            7,
            listOf(
                LauncherMigration2To3,
                LauncherMigration3To4,
                LauncherMigration4To5,
                LauncherMigration5To6,
                LauncherMigration6To7,
            ),
        ).closing { connection ->
            assertLongQuery(connection, "SELECT COUNT(*) FROM destinations", 1)
            assertTextQuery(
                connection,
                "SELECT name FROM destinations WHERE id = '$DESTINATION_ID' AND x = -3 AND y = 8",
                "Start",
            )
            assertLongQuery(connection, "SELECT COUNT(*) FROM destination_layouts", 1)
            assertTextQuery(
                connection,
                "SELECT rootInstanceId FROM destination_layouts " +
                    "WHERE destinationId = '$DESTINATION_ID' " +
                    "AND layoutContributionId = '$LAYOUT_ID' AND selected = 1",
                INSTANCE_ID,
            )
            assertLongQuery(connection, "SELECT COUNT(*) FROM module_instances", 2)
            assertTextQuery(
                connection,
                "SELECT quarantineReasonMessage FROM module_instances WHERE id = '$INSTANCE_ID'",
                "legacy.failure",
            )
            assertTextQuery(
                connection,
                "SELECT quarantineOrigin FROM module_instances WHERE id = '$INSTANCE_ID'",
                "OTHER",
            )
            assertLongQuery(connection, "SELECT COUNT(*) FROM configuration_documents", 2)
            assertTextQuery(
                connection,
                "SELECT encoded FROM configuration_documents WHERE id = '$CONFIGURATION_ID'",
                RAW_CONFIGURATION,
            )
            assertTextQuery(
                connection,
                "SELECT encoded FROM configuration_documents " +
                    "WHERE id = '$CHILD_CONFIGURATION_ID'",
                CHILD_CONFIGURATION_PAYLOAD,
            )
            assertLongQuery(connection, "SELECT COUNT(*) FROM placements", 1)
            assertTextQuery(
                connection,
                "SELECT encodedPlacement FROM placements " +
                    "WHERE id = '$PLACEMENT_ID' AND payloadSchemaVersion = 3",
                RAW_PLACEMENT,
            )
            assertLongQuery(connection, "SELECT COUNT(*) FROM content_items", 3)
            assertTextQuery(
                connection,
                "SELECT encodedSemanticData FROM content_items WHERE id = '$FOLDER_ID'",
                FOLDER_PAYLOAD,
            )
            assertTextQuery(
                connection,
                "SELECT encodedSemanticData FROM content_items " +
                    "WHERE id = '$CONTENT_ITEM_ID' AND profileSerial = 10 " +
                    "AND packageName = 'org.example.app'",
                CONTENT_ITEM_PAYLOAD,
            )
            assertNullQuery(
                connection,
                "SELECT shortcutId FROM content_items WHERE id = '$CONTENT_ITEM_ID'",
            )
            assertTextQuery(
                connection,
                "SELECT encodedSemanticData FROM content_items " +
                    "WHERE id = '$SHORTCUT_CONTENT_ITEM_ID' AND kind = 'SHORTCUT' " +
                    "AND profileSerial = 10 AND packageName = 'org.example.app'",
                SHORTCUT_CONTENT_PAYLOAD,
            )
            assertTextQuery(
                connection,
                "SELECT shortcutId FROM content_items WHERE id = '$SHORTCUT_CONTENT_ITEM_ID'",
                SHORTCUT_ID,
            )
            assertLongQuery(connection, "SELECT COUNT(*) FROM folder_members", 1)
            assertTextQuery(
                connection,
                "SELECT memberContentItemId FROM folder_members " +
                    "WHERE folderContentItemId = '$FOLDER_ID' AND position = 0",
                CONTENT_ITEM_ID,
            )
            assertTextQuery(
                connection,
                "SELECT activityName FROM app_overrides " +
                    "WHERE profileSerial = 10 AND packageName = 'org.example.app'",
                "org.example.app.Main",
            )
            assertTextQuery(
                connection,
                "SELECT encodedOverride FROM app_overrides " +
                    "WHERE profileSerial = 10 AND packageName = 'org.example.app'",
                APP_OVERRIDE_PAYLOAD,
            )
            assertTextQuery(
                connection,
                "SELECT encodedOptions FROM widget_placements " +
                    "WHERE moduleInstanceId = '$CHILD_INSTANCE_ID' AND appWidgetId = 42",
                WIDGET_OPTIONS,
            )
            assertTextQuery(
                connection,
                "SELECT encodedTheme FROM theme_profiles " +
                    "WHERE id = '$THEME_ID' AND schemaVersion = 4",
                THEME_PAYLOAD,
            )
            assertTextQuery(
                connection,
                "SELECT encodedBackground FROM destination_backgrounds " +
                    "WHERE destinationId = '$DESTINATION_ID' AND schemaVersion = 2",
                BACKGROUND_PAYLOAD,
            )
            assertTextQuery(
                connection,
                "SELECT appVersion FROM crash_markers WHERE id = '$CRASH_ID'",
                "0.1",
            )
            assertLongQuery(
                connection,
                "SELECT startupAttempt FROM crash_markers WHERE id = '$CRASH_ID'",
                2,
            )
            assertLongQuery(
                connection,
                "SELECT timestampEpochMillis FROM crash_markers WHERE id = '$CRASH_ID'",
                1234,
            )
            assertTextQuery(
                connection,
                "SELECT outcome FROM crash_markers WHERE id = '$CRASH_ID'",
                "renderer-failed",
            )
            assertLongQuery(
                connection,
                "SELECT firstObservedAtEpochMillis FROM crash_markers WHERE id = '$CRASH_ID'",
                1200,
            )
            assertLongQuery(
                connection,
                "SELECT revision FROM store_metadata WHERE singletonKey = 1 " +
                    "AND startDestinationId = '$DESTINATION_ID'",
                0,
            )
        }
    }

    @Test
    fun versionOneAutoMigrationPreservesLegacyRowsIntoVersionTwo() = runTest {
        migrationHelper.createDatabase(1).closing(::insertVersionOneState)

        migrationHelper.runMigrationsAndValidate(2, emptyList()).closing { connection ->
            assertTextQuery(
                connection,
                "SELECT encoded FROM configuration_documents WHERE id = '$CONFIGURATION_ID'",
                RAW_CONFIGURATION,
            )
            assertTextQuery(
                connection,
                "SELECT encodedPlacement FROM placements WHERE id = '$PLACEMENT_ID'",
                RAW_PLACEMENT,
            )
            assertLongQuery(connection, "SELECT COUNT(*) FROM content_items", 3)
            assertLongQuery(connection, "SELECT COUNT(*) FROM widget_placements", 1)
        }
    }

    @Test
    fun versionTwoMigrationPreservesOpaqueRowsAndAddsStructuralActivityIdentity() = runTest {
        migrationHelper.createDatabase(2).closing(::insertVersionOneState)

        migrationHelper.runMigrationsAndValidate(
            3,
            listOf(LauncherMigration2To3),
        ).closing { connection ->
            assertTextQuery(
                connection,
                "SELECT activityName FROM app_overrides WHERE profileSerial = 10",
                "org.example.app.Main",
            )
            assertTextQuery(
                connection,
                "SELECT encodedOverride FROM app_overrides WHERE profileSerial = 10",
                APP_OVERRIDE_PAYLOAD,
            )
            assertTextQuery(
                connection,
                "SELECT quarantineOrigin FROM module_instances WHERE id = '$INSTANCE_ID'",
                "OTHER",
            )
        }
    }

    @Test
    fun versionThreeMigrationPreservesShortcutPayloadAndAddsStructuralShortcutIdentity() = runTest {
        migrationHelper.createDatabase(3).closing(::insertVersionThreeState)

        migrationHelper.runMigrationsAndValidate(
            4,
            listOf(LauncherMigration3To4),
        ).closing { connection ->
            assertTextQuery(
                connection,
                "SELECT shortcutId FROM content_items WHERE id = '$SHORTCUT_CONTENT_ITEM_ID'",
                SHORTCUT_ID,
            )
            assertTextQuery(
                connection,
                "SELECT encodedSemanticData FROM content_items " +
                    "WHERE id = '$SHORTCUT_CONTENT_ITEM_ID'",
                SHORTCUT_CONTENT_PAYLOAD,
            )
        }
    }

    @Test
    fun versionFiveWidgetCleanupMigrationPreservesBindingAndAddsRecoverableState() = runTest {
        migrationHelper.createDatabase(5).closing { connection ->
            connection.execSQL(
                "INSERT INTO configuration_documents " +
                    "(id, configType, schemaVersion, encoded, lastMigrationErrorCode) VALUES " +
                    "('$CHILD_CONFIGURATION_ID', '$CHILD_CONFIG_TYPE', 2, " +
                    "'$CHILD_CONFIGURATION_PAYLOAD', NULL)",
            )
            connection.execSQL(
                "INSERT INTO module_instances " +
                    "(id, contributionId, configurationDocumentId, lifecycle, quarantineReasonCode, " +
                    "quarantineReasonMessage, quarantineOrigin) VALUES " +
                    "('$CHILD_INSTANCE_ID', '$BLOCK_ID', '$CHILD_CONFIGURATION_ID', " +
                    "'active', NULL, NULL, 'OTHER')",
            )
            connection.execSQL(
                "INSERT INTO widget_placements " +
                    "(moduleInstanceId, profileSerial, providerPackageName, providerClassName, " +
                    "appWidgetId, bindingState, encodedOptions) VALUES " +
                    "('$CHILD_INSTANCE_ID', 10, 'org.example.widget', " +
                    "'org.example.widget.Provider', 42, 'BOUND', '$WIDGET_OPTIONS')",
            )
        }

        migrationHelper.runMigrationsAndValidate(
            7,
            listOf(LauncherMigration5To6, LauncherMigration6To7),
        ).closing { connection ->
            assertTextQuery(
                connection,
                "SELECT cleanupState FROM widget_placements WHERE appWidgetId = 42",
                "NONE",
            )
            assertTextQuery(
                connection,
                "SELECT encodedOptions FROM widget_placements WHERE appWidgetId = 42",
                WIDGET_OPTIONS,
            )
        }
    }

    @Test
    fun versionSixMigrationAddsBoundedTypedHistoryAndPreservesMalformedRowsOnRead() = runTest {
        migrationHelper.createDatabase(6).close()
        migrationHelper.runMigrationsAndValidate(
            7,
            listOf(LauncherMigration6To7),
        ).closing { connection ->
            connection.execSQL(
                "INSERT INTO search_launch_history " +
                    "(targetKind, profileSerial, packageName, targetName, launchCount, " +
                    "lastLaunchedAtMillis) VALUES " +
                    "('UNKNOWN', 0, 'org.example.invalid', 'opaque', 1, 10)",
            )
        }

        val store = RoomLauncherStore.open(databaseFile)
        assertTrue(store.readSearchLaunchHistory().isEmpty())
        repeat(300) { index ->
            store.recordSearchLaunch(
                SearchLaunchTargetRecord.App(
                    org.quicklauncher.contracts.domain.AppActivityIdentity(
                        ProfileSerial.of(0),
                        PackageName.parse("org.example.app$index"),
                        org.quicklauncher.contracts.domain.ActivityName.parse(
                            "org.example.app$index.MainActivity",
                        ),
                    ),
                    org.quicklauncher.host.data.store.SearchLaunchProfileKind.PERSONAL,
                ),
                index.toLong(),
            )
        }
        assertEquals(256, store.readSearchLaunchHistory().size)
        store.close()

        val reopened = RoomLauncherStore.open(databaseFile)
        val restored = reopened.readSearchLaunchHistory()
        assertEquals(256, restored.size)
        assertEquals(299L, restored.first().lastLaunchedAtMillis)
        reopened.close()
    }

    @Test
    fun versionSixHistoryMigrationFailureRollsBackWithoutChangingExistingSchema() = runTest {
        migrationHelper.createDatabase(6).closing { connection ->
            connection.execSQL(
                "CREATE TABLE search_launch_history (unrelatedOpaque TEXT NOT NULL)",
            )
            connection.execSQL(
                "INSERT INTO search_launch_history (unrelatedOpaque) VALUES ('preserve-me')",
            )
        }

        val failure = runCatching {
            migrationHelper.runMigrationsAndValidate(
                7,
                listOf(LauncherMigration6To7),
            ).close()
        }.exceptionOrNull()
        assertTrue(failure != null)

        migrationHelper.runMigrationsAndValidate(6, emptyList()).closing { connection ->
            assertTextQuery(
                connection,
                "SELECT unrelatedOpaque FROM search_launch_history",
                "preserve-me",
            )
        }
    }

    @Test
    fun versionFourCrashMarkerMigratesWithoutChangingDurableFieldsAndBecomesUnique() = runTest {
        migrationHelper.createDatabase(4).closing { connection ->
            insertVersionFourRecoveryState(connection, includeDuplicateMarker = false)
        }

        migrationHelper.runMigrationsAndValidate(
            5,
            listOf(LauncherMigration4To5),
        ).closing { connection ->
            assertTextQuery(
                connection,
                "SELECT encoded FROM configuration_documents WHERE id = '$CONFIGURATION_ID'",
                RAW_CONFIGURATION,
            )
            assertTextQuery(
                connection,
                "SELECT appVersion FROM crash_markers WHERE id = '$CRASH_ID'",
                "0.1",
            )
            assertLongQuery(
                connection,
                "SELECT startupAttempt FROM crash_markers WHERE id = '$CRASH_ID'",
                2,
            )
            assertLongQuery(
                connection,
                "SELECT timestampEpochMillis FROM crash_markers WHERE id = '$CRASH_ID'",
                1234,
            )
            assertTextQuery(
                connection,
                "SELECT outcome FROM crash_markers WHERE id = '$CRASH_ID'",
                "renderer-failed",
            )
            assertLongQuery(
                connection,
                "SELECT firstObservedAtEpochMillis FROM crash_markers WHERE id = '$CRASH_ID'",
                1200,
            )

            val duplicateFailure = runCatching {
                insertCrashMarker(
                    connection = connection,
                    id = SECOND_CRASH_ID,
                    appVersion = "0.2",
                    startupAttempt = 3,
                    timestampEpochMillis = 2234,
                    outcome = "retry-failed",
                    firstObservedAtEpochMillis = 2200,
                )
            }.exceptionOrNull()
            assertTrue(duplicateFailure != null)
            assertLongQuery(connection, "SELECT COUNT(*) FROM crash_markers", 1)
        }
    }

    @Test
    fun versionFourDuplicateCrashMarkersFailMigrationAndPreserveLegacyRows() = runTest {
        migrationHelper.createDatabase(4).closing { connection ->
            insertVersionFourRecoveryState(connection, includeDuplicateMarker = true)
        }

        val failure = runCatching {
            migrationHelper.runMigrationsAndValidate(
                5,
                listOf(LauncherMigration4To5),
            ).close()
        }.exceptionOrNull()
        assertTrue(failure != null)

        migrationHelper.runMigrationsAndValidate(4, emptyList()).closing { connection ->
            assertLongQuery(connection, "SELECT COUNT(*) FROM crash_markers", 2)
            assertTextQuery(
                connection,
                "SELECT encoded FROM configuration_documents WHERE id = '$CONFIGURATION_ID'",
                RAW_CONFIGURATION,
            )
            assertTextQuery(
                connection,
                "SELECT appVersion FROM crash_markers WHERE id = '$CRASH_ID'",
                "0.1",
            )
            assertTextQuery(
                connection,
                "SELECT outcome FROM crash_markers WHERE id = '$CRASH_ID'",
                "renderer-failed",
            )
            assertTextQuery(
                connection,
                "SELECT appVersion FROM crash_markers WHERE id = '$SECOND_CRASH_ID'",
                "0.2",
            )
            assertTextQuery(
                connection,
                "SELECT outcome FROM crash_markers WHERE id = '$SECOND_CRASH_ID'",
                "retry-failed",
            )
        }
    }

    @Test
    fun versionThreeShortcutWithoutRecoverableIdentityFailsAndLeavesSourceBytesUntouched() = runTest {
        migrationHelper.createDatabase(3).closing { connection ->
            connection.execSQL(
                "INSERT INTO content_items " +
                    "(id, kind, profileSerial, packageName, encodedSemanticData) VALUES " +
                    "('$SHORTCUT_CONTENT_ITEM_ID', 'SHORTCUT', 10, 'org.example.app', " +
                    "'$UNRECOVERABLE_SHORTCUT_PAYLOAD')",
            )
        }

        val failure = runCatching {
            migrationHelper.runMigrationsAndValidate(
                4,
                listOf(LauncherMigration3To4),
            ).close()
        }.exceptionOrNull()
        assertTrue(failure != null)

        migrationHelper.runMigrationsAndValidate(3, emptyList()).closing { connection ->
            assertTextQuery(
                connection,
                "SELECT encodedSemanticData FROM content_items " +
                    "WHERE id = '$SHORTCUT_CONTENT_ITEM_ID'",
                UNRECOVERABLE_SHORTCUT_PAYLOAD,
            )
        }
    }

    @Test
    fun unrelatedCommitPreservesEveryStructuredAndOpaqueCurrentField() = runTest {
        migrationHelper.createDatabase(3).closing(::insertCurrentState)

        val store = RoomLauncherStore.open(
            databaseFile,
            placementPolicy = PlacementPolicy { null },
        )
        val before = store.read()
        assertEquals(2, before.appOverrides.size)
        assertEquals("org.example.widget.Provider", before.widgetPlacements.single().providerClassName)
        assertEquals(4, before.themeProfiles.single().schemaVersion)
        assertEquals(1200L, before.crashMarkers.single().firstObservedAtEpochMillis)
        assertEquals("configuration.decode-failed", before.configurationDocuments.first {
            it.id.value == CONFIGURATION_ID
        }.lastMigrationErrorCode)
        assertEquals(
            ModuleQuarantineOrigin.RENDERER,
            (before.moduleInstances.first {
                it.id.value == INSTANCE_ID
            }.status as ModuleInstanceStatus.Quarantined).origin,
        )

        val committed = store.commit(
            LauncherTransaction(
                before.revision,
                listOf(
                    LauncherEdit.RenameDestination(
                        DestinationId.parse(DESTINATION_ID),
                        "Renamed",
                    ),
                ),
            ),
        ) as CommitResult.Committed
        store.close()

        val reopened = RoomLauncherStore.open(
            databaseFile,
            placementPolicy = PlacementPolicy { null },
        )
        try {
            val restored = reopened.read()
            assertEquals(committed.state, restored)
            assertEquals(RAW_CONFIGURATION, restored.configurationDocuments.first {
                it.id.value == CONFIGURATION_ID
            }.document.encoded.value)
            assertEquals(RAW_PLACEMENT, restored.placements.single().data.value)
            assertEquals("org.example.app.First", restored.appOverrides.first().activityName)
            assertEquals("org.example.app.Second", restored.appOverrides.last().activityName)
            assertEquals("org.example.app", restored.contentItems.first {
                it.id.value == CONTENT_ITEM_ID
            }.packageName?.value)
        } finally {
            reopened.close()
        }
    }

    @Test
    fun committedStateSurvivesCloseAndReopenWhileRejectedEditRollsBack() = runTest {
        val first = RoomLauncherStore.open(
            databaseFile,
            placementPolicy = PlacementPolicy { null },
        )
        val committed = first.commit(
            LauncherTransaction(
                StoreRevision.ZERO,
                listOf(LauncherEdit.Bootstrap(install("start", 0, 0))),
            ),
        ) as CommitResult.Committed
        val rejected = first.commit(
            LauncherTransaction(
                committed.state.revision,
                listOf(LauncherEdit.InstallDestination(install("island", 2, 0))),
            ),
        )
        assertTrue(rejected is CommitResult.Rejected)
        assertEquals(committed.state, first.read())
        first.close()

        val reopened = RoomLauncherStore.open(
            databaseFile,
            placementPolicy = PlacementPolicy { null },
        )
        assertEquals(committed.state, reopened.read())
        reopened.close()
    }

    @Test
    fun duplicateShortcutPlacementsAndFolderOrderSurviveCloseAndReopen() = runTest {
        val store = openStore()
        val target = ShortcutTarget(
            ProfileSerial.of(0),
            PackageName.parse("org.example.mail"),
            ShortcutId.parse("compose"),
        )
        val before = store.commit(
            LauncherTransaction(
                StoreRevision.ZERO,
                listOf(LauncherEdit.Bootstrap(install("start", 0, 0))),
            ),
        ).let { it as CommitResult.Committed }.state
        val firstId = ContentItemId.parse("org.quicklauncher.content/compose-one")
        val secondId = ContentItemId.parse("org.quicklauncher.content/compose-two")
        val folderId = ContentItemId.parse("org.quicklauncher.content/compose-folder")
        val result = store.commit(
            LauncherTransaction(
                before.revision,
                listOf(
                    LauncherEdit.CreateShortcutPlacement(
                        ContentItemRecord.shortcut(
                            firstId,
                            "opaque-one",
                            target,
                        ),
                    ),
                    LauncherEdit.CreateShortcutPlacement(
                        ContentItemRecord.shortcut(
                            secondId,
                            "opaque-two",
                            target,
                        ),
                    ),
                    LauncherEdit.CreateFolder(
                        ContentItemRecord(folderId, ContentItemKind.FOLDER, "folder-opaque"),
                        listOf(secondId, firstId),
                    ),
                ),
            ),
        ) as CommitResult.Committed
        store.close()

        val reopened = openStore()
        try {
            assertEquals(result.state, reopened.read())
            assertEquals(2, reopened.read().contentItems.count { it.shortcutTarget == target })
            assertEquals(
                listOf(secondId, firstId),
                reopened.read().folderMembers.filter { it.folderId == folderId }.map { it.memberId },
            )
        } finally {
            reopened.close()
        }
    }

    @Test
    fun widgetPendingFailureAndCleanupStatesSurviveCloseAndReopen() = runTest {
        var store = openStore()
        val root = store.commit(
            LauncherTransaction(
                StoreRevision.ZERO,
                listOf(LauncherEdit.Bootstrap(install("start", 0, 0))),
            ),
        ).let { it as CommitResult.Committed }.state
        val childId = ModuleInstanceId.parse(CHILD_INSTANCE_ID)
        val childConfigurationId = ConfigurationDocumentId.parse(CHILD_CONFIGURATION_ID)
        val block = NewPlacedModule(
            instance = ModuleInstanceRecord(
                childId,
                ContributionId.parse(BLOCK_ID),
                childConfigurationId,
            ),
            configuration = StoredConfigurationDocument(
                childConfigurationId,
                ConfigurationDocument(
                    ConfigTypeId.parse(CHILD_CONFIG_TYPE),
                    SchemaVersion.of(1),
                    EncodedConfiguration.of(CHILD_CONFIGURATION_PAYLOAD),
                ),
            ),
            placement = PlacementRecord(
                PlacementId.parse(PLACEMENT_ID),
                root.destinationLayouts.single().layoutInstanceId,
                root.destinationLayouts.single().layoutInstanceId,
                StableKey.parse("main"),
                childId,
                0,
                EncodedPlacementData.of(RAW_PLACEMENT),
                1,
            ),
        )
        val placed = store.commit(
            LauncherTransaction(root.revision, listOf(LauncherEdit.CommitDrop(block))),
        ).let { it as CommitResult.Committed }.state
        val pending = WidgetPlacementRecord(
            moduleInstanceId = childId,
            appWidgetId = 51,
            providerPackage = PackageName.parse("org.example.widget"),
            providerClassName = "org.example.widget.Provider",
            profile = ProfileSerial.of(0),
            intendedWidthDp = 240,
            intendedHeightDp = 120,
            bindState = WidgetBindState.PENDING,
            restoreState = WidgetRestoreState.READY,
        )
        store.commit(
            LauncherTransaction(placed.revision, listOf(LauncherEdit.BeginWidgetBinding(pending))),
        ).let { it as CommitResult.Committed }
        store.close()

        store = openStore()
        assertEquals(WidgetBindState.PENDING, store.read().widgetPlacements.single().bindState)
        val cleanup = store.commit(
            LauncherTransaction(
                store.read().revision,
                listOf(LauncherEdit.BeginWidgetDeletion(childId)),
            ),
        ).let { it as CommitResult.Committed }.state
        assertEquals(WidgetCleanupState.DELETE_PENDING, cleanup.widgetPlacements.single().cleanupState)
        store.close()

        store = openStore()
        assertEquals(WidgetCleanupState.DELETE_PENDING, store.read().widgetPlacements.single().cleanupState)
        store.commit(
            LauncherTransaction(store.read().revision, listOf(LauncherEdit.FailWidgetBinding(childId))),
        ).let { it as CommitResult.Committed }
        store.close()

        store = openStore()
        val failed = store.read().widgetPlacements.single()
        assertEquals(WidgetBindState.FAILED, failed.bindState)
        assertEquals(null, failed.appWidgetId)
        val reboundPending = pending.copy(appWidgetId = 52)
        val rebound = store.commit(
            LauncherTransaction(
                store.read().revision,
                listOf(
                    LauncherEdit.BeginWidgetBinding(reboundPending),
                    LauncherEdit.CompleteWidgetBinding(childId),
                    LauncherEdit.BeginWidgetInvalidation(childId),
                ),
            ),
        ).let { it as CommitResult.Committed }.state
        assertEquals(WidgetCleanupState.REBIND_PENDING, rebound.widgetPlacements.single().cleanupState)
        store.close()

        store = openStore()
        try {
            val restored = store.read().widgetPlacements.single()
            assertEquals(WidgetBindState.BOUND, restored.bindState)
            assertEquals(WidgetCleanupState.REBIND_PENDING, restored.cleanupState)
            assertEquals(52, restored.appWidgetId)
        } finally {
            store.close()
        }
    }

    @Test
    fun destructiveReplacementFailureRollsBackTheRoomTransaction() = runTest {
        val expected = bootstrappedState()
        val failing = RoomLauncherStore.openForTesting(
            file = databaseFile,
            placementPolicy = PlacementPolicy { null },
            afterRowsCleared = { throw InjectedReplacementFailure },
        )

        val failure = runCatching {
            failing.commit(rename(expected, "Not persisted"))
        }.exceptionOrNull()
        assertTrue(failure === InjectedReplacementFailure)
        failing.close()

        val reopened = openStore()
        try {
            assertEquals(expected, reopened.read())
        } finally {
            reopened.close()
        }
    }

    @Test
    fun destructiveReplacementCancellationRollsBackTheRoomTransaction() = runTest {
        val expected = bootstrappedState()
        val cancelling = RoomLauncherStore.openForTesting(
            file = databaseFile,
            placementPolicy = PlacementPolicy { null },
            afterRowsCleared = { throw CancellationException("injected replacement cancellation") },
        )

        val failure = runCatching {
            cancelling.commit(rename(expected, "Not persisted"))
        }.exceptionOrNull()
        assertTrue(failure is CancellationException)
        cancelling.close()

        val reopened = openStore()
        try {
            assertEquals(expected, reopened.read())
        } finally {
            reopened.close()
        }
    }

    @Test
    fun twoStoreInstancesCannotCommitTheSameExpectedRevision() = runTest {
        val expected = bootstrappedState()
        val first = openStore()
        val second = openStore()
        try {
            val results = listOf(
                async { first.commit(rename(expected, "First")) },
                async { second.commit(rename(expected, "Second")) },
            ).awaitAll()

            assertEquals(1, results.count { it is CommitResult.Committed })
            val rejected = results.single { it is CommitResult.Rejected } as CommitResult.Rejected
            assertEquals(StoreRejectionCode.STALE_REVISION, rejected.reason.code)
            assertEquals(StoreRevision.of(2), first.read().revision)
        } finally {
            first.close()
            second.close()
        }
    }

    private suspend fun bootstrappedState() = openStore().let { store ->
        try {
            (store.commit(
                LauncherTransaction(
                    StoreRevision.ZERO,
                    listOf(LauncherEdit.Bootstrap(install("start", 0, 0))),
                ),
            ) as CommitResult.Committed).state
        } finally {
            store.close()
        }
    }

    private fun openStore(): RoomLauncherStore = RoomLauncherStore.open(
        databaseFile,
        placementPolicy = PlacementPolicy { null },
    )

    private fun rename(
        snapshot: org.quicklauncher.host.data.store.LauncherSnapshot,
        name: String,
    ): LauncherTransaction = LauncherTransaction(
        snapshot.revision,
        listOf(LauncherEdit.RenameDestination(DestinationId.parse(DESTINATION_ID), name)),
    )

    private fun install(local: String, x: Long, y: Long): DestinationInstall {
        val destinationId = DestinationId.parse("org.quicklauncher.destination/$local")
        val instanceId = ModuleInstanceId.parse("org.quicklauncher.instance/$local-layout")
        val configurationId = ConfigurationDocumentId.parse(
            "org.quicklauncher.configuration/$local-layout",
        )
        val contributionId = ContributionId.parse(LAYOUT_ID)
        return DestinationInstall(
            destination = DestinationRecord(
                destinationId,
                local.replaceFirstChar(Char::uppercase),
                DestinationCoordinate(x, y),
            ),
            layout = DestinationLayoutRecord(
                destinationId,
                contributionId,
                instanceId,
                selected = true,
            ),
            layoutInstance = ModuleInstanceRecord(instanceId, contributionId, configurationId),
            configuration = StoredConfigurationDocument(
                configurationId,
                ConfigurationDocument(
                    ConfigTypeId.parse(CONFIG_TYPE),
                    SchemaVersion.of(1),
                    EncodedConfiguration.of(" { value: '$local' } "),
                ),
            ),
        )
    }

    private fun insertVersionOneState(connection: SQLiteConnection) {
        connection.execSQL(
            "INSERT INTO configuration_documents " +
                "(id, configType, schemaVersion, encoded, lastMigrationErrorCode) VALUES " +
                "('$CONFIGURATION_ID', '$CONFIG_TYPE', 1, '$RAW_CONFIGURATION', NULL), " +
                "('$CHILD_CONFIGURATION_ID', '$CHILD_CONFIG_TYPE', 2, " +
                "'$CHILD_CONFIGURATION_PAYLOAD', 'legacy.decode')",
        )
        connection.execSQL(
            "INSERT INTO module_instances " +
                "(id, contributionId, configurationDocumentId, lifecycle, quarantineReasonCode) " +
                "VALUES ('$INSTANCE_ID', '$LAYOUT_ID', '$CONFIGURATION_ID', " +
                "'quarantined', 'legacy.failure'), " +
                "('$CHILD_INSTANCE_ID', '$BLOCK_ID', '$CHILD_CONFIGURATION_ID', " +
                "'active', NULL)",
        )
        connection.execSQL(
            "INSERT INTO destinations (id, name, x, y) VALUES " +
                "('$DESTINATION_ID', 'Start', -3, 8)",
        )
        connection.execSQL(
            "INSERT INTO destination_layouts " +
                "(destinationId, layoutContributionId, rootInstanceId, selected) VALUES " +
                "('$DESTINATION_ID', '$LAYOUT_ID', '$INSTANCE_ID', 1)",
        )
        connection.execSQL(
            "INSERT INTO placements " +
                "(id, treeRootInstanceId, parentInstanceId, slotId, childInstanceId, position, " +
                "payloadSchemaVersion, encodedPlacement) VALUES " +
                "('$PLACEMENT_ID', '$INSTANCE_ID', '$INSTANCE_ID', 'main', " +
                "'$CHILD_INSTANCE_ID', 0, 3, '$RAW_PLACEMENT')",
        )
        connection.execSQL(
            "INSERT INTO content_items " +
                "(id, kind, profileSerial, packageName, encodedSemanticData) VALUES " +
                "('$FOLDER_ID', 'FOLDER', NULL, NULL, '$FOLDER_PAYLOAD'), " +
                "('$CONTENT_ITEM_ID', 'FAVORITE', 10, 'org.example.app', " +
                "'$CONTENT_ITEM_PAYLOAD'), " +
                "('$SHORTCUT_CONTENT_ITEM_ID', 'SHORTCUT', 10, 'org.example.app', " +
                "'$SHORTCUT_CONTENT_PAYLOAD')",
        )
        connection.execSQL(
            "INSERT INTO folder_members " +
                "(folderContentItemId, memberContentItemId, position) VALUES " +
                "('$FOLDER_ID', '$CONTENT_ITEM_ID', 0)",
        )
        connection.execSQL(
            "INSERT INTO app_overrides " +
                "(profileSerial, packageName, encodedOverride) VALUES " +
                "(10, 'org.example.app', '$APP_OVERRIDE_PAYLOAD')",
        )
        connection.execSQL(
            "INSERT INTO widget_placements " +
                "(moduleInstanceId, profileSerial, providerPackageName, providerClassName, " +
                "appWidgetId, bindingState, encodedOptions) VALUES " +
                "('$CHILD_INSTANCE_ID', 10, 'org.example.widget', " +
                "'org.example.widget.Provider', 42, 'BOUND', '$WIDGET_OPTIONS')",
        )
        connection.execSQL(
            "INSERT INTO theme_profiles (id, name, schemaVersion, encodedTheme) VALUES " +
                "('$THEME_ID', 'Default', 4, '$THEME_PAYLOAD')",
        )
        connection.execSQL(
            "INSERT INTO destination_backgrounds " +
                "(destinationId, themeProfileId, schemaVersion, encodedBackground) VALUES " +
                "('$DESTINATION_ID', '$THEME_ID', 2, '$BACKGROUND_PAYLOAD')",
        )
        connection.execSQL(
            "INSERT INTO crash_markers " +
                "(id, moduleInstanceId, failureKind, firstObservedAtEpochMillis, " +
                "lastObservedAtEpochMillis, occurrenceCount) VALUES " +
                "('$CRASH_ID', '$CHILD_INSTANCE_ID', '$CRASH_PAYLOAD', 1200, 1234, 2)",
        )
        connection.execSQL(
            "INSERT INTO store_metadata (singletonKey, startDestinationId) VALUES " +
                "(1, '$DESTINATION_ID')",
        )
    }

    private fun insertCurrentState(connection: SQLiteConnection) {
        connection.execSQL(
            "INSERT INTO configuration_documents " +
                "(id, configType, schemaVersion, encoded, lastMigrationErrorCode) VALUES " +
                "('$CONFIGURATION_ID', '$CONFIG_TYPE', 1, '$RAW_CONFIGURATION', " +
                "'configuration.decode-failed'), " +
                "('$CHILD_CONFIGURATION_ID', '$CHILD_CONFIG_TYPE', 2, 'child opaque', NULL)",
        )
        connection.execSQL(
            "INSERT INTO module_instances " +
                "(id, contributionId, configurationDocumentId, lifecycle, quarantineReasonCode, " +
                "quarantineReasonMessage, quarantineOrigin) VALUES " +
                "('$INSTANCE_ID', '$LAYOUT_ID', '$CONFIGURATION_ID', 'quarantined', " +
                "'renderer.crash', 'Renderer failed', 'RENDERER'), " +
                "('$CHILD_INSTANCE_ID', '$BLOCK_ID', '$CHILD_CONFIGURATION_ID', 'active', " +
                "NULL, NULL, 'OTHER')",
        )
        connection.execSQL(
            "INSERT INTO destinations (id, name, x, y) VALUES " +
                "('$DESTINATION_ID', 'Start', 0, 0)",
        )
        connection.execSQL(
            "INSERT INTO destination_layouts " +
                "(destinationId, layoutContributionId, rootInstanceId, selected) VALUES " +
                "('$DESTINATION_ID', '$LAYOUT_ID', '$INSTANCE_ID', 1)",
        )
        connection.execSQL(
            "INSERT INTO placements " +
                "(id, treeRootInstanceId, parentInstanceId, slotId, childInstanceId, position, " +
                "payloadSchemaVersion, encodedPlacement) VALUES " +
                "('$PLACEMENT_ID', '$INSTANCE_ID', '$INSTANCE_ID', 'main', " +
                "'$CHILD_INSTANCE_ID', 0, 3, '$RAW_PLACEMENT')",
        )
        connection.execSQL(
            "INSERT INTO content_items " +
                "(id, kind, profileSerial, packageName, encodedSemanticData) VALUES " +
                "('$FOLDER_ID', 'FOLDER', NULL, NULL, 'folder opaque'), " +
                "('$CONTENT_ITEM_ID', 'FAVORITE', 10, 'org.example.app', 'favorite opaque')",
        )
        connection.execSQL(
            "INSERT INTO folder_members " +
                "(folderContentItemId, memberContentItemId, position) VALUES " +
                "('$FOLDER_ID', '$CONTENT_ITEM_ID', 0)",
        )
        connection.execSQL(
            "INSERT INTO app_overrides " +
                "(profileSerial, packageName, activityName, encodedOverride) VALUES " +
                "(10, 'org.example.app', 'org.example.app.First', '$FIRST_APP_OVERRIDE_PAYLOAD'), " +
                "(10, 'org.example.app', 'org.example.app.Second', '$SECOND_APP_OVERRIDE_PAYLOAD')",
        )
        connection.execSQL(
            "INSERT INTO widget_placements " +
                "(moduleInstanceId, profileSerial, providerPackageName, providerClassName, " +
                "appWidgetId, bindingState, encodedOptions) VALUES " +
                "('$CHILD_INSTANCE_ID', 10, 'org.example.widget', " +
                "'org.example.widget.Provider', 42, 'BOUND', '$WIDGET_OPTIONS')",
        )
        connection.execSQL(
            "INSERT INTO theme_profiles (id, name, schemaVersion, encodedTheme) VALUES " +
                "('$THEME_ID', 'Default', 4, 'theme opaque')",
        )
        connection.execSQL(
            "INSERT INTO destination_backgrounds " +
                "(destinationId, themeProfileId, schemaVersion, encodedBackground) VALUES " +
                "('$DESTINATION_ID', '$THEME_ID', 2, 'background opaque')",
        )
        connection.execSQL(
            "INSERT INTO crash_markers " +
                "(id, moduleInstanceId, failureKind, firstObservedAtEpochMillis, " +
                "lastObservedAtEpochMillis, occurrenceCount) VALUES " +
                "('$CRASH_ID', '$CHILD_INSTANCE_ID', '$CRASH_PAYLOAD', 1200, 1234, 2)",
        )
        connection.execSQL(
            "INSERT INTO store_metadata (singletonKey, startDestinationId, revision) VALUES " +
                "(1, '$DESTINATION_ID', 7)",
        )
    }

    private fun insertVersionThreeState(connection: SQLiteConnection) {
        connection.execSQL(
            "INSERT INTO content_items " +
                "(id, kind, profileSerial, packageName, encodedSemanticData) VALUES " +
                "('$SHORTCUT_CONTENT_ITEM_ID', 'SHORTCUT', 10, 'org.example.app', " +
                "'$SHORTCUT_CONTENT_PAYLOAD')",
        )
    }

    private fun insertVersionFourRecoveryState(
        connection: SQLiteConnection,
        includeDuplicateMarker: Boolean,
    ) {
        connection.execSQL(
            "INSERT INTO configuration_documents " +
                "(id, configType, schemaVersion, encoded, lastMigrationErrorCode) VALUES " +
                "('$CONFIGURATION_ID', '$CONFIG_TYPE', 1, '$RAW_CONFIGURATION', " +
                "'configuration.decode-failed')",
        )
        connection.execSQL(
            "INSERT INTO module_instances " +
                "(id, contributionId, configurationDocumentId, lifecycle, quarantineReasonCode, " +
                "quarantineReasonMessage, quarantineOrigin) VALUES " +
                "('$INSTANCE_ID', '$LAYOUT_ID', '$CONFIGURATION_ID', 'quarantined', " +
                "'renderer.crash', 'Renderer failed', 'RENDERER')",
        )
        insertCrashMarker(
            connection = connection,
            id = CRASH_ID,
            appVersion = "0.1",
            startupAttempt = 2,
            timestampEpochMillis = 1234,
            outcome = "renderer-failed",
            firstObservedAtEpochMillis = 1200,
        )
        if (includeDuplicateMarker) {
            insertCrashMarker(
                connection = connection,
                id = SECOND_CRASH_ID,
                appVersion = "0.2",
                startupAttempt = 3,
                timestampEpochMillis = 2234,
                outcome = "retry-failed",
                firstObservedAtEpochMillis = 2200,
            )
        }
    }

    private fun insertCrashMarker(
        connection: SQLiteConnection,
        id: String,
        appVersion: String,
        startupAttempt: Int,
        timestampEpochMillis: Long,
        outcome: String,
        firstObservedAtEpochMillis: Long,
    ) {
        connection.execSQL(
            "INSERT INTO crash_markers " +
                "(id, moduleInstanceId, appVersion, startupAttempt, timestampEpochMillis, " +
                "outcome, firstObservedAtEpochMillis) VALUES " +
                "('$id', '$INSTANCE_ID', '$appVersion', $startupAttempt, " +
                "$timestampEpochMillis, '$outcome', $firstObservedAtEpochMillis)",
        )
    }

    private inline fun <T : AutoCloseable, R> T.closing(block: (T) -> R): R = try {
        block(this)
    } finally {
        close()
    }

    private fun assertTextQuery(
        connection: SQLiteConnection,
        query: String,
        expected: String,
    ) {
        connection.prepare(query).closing { statement ->
            assertTrue("Expected one row for: $query", statement.step())
            assertEquals(expected, statement.getText(0))
        }
    }

    private fun assertLongQuery(
        connection: SQLiteConnection,
        query: String,
        expected: Long,
    ) {
        connection.prepare(query).closing { statement ->
            assertTrue("Expected one row for: $query", statement.step())
            assertEquals(expected, statement.getLong(0))
        }
    }

    private fun assertNullQuery(connection: SQLiteConnection, query: String) {
        connection.prepare(query).closing { statement ->
            assertTrue("Expected one row for: $query", statement.step())
            assertTrue(statement.isNull(0))
        }
    }

    private companion object {
        object InjectedReplacementFailure : RuntimeException("injected replacement failure")

        const val DATABASE_NAME = "phase-two-room-test.db"
        const val DESTINATION_ID = "org.quicklauncher.destination/start"
        const val INSTANCE_ID = "org.quicklauncher.instance/start-layout"
        const val CONFIGURATION_ID = "org.quicklauncher.configuration/start-layout"
        const val LAYOUT_ID = "org.quicklauncher.layout/grid"
        const val CONFIG_TYPE = "org.quicklauncher.config/grid"
        const val RAW_CONFIGURATION = " { unknown : [ 1, 2 ] } "
        const val CHILD_CONFIGURATION_PAYLOAD = " child opaque data "
        const val RAW_PLACEMENT = " { placement : opaque } "
        const val CHILD_INSTANCE_ID = "org.quicklauncher.instance/child"
        const val CHILD_CONFIGURATION_ID = "org.quicklauncher.configuration/child"
        const val CHILD_CONFIG_TYPE = "org.quicklauncher.config/child"
        const val BLOCK_ID = "org.quicklauncher.block/sample"
        const val PLACEMENT_ID = "org.quicklauncher.placement/child"
        const val FOLDER_ID = "org.quicklauncher.content/folder"
        const val CONTENT_ITEM_ID = "org.quicklauncher.content/item"
        const val SHORTCUT_CONTENT_ITEM_ID = "org.quicklauncher.content/shortcut"
        const val SHORTCUT_ID = "published/shortcut"
        const val FOLDER_PAYLOAD = " folder opaque data "
        const val CONTENT_ITEM_PAYLOAD = " favorite opaque data "
        const val SHORTCUT_CONTENT_PAYLOAD =
            "{\"shortcutId\":\"$SHORTCUT_ID\",\"unknown\":[1,2]}"
        const val UNRECOVERABLE_SHORTCUT_PAYLOAD = "{\"unknown\":[1,2]}"
        const val THEME_ID = "org.quicklauncher.theme/default"
        const val THEME_PAYLOAD = " theme opaque data "
        const val BACKGROUND_PAYLOAD = " background opaque data "
        const val CRASH_ID = "org.quicklauncher.crash/child"
        const val SECOND_CRASH_ID = "org.quicklauncher.crash/child-second"
        const val APP_OVERRIDE_PAYLOAD =
            "{\"activityName\":\"org.example.app.Main\",\"customLabel\":null," +
                "\"encodedIcon\":null,\"favorite\":true,\"collectionVisible\":false," +
                "\"searchVisible\":true}"
        const val FIRST_APP_OVERRIDE_PAYLOAD =
            "{\"activityName\":\"org.example.app.First\",\"customLabel\":null," +
                "\"encodedIcon\":null,\"favorite\":true,\"collectionVisible\":false," +
                "\"searchVisible\":true}"
        const val SECOND_APP_OVERRIDE_PAYLOAD =
            "{\"activityName\":\"org.example.app.Second\",\"customLabel\":\"Second\"," +
                "\"encodedIcon\":\"icon\",\"favorite\":false,\"collectionVisible\":true," +
                "\"searchVisible\":false}"
        const val WIDGET_OPTIONS =
            "{\"intendedWidthDp\":240,\"intendedHeightDp\":120," +
                "\"bindState\":\"BOUND\",\"restoreState\":\"READY\"}"
        const val CRASH_PAYLOAD =
            "{\"appVersion\":\"0.1\",\"outcome\":\"renderer-failed\"}"
    }
}
