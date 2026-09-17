package org.quicklauncher.host.backup.payload

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.quicklauncher.host.backup.canonicalJsonFixture
import org.quicklauncher.host.backup.fixtureBytes

class VersionedSectionPayloadTest {
    @Test
    fun `checked in theme and asset manifest fixtures remain byte stable`() {
        listOf(
            "theme-profiles-payload-v1.json" to SectionPayloadKind.THEME_PROFILES,
            "asset-manifest-payload-v1.json" to SectionPayloadKind.ASSET_MANIFEST,
        ).forEach { (name, kind) ->
            val fixture = fixtureBytes(name)
            val decoded = VersionedSectionPayloadCodec.decode(fixture, kind) as SectionPayloadResult.Decoded
            val current = VersionedSectionPayloadCodec.encode(decoded.value) as SectionPayloadResult.Decoded

            assertArrayEquals(canonicalJsonFixture(name), current.value)
        }
    }

    @Test
    fun `checked in version one optional payload preserves opaque entries byte for byte`() {
        val fixture = fixtureBytes("web-adapters-payload-v1.json")
        val decoded = VersionedSectionPayloadCodec.decode(
            fixture,
            SectionPayloadKind.WEB_ADAPTERS,
        ) as SectionPayloadResult.Decoded

        assertEquals(listOf("alpha", "zeta"), decoded.value.entries.map { it.name })
        assertArrayEquals(byteArrayOf(1), decoded.value.entries[0].contentCopy())
        assertArrayEquals(byteArrayOf(2), decoded.value.entries[1].contentCopy())

        val current = VersionedSectionPayloadCodec.encode(decoded.value) as SectionPayloadResult.Decoded
        assertArrayEquals(canonicalJsonFixture("web-adapters-payload-v1.json"), current.value)
    }

    @Test
    fun `typed optional payload sorts and round trips opaque entries`() {
        val encoded = VersionedSectionPayloadCodec.encode(
            VersionedSectionPayload(
                SectionPayloadKind.WEB_ADAPTERS,
                listOf(
                    SectionPayloadEntry("zeta", byteArrayOf(2)),
                    SectionPayloadEntry("alpha", byteArrayOf(1)),
                ),
            ),
        ) as SectionPayloadResult.Decoded

        val decoded = VersionedSectionPayloadCodec.decode(
            encoded.value,
            SectionPayloadKind.WEB_ADAPTERS,
        ) as SectionPayloadResult.Decoded

        assertEquals(listOf("alpha", "zeta"), decoded.value.entries.map { it.name })
        assertArrayEquals(byteArrayOf(1), decoded.value.entries.first().contentCopy())
    }

    @Test
    fun `kind mismatch and future schema fail closed`() {
        val encoded = (VersionedSectionPayloadCodec.encode(
            VersionedSectionPayload(SectionPayloadKind.THEME_PROFILES, emptyList()),
        ) as SectionPayloadResult.Decoded).value
        assertTrue(
            VersionedSectionPayloadCodec.decode(encoded, SectionPayloadKind.WEB_ADAPTERS) is
                SectionPayloadResult.Rejected,
        )
        val future = encoded.toString(Charsets.UTF_8)
            .replace("\"schemaVersion\":1", "\"schemaVersion\":2")
            .toByteArray()
        assertEquals(
            LauncherPayloadProblem.UNSUPPORTED_VERSION,
            (VersionedSectionPayloadCodec.decode(
                future,
                SectionPayloadKind.THEME_PROFILES,
            ) as SectionPayloadResult.Rejected).problem,
        )
    }

    @Test
    fun `duplicate manifest entry names fail closed`() {
        val result = VersionedSectionPayloadCodec.encode(
            VersionedSectionPayload(
                SectionPayloadKind.ASSET_MANIFEST,
                listOf(
                    SectionPayloadEntry("font.primary", ByteArray(32)),
                    SectionPayloadEntry("font.primary", ByteArray(32)),
                ),
            ),
        )

        assertTrue(result is SectionPayloadResult.Rejected)
    }
}
