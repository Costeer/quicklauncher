package org.quicklauncher.host.data.room

import androidx.room3.migration.Migration
import androidx.sqlite.SQLiteConnection
import androidx.sqlite.execSQL

/**
 * Makes activity identity structural without discarding the v1/v2 JSON payload and records why an
 * instance was quarantined. Android 15's SQLite includes JSON scalar extraction.
 */
internal object LauncherMigration2To3 : Migration(2, 3) {
    override suspend fun migrate(connection: SQLiteConnection) {
        connection.execSQL(
            "ALTER TABLE module_instances ADD COLUMN quarantineOrigin " +
                "TEXT NOT NULL DEFAULT 'OTHER'",
        )
        connection.execSQL(
            """
            UPDATE module_instances
            SET
                quarantineReasonCode = COALESCE(
                    NULLIF(quarantineReasonCode, ''),
                    'legacy.quarantine'
                ),
                quarantineReasonMessage = COALESCE(
                    NULLIF(quarantineReasonMessage, ''),
                    NULLIF(quarantineReasonCode, ''),
                    'Legacy quarantine state'
                )
            WHERE lifecycle = 'quarantined'
            """.trimIndent(),
        )
        connection.execSQL(
            """
            CREATE TABLE IF NOT EXISTS app_overrides_v3 (
                profileSerial INTEGER NOT NULL,
                packageName TEXT NOT NULL,
                activityName TEXT NOT NULL,
                encodedOverride TEXT NOT NULL,
                PRIMARY KEY(profileSerial, packageName, activityName)
            )
            """.trimIndent(),
        )
        connection.execSQL(
            """
            INSERT INTO app_overrides_v3 (
                profileSerial,
                packageName,
                activityName,
                encodedOverride
            )
            SELECT
                profileSerial,
                packageName,
                json_extract(encodedOverride, '$.activityName'),
                encodedOverride
            FROM app_overrides
            """.trimIndent(),
        )
        connection.execSQL("DROP TABLE app_overrides")
        connection.execSQL("ALTER TABLE app_overrides_v3 RENAME TO app_overrides")
    }
}

/**
 * Makes shortcut and crash-marker identities structural. Pre-public shortcut rows stored their
 * shortcut ID at `$.shortcutId`; migration rejects a row when that identity cannot be recovered.
 */
internal object LauncherMigration3To4 : Migration(3, 4) {
    override suspend fun migrate(connection: SQLiteConnection) {
        connection.execSQL("ALTER TABLE content_items ADD COLUMN shortcutId TEXT DEFAULT NULL")
        connection.execSQL(
            """
            UPDATE content_items
            SET shortcutId = json_extract(encodedSemanticData, '$.shortcutId')
            WHERE kind = 'SHORTCUT'
            """.trimIndent(),
        )
        connection.execSQL(
            """
            CREATE TEMP TABLE validate_shortcut_migration (
                invalid INTEGER NOT NULL CHECK(invalid = 0)
            )
            """.trimIndent(),
        )
        connection.execSQL(
            """
            INSERT INTO validate_shortcut_migration(invalid)
            SELECT 1
            FROM content_items
            WHERE kind = 'SHORTCUT'
                AND (
                    profileSerial IS NULL OR
                    packageName IS NULL OR
                    length(trim(packageName)) = 0 OR
                    shortcutId IS NULL OR
                    length(trim(shortcutId)) = 0
                )
            """.trimIndent(),
        )
        connection.execSQL("DROP TABLE validate_shortcut_migration")
        connection.execSQL(
            """
            CREATE TABLE IF NOT EXISTS crash_markers_v4 (
                id TEXT NOT NULL,
                moduleInstanceId TEXT NOT NULL,
                appVersion TEXT NOT NULL,
                startupAttempt INTEGER NOT NULL,
                timestampEpochMillis INTEGER NOT NULL,
                outcome TEXT,
                firstObservedAtEpochMillis INTEGER NOT NULL,
                PRIMARY KEY(id),
                FOREIGN KEY(moduleInstanceId) REFERENCES module_instances(id)
                    ON UPDATE NO ACTION ON DELETE CASCADE
            )
            """.trimIndent(),
        )
        connection.execSQL(
            """
            INSERT INTO crash_markers_v4 (
                id,
                moduleInstanceId,
                appVersion,
                startupAttempt,
                timestampEpochMillis,
                outcome,
                firstObservedAtEpochMillis
            )
            SELECT
                id,
                moduleInstanceId,
                json_extract(failureKind, '$.appVersion'),
                occurrenceCount,
                lastObservedAtEpochMillis,
                json_extract(failureKind, '$.outcome'),
                firstObservedAtEpochMillis
            FROM crash_markers
            """.trimIndent(),
        )
        connection.execSQL("DROP TABLE crash_markers")
        connection.execSQL("ALTER TABLE crash_markers_v4 RENAME TO crash_markers")
        connection.execSQL(
            "CREATE INDEX IF NOT EXISTS index_crash_markers_moduleInstanceId " +
                "ON crash_markers(moduleInstanceId)",
        )
    }
}

/**
 * Makes the one-marker-per-instance recovery invariant structural. Legacy duplicates abort the
 * migration so Room rolls the transaction back without choosing or deleting durable evidence.
 */
internal object LauncherMigration4To5 : Migration(4, 5) {
    override suspend fun migrate(connection: SQLiteConnection) {
        connection.execSQL(
            """
            CREATE TEMP TABLE validate_crash_marker_uniqueness (
                invalid INTEGER NOT NULL CHECK(invalid = 0)
            )
            """.trimIndent(),
        )
        connection.execSQL(
            """
            INSERT INTO validate_crash_marker_uniqueness(invalid)
            SELECT 1
            FROM crash_markers
            GROUP BY moduleInstanceId
            HAVING COUNT(*) > 1
            """.trimIndent(),
        )
        connection.execSQL("DROP TABLE validate_crash_marker_uniqueness")
        connection.execSQL("DROP INDEX index_crash_markers_moduleInstanceId")
        connection.execSQL(
            "CREATE UNIQUE INDEX index_crash_markers_moduleInstanceId " +
                "ON crash_markers(moduleInstanceId)",
        )
    }
}

/** Records framework cleanup before deleting the durable widget placement. */
internal object LauncherMigration5To6 : Migration(5, 6) {
    override suspend fun migrate(connection: SQLiteConnection) {
        connection.execSQL(
            "ALTER TABLE widget_placements ADD COLUMN cleanupState " +
                "TEXT NOT NULL DEFAULT 'NONE'",
        )
    }
}
