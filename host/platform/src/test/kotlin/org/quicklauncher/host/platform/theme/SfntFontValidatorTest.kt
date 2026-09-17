package org.quicklauncher.host.platform.theme

import java.nio.ByteBuffer
import java.nio.ByteOrder
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SfntFontValidatorTest {
    @Test
    fun `static and supported variable metadata are bounded and accepted`() {
        val static = SfntFontValidator.validate(font())
        val variable = SfntFontValidator.validate(font(axis = "wght"))

        assertEquals(1, static?.familyCount)
        assertEquals(1, static?.faceCount)
        assertTrue(static?.axes.orEmpty().isEmpty())
        assertEquals("wght", variable?.axes?.single()?.tag)
        assertEquals(400f, variable?.axes?.single()?.default)
    }

    @Test
    fun `truncated misleading and unsupported font inputs fail closed`() {
        assertNull(SfntFontValidator.validate(ByteArray(63)))
        assertNull(SfntFontValidator.validate(font().copyOf(100)))
        assertNull(SfntFontValidator.validate(font(axis = "EXEC")))
        assertNull(SfntFontValidator.validate(ByteArray(80) { 0x41 }))
    }

    private fun font(axis: String? = null): ByteArray {
        val tables = linkedMapOf(
            "name" to nameTable(),
            "head" to ByteArray(46),
            "maxp" to ByteArray(4),
            "cmap" to ByteArray(4),
            "glyf" to ByteArray(1),
        )
        if (axis != null) tables["fvar"] = fvarTable(axis)
        val directorySize = 12 + tables.size * 16
        val total = directorySize + tables.values.sumOf(ByteArray::size)
        val output = ByteBuffer.allocate(total).order(ByteOrder.BIG_ENDIAN)
        output.putInt(0x00010000)
        output.putShort(tables.size.toShort())
        output.putShort(0).putShort(0).putShort(0)
        var offset = directorySize
        tables.forEach { (tag, bytes) ->
            output.put(tag.toByteArray(Charsets.ISO_8859_1))
            output.putInt(0)
            output.putInt(offset)
            output.putInt(bytes.size)
            offset += bytes.size
        }
        tables.values.forEach(output::put)
        return output.array()
    }

    private fun nameTable(): ByteArray = ByteBuffer.allocate(20).order(ByteOrder.BIG_ENDIAN).apply {
        putShort(0)
        putShort(1)
        putShort(18)
        putShort(3)
        putShort(1)
        putShort(0x0409)
        putShort(1)
        putShort(2)
        putShort(0)
        putShort(0x0041)
    }.array()

    private fun fvarTable(axis: String): ByteArray = ByteBuffer.allocate(36).order(ByteOrder.BIG_ENDIAN).apply {
        putInt(0x00010000)
        putShort(16)
        putShort(2)
        putShort(1)
        putShort(20)
        putShort(0)
        putShort(0)
        put(axis.toByteArray(Charsets.ISO_8859_1))
        putInt(100 shl 16)
        putInt(400 shl 16)
        putInt(900 shl 16)
        putShort(0)
        putShort(256)
    }.array()
}
