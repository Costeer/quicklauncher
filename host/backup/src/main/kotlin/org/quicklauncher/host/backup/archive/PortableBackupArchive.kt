package org.quicklauncher.host.backup.archive

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.EOFException
import java.nio.ByteBuffer
import java.nio.charset.CodingErrorAction
import java.security.MessageDigest
import java.util.Collections

enum class PortableBackupKind(
    internal val wireValue: Int,
) {
    MANUAL(1),
    AUTOMATIC(2),
    PRE_RESTORE(3),
    ;

    internal companion object {
        fun fromWireValue(value: Int): PortableBackupKind? = entries.firstOrNull { it.wireValue == value }
    }
}

enum class PortableBackupSectionType(
    internal val wireValue: Int,
) {
    LAUNCHER_MAP(1),
    THEME_PROFILES(2),
    WEB_ADAPTERS(3),
    ASSET(4),
    ;

    internal companion object {
        fun fromWireValue(value: Int): PortableBackupSectionType? = entries.firstOrNull { it.wireValue == value }
    }
}

class PortableBackupSection(
    val type: PortableBackupSectionType,
    val name: String,
    content: ByteArray,
) {
    private val immutableContent = content.copyOf()

    val size: Int
        get() = immutableContent.size

    fun contentCopy(): ByteArray = immutableContent.copyOf()

    internal fun contentUnsafe(): ByteArray = immutableContent

    override fun equals(other: Any?): Boolean =
        other is PortableBackupSection &&
            type == other.type &&
            name == other.name &&
            immutableContent.contentEquals(other.immutableContent)

    override fun hashCode(): Int =
        31 * (31 * type.hashCode() + name.hashCode()) + immutableContent.contentHashCode()
}

class PortableBackupArchive(
    val createdAtEpochMillis: Long,
    val kind: PortableBackupKind,
    sections: List<PortableBackupSection>,
) {
    val sections: List<PortableBackupSection> = Collections.unmodifiableList(sections.toList())

    override fun equals(other: Any?): Boolean =
        other is PortableBackupArchive &&
            createdAtEpochMillis == other.createdAtEpochMillis &&
            kind == other.kind &&
            sections == other.sections

    override fun hashCode(): Int =
        31 * (31 * createdAtEpochMillis.hashCode() + kind.hashCode()) + sections.hashCode()
}

enum class PortableBackupProblem {
    OVERSIZED_ARCHIVE,
    TOO_MANY_SECTIONS,
    OVERSIZED_SECTION,
    INVALID_HEADER,
    UNSUPPORTED_VERSION,
    UNSUPPORTED_FEATURES,
    INVALID_METADATA,
    INVALID_SECTION_NAME,
    DUPLICATE_SECTION,
    NON_CANONICAL_ORDER,
    MISSING_LAUNCHER_MAP,
    CHECKSUM_MISMATCH,
    TRUNCATED,
    TRAILING_BYTES,
}

sealed interface PortableBackupEncodeResult {
    class Encoded(bytes: ByteArray) : PortableBackupEncodeResult {
        private val immutableBytes = bytes.copyOf()

        val size: Int
            get() = immutableBytes.size

        fun bytesCopy(): ByteArray = immutableBytes.copyOf()
    }

    data class Rejected(
        val problem: PortableBackupProblem,
    ) : PortableBackupEncodeResult
}

sealed interface PortableBackupDecodeResult {
    data class Decoded(
        val archive: PortableBackupArchive,
    ) : PortableBackupDecodeResult

    data class Rejected(
        val problem: PortableBackupProblem,
    ) : PortableBackupDecodeResult
}

object PortableBackupArchiveCodec {
    const val FORMAT_MAJOR: Int = 1
    const val FORMAT_MINOR: Int = 0
    const val MAX_ARCHIVE_BYTES: Int = 128 * 1024 * 1024
    const val MAX_SECTION_BYTES: Int = 16 * 1024 * 1024
    const val MAX_SECTIONS: Int = 512
    const val MAX_SECTION_NAME_BYTES: Int = 128

    private const val SECTION_DIGEST_BYTES = 32
    private const val ARCHIVE_HEADER_BYTES = 32
    private const val SECTION_HEADER_BYTES = 42
    private const val SUPPORTED_FLAGS = 0
    private val magic = byteArrayOf('Q'.code.toByte(), 'L'.code.toByte(), 'B'.code.toByte(), 'A'.code.toByte(), 'C'.code.toByte(), 'K'.code.toByte(), 'U'.code.toByte(), 'P'.code.toByte())
    private val sectionNamePattern = Regex("[a-z0-9][a-z0-9._-]{0,127}")
    private val sectionComparator = compareBy<PortableBackupSection>({ it.type.wireValue }, { it.name })

    fun encode(archive: PortableBackupArchive): PortableBackupEncodeResult {
        if (archive.createdAtEpochMillis < 0L) return rejectedEncode(PortableBackupProblem.INVALID_METADATA)
        if (archive.sections.size > MAX_SECTIONS) return rejectedEncode(PortableBackupProblem.TOO_MANY_SECTIONS)

        val canonicalSections = archive.sections.sortedWith(sectionComparator)
        val keys = HashSet<Pair<PortableBackupSectionType, String>>(canonicalSections.size)
        var encodedSize = ARCHIVE_HEADER_BYTES.toLong()
        canonicalSections.forEach { section ->
            val nameBytes = section.name.toByteArray(Charsets.UTF_8)
            if (!validSectionName(section.name, nameBytes)) {
                return rejectedEncode(PortableBackupProblem.INVALID_SECTION_NAME)
            }
            if (!keys.add(section.type to section.name)) {
                return rejectedEncode(PortableBackupProblem.DUPLICATE_SECTION)
            }
            if (section.size > MAX_SECTION_BYTES) {
                return rejectedEncode(PortableBackupProblem.OVERSIZED_SECTION)
            }
            encodedSize += SECTION_HEADER_BYTES + nameBytes.size.toLong() + section.size.toLong()
            if (encodedSize > MAX_ARCHIVE_BYTES) {
                return rejectedEncode(PortableBackupProblem.OVERSIZED_ARCHIVE)
            }
        }
        if (canonicalSections.count { it.type == PortableBackupSectionType.LAUNCHER_MAP } != 1) {
            return rejectedEncode(PortableBackupProblem.MISSING_LAUNCHER_MAP)
        }

        val sink = ByteArrayOutputStream(encodedSize.toInt())
        DataOutputStream(sink).use { output ->
            output.write(magic)
            output.writeShort(FORMAT_MAJOR)
            output.writeShort(FORMAT_MINOR)
            output.writeInt(SUPPORTED_FLAGS)
            output.writeLong(archive.createdAtEpochMillis)
            output.writeByte(archive.kind.wireValue)
            output.write(byteArrayOf(0, 0, 0))
            output.writeInt(canonicalSections.size)
            canonicalSections.forEach { section ->
                val nameBytes = section.name.toByteArray(Charsets.UTF_8)
                val content = section.contentUnsafe()
                output.writeByte(section.type.wireValue)
                output.write(byteArrayOf(0, 0, 0))
                output.writeShort(nameBytes.size)
                output.writeInt(content.size)
                output.write(sha256(content))
                output.write(nameBytes)
                output.write(content)
            }
        }
        return PortableBackupEncodeResult.Encoded(sink.toByteArray())
    }

    fun decode(bytes: ByteArray): PortableBackupDecodeResult {
        if (bytes.size > MAX_ARCHIVE_BYTES) return rejectedDecode(PortableBackupProblem.OVERSIZED_ARCHIVE)
        if (bytes.size < ARCHIVE_HEADER_BYTES) return rejectedDecode(PortableBackupProblem.TRUNCATED)

        return try {
            val source = ByteArrayInputStream(bytes)
            val input = DataInputStream(source)
            val observedMagic = ByteArray(magic.size).also(input::readFully)
            if (!observedMagic.contentEquals(magic)) return rejectedDecode(PortableBackupProblem.INVALID_HEADER)

            val major = input.readUnsignedShort()
            val minor = input.readUnsignedShort()
            if (major != FORMAT_MAJOR || minor != FORMAT_MINOR) {
                return rejectedDecode(PortableBackupProblem.UNSUPPORTED_VERSION)
            }
            if (input.readInt() != SUPPORTED_FLAGS) {
                return rejectedDecode(PortableBackupProblem.UNSUPPORTED_FEATURES)
            }

            val createdAtEpochMillis = input.readLong()
            val kind = PortableBackupKind.fromWireValue(input.readUnsignedByte())
            val reserved = ByteArray(3).also(input::readFully)
            val sectionCount = input.readInt()
            if (createdAtEpochMillis < 0L || kind == null || reserved.any { it != 0.toByte() } || sectionCount < 0) {
                return rejectedDecode(PortableBackupProblem.INVALID_METADATA)
            }
            if (sectionCount > MAX_SECTIONS) return rejectedDecode(PortableBackupProblem.TOO_MANY_SECTIONS)

            val sections = ArrayList<PortableBackupSection>(sectionCount)
            var priorKey: Pair<Int, String>? = null
            repeat(sectionCount) {
                val type = PortableBackupSectionType.fromWireValue(input.readUnsignedByte())
                    ?: return rejectedDecode(PortableBackupProblem.UNSUPPORTED_FEATURES)
                val sectionReserved = ByteArray(3).also(input::readFully)
                val nameLength = input.readUnsignedShort()
                val contentLength = input.readInt()
                val expectedDigest = ByteArray(SECTION_DIGEST_BYTES).also(input::readFully)
                if (sectionReserved.any { it != 0.toByte() } || contentLength < 0) {
                    return rejectedDecode(PortableBackupProblem.INVALID_METADATA)
                }
                if (nameLength !in 1..MAX_SECTION_NAME_BYTES) {
                    return rejectedDecode(PortableBackupProblem.INVALID_SECTION_NAME)
                }
                if (contentLength > MAX_SECTION_BYTES) {
                    return rejectedDecode(PortableBackupProblem.OVERSIZED_SECTION)
                }
                if (source.available().toLong() < nameLength.toLong() + contentLength.toLong()) {
                    return rejectedDecode(PortableBackupProblem.TRUNCATED)
                }

                val nameBytes = ByteArray(nameLength).also(input::readFully)
                val name = decodeUtf8(nameBytes) ?: return rejectedDecode(PortableBackupProblem.INVALID_SECTION_NAME)
                if (!validSectionName(name, nameBytes)) {
                    return rejectedDecode(PortableBackupProblem.INVALID_SECTION_NAME)
                }
                val key = type.wireValue to name
                priorKey?.let { previous ->
                    val order = compareKeys(previous, key)
                    if (order == 0) return rejectedDecode(PortableBackupProblem.DUPLICATE_SECTION)
                    if (order > 0) return rejectedDecode(PortableBackupProblem.NON_CANONICAL_ORDER)
                }

                val content = ByteArray(contentLength).also(input::readFully)
                if (!MessageDigest.isEqual(expectedDigest, sha256(content))) {
                    return rejectedDecode(PortableBackupProblem.CHECKSUM_MISMATCH)
                }
                sections += PortableBackupSection(type, name, content)
                priorKey = key
            }
            if (source.available() != 0) return rejectedDecode(PortableBackupProblem.TRAILING_BYTES)
            if (sections.count { it.type == PortableBackupSectionType.LAUNCHER_MAP } != 1) {
                return rejectedDecode(PortableBackupProblem.MISSING_LAUNCHER_MAP)
            }
            PortableBackupDecodeResult.Decoded(
                PortableBackupArchive(
                    createdAtEpochMillis = createdAtEpochMillis,
                    kind = kind,
                    sections = sections,
                ),
            )
        } catch (_: EOFException) {
            rejectedDecode(PortableBackupProblem.TRUNCATED)
        }
    }

    private fun validSectionName(name: String, bytes: ByteArray): Boolean =
        bytes.size in 1..MAX_SECTION_NAME_BYTES && sectionNamePattern.matches(name)

    private fun decodeUtf8(bytes: ByteArray): String? =
        runCatching {
            Charsets.UTF_8
                .newDecoder()
                .onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT)
                .decode(ByteBuffer.wrap(bytes))
                .toString()
        }.getOrNull()

    private fun compareKeys(
        left: Pair<Int, String>,
        right: Pair<Int, String>,
    ): Int = left.first.compareTo(right.first).takeIf { it != 0 } ?: left.second.compareTo(right.second)

    private fun sha256(bytes: ByteArray): ByteArray = MessageDigest.getInstance("SHA-256").digest(bytes)

    private fun rejectedEncode(problem: PortableBackupProblem) = PortableBackupEncodeResult.Rejected(problem)

    private fun rejectedDecode(problem: PortableBackupProblem) = PortableBackupDecodeResult.Rejected(problem)
}
