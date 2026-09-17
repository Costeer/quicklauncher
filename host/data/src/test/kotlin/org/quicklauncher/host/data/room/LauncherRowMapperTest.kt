package org.quicklauncher.host.data.room

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test
import org.quicklauncher.contracts.contribution.ConfigurationDocument
import org.quicklauncher.contracts.contribution.EncodedConfiguration
import org.quicklauncher.contracts.domain.ConfigTypeId
import org.quicklauncher.contracts.domain.ConfigurationDocumentId
import org.quicklauncher.contracts.domain.ContentItemId
import org.quicklauncher.contracts.domain.ContributionId
import org.quicklauncher.contracts.domain.CrashMarkerId
import org.quicklauncher.contracts.domain.DestinationId
import org.quicklauncher.contracts.domain.ModuleInstanceId
import org.quicklauncher.contracts.domain.PackageName
import org.quicklauncher.contracts.domain.PlacementId
import org.quicklauncher.contracts.domain.ProfileSerial
import org.quicklauncher.contracts.domain.SchemaVersion
import org.quicklauncher.contracts.domain.ShortcutId
import org.quicklauncher.contracts.domain.StableKey
import org.quicklauncher.contracts.domain.ThemeProfileId
import org.quicklauncher.host.data.spatial.DestinationCoordinate
import org.quicklauncher.host.data.store.AppOverrideRecord
import org.quicklauncher.host.data.store.ContentItemKind
import org.quicklauncher.host.data.store.ContentItemRecord
import org.quicklauncher.host.data.store.CrashMarkerRecord
import org.quicklauncher.host.data.store.DestinationBackgroundRecord
import org.quicklauncher.host.data.store.DestinationLayoutRecord
import org.quicklauncher.host.data.store.DestinationRecord
import org.quicklauncher.host.data.store.EncodedPlacementData
import org.quicklauncher.host.data.store.FolderMemberRecord
import org.quicklauncher.host.data.store.LauncherSnapshot
import org.quicklauncher.host.data.store.ModuleInstanceRecord
import org.quicklauncher.host.data.store.ModuleInstanceStatus
import org.quicklauncher.host.data.store.ModuleQuarantineOrigin
import org.quicklauncher.host.data.store.PlacementRecord
import org.quicklauncher.host.data.store.StoreRevision
import org.quicklauncher.host.data.store.StoredConfigurationDocument
import org.quicklauncher.host.data.store.ShortcutTarget
import org.quicklauncher.host.data.store.ThemeProfileRecord
import org.quicklauncher.host.data.store.WidgetBindState
import org.quicklauncher.host.data.store.WidgetPlacementRecord
import org.quicklauncher.host.data.store.WidgetRestoreState

class LauncherRowMapperTest {
    @Test
    fun `all initial records and opaque payloads round trip through Room rows`() {
        val rootId = instanceId("root")
        val childId = instanceId("child")
        val themeId = ThemeProfileId.parse("org.quicklauncher.theme/default")
        val snapshot = LauncherSnapshot(
            revision = StoreRevision.of(7),
            startDestinationId = destinationId,
            destinations = listOf(
                DestinationRecord(destinationId, "Start", DestinationCoordinate(-3, 8)),
            ),
            destinationLayouts = listOf(
                DestinationLayoutRecord(destinationId, layoutId, rootId, selected = true),
            ),
            moduleInstances = listOf(
                ModuleInstanceRecord(rootId, layoutId, configurationId("root")),
                ModuleInstanceRecord(
                    childId,
                    blockId,
                    configurationId("child"),
                    ModuleInstanceStatus.Quarantined(
                        "renderer.crash",
                        "Original data retained",
                        ModuleQuarantineOrigin.RENDERER,
                    ),
                ),
            ),
            configurationDocuments = listOf(
                storedConfiguration(
                    "root",
                    " { \"unknown\" : [ 1, 2 ] } \n",
                    lastMigrationErrorCode = "configuration.migration-failed",
                ),
                storedConfiguration("child", "not-json\u0000still-preserved"),
            ),
            placements = listOf(
                PlacementRecord(
                    id = PlacementId.parse("org.quicklauncher.placement/child"),
                    layoutInstanceId = rootId,
                    parentInstanceId = rootId,
                    parentSlotId = StableKey.parse("main"),
                    childInstanceId = childId,
                    index = 0,
                    data = EncodedPlacementData.of(" { placement : opaque } "),
                    placementSchemaVersion = 3,
                ),
            ),
            contentItems = listOf(
                ContentItemRecord(folderId, ContentItemKind.FOLDER, "folder-raw"),
                ContentItemRecord(
                    itemId,
                    ContentItemKind.FAVORITE,
                    "item-raw",
                    profile = ProfileSerial.of(10),
                    packageName = PackageName.parse("org.example.app"),
                ),
            ),
            folderMembers = listOf(FolderMemberRecord(folderId, itemId, 0)),
            appOverrides = listOf(
                AppOverrideRecord(
                    profile = ProfileSerial.of(10),
                    packageName = PackageName.parse("org.example.app"),
                    activityName = "org.example.app.Main",
                    customLabel = "Example",
                    encodedIcon = "icon-bytes",
                    favorite = true,
                    collectionVisible = false,
                    searchVisible = true,
                ),
            ),
            widgetPlacements = listOf(
                WidgetPlacementRecord(
                    moduleInstanceId = childId,
                    appWidgetId = 42,
                    providerPackage = PackageName.parse("org.example.widget"),
                    providerClassName = "org.example.widget.Provider",
                    profile = ProfileSerial.of(10),
                    intendedWidthDp = 240,
                    intendedHeightDp = 120,
                    bindState = WidgetBindState.BOUND,
                    restoreState = WidgetRestoreState.READY,
                ),
            ),
            themeProfiles = listOf(
                ThemeProfileRecord(themeId, "Default", "theme-raw", schemaVersion = 4),
            ),
            destinationBackgrounds = listOf(
                DestinationBackgroundRecord(destinationId, themeId, "background-raw", 2),
            ),
            crashMarkers = listOf(
                CrashMarkerRecord(
                    id = CrashMarkerId.parse("org.quicklauncher.crash/child"),
                    moduleInstanceId = childId,
                    appVersion = "0.1",
                    startupAttempt = 2,
                    timestampEpochMillis = 1234,
                    outcome = StableKey.parse("renderer-failed"),
                    firstObservedAtEpochMillis = 1200,
                ),
            ),
        )

        val restored = snapshot.toLauncherRows().toLauncherSnapshot()
        val storedCrash = snapshot.toLauncherRows().crashMarkers.single()

        assertEquals(snapshot, restored)
        assertEquals("0.1", storedCrash.appVersion)
        assertEquals(2, storedCrash.startupAttempt)
        assertEquals(1234L, storedCrash.timestampEpochMillis)
        assertEquals("renderer-failed", storedCrash.outcome)
        assertEquals(
            " { \"unknown\" : [ 1, 2 ] } \n",
            restored.configurationDocuments.single { it.id == configurationId("root") }.document.encoded.value,
        )
        assertEquals(
            "not-json\u0000still-preserved",
            restored.configurationDocuments.single { it.id == configurationId("child") }.document.encoded.value,
        )
    }

    @Test
    fun `two activities from one package retain distinct structured identities`() {
        val first = appOverride("org.example.app.First")
        val second = appOverride("org.example.app.Second")
        val rootId = instanceId("activity-root")
        val configurationId = configurationId("activity-root")
        val snapshot = LauncherSnapshot(
            revision = StoreRevision.ZERO,
            startDestinationId = destinationId,
            destinations = listOf(
                DestinationRecord(destinationId, "Start", DestinationCoordinate(0, 0)),
            ),
            destinationLayouts = listOf(
                DestinationLayoutRecord(destinationId, layoutId, rootId, selected = true),
            ),
            moduleInstances = listOf(
                ModuleInstanceRecord(rootId, layoutId, configurationId),
            ),
            configurationDocuments = listOf(
                StoredConfigurationDocument(
                    configurationId,
                    ConfigurationDocument(
                        ConfigTypeId.parse("org.quicklauncher.config/activity-root"),
                        SchemaVersion.of(1),
                        EncodedConfiguration.of("{}"),
                    ),
                ),
            ),
            placements = emptyList(),
            appOverrides = listOf(second, first),
        )

        val restored = snapshot.toLauncherRows().toLauncherSnapshot()

        assertEquals(listOf(first, second), restored.appOverrides)
        assertEquals(
            listOf("org.example.app.First", "org.example.app.Second"),
            snapshot.toLauncherRows().appOverrides.map(AppOverrideEntity::activityName).sorted(),
        )
    }

    @Test
    fun `shortcut identity round trips through structural Room columns`() {
        val shortcut = ContentItemRecord.shortcut(
            id = itemId,
            encoded = "shortcut opaque",
            target = ShortcutTarget(
                profile = ProfileSerial.of(10),
                packageName = PackageName.parse("org.example.app"),
                shortcutId = ShortcutId.parse("shortcut/one"),
            ),
        )
        val rows = rowsWithContent(shortcut).toLauncherRows()

        assertEquals("shortcut/one", rows.contentItems.single().shortcutId)
        assertEquals(shortcut, rows.toLauncherSnapshot().contentItems.single())
    }

    @Test
    fun `stored shortcut rejects each missing structural identity field`() {
        val complete = ContentItemEntity(
            id = itemId.value,
            kind = ContentItemKind.SHORTCUT.name,
            profileSerial = 10,
            packageName = "org.example.app",
            shortcutId = "shortcut/one",
            encodedSemanticData = "shortcut opaque",
        )

        listOf(
            complete.copy(profileSerial = null),
            complete.copy(packageName = null),
            complete.copy(shortcutId = null),
        ).forEach { invalid ->
            assertThrows(IllegalArgumentException::class.java) {
                rowsWithContentEntity(invalid).toLauncherSnapshot()
            }
        }
    }

    @Test
    fun `stored non-shortcut rejects a shortcut ID`() {
        val invalid = ContentItemEntity(
            id = itemId.value,
            kind = ContentItemKind.FAVORITE.name,
            profileSerial = 10,
            packageName = "org.example.app",
            shortcutId = "shortcut/one",
            encodedSemanticData = "favorite opaque",
        )

        assertThrows(IllegalArgumentException::class.java) {
            rowsWithContentEntity(invalid).toLauncherSnapshot()
        }
    }

    @Test
    fun `empty destination table rejects hidden durable rows`() {
        val rows = LauncherRows(
            destinations = emptyList(),
            destinationLayouts = emptyList(),
            moduleInstances = emptyList(),
            configurationDocuments = listOf(
                ConfigurationDocumentEntity(
                    id = "org.quicklauncher.configuration/orphan",
                    configType = "org.quicklauncher.config/orphan",
                    schemaVersion = 1,
                    encoded = "opaque orphan data",
                    lastMigrationErrorCode = "configuration.decode-failed",
                ),
            ),
            placements = emptyList(),
            contentItems = emptyList(),
            folderMembers = emptyList(),
            appOverrides = emptyList(),
            widgetPlacements = emptyList(),
            themeProfiles = emptyList(),
            destinationBackgrounds = emptyList(),
            crashMarkers = emptyList(),
            metadata = null,
        )

        assertThrows(IllegalStateException::class.java, rows::toLauncherSnapshot)
    }

    @Test
    fun `empty store round trip retains the bootstrap state`() {
        assertEquals(
            LauncherSnapshot.empty(),
            LauncherSnapshot.empty().toLauncherRows().toLauncherSnapshot(),
        )
    }

    @Test
    fun `malformed theme rows are isolated without dropping unrelated durable rows`() {
        val content = ContentItemEntity(
            id = "org.quicklauncher.content/synthetic",
            kind = ContentItemKind.FAVORITE.name,
            profileSerial = 10,
            packageName = "org.example.app",
            shortcutId = null,
            encodedSemanticData = "opaque synthetic data",
        )
        val validThemeId = "org.quicklauncher.theme/valid"
        val rows = rowsWithContentEntity(content).copy(
            themeProfiles = listOf(
                ThemeProfileEntity(validThemeId, "Valid", 1, "{}"),
                ThemeProfileEntity("invalid id", "", 0, "malformed"),
            ),
            destinationBackgrounds = listOf(
                DestinationBackgroundEntity(destinationId.value, validThemeId, 1, "{}"),
                DestinationBackgroundEntity("invalid id", validThemeId, 0, "malformed"),
                DestinationBackgroundEntity(
                    "org.quicklauncher.destination/missing",
                    "org.quicklauncher.theme/missing",
                    1,
                    "{}",
                ),
            ),
        )

        val restored = rows.toLauncherSnapshot()

        assertEquals(listOf("Valid"), restored.themeProfiles.map { it.name })
        assertEquals(1, restored.destinationBackgrounds.size)
        assertEquals("opaque synthetic data", restored.contentItems.single().encoded)
    }

    private fun storedConfiguration(
        local: String,
        encoded: String,
        lastMigrationErrorCode: String? = null,
    ): StoredConfigurationDocument =
        StoredConfigurationDocument(
            id = configurationId(local),
            document = ConfigurationDocument(
                configType = ConfigTypeId.parse("org.quicklauncher.config/$local"),
                schemaVersion = SchemaVersion.of(1),
                encoded = EncodedConfiguration.of(encoded),
            ),
            lastMigrationErrorCode = lastMigrationErrorCode,
        )

    private fun appOverride(activityName: String): AppOverrideRecord = AppOverrideRecord(
        profile = ProfileSerial.of(10),
        packageName = PackageName.parse("org.example.app"),
        activityName = activityName,
        customLabel = null,
        encodedIcon = null,
        favorite = false,
        collectionVisible = true,
        searchVisible = true,
    )

    private fun instanceId(local: String): ModuleInstanceId =
        ModuleInstanceId.parse("org.quicklauncher.instance/$local")

    private fun configurationId(local: String): ConfigurationDocumentId =
        ConfigurationDocumentId.parse("org.quicklauncher.configuration/$local")

    private fun rowsWithContent(contentItem: ContentItemRecord): LauncherSnapshot = LauncherSnapshot(
        revision = StoreRevision.of(1),
        startDestinationId = destinationId,
        destinations = listOf(
            DestinationRecord(destinationId, "Start", DestinationCoordinate(0, 0)),
        ),
        destinationLayouts = emptyList(),
        moduleInstances = emptyList(),
        configurationDocuments = emptyList(),
        placements = emptyList(),
        contentItems = listOf(contentItem),
    )

    private fun rowsWithContentEntity(contentItem: ContentItemEntity): LauncherRows = LauncherRows(
        destinations = listOf(DestinationEntity(destinationId.value, "Start", 0, 0)),
        destinationLayouts = emptyList(),
        moduleInstances = emptyList(),
        configurationDocuments = emptyList(),
        placements = emptyList(),
        contentItems = listOf(contentItem),
        folderMembers = emptyList(),
        appOverrides = emptyList(),
        widgetPlacements = emptyList(),
        themeProfiles = emptyList(),
        destinationBackgrounds = emptyList(),
        crashMarkers = emptyList(),
        metadata = StoreMetadataEntity(startDestinationId = destinationId.value, revision = 1),
    )

    private companion object {
        val destinationId = DestinationId.parse("org.quicklauncher.destination/start")
        val layoutId = ContributionId.parse("org.quicklauncher.layout/grid")
        val blockId = ContributionId.parse("org.quicklauncher.block/sample")
        val folderId = ContentItemId.parse("org.quicklauncher.content/folder")
        val itemId = ContentItemId.parse("org.quicklauncher.content/item")
    }
}
