package org.quicklauncher.host.platform.theme

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class DeclarativeIconPackParserTest {
    @Test
    fun `nova and adw declarative items parse with deterministic first duplicate`() {
        val parsed = DeclarativeIconPackParser.parse(
            """
            <resources>
              <item component="ComponentInfo{org.example.alpha/.MainActivity}" drawable="alpha_first" />
              <item component="org.example.alpha/org.example.alpha.MainActivity" drawable="alpha_second" />
              <item component="ComponentInfo{org.example.beta/org.example.beta.Home}" drawable="beta" />
            </resources>
            """.trimIndent(),
        )

        assertEquals(2, parsed?.size)
        assertEquals(
            "alpha_first",
            parsed?.get(IconComponentKey("org.example.alpha", "org.example.alpha.MainActivity")),
        )
    }

    @Test
    fun `malformed executable and oversized mappings fail closed`() {
        assertNull(DeclarativeIconPackParser.parse("<!DOCTYPE x [<!ENTITY y SYSTEM 'file:///x'>]><x>&y;</x>"))
        assertEquals(
            emptyMap<IconComponentKey, String>(),
            DeclarativeIconPackParser.parse(
                "<resources><item component='org.example.app/.Main' drawable='../bad'/></resources>",
            ),
        )
        assertNull(
            DeclarativeIconPackParser.parse(
                "x".repeat(DeclarativeIconPackParser.MAX_MAPPING_CHARS + 1),
            ),
        )
        assertEquals(
            emptyMap<IconComponentKey, String>(),
            DeclarativeIconPackParser.parse(
                "<resources><item component='" +
                    "a".repeat(DeclarativeIconPackParser.MAX_COMPONENT_CHARS + 1) +
                    "' drawable='valid_name'/></resources>",
            ),
        )
    }
}
