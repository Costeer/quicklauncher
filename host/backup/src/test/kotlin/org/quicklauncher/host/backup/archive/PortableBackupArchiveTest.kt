package org.quicklauncher.host.backup.archive

import java.nio.ByteBuffer
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import org.quicklauncher.host.backup.hexFixture
import org.quicklauncher.host.backup.payload.LauncherPayloadDecodeResult
import org.quicklauncher.host.backup.payload.LauncherSnapshotPayloadCodec
import org.quicklauncher.host.backup.payload.SectionPayloadKind
import org.quicklauncher.host.backup.payload.SectionPayloadResult
import org.quicklauncher.host.backup.payload.VersionedSectionPayloadCodec

class PortableBackupArchiveTest {
    @Test
    fun `checked in version one archive decodes and re-encodes without losing opaque sections`() {
        val fixture = hexFixture("archive-v1.hex")
        val decoded = PortableBackupArchiveCodec.decode(fixture).decodedArchive()

        assertEquals(1_700_000_000_000L, decoded.createdAtEpochMillis)
        assertEquals(
            listOf("launcher-map", "web-adapters", "preview.future-opaque"),
            decoded.sections.map(PortableBackupSection::name),
        )
        assertTrue(
            LauncherSnapshotPayloadCodec.decode(decoded.sections[0].contentCopy()) is
                LauncherPayloadDecodeResult.Decoded,
        )
        assertTrue(
            VersionedSectionPayloadCodec.decode(
                decoded.sections[1].contentCopy(),
                SectionPayloadKind.WEB_ADAPTERS,
            ) is SectionPayloadResult.Decoded,
        )
        assertArrayEquals(byteArrayOf(0xde.toByte(), 0xad.toByte(), 0xbe.toByte(), 0xef.toByte()), decoded.sections[2].contentCopy())

        assertArrayEquals(fixture, PortableBackupArchiveCodec.encode(decoded).encodedBytes())
    }

    @Test
    fun `canonical archive round trips deterministically through the public seam`() {
        val input = archive(
            PortableBackupSection(PortableBackupSectionType.ASSET, "background-b", byteArrayOf(9, 8)),
            PortableBackupSection(PortableBackupSectionType.LAUNCHER_MAP, "launcher-map", byteArrayOf(1, 2, 3)),
            PortableBackupSection(PortableBackupSectionType.THEME_PROFILES, "themes", byteArrayOf(4, 5)),
            PortableBackupSection(PortableBackupSectionType.ASSET, "background-a", byteArrayOf(7, 6)),
        )

        val first = PortableBackupArchiveCodec.encode(input).encodedBytes()
        val second = PortableBackupArchiveCodec.encode(input).encodedBytes()
        assertArrayEquals(first, second)

        val decoded = PortableBackupArchiveCodec.decode(first).decodedArchive()
        assertEquals(1_700_000_000_000L, decoded.createdAtEpochMillis)
        assertEquals(PortableBackupKind.MANUAL, decoded.kind)
        assertEquals(
            listOf("launcher-map", "themes", "background-a", "background-b"),
            decoded.sections.map(PortableBackupSection::name),
        )
        assertArrayEquals(byteArrayOf(1, 2, 3), decoded.sections.first().contentCopy())
    }

    @Test
    fun `archive and encoded bytes do not retain caller-owned mutable arrays`() {
        val content = byteArrayOf(1, 2, 3)
        val section = PortableBackupSection(PortableBackupSectionType.LAUNCHER_MAP, "launcher-map", content)
        val callerSections = mutableListOf(section)
        val archive = PortableBackupArchive(1_700_000_000_000L, PortableBackupKind.MANUAL, callerSections)
        content[0] = 99
        callerSections.clear()
        try {
            @Suppress("UNCHECKED_CAST")
            val mutableView = archive.sections as MutableList<PortableBackupSection>
            mutableView.clear()
            fail("The public section snapshot must not be mutable")
        } catch (_: UnsupportedOperationException) {
            // Expected: callers cannot mutate the archive through a cast.
        }
        val encoded = PortableBackupArchiveCodec.encode(archive) as PortableBackupEncodeResult.Encoded
        val callerCopy = encoded.bytesCopy()
        callerCopy.fill(0)

        val decoded = PortableBackupArchiveCodec.decode(encoded.bytesCopy()).decodedArchive()
        assertArrayEquals(byteArrayOf(1, 2, 3), decoded.sections.single().contentCopy())
    }

    @Test
    fun `checksum corruption is rejected without exposing section identity`() {
        val bytes = PortableBackupArchiveCodec.encode(archive(launcherMap())).encodedBytes()
        bytes[bytes.lastIndex] = (bytes.last().toInt() xor 0x01).toByte()

        assertRejected(bytes, PortableBackupProblem.CHECKSUM_MISMATCH)
    }

    @Test
    fun `duplicate section keys are rejected before encoding`() {
        val result = PortableBackupArchiveCodec.encode(archive(launcherMap(), launcherMap()))

        assertEquals(
            PortableBackupProblem.DUPLICATE_SECTION,
            (result as PortableBackupEncodeResult.Rejected).problem,
        )
    }

    @Test
    fun `missing launcher map is rejected`() {
        val result = PortableBackupArchiveCodec.encode(
            archive(PortableBackupSection(PortableBackupSectionType.THEME_PROFILES, "themes", byteArrayOf())),
        )

        assertEquals(
            PortableBackupProblem.MISSING_LAUNCHER_MAP,
            (result as PortableBackupEncodeResult.Rejected).problem,
        )
    }

    @Test
    fun `oversized section length is rejected before allocation`() {
        val bytes = PortableBackupArchiveCodec.encode(archive(launcherMap())).encodedBytes()
        ByteBuffer.wrap(bytes).putInt(38, PortableBackupArchiveCodec.MAX_SECTION_BYTES + 1)

        assertRejected(bytes, PortableBackupProblem.OVERSIZED_SECTION)
    }

    @Test
    fun `unsupported version and feature flags are typed`() {
        val futureVersion = PortableBackupArchiveCodec.encode(archive(launcherMap())).encodedBytes()
        ByteBuffer.wrap(futureVersion).putShort(8, 2)
        assertRejected(futureVersion, PortableBackupProblem.UNSUPPORTED_VERSION)

        val encrypted = PortableBackupArchiveCodec.encode(archive(launcherMap())).encodedBytes()
        ByteBuffer.wrap(encrypted).putInt(12, 1)
        assertRejected(encrypted, PortableBackupProblem.UNSUPPORTED_FEATURES)
    }

    @Test
    fun `truncation and trailing bytes are rejected`() {
        val bytes = PortableBackupArchiveCodec.encode(archive(launcherMap())).encodedBytes()

        assertRejected(bytes.copyOf(bytes.size - 1), PortableBackupProblem.TRUNCATED)
        assertRejected(bytes + 0, PortableBackupProblem.TRAILING_BYTES)
    }

    @Test
    fun `section names cannot act as paths`() {
        val result = PortableBackupArchiveCodec.encode(
            archive(PortableBackupSection(PortableBackupSectionType.LAUNCHER_MAP, "../launcher-map", byteArrayOf())),
        )

        assertEquals(
            PortableBackupProblem.INVALID_SECTION_NAME,
            (result as PortableBackupEncodeResult.Rejected).problem,
        )
    }

    @Test
    fun `decoder rejects malformed names and non-canonical section order`() {
        val malformedName = PortableBackupArchiveCodec.encode(archive(launcherMap())).encodedBytes()
        malformedName[74] = 0xff.toByte()
        assertRejected(malformedName, PortableBackupProblem.INVALID_SECTION_NAME)

        val outOfOrder = PortableBackupArchiveCodec.encode(
            archive(
                launcherMap(),
                PortableBackupSection(PortableBackupSectionType.ASSET, "asset-a", byteArrayOf(4)),
            ),
        ).encodedBytes()
        val secondSectionOffset = 32 + 42 + "launcher-map".length + launcherMap().size
        outOfOrder[32] = PortableBackupSectionType.ASSET.wireValue.toByte()
        outOfOrder[secondSectionOffset] = PortableBackupSectionType.LAUNCHER_MAP.wireValue.toByte()
        assertRejected(outOfOrder, PortableBackupProblem.NON_CANONICAL_ORDER)
    }

    @Test
    fun `decoder rejects duplicate section keys`() {
        val bytes = PortableBackupArchiveCodec.encode(
            archive(
                launcherMap(),
                PortableBackupSection(PortableBackupSectionType.THEME_PROFILES, "launcher-map", byteArrayOf(4)),
            ),
        ).encodedBytes()
        val secondSectionOffset = 32 + 42 + "launcher-map".length + launcherMap().size
        bytes[secondSectionOffset] = PortableBackupSectionType.LAUNCHER_MAP.wireValue.toByte()

        assertRejected(bytes, PortableBackupProblem.DUPLICATE_SECTION)
    }

    @Test
    fun `decoded values have content equality`() {
        val first = PortableBackupArchiveCodec.decode(
            PortableBackupArchiveCodec.encode(archive(launcherMap())).encodedBytes(),
        ).decodedArchive()
        val second = PortableBackupArchiveCodec.decode(
            PortableBackupArchiveCodec.encode(archive(launcherMap())).encodedBytes(),
        ).decodedArchive()

        assertEquals(first, second)
        assertEquals(first.hashCode(), second.hashCode())
        assertNotEquals(first, archive(launcherMap(), kind = PortableBackupKind.AUTOMATIC))
    }

    private fun archive(
        vararg sections: PortableBackupSection,
        kind: PortableBackupKind = PortableBackupKind.MANUAL,
    ) = PortableBackupArchive(
        createdAtEpochMillis = 1_700_000_000_000L,
        kind = kind,
        sections = sections.toList(),
    )

    private fun launcherMap() =
        PortableBackupSection(PortableBackupSectionType.LAUNCHER_MAP, "launcher-map", byteArrayOf(1, 2, 3))

    private fun PortableBackupEncodeResult.encodedBytes(): ByteArray {
        assertTrue(this is PortableBackupEncodeResult.Encoded)
        return (this as PortableBackupEncodeResult.Encoded).bytesCopy()
    }

    private fun PortableBackupDecodeResult.decodedArchive(): PortableBackupArchive {
        assertTrue(this is PortableBackupDecodeResult.Decoded)
        return (this as PortableBackupDecodeResult.Decoded).archive
    }

    private fun assertRejected(
        bytes: ByteArray,
        expected: PortableBackupProblem,
    ) {
        val result = PortableBackupArchiveCodec.decode(bytes)
        assertTrue(result is PortableBackupDecodeResult.Rejected)
        assertEquals(expected, (result as PortableBackupDecodeResult.Rejected).problem)
    }
}
