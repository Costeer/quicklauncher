package org.quicklauncher.host.runtime.theme

import java.util.Collections
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.isActive
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.quicklauncher.contracts.domain.ArgbColor
import org.quicklauncher.contracts.domain.StableKey

data class FontAxisMetadata(
    val tag: String,
    val minimum: Float,
    val default: Float,
    val maximum: Float,
) {
    init {
        require(tag in SUPPORTED_FONT_AXES) { "Unsupported variable-font axis" }
        require(minimum.isFinite() && default.isFinite() && maximum.isFinite())
        require(minimum <= default && default <= maximum)
    }
}

class ImportedFontMetadata(
    val assetId: StableKey,
    val familyCount: Int,
    val faceCount: Int,
    axes: Collection<FontAxisMetadata>,
    val weight: Int,
    val italic: Boolean,
) {
    val axes: List<FontAxisMetadata> = Collections.unmodifiableList(ArrayList(axes))

    init {
        require(familyCount in 1..MAX_FONT_FAMILIES)
        require(faceCount in 1..MAX_FONT_FACES)
        require(this.axes.size <= MAX_FONT_AXES)
        require(weight in 1..1000)
    }
}

sealed interface FontImportResult {
    data class Imported(val metadata: ImportedFontMetadata) : FontImportResult
    data object Cancelled : FontImportResult
    data object Denied : FontImportResult
    data object Unsupported : FontImportResult
    data object Malformed : FontImportResult
    data object Oversized : FontImportResult
    data object TimedOut : FontImportResult
    data object Failed : FontImportResult
}

data class ImportedImageMetadata(
    val assetId: StableKey,
    val previewId: StableKey,
    val width: Int,
    val height: Int,
    val representativeColor: ArgbColor,
    val seed: ArgbColor,
) {
    init {
        require(width in 1..MAX_IMAGE_DIMENSION && height in 1..MAX_IMAGE_DIMENSION)
        require(width.toLong() * height <= MAX_IMAGE_PIXELS)
    }
}

sealed interface ImageImportResult {
    data class Imported(val metadata: ImportedImageMetadata) : ImageImportResult
    data object Cancelled : ImageImportResult
    data object Denied : ImageImportResult
    data object Unsupported : ImageImportResult
    data object Malformed : ImageImportResult
    data object Oversized : ImageImportResult
    data object TimedOut : ImageImportResult
    data object Failed : ImageImportResult
}

interface ThemeAssetInventory {
    suspend fun fontAssets(): Set<StableKey>
    suspend fun imageAssets(): Set<StableKey>
    suspend fun deleteUnreferenced(referenced: Set<ThemeAssetIdentity>)
}

enum class ThemeAssetKind { FONT, IMAGE, PREVIEW }

data class ThemeAssetIdentity(
    val id: StableKey,
    val kind: ThemeAssetKind,
)

enum class WallpaperTarget {
    HOME,
    LOCK,
    BOTH,
}

data class WallpaperCrop(
    val left: Float,
    val top: Float,
    val right: Float,
    val bottom: Float,
) {
    init {
        require(listOf(left, top, right, bottom).all(Float::isFinite))
        require(left in 0f..1f && top in 0f..1f && right in 0f..1f && bottom in 0f..1f)
        require(left < right && top < bottom)
    }
}

data class WallpaperPreview(
    val previewId: StableKey,
    val assetId: StableKey,
    val generation: Long,
    val crop: WallpaperCrop,
) {
    init {
        require(generation > 0L)
    }
}

data class WallpaperCommand(
    val previewId: StableKey,
    val generation: Long,
    val target: WallpaperTarget,
    val warningAcknowledged: Boolean,
)

sealed interface WallpaperResult {
    data object Success : WallpaperResult
    data object Cancelled : WallpaperResult
    data object Unavailable : WallpaperResult
    data object Stale : WallpaperResult
    data object Denied : WallpaperResult
    data object RecoverableFailure : WallpaperResult
}

sealed interface WallpaperPlatformResult {
    data object Applied : WallpaperPlatformResult
    data object Unavailable : WallpaperPlatformResult
    data object Denied : WallpaperPlatformResult
    data object RecoverableFailure : WallpaperPlatformResult
}

interface WallpaperPlatform {
    suspend fun isSupported(target: WallpaperTarget): Boolean
    suspend fun validateOwnedImage(assetId: StableKey): Boolean
    suspend fun createPreview(assetId: StableKey, crop: WallpaperCrop): StableKey?
    suspend fun validatePreview(previewId: StableKey, assetId: StableKey, crop: WallpaperCrop): Boolean
    suspend fun apply(assetId: StableKey, crop: WallpaperCrop, target: WallpaperTarget): WallpaperPlatformResult
}

class WallpaperCoordinator(
    private val platform: WallpaperPlatform,
) {
    private val mutex = Mutex()
    private val previews = linkedMapOf<StableKey, WallpaperPreview>()
    private val consumed = linkedSetOf<Pair<StableKey, Long>>()
    private var generation = 0L
    private val closed = AtomicBoolean(false)

    suspend fun preview(assetId: StableKey, crop: WallpaperCrop): WallpaperPreview? = mutex.withLock {
        try {
            check(!closed.get())
            if (!platform.validateOwnedImage(assetId)) return@withLock null
            val previewId = platform.createPreview(assetId, crop) ?: return@withLock null
            generation += 1L
            val preview = WallpaperPreview(previewId, assetId, generation, crop)
            previews[previewId] = preview
            while (previews.size > MAX_WALLPAPER_PREVIEWS) previews.remove(previews.keys.first())
            preview
        } catch (cancelled: CancellationException) {
            if (!currentCoroutineContext().isActive) throw cancelled
            null
        } catch (_: Exception) {
            null
        }
    }

    suspend fun execute(command: WallpaperCommand): WallpaperResult = mutex.withLock {
        try {
            if (closed.get()) return@withLock WallpaperResult.Cancelled
            val identity = command.previewId to command.generation
            if (identity in consumed) return@withLock WallpaperResult.Stale
            val preview = previews[command.previewId]
                ?.takeIf { it.generation == command.generation }
                ?: return@withLock WallpaperResult.Stale
            if (!command.warningAcknowledged) return@withLock WallpaperResult.Cancelled
            if (!platform.isSupported(command.target)) return@withLock WallpaperResult.Unavailable
            if (!platform.validateOwnedImage(preview.assetId)) return@withLock WallpaperResult.Stale
            if (!platform.validatePreview(preview.previewId, preview.assetId, preview.crop)) {
                return@withLock WallpaperResult.Stale
            }
            consumed += identity
            while (consumed.size > MAX_CONSUMED_WALLPAPER_COMMANDS) consumed.remove(consumed.first())
            previews.remove(command.previewId)
            when (platform.apply(preview.assetId, preview.crop, command.target)) {
                WallpaperPlatformResult.Applied -> WallpaperResult.Success
                WallpaperPlatformResult.Unavailable -> WallpaperResult.Unavailable
                WallpaperPlatformResult.Denied -> WallpaperResult.Denied
                WallpaperPlatformResult.RecoverableFailure -> WallpaperResult.RecoverableFailure
            }
        } catch (cancelled: CancellationException) {
            if (!currentCoroutineContext().isActive) throw cancelled
            WallpaperResult.Cancelled
        } catch (_: SecurityException) {
            WallpaperResult.Denied
        } catch (_: Exception) {
            WallpaperResult.RecoverableFailure
        }
    }

    suspend fun cancel(previewId: StableKey): WallpaperResult = mutex.withLock {
        previews.remove(previewId)
        WallpaperResult.Cancelled
    }

    suspend fun invalidateAll() = mutex.withLock { previews.clear() }

    fun close() {
        closed.set(true)
    }
}

const val MAX_FONT_BYTES: Int = 8 * 1024 * 1024
const val MAX_FONT_FAMILIES: Int = 4
const val MAX_FONT_FACES: Int = 8
const val MAX_FONT_AXES: Int = 16
const val MAX_IMAGE_BYTES: Int = 16 * 1024 * 1024
const val MAX_IMAGE_DIMENSION: Int = 8_192
const val MAX_IMAGE_PIXELS: Long = 24_000_000L
const val MAX_IMAGE_MEMORY_BYTES: Long = 96L * 1024 * 1024
const val MAX_PREVIEW_DIMENSION: Int = 1_024
const val MAX_WALLPAPER_PREVIEWS: Int = 4
const val MAX_CONSUMED_WALLPAPER_COMMANDS: Int = 128
val SUPPORTED_FONT_AXES: Set<String> = setOf("wght", "wdth", "ital", "slnt", "opsz")
