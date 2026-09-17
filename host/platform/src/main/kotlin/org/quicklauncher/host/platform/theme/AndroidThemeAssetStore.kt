package org.quicklauncher.host.platform.theme

import android.app.WallpaperManager
import android.content.Context
import android.graphics.Bitmap
import android.graphics.ImageDecoder
import android.graphics.Typeface
import android.net.Uri
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.nio.ByteBuffer
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.security.SecureRandom
import java.util.Collections
import java.util.UUID
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runInterruptible
import kotlinx.coroutines.withTimeout
import org.quicklauncher.contracts.domain.ArgbColor
import org.quicklauncher.contracts.domain.StableKey
import org.quicklauncher.host.runtime.theme.FontImportResult
import org.quicklauncher.host.runtime.theme.ImageImportResult
import org.quicklauncher.host.runtime.theme.ImportedFontMetadata
import org.quicklauncher.host.runtime.theme.ImportedImageMetadata
import org.quicklauncher.host.runtime.theme.MAX_FONT_BYTES
import org.quicklauncher.host.runtime.theme.MAX_IMAGE_BYTES
import org.quicklauncher.host.runtime.theme.MAX_IMAGE_DIMENSION
import org.quicklauncher.host.runtime.theme.MAX_IMAGE_MEMORY_BYTES
import org.quicklauncher.host.runtime.theme.MAX_IMAGE_PIXELS
import org.quicklauncher.host.runtime.theme.MAX_PREVIEW_DIMENSION
import org.quicklauncher.host.runtime.theme.ThemeAssetInventory
import org.quicklauncher.host.runtime.theme.WallpaperCrop
import org.quicklauncher.host.runtime.theme.WallpaperPlatform
import org.quicklauncher.host.runtime.theme.WallpaperPlatformResult
import org.quicklauncher.host.runtime.theme.WallpaperTarget

enum class ThemeAssetBackupKind { FONT, IMAGE, PREVIEW }

class ThemeAssetBackupEntry(
    val id: StableKey,
    val kind: ThemeAssetBackupKind,
    bytes: ByteArray,
) {
    private val bytes = bytes.copyOf()
    fun bytesCopy(): ByteArray = bytes.copyOf()
}

data class ThemeAssetBackupIdentity(
    val id: StableKey,
    val kind: ThemeAssetBackupKind,
)

class StagedThemeAssetRestore internal constructor(
    private val commitAction: suspend () -> Boolean,
    private val discardAction: suspend () -> Unit,
) {
    suspend fun commit(): Boolean = commitAction()
    suspend fun discard() = discardAction()
}

class AndroidThemeAssetStore(
    context: Context,
    private val timeoutMillis: Long = 5_000L,
) : ThemeAssetInventory, WallpaperPlatform {
    private val appContext = context.applicationContext
    private val resolver = appContext.contentResolver
    private val root = File(appContext.filesDir, "theme-assets")
    private val fontDirectory = File(root, "fonts")
    private val imageDirectory = File(root, "images")
    private val previewDirectory = File(root, "previews")
    private val preservedBackupAssetsFile = File(root, "preserved-backup-assets.v1")
    private val random = SecureRandom()

    suspend fun importFont(uri: Uri?): FontImportResult {
        if (uri == null) return FontImportResult.Cancelled
        return try {
            withTimeout(timeoutMillis) {
                runInterruptible(Dispatchers.IO) {
                    if (uri.scheme != "content") return@runInterruptible FontImportResult.Denied
                    if (resolver.getType(uri) !in FONT_MIME_TYPES) return@runInterruptible FontImportResult.Unsupported
                    val bytes = readBounded(uri, MAX_FONT_BYTES) ?: return@runInterruptible FontImportResult.Denied
                    if (bytes.size > MAX_FONT_BYTES) return@runInterruptible FontImportResult.Oversized
                    val validated = SfntFontValidator.validate(bytes) ?: return@runInterruptible FontImportResult.Malformed
                    if (!hasCapacity(fontDirectory, MAX_FONT_ASSETS)) return@runInterruptible FontImportResult.Failed
                    val id = assetId("font")
                    val file = privateFile(fontDirectory, id, FONT_SUFFIX)
                    if (!writePrivateAtomically(file, bytes)) return@runInterruptible FontImportResult.Failed
                    val loadable = runCatching { Typeface.Builder(file).build() }.getOrNull()
                    if (loadable == null) {
                        file.delete()
                        return@runInterruptible FontImportResult.Malformed
                    }
                    FontImportResult.Imported(
                        ImportedFontMetadata(
                            id,
                            validated.familyCount,
                            validated.faceCount,
                            validated.axes,
                            validated.weight,
                            validated.italic,
                        ),
                    )
                }
            }
        } catch (_: kotlinx.coroutines.TimeoutCancellationException) {
            FontImportResult.TimedOut
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: SecurityException) {
            FontImportResult.Denied
        } catch (_: IOException) {
            FontImportResult.Denied
        } catch (_: RuntimeException) {
            FontImportResult.Failed
        }
    }

    suspend fun importImage(uri: Uri?, retainSource: Boolean): ImageImportResult {
        if (uri == null) return ImageImportResult.Cancelled
        return try {
            withTimeout(timeoutMillis) {
                runInterruptible(Dispatchers.IO) {
                    if (uri.scheme != "content") return@runInterruptible ImageImportResult.Denied
                    if (resolver.getType(uri) !in IMAGE_MIME_TYPES) return@runInterruptible ImageImportResult.Unsupported
                    val bytes = readBounded(uri, MAX_IMAGE_BYTES) ?: return@runInterruptible ImageImportResult.Denied
                    if (bytes.size > MAX_IMAGE_BYTES) return@runInterruptible ImageImportResult.Oversized
                    if (!supportedImageSignature(bytes)) return@runInterruptible ImageImportResult.Malformed
                    val bitmap = decodeBounded(bytes) ?: return@runInterruptible ImageImportResult.Malformed
                    try {
                        if (!hasCapacity(previewDirectory, MAX_PREVIEW_ASSETS) ||
                            retainSource && !hasCapacity(imageDirectory, MAX_IMAGE_ASSETS)
                        ) return@runInterruptible ImageImportResult.Failed
                        val representative = representativeColor(bitmap)
                        val preview = scaled(bitmap, MAX_PREVIEW_DIMENSION)
                        val previewId = assetId("preview")
                        val previewFile = privateFile(previewDirectory, previewId, PREVIEW_SUFFIX)
                        val previewWritten = writeBitmapAtomically(previewFile, preview)
                        if (preview !== bitmap) preview.recycle()
                        if (!previewWritten) return@runInterruptible ImageImportResult.Failed
                        val assetId = if (retainSource) assetId("image") else previewId
                        if (retainSource) {
                            val imageFile = privateFile(imageDirectory, assetId, IMAGE_SUFFIX)
                            if (!writePrivateAtomically(imageFile, bytes)) {
                                previewFile.delete()
                                return@runInterruptible ImageImportResult.Failed
                            }
                        }
                        ImageImportResult.Imported(
                            ImportedImageMetadata(
                                assetId,
                                previewId,
                                bitmap.width,
                                bitmap.height,
                                representative,
                                representative,
                            ),
                        )
                    } finally {
                        bitmap.recycle()
                    }
                }
            }
        } catch (_: kotlinx.coroutines.TimeoutCancellationException) {
            ImageImportResult.TimedOut
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: SecurityException) {
            ImageImportResult.Denied
        } catch (_: IOException) {
            ImageImportResult.Denied
        } catch (_: RuntimeException) {
            ImageImportResult.Failed
        }
    }

    suspend fun loadTypeface(assetId: StableKey, weight: Int, italic: Boolean): Typeface? =
        boundedIo(null) {
            val file = privateFile(fontDirectory, assetId, FONT_SUFFIX)
            if (!validFontFile(file)) return@boundedIo null
            runCatching {
                Typeface.Builder(file)
                    .setWeight(weight.coerceIn(1, 1000))
                    .setItalic(italic)
                    .build()
            }.getOrNull()
        }

    suspend fun loadPreview(assetId: StableKey): Bitmap? = boundedIo(null) {
        val file = privateFile(previewDirectory, assetId, PREVIEW_SUFFIX)
        if (!validImageFile(file)) return@boundedIo null
        val bytes = runCatching {
            file.inputStream().use { it.readNBytes(MAX_IMAGE_BYTES + 1) }
        }.getOrNull() ?: return@boundedIo null
        if (bytes.size > MAX_IMAGE_BYTES) null else decodeBounded(bytes)
    }

    override suspend fun fontAssets(): Set<StableKey> = boundedIo(emptySet()) {
        validAssets(fontDirectory, FONT_SUFFIX, MAX_FONT_ASSETS) { validFontFile(it) }
    }

    override suspend fun imageAssets(): Set<StableKey> = boundedIo(emptySet()) {
        Collections.unmodifiableSet(
            linkedSetOf<StableKey>().apply {
                addAll(validAssets(imageDirectory, IMAGE_SUFFIX, MAX_IMAGE_ASSETS) { validImageFile(it) })
                addAll(validAssets(previewDirectory, PREVIEW_SUFFIX, MAX_PREVIEW_ASSETS) { validImageFile(it) })
            },
        )
    }

    suspend fun exportBackupAssets(): List<ThemeAssetBackupEntry> = boundedIo(emptyList()) {
        val result = ArrayList<ThemeAssetBackupEntry>()
        var total = 0L
        fun append(directory: File, suffix: String, limit: Int, kind: ThemeAssetBackupKind) {
            boundedFiles(directory, limit).forEach { file ->
                val id = parseAssetFile(file, suffix) ?: return@forEach
                val maximum = if (kind == ThemeAssetBackupKind.FONT) MAX_FONT_BYTES else MAX_IMAGE_BYTES
                val bytes = file.inputStream().use { it.readNBytes(maximum + 1) }
                if (bytes.size > maximum) return@forEach
                val valid = when (kind) {
                    ThemeAssetBackupKind.FONT -> SfntFontValidator.validate(bytes) != null
                    ThemeAssetBackupKind.IMAGE,
                    ThemeAssetBackupKind.PREVIEW,
                    -> supportedImageSignature(bytes) && decodeBounded(bytes)?.let { bitmap ->
                        bitmap.recycle()
                        true
                    } == true
                }
                if (!valid || total + bytes.size > MAX_BACKUP_ASSET_BYTES) return@forEach
                total += bytes.size
                result += ThemeAssetBackupEntry(id, kind, bytes)
            }
        }
        append(fontDirectory, FONT_SUFFIX, MAX_FONT_ASSETS, ThemeAssetBackupKind.FONT)
        append(imageDirectory, IMAGE_SUFFIX, MAX_IMAGE_ASSETS, ThemeAssetBackupKind.IMAGE)
        append(previewDirectory, PREVIEW_SUFFIX, MAX_PREVIEW_ASSETS, ThemeAssetBackupKind.PREVIEW)
        Collections.unmodifiableList(result)
    }

    suspend fun stageBackupAssets(
        entries: List<ThemeAssetBackupEntry>,
        preserved: Set<ThemeAssetBackupIdentity> = emptySet(),
    ): StagedThemeAssetRestore? =
        boundedIo(null) {
            if (entries.size > MAX_BACKUP_ASSETS || entries.map { it.kind to it.id }.toSet().size != entries.size) {
                return@boundedIo null
            }
            if (preserved.size > MAX_BACKUP_ASSETS || preserved.any { identity ->
                    entries.none { it.kind == identity.kind && it.id == identity.id }
                }
            ) return@boundedIo null
            var total = 0L
            val staging = File(root, "restore-${UUID.randomUUID()}")
            if (!staging.mkdirs()) return@boundedIo null
            val staged = ArrayList<Pair<File, File>>()
            try {
                entries.forEach { entry ->
                    val bytes = entry.bytesCopy()
                    total += bytes.size
                    if (total > MAX_BACKUP_ASSET_BYTES) error("Backup asset total exceeds bound")
                    val (targetDirectory, suffix) = when (entry.kind) {
                        ThemeAssetBackupKind.FONT -> fontDirectory to FONT_SUFFIX
                        ThemeAssetBackupKind.IMAGE -> imageDirectory to IMAGE_SUFFIX
                        ThemeAssetBackupKind.PREVIEW -> previewDirectory to PREVIEW_SUFFIX
                    }
                    val valid = when (entry.kind) {
                        ThemeAssetBackupKind.FONT ->
                            bytes.size <= MAX_FONT_BYTES && SfntFontValidator.validate(bytes) != null
                        ThemeAssetBackupKind.IMAGE,
                        ThemeAssetBackupKind.PREVIEW,
                        -> bytes.size <= MAX_IMAGE_BYTES && supportedImageSignature(bytes) &&
                            decodeBounded(bytes)?.let { bitmap -> bitmap.recycle(); true } == true
                    }
                    if (!valid) error("Backup asset is invalid")
                    val stagedFile = File(staging, entry.id.value + suffix)
                    if (!writePrivateAtomically(stagedFile, bytes)) error("Backup asset could not be staged")
                    staged += stagedFile to privateFile(targetDirectory, entry.id, suffix)
                }
            } catch (_: RuntimeException) {
                staging.listFiles()?.take(MAX_BACKUP_ASSETS)?.forEach(File::delete)
                staging.delete()
                return@boundedIo null
            }
            val installed = ArrayList<File>()
            val previousPreserved = preservedBackupAssetsFile
                .takeIf { it.isFile && it.length() <= MAX_PRESERVED_BYTES }
                ?.inputStream()
                ?.use { it.readNBytes(MAX_PRESERVED_BYTES.toInt() + 1) }
            StagedThemeAssetRestore(
                commitAction = {
                    boundedIo(false) {
                        try {
                            staged.forEach { (source, target) ->
                                if (target.exists()) {
                                    if (!source.readBytes().contentEquals(target.readBytes())) {
                                        error("Backup asset identity collision")
                                    }
                                    source.delete()
                                } else {
                                    target.parentFile?.mkdirs()
                                    Files.move(source.toPath(), target.toPath(), StandardCopyOption.ATOMIC_MOVE)
                                    installed += target
                                }
                            }
                            if (!writePreservedBackupAssets(preserved)) {
                                error("Backup asset preservation metadata could not be written")
                            }
                            staging.delete()
                            true
                        } catch (_: RuntimeException) {
                            installed.forEach(File::delete)
                            false
                        }
                    }
                },
                discardAction = {
                    boundedIo(Unit) {
                        staged.map { it.first }.take(MAX_BACKUP_ASSETS).forEach(File::delete)
                        installed.take(MAX_BACKUP_ASSETS).forEach(File::delete)
                        if (previousPreserved == null) {
                            preservedBackupAssetsFile.delete()
                        } else {
                            writePrivateAtomically(preservedBackupAssetsFile, previousPreserved)
                        }
                        staging.delete()
                    }
                },
            )
        }

    override suspend fun deleteUnreferenced(referenced: Set<StableKey>) = boundedIo(Unit) {
        cleanupInterruptedRestoreStaging()
        val preserved = readPreservedBackupAssets().filterNotTo(linkedSetOf()) { it.id in referenced }
        listOf(
            AssetDirectory(fontDirectory, FONT_SUFFIX, MAX_FONT_ASSETS, ThemeAssetBackupKind.FONT),
            AssetDirectory(imageDirectory, IMAGE_SUFFIX, MAX_IMAGE_ASSETS, ThemeAssetBackupKind.IMAGE),
            AssetDirectory(previewDirectory, PREVIEW_SUFFIX, MAX_PREVIEW_ASSETS, ThemeAssetBackupKind.PREVIEW),
        ).forEach { (directory, suffix, limit, kind) ->
            boundedFiles(directory, limit + MAX_PENDING_FILES).forEach { file ->
                val id = parseAssetFile(file, suffix)
                if (id == null || id !in referenced && ThemeAssetBackupIdentity(id, kind) !in preserved) {
                    file.delete()
                }
            }
        }
        writePreservedBackupAssets(preserved)
        Unit
    }

    private fun readPreservedBackupAssets(): Set<ThemeAssetBackupIdentity> {
        if (!preservedBackupAssetsFile.isFile || preservedBackupAssetsFile.length() > MAX_PRESERVED_BYTES) {
            return emptySet()
        }
        return runCatching {
            preservedBackupAssetsFile.readLines(Charsets.UTF_8)
                .take(MAX_BACKUP_ASSETS + 1)
                .map { line ->
                    val kind = ThemeAssetBackupKind.valueOf(line.substringBefore(':'))
                    val id = StableKey.parse(line.substringAfter(':'))
                    ThemeAssetBackupIdentity(id, kind)
                }
                .takeIf { it.size <= MAX_BACKUP_ASSETS }
                ?.toSet()
                ?: emptySet()
        }.getOrDefault(emptySet())
    }

    private fun writePreservedBackupAssets(preserved: Set<ThemeAssetBackupIdentity>): Boolean {
        if (preserved.isEmpty()) return !preservedBackupAssetsFile.exists() || preservedBackupAssetsFile.delete()
        val bytes = preserved.sortedWith(compareBy({ it.kind.name }, { it.id.value }))
            .joinToString(separator = "\n", postfix = "\n") { "${it.kind.name}:${it.id.value}" }
            .toByteArray(Charsets.UTF_8)
        return bytes.size <= MAX_PRESERVED_BYTES && writePrivateAtomically(preservedBackupAssetsFile, bytes)
    }

    private fun cleanupInterruptedRestoreStaging() {
        root.listFiles()
            ?.asSequence()
            ?.filter { it.isDirectory && RESTORE_STAGING_NAME.matches(it.name) }
            ?.take(MAX_PENDING_FILES)
            ?.forEach { directory ->
                directory.listFiles()
                    ?.asSequence()
                    ?.filter(File::isFile)
                    ?.take(MAX_BACKUP_ASSETS + 1)
                    ?.forEach(File::delete)
                directory.delete()
            }
    }

    private data class AssetDirectory(
        val directory: File,
        val suffix: String,
        val limit: Int,
        val kind: ThemeAssetBackupKind,
    )

    suspend fun deleteAssets(assetIds: Set<StableKey>) = boundedIo(Unit) {
        assetIds.take(MAX_DELETE_ASSETS).forEach { id ->
            privateFile(fontDirectory, id, FONT_SUFFIX).delete()
            privateFile(imageDirectory, id, IMAGE_SUFFIX).delete()
            privateFile(previewDirectory, id, PREVIEW_SUFFIX).delete()
        }
    }

    override suspend fun isSupported(target: WallpaperTarget): Boolean = try {
        withTimeout(timeoutMillis) {
                runInterruptible(Dispatchers.IO) {
                val manager = WallpaperManager.getInstance(appContext)
                manager.isWallpaperSupported && manager.isSetWallpaperAllowed && when (target) {
                    WallpaperTarget.HOME, WallpaperTarget.BOTH -> true
                    WallpaperTarget.LOCK -> true
                }
            }
        }
    } catch (_: kotlinx.coroutines.TimeoutCancellationException) {
        false
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (_: SecurityException) {
        false
    } catch (_: RuntimeException) {
        false
    }

    override suspend fun validateOwnedImage(assetId: StableKey): Boolean = boundedIo(false) {
        validImageFile(privateFile(imageDirectory, assetId, IMAGE_SUFFIX))
    }

    override suspend fun createPreview(assetId: StableKey, crop: WallpaperCrop): StableKey? = try {
        withTimeout(timeoutMillis) {
            runInterruptible(Dispatchers.IO) {
            val bitmap = decodeOwned(assetId) ?: return@runInterruptible null
            try {
                if (!hasCapacity(previewDirectory, MAX_PREVIEW_ASSETS)) return@runInterruptible null
                val cropped = crop(bitmap, crop)
                try {
                    val scaled = scaled(cropped, MAX_PREVIEW_DIMENSION)
                    try {
                        val previewId = assetId("wallpaper-preview")
                        val file = privateFile(previewDirectory, previewId, PREVIEW_SUFFIX)
                        if (writeBitmapAtomically(file, scaled)) previewId else null
                    } finally {
                        if (scaled !== cropped) scaled.recycle()
                    }
                } finally {
                    cropped.recycle()
                }
            } finally {
                bitmap.recycle()
            }
            }
        }
    } catch (_: kotlinx.coroutines.TimeoutCancellationException) {
        null
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (_: SecurityException) {
        null
    } catch (_: RuntimeException) {
        null
    }

    override suspend fun validatePreview(
        previewId: StableKey,
        assetId: StableKey,
        crop: WallpaperCrop,
    ): Boolean = try {
        withTimeout(timeoutMillis) {
            runInterruptible(Dispatchers.IO) {
                if (!validImageFile(privateFile(imageDirectory, assetId, IMAGE_SUFFIX))) {
                    return@runInterruptible false
                }
                val preview = privateFile(previewDirectory, previewId, PREVIEW_SUFFIX)
                validImageFile(preview) && crop.left < crop.right && crop.top < crop.bottom
            }
        }
    } catch (_: kotlinx.coroutines.TimeoutCancellationException) {
        false
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (_: SecurityException) {
        false
    } catch (_: RuntimeException) {
        false
    }

    override suspend fun apply(
        assetId: StableKey,
        crop: WallpaperCrop,
        target: WallpaperTarget,
    ): WallpaperPlatformResult = try {
        withTimeout(timeoutMillis) {
            runInterruptible(Dispatchers.IO) {
        val manager = WallpaperManager.getInstance(appContext)
        if (!manager.isWallpaperSupported || !manager.isSetWallpaperAllowed) {
            return@runInterruptible WallpaperPlatformResult.Unavailable
        }
        val bitmap = decodeOwned(assetId) ?: return@runInterruptible WallpaperPlatformResult.RecoverableFailure
        try {
            val cropped = crop(bitmap, crop)
            try {
                val flags = when (target) {
                    WallpaperTarget.HOME -> WallpaperManager.FLAG_SYSTEM
                    WallpaperTarget.LOCK -> WallpaperManager.FLAG_LOCK
                    WallpaperTarget.BOTH -> WallpaperManager.FLAG_SYSTEM or WallpaperManager.FLAG_LOCK
                }
                manager.setBitmap(cropped, null, true, flags)
                WallpaperPlatformResult.Applied
            } finally {
                cropped.recycle()
            }
        } catch (_: SecurityException) {
            WallpaperPlatformResult.Denied
        } catch (_: IOException) {
            WallpaperPlatformResult.RecoverableFailure
        } catch (_: RuntimeException) {
            WallpaperPlatformResult.RecoverableFailure
        } finally {
            bitmap.recycle()
        }
            }
        }
    } catch (_: kotlinx.coroutines.TimeoutCancellationException) {
        WallpaperPlatformResult.RecoverableFailure
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (_: SecurityException) {
        WallpaperPlatformResult.Denied
    } catch (_: IOException) {
        WallpaperPlatformResult.RecoverableFailure
    } catch (_: RuntimeException) {
        WallpaperPlatformResult.RecoverableFailure
    }

    private suspend fun <T> boundedIo(fallback: T, block: () -> T): T =
        runBoundedThemeIo(timeoutMillis, fallback, block)

    private fun readBounded(uri: Uri, maximum: Int): ByteArray? {
        val input = resolver.openInputStream(uri) ?: return null
        return input.use { stream ->
            val output = ByteArrayOutputStream(minOf(maximum, 64 * 1024))
            val buffer = ByteArray(16 * 1024)
            var total = 0
            while (true) {
                val count = stream.read(buffer)
                if (count < 0) break
                total += count
                if (total > maximum) return ByteArray(maximum + 1)
                output.write(buffer, 0, count)
            }
            output.toByteArray()
        }
    }

    private fun decodeBounded(bytes: ByteArray): Bitmap? = runCatching {
        ImageDecoder.decodeBitmap(ImageDecoder.createSource(ByteBuffer.wrap(bytes))) { decoder, info, _ ->
            val width = info.size.width
            val height = info.size.height
            require(width in 1..MAX_IMAGE_DIMENSION && height in 1..MAX_IMAGE_DIMENSION)
            val pixels = width.toLong() * height
            require(pixels in 1..MAX_IMAGE_PIXELS && pixels * 4L <= MAX_IMAGE_MEMORY_BYTES)
            decoder.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
            decoder.memorySizePolicy = ImageDecoder.MEMORY_POLICY_LOW_RAM
        }
    }.getOrNull()

    private fun decodeOwned(assetId: StableKey): Bitmap? {
        val file = privateFile(imageDirectory, assetId, IMAGE_SUFFIX)
        if (!validImageFile(file)) return null
        val bytes = runCatching {
            file.inputStream().use { it.readNBytes(MAX_IMAGE_BYTES + 1) }
        }.getOrNull() ?: return null
        if (bytes.size > MAX_IMAGE_BYTES) return null
        return decodeBounded(bytes)
    }

    private fun validFontFile(file: File): Boolean {
        if (!file.isFile || file.length() !in 64..MAX_FONT_BYTES.toLong()) return false
        val bytes = runCatching {
            file.inputStream().use { it.readNBytes(MAX_FONT_BYTES + 1) }
        }.getOrNull() ?: return false
        return bytes.size <= MAX_FONT_BYTES &&
            SfntFontValidator.validate(bytes) != null &&
            runCatching { Typeface.Builder(file).build() }.getOrNull() != null
    }

    private fun validImageFile(file: File): Boolean {
        if (!file.isFile || file.length() !in 16..MAX_IMAGE_BYTES.toLong()) return false
        val bytes = runCatching {
            file.inputStream().use { it.readNBytes(MAX_IMAGE_BYTES + 1) }
        }.getOrNull() ?: return false
        if (bytes.size > MAX_IMAGE_BYTES || !supportedImageSignature(bytes)) return false
        return decodeBounded(bytes)?.let { bitmap -> bitmap.recycle(); true } ?: false
    }

    private fun validAssets(
        directory: File,
        suffix: String,
        limit: Int,
        validator: (File) -> Boolean,
    ): Set<StableKey> = Collections.unmodifiableSet(
        boundedFiles(directory, limit).mapNotNull { file ->
            parseAssetFile(file, suffix)?.takeIf { validator(file) }
        }.toCollection(LinkedHashSet()),
    )

    private fun writePrivateAtomically(target: File, bytes: ByteArray): Boolean {
        target.parentFile?.mkdirs()
        val pending = File(target.parentFile, target.name + ".pending")
        return try {
            FileOutputStream(pending).use { output ->
                output.write(bytes)
                output.fd.sync()
            }
            pending.renameTo(target)
        } catch (_: IOException) {
            false
        } catch (_: SecurityException) {
            false
        } finally {
            pending.delete()
        }
    }

    private fun writeBitmapAtomically(target: File, bitmap: Bitmap): Boolean {
        target.parentFile?.mkdirs()
        val pending = File(target.parentFile, target.name + ".pending")
        return try {
            FileOutputStream(pending).use { output ->
                if (!bitmap.compress(Bitmap.CompressFormat.PNG, 100, output)) return false
                output.fd.sync()
            }
            pending.length() in 1..MAX_IMAGE_BYTES.toLong() && pending.renameTo(target)
        } catch (_: IOException) {
            false
        } catch (_: SecurityException) {
            false
        } finally {
            pending.delete()
        }
    }

    private fun representativeColor(bitmap: Bitmap): ArgbColor {
        val sample = scaled(bitmap, 64)
        try {
            val pixels = IntArray(sample.width * sample.height)
            sample.getPixels(pixels, 0, sample.width, 0, 0, sample.width, sample.height)
            val count = pixels.size.coerceAtLeast(1)
            val red = pixels.sumOf { (it shr 16) and 0xff }.toLong() / count
            val green = pixels.sumOf { (it shr 8) and 0xff }.toLong() / count
            val blue = pixels.sumOf { it and 0xff }.toLong() / count
            return ArgbColor.of(0xff000000L or (red shl 16) or (green shl 8) or blue)
        } finally {
            if (sample !== bitmap) sample.recycle()
        }
    }

    private fun scaled(bitmap: Bitmap, maximum: Int): Bitmap {
        val largest = maxOf(bitmap.width, bitmap.height)
        if (largest <= maximum) return bitmap
        val scale = maximum.toFloat() / largest
        return Bitmap.createScaledBitmap(
            bitmap,
            (bitmap.width * scale).toInt().coerceAtLeast(1),
            (bitmap.height * scale).toInt().coerceAtLeast(1),
            true,
        )
    }

    private fun crop(bitmap: Bitmap, crop: WallpaperCrop): Bitmap {
        val left = (bitmap.width * crop.left).toInt().coerceIn(0, bitmap.width - 1)
        val top = (bitmap.height * crop.top).toInt().coerceIn(0, bitmap.height - 1)
        val right = (bitmap.width * crop.right).toInt().coerceIn(left + 1, bitmap.width)
        val bottom = (bitmap.height * crop.bottom).toInt().coerceIn(top + 1, bitmap.height)
        return Bitmap.createBitmap(bitmap, left, top, right - left, bottom - top)
    }

    private fun assetId(prefix: String): StableKey {
        val bytes = ByteArray(16).also(random::nextBytes)
        return StableKey.parse(prefix + "-" + bytes.joinToString("") { "%02x".format(it) })
    }

    private fun privateFile(directory: File, id: StableKey, suffix: String): File =
        File(directory, id.value + suffix)

    private fun hasCapacity(directory: File, maximum: Int): Boolean =
        boundedFiles(directory, maximum).size < maximum

    private fun boundedFiles(directory: File, maximum: Int): List<File> {
        if (maximum <= 0 || !directory.isDirectory) return emptyList()
        return try {
            Files.newDirectoryStream(directory.toPath()).use { stream ->
                val files = ArrayList<File>(maximum)
                val iterator = stream.iterator()
                while (files.size < maximum && iterator.hasNext()) {
                    files += iterator.next().toFile()
                }
                files.sortedBy(File::getName)
            }
        } catch (_: IOException) {
            emptyList()
        } catch (_: SecurityException) {
            emptyList()
        } catch (_: RuntimeException) {
            emptyList()
        }
    }

    private fun parseAssetFile(file: File, suffix: String): StableKey? {
        if (!file.name.endsWith(suffix)) return null
        return runCatching { StableKey.parse(file.name.removeSuffix(suffix)) }.getOrNull()
    }

    private fun supportedImageSignature(bytes: ByteArray): Boolean =
        bytes.size >= 12 && (
            bytes.copyOfRange(0, 8).contentEquals(PNG_SIGNATURE) ||
                bytes[0] == 0xff.toByte() && bytes[1] == 0xd8.toByte() && bytes[2] == 0xff.toByte() ||
                String(bytes, 0, 4, Charsets.US_ASCII) == "RIFF" &&
                String(bytes, 8, 4, Charsets.US_ASCII) == "WEBP"
            )

    private companion object {
        const val FONT_SUFFIX = ".font"
        const val IMAGE_SUFFIX = ".image"
        const val PREVIEW_SUFFIX = ".preview"
        const val MAX_FONT_ASSETS = 64
        const val MAX_IMAGE_ASSETS = 64
        const val MAX_PREVIEW_ASSETS = 128
        const val MAX_DELETE_ASSETS = 256
        const val MAX_PENDING_FILES = 8
        private const val MAX_PRESERVED_BYTES = 16 * 1024L
        private val RESTORE_STAGING_NAME = Regex(
            "restore-[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}",
        )
        const val MAX_BACKUP_ASSETS = MAX_FONT_ASSETS + MAX_IMAGE_ASSETS + MAX_PREVIEW_ASSETS
        const val MAX_BACKUP_ASSET_BYTES = 64L * 1024L * 1024L
        val FONT_MIME_TYPES = setOf(
            "font/ttf", "font/otf", "application/x-font-ttf", "application/x-font-opentype",
        )
        val IMAGE_MIME_TYPES = setOf("image/png", "image/jpeg", "image/webp")
        val PNG_SIGNATURE = byteArrayOf(
            0x89.toByte(), 0x50, 0x4e, 0x47, 0x0d, 0x0a, 0x1a, 0x0a,
        )
    }
}

internal suspend fun <T> runBoundedThemeIo(
    timeoutMillis: Long,
    fallback: T,
    block: () -> T,
): T = try {
    withTimeout(timeoutMillis) { runInterruptible(Dispatchers.IO, block) }
} catch (_: kotlinx.coroutines.TimeoutCancellationException) {
    fallback
} catch (cancelled: CancellationException) {
    throw cancelled
} catch (_: Exception) {
    fallback
}
