package org.quicklauncher.host.backup.payload

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.quicklauncher.host.backup.canonicalJsonFixture
import org.quicklauncher.host.backup.fixtureBytes
import org.quicklauncher.contracts.contribution.ConfigurationDocument
import org.quicklauncher.contracts.contribution.EncodedConfiguration
import org.quicklauncher.contracts.domain.ConfigTypeId
import org.quicklauncher.contracts.domain.ConfigurationDocumentId
import org.quicklauncher.contracts.domain.ContributionId
import org.quicklauncher.contracts.domain.DestinationId
import org.quicklauncher.contracts.domain.ModuleInstanceId
import org.quicklauncher.contracts.domain.SchemaVersion
import org.quicklauncher.host.data.spatial.DestinationCoordinate
import org.quicklauncher.host.data.store.DestinationLayoutRecord
import org.quicklauncher.host.data.store.DestinationRecord
import org.quicklauncher.host.data.store.LauncherSnapshot
import org.quicklauncher.host.data.store.ModuleInstanceRecord
import org.quicklauncher.host.data.store.StoredConfigurationDocument

class LauncherSnapshotPayloadTest {
    @Test
    fun `checked in version one payload decodes and current encoder remains byte stable`() {
        val fixture = fixtureBytes("launcher-payload-v1.json")
        val decoded = LauncherSnapshotPayloadCodec.decode(fixture) as LauncherPayloadDecodeResult.Decoded

        assertEquals(DestinationId.parse("org.quicklauncher.test/home"), decoded.snapshot.startDestinationId)
        assertEquals("Home", decoded.snapshot.destinations.single().name)
        assertArrayEquals(byteArrayOf(9, 8, 7), decoded.extensionCopy("future.owner"))

        val current = LauncherSnapshotPayloadCodec.encode(
            decoded.snapshot,
            decoded.extensionNames.associateWith { requireNotNull(decoded.extensionCopy(it)) },
        ).bytes()
        assertArrayEquals(canonicalJsonFixture("launcher-payload-v1.json"), current)
    }

    @Test
    fun `version one payload is deterministic and preserves opaque extensions`() {
        val snapshot = snapshot("Home")
        val extension = byteArrayOf(9, 8, 7)

        val first = LauncherSnapshotPayloadCodec.encode(snapshot, mapOf("future.owner" to extension)).bytes()
        extension.fill(0)
        val second = LauncherSnapshotPayloadCodec.encode(
            snapshot,
            mapOf("future.owner" to byteArrayOf(9, 8, 7)),
        ).bytes()
        assertArrayEquals(first, second)

        val decoded = LauncherSnapshotPayloadCodec.decode(first) as LauncherPayloadDecodeResult.Decoded
        assertEquals(snapshot.startDestinationId, decoded.snapshot.startDestinationId)
        assertEquals(snapshot.destinations, decoded.snapshot.destinations)
        assertArrayEquals(byteArrayOf(9, 8, 7), decoded.extensionCopy("future.owner"))
    }

    @Test
    fun `malformed and future payloads return typed failures`() {
        assertEquals(
            LauncherPayloadProblem.MALFORMED,
            (LauncherSnapshotPayloadCodec.decode("{".toByteArray()) as LauncherPayloadDecodeResult.Rejected).problem,
        )
        val future = LauncherSnapshotPayloadCodec.encode(snapshot("Home")).bytes()
            .toString(Charsets.UTF_8)
            .replace("\"schemaVersion\":1", "\"schemaVersion\":2")
            .toByteArray()
        assertEquals(
            LauncherPayloadProblem.UNSUPPORTED_VERSION,
            (LauncherSnapshotPayloadCodec.decode(future) as LauncherPayloadDecodeResult.Rejected).problem,
        )

        val unknownField = canonicalJsonFixture("launcher-payload-v1.json")
            .toString(Charsets.UTF_8)
            .replaceFirst("{", "{\"unpublished\":true,")
            .toByteArray()
        assertEquals(
            LauncherPayloadProblem.MALFORMED,
            (LauncherSnapshotPayloadCodec.decode(unknownField) as LauncherPayloadDecodeResult.Rejected).problem,
        )
    }

    private fun LauncherPayloadEncodeResult.bytes(): ByteArray {
        assertTrue(this is LauncherPayloadEncodeResult.Encoded)
        return (this as LauncherPayloadEncodeResult.Encoded).bytesCopy()
    }
}

internal fun snapshot(name: String): LauncherSnapshot {
    val destinationId = DestinationId.parse("org.quicklauncher.test/home")
    val instanceId = ModuleInstanceId.parse("org.quicklauncher.test/safe-root")
    val configurationId = ConfigurationDocumentId.parse("org.quicklauncher.test/safe-config")
    val contributionId = ContributionId.parse("org.quicklauncher.core/safe-layout")
    return LauncherSnapshot.restored(
        startDestinationId = destinationId,
        destinations = listOf(DestinationRecord(destinationId, name, DestinationCoordinate(0, 0))),
        destinationLayouts = listOf(
            DestinationLayoutRecord(destinationId, contributionId, instanceId, selected = true),
        ),
        moduleInstances = listOf(ModuleInstanceRecord(instanceId, contributionId, configurationId)),
        configurationDocuments = listOf(
            StoredConfigurationDocument(
                configurationId,
                ConfigurationDocument(
                    ConfigTypeId.parse("org.quicklauncher.core/safe-layout"),
                    SchemaVersion.of(1),
                    EncodedConfiguration.of("{}"),
                ),
            ),
        ),
        placements = emptyList(),
    )
}
