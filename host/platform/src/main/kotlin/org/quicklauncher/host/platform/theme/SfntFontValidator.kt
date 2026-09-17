package org.quicklauncher.host.platform.theme

import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.charset.Charset
import org.quicklauncher.host.runtime.theme.FontAxisMetadata
import org.quicklauncher.host.runtime.theme.MAX_FONT_AXES
import org.quicklauncher.host.runtime.theme.MAX_FONT_BYTES
import org.quicklauncher.host.runtime.theme.MAX_FONT_FACES
import org.quicklauncher.host.runtime.theme.MAX_FONT_FAMILIES
import org.quicklauncher.host.runtime.theme.SUPPORTED_FONT_AXES

data class ValidatedFontFile(
    val familyCount: Int,
    val faceCount: Int,
    val axes: List<FontAxisMetadata>,
    val weight: Int,
    val italic: Boolean,
)

object SfntFontValidator {
    private const val MAX_TABLES_PER_FACE = 64
    private const val MAX_NAME_RECORDS = 128
    private const val MAX_NAME_BYTES = 512

    fun validate(bytes: ByteArray): ValidatedFontFile? {
        if (bytes.size !in 64..MAX_FONT_BYTES) return null
        return runCatching {
            val offsets = faceOffsets(bytes)
            require(offsets.size in 1..MAX_FONT_FACES)
            val faces = offsets.map { parseFace(bytes, it) }
            val families = faces.flatMap { it.families }.toSet()
            require(families.size in 1..MAX_FONT_FAMILIES)
            val axes = faces.flatMap { it.axes }.distinctBy { it.tag }
            require(axes.size <= MAX_FONT_AXES)
            ValidatedFontFile(
                familyCount = families.size,
                faceCount = faces.size,
                axes = axes,
                weight = faces.first().weight,
                italic = faces.first().italic,
            )
        }.getOrNull()
    }

    private fun faceOffsets(bytes: ByteArray): List<Int> {
        return if (tag(bytes, 0) == "ttcf") {
            require(bytes.size >= 12)
            val count = u32(bytes, 8)
            require(count in 1..MAX_FONT_FACES.toLong())
            requireRange(12, count.toInt() * 4, bytes.size)
            List(count.toInt()) { index ->
                val offset = u32(bytes, 12 + index * 4)
                require(offset <= Int.MAX_VALUE)
                offset.toInt()
            }.distinct().also { require(it.size == count.toInt()) }
        } else {
            listOf(0)
        }
    }

    private fun parseFace(bytes: ByteArray, offset: Int): ParsedFace {
        requireRange(offset, 12, bytes.size)
        val signature = tag(bytes, offset)
        require(signature == "OTTO" || signature == "\u0000\u0001\u0000\u0000")
        val tableCount = u16(bytes, offset + 4)
        require(tableCount in 1..MAX_TABLES_PER_FACE)
        requireRange(offset + 12, tableCount * 16, bytes.size)
        val tables = linkedMapOf<String, Table>()
        repeat(tableCount) { index ->
            val entry = offset + 12 + index * 16
            val name = tag(bytes, entry)
            val tableOffset = u32(bytes, entry + 8)
            val length = u32(bytes, entry + 12)
            require(tableOffset <= Int.MAX_VALUE && length <= Int.MAX_VALUE)
            requireRange(tableOffset.toInt(), length.toInt(), bytes.size)
            require(tables.put(name, Table(tableOffset.toInt(), length.toInt())) == null)
        }
        require("name" in tables && "head" in tables && "maxp" in tables && "cmap" in tables)
        require("glyf" in tables || "CFF " in tables || "CFF2" in tables)
        val families = parseFamilies(bytes, tables.getValue("name"))
        require(families.isNotEmpty())
        val os2 = tables["OS/2"]
        val weight = os2?.takeIf { it.length >= 64 }?.let { u16(bytes, it.offset + 4) }
            ?.coerceIn(1, 1000) ?: 400
        val italic = when {
            os2 != null && os2.length >= 64 -> u16(bytes, os2.offset + 62) and 1 != 0
            tables.getValue("head").length >= 46 ->
                u16(bytes, tables.getValue("head").offset + 44) and 2 != 0
            else -> false
        }
        return ParsedFace(families, tables["fvar"]?.let { parseAxes(bytes, it) }.orEmpty(), weight, italic)
    }

    private fun parseFamilies(bytes: ByteArray, table: Table): Set<String> {
        require(table.length >= 6)
        val count = u16(bytes, table.offset + 2)
        val stringsOffset = u16(bytes, table.offset + 4)
        require(count in 1..MAX_NAME_RECORDS)
        requireRange(table.offset + 6, count * 12, table.offset + table.length)
        val result = linkedSetOf<String>()
        repeat(count) { index ->
            val record = table.offset + 6 + index * 12
            val platform = u16(bytes, record)
            val nameId = u16(bytes, record + 6)
            val length = u16(bytes, record + 8)
            val relativeOffset = u16(bytes, record + 10)
            if (nameId !in setOf(1, 16) || length !in 1..MAX_NAME_BYTES) return@repeat
            val start = table.offset + stringsOffset + relativeOffset
            requireRange(start, length, table.offset + table.length)
            val charset = if (platform == 0 || platform == 3) Charsets.UTF_16BE else MAC_ROMAN
            val value = bytes.copyOfRange(start, start + length)
                .toString(charset)
                .trim()
            if (value.isNotEmpty() && value.length <= 128 && value.none(Char::isISOControl)) result += value
        }
        return result
    }

    private fun parseAxes(bytes: ByteArray, table: Table): List<FontAxisMetadata> {
        require(table.length >= 16)
        val axesOffset = u16(bytes, table.offset + 4)
        val axisCount = u16(bytes, table.offset + 8)
        val axisSize = u16(bytes, table.offset + 10)
        require(axisCount in 1..MAX_FONT_AXES && axisSize >= 20)
        requireRange(table.offset + axesOffset, axisCount * axisSize, table.offset + table.length)
        return List(axisCount) { index ->
            val offset = table.offset + axesOffset + index * axisSize
            val axisTag = tag(bytes, offset)
            require(axisTag in SUPPORTED_FONT_AXES)
            FontAxisMetadata(
                axisTag,
                fixed(bytes, offset + 4),
                fixed(bytes, offset + 8),
                fixed(bytes, offset + 12),
            )
        }.also { require(it.distinctBy(FontAxisMetadata::tag).size == it.size) }
    }

    private fun fixed(bytes: ByteArray, offset: Int): Float = i32(bytes, offset) / 65536f

    private fun tag(bytes: ByteArray, offset: Int): String {
        requireRange(offset, 4, bytes.size)
        return String(bytes, offset, 4, Charsets.ISO_8859_1)
    }

    private fun u16(bytes: ByteArray, offset: Int): Int {
        requireRange(offset, 2, bytes.size)
        return ByteBuffer.wrap(bytes, offset, 2).order(ByteOrder.BIG_ENDIAN).short.toInt() and 0xffff
    }

    private fun i32(bytes: ByteArray, offset: Int): Int {
        requireRange(offset, 4, bytes.size)
        return ByteBuffer.wrap(bytes, offset, 4).order(ByteOrder.BIG_ENDIAN).int
    }

    private fun u32(bytes: ByteArray, offset: Int): Long = i32(bytes, offset).toLong() and 0xffffffffL

    private fun requireRange(offset: Int, length: Int, limit: Int) {
        require(offset >= 0 && length >= 0 && offset.toLong() + length <= limit.toLong())
    }

    private data class Table(val offset: Int, val length: Int)
    private data class ParsedFace(
        val families: Set<String>,
        val axes: List<FontAxisMetadata>,
        val weight: Int,
        val italic: Boolean,
    )

    private val MAC_ROMAN: Charset = runCatching { Charset.forName("x-MacRoman") }
        .getOrDefault(Charsets.ISO_8859_1)
}
