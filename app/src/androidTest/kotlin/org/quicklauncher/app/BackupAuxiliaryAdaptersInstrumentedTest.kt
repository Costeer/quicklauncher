package org.quicklauncher.app

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.quicklauncher.host.backup.archive.PortableBackupSection
import org.quicklauncher.host.backup.archive.PortableBackupSectionType
import org.quicklauncher.host.backup.library.RestoreAuxiliaryStageRequest
import org.quicklauncher.host.backup.library.RestoreSelection
import org.quicklauncher.host.backup.payload.SectionPayloadEntry
import org.quicklauncher.host.backup.payload.SectionPayloadKind
import org.quicklauncher.host.backup.payload.SectionPayloadResult
import org.quicklauncher.host.backup.payload.VersionedSectionPayload
import org.quicklauncher.host.backup.payload.VersionedSectionPayloadCodec
import org.quicklauncher.host.data.store.InMemoryLauncherStore

@RunWith(AndroidJUnit4::class)
class BackupAuxiliaryAdaptersInstrumentedTest {
    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext

    @Before
    @After
    fun clearStoredAdapters() {
        context.getSharedPreferences("portable-backup-web-adapters", 0).edit().clear().commit()
    }

    @Test
    fun selectedWebAdaptersPersistRoundTripAndSelectedEmptyImportClears() = runBlocking {
        val payload = (
            VersionedSectionPayloadCodec.encode(
                VersionedSectionPayload(
                    SectionPayloadKind.WEB_ADAPTERS,
                    listOf(SectionPayloadEntry("fixture", byteArrayOf(1, 2, 3))),
                ),
            ) as SectionPayloadResult.Decoded
            ).value
        val section = PortableBackupSection(
            PortableBackupSectionType.WEB_ADAPTERS,
            "adapters",
            payload,
        )
        val webOnly = RestoreSelection(
            launcherMap = false,
            themes = false,
            webAdapters = true,
            assets = false,
        )
        val snapshot = InMemoryLauncherStore().read()
        val first = PersistedWebAdapterBackupPort(context)
        first.stage(RestoreAuxiliaryStageRequest(snapshot, listOf(section), webOnly, emptyMap())).commit()

        val recreated = PersistedWebAdapterBackupPort(context)
        val exported = recreated.export(InMemoryLauncherStore().read())
        assertEquals(1, exported.size)
        assertArrayEquals(payload, exported.single().contentCopy())

        recreated.stage(RestoreAuxiliaryStageRequest(snapshot, emptyList(), webOnly, emptyMap())).commit()
        assertEquals(emptyList<PortableBackupSection>(), recreated.export(InMemoryLauncherStore().read()))
    }

    @Test
    fun unselectedWebAdaptersRemainUntouchedAndDiscardRollsBackCommit() = runBlocking {
        val payload = (
            VersionedSectionPayloadCodec.encode(
                VersionedSectionPayload(
                    SectionPayloadKind.WEB_ADAPTERS,
                    listOf(SectionPayloadEntry("fixture", byteArrayOf(7))),
                ),
            ) as SectionPayloadResult.Decoded
            ).value
        val section = PortableBackupSection(
            PortableBackupSectionType.WEB_ADAPTERS,
            "adapters",
            payload,
        )
        val port = PersistedWebAdapterBackupPort(context)
        val webOnly = RestoreSelection(false, false, true, false)
        val snapshot = InMemoryLauncherStore().read()
        port.stage(RestoreAuxiliaryStageRequest(snapshot, listOf(section), webOnly, emptyMap())).commit()

        port.stage(
            RestoreAuxiliaryStageRequest(
                snapshot,
                emptyList(),
                RestoreSelection(true, false, false, false),
                emptyMap(),
            ),
        ).commit()
        assertArrayEquals(payload, port.export(InMemoryLauncherStore().read()).single().contentCopy())

        val clearing = port.stage(RestoreAuxiliaryStageRequest(snapshot, emptyList(), webOnly, emptyMap()))
        clearing.commit()
        assertEquals(emptyList<PortableBackupSection>(), port.export(InMemoryLauncherStore().read()))
        clearing.discard()
        assertArrayEquals(payload, port.export(InMemoryLauncherStore().read()).single().contentCopy())
    }

    @Test
    fun opaqueLauncherExtensionsSurviveRecreationAndRollback() = runBlocking {
        val mapOnly = RestoreSelection(true, false, false, false)
        val port = PersistedWebAdapterBackupPort(context)
        val snapshot = InMemoryLauncherStore().read()
        port.stage(
            RestoreAuxiliaryStageRequest(
                snapshot,
                emptyList(),
                mapOnly,
                mapOf("future.record" to byteArrayOf(4, 5, 6)),
            ),
        ).commit()

        val recreated = PersistedWebAdapterBackupPort(context)
        assertArrayEquals(byteArrayOf(4, 5, 6), recreated.export().getValue("future.record"))

        val clearing = recreated.stage(
            RestoreAuxiliaryStageRequest(snapshot, emptyList(), mapOnly, emptyMap()),
        )
        clearing.commit()
        assertEquals(emptyMap<String, ByteArray>(), recreated.export())
        clearing.discard()
        assertArrayEquals(byteArrayOf(4, 5, 6), recreated.export().getValue("future.record"))
    }
}
