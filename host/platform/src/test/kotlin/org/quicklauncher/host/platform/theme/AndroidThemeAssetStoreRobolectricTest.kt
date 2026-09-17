package org.quicklauncher.host.platform.theme

import android.graphics.Bitmap
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.UUID
import kotlin.system.measureTimeMillis
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.quicklauncher.contracts.domain.StableKey
import org.quicklauncher.host.runtime.theme.WallpaperCrop
import org.quicklauncher.host.runtime.theme.WallpaperPlatformResult
import org.quicklauncher.host.runtime.theme.WallpaperTarget
import org.robolectric.annotation.Config

@RunWith(AndroidJUnit4::class)
@Config(sdk = [35])
class AndroidThemeAssetStoreRobolectricTest {
    @Test
    fun `bounded theme IO interrupts a blocking operation`() {
        runBlocking {
            var result = ""
            val elapsed = measureTimeMillis {
                result = runBoundedThemeIo(50L, "timed-out") {
                    Thread.sleep(10_000L)
                    "completed"
                }
            }

            assertEquals("timed-out", result)
            assertTrue(elapsed < 2_000L)
        }
    }

    @Test
    fun `missing private wallpaper image fails without changing platform state`() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val assets = AndroidThemeAssetStore(context)
        val missing = StableKey.parse("synthetic-missing-wallpaper")
        val crop = WallpaperCrop(0f, 0f, 1f, 1f)

        assertFalse(assets.validateOwnedImage(missing))
        assertFalse(assets.validatePreview(missing, missing, crop))
        assertEquals(
            WallpaperPlatformResult.RecoverableFailure,
            assets.apply(missing, crop, WallpaperTarget.HOME),
        )
    }

    @Test
    fun `backup image stage stays private until commit and discard rolls it back`() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val id = StableKey.parse("restore-image-${UUID.randomUUID().toString().replace("-", "")}")
        val assets = AndroidThemeAssetStore(context)
        val entry = ThemeAssetBackupEntry(id, ThemeAssetBackupKind.IMAGE, testPng())

        val staged = requireNotNull(assets.stageBackupAssets(listOf(entry)))
        assertFalse(id in assets.imageAssets())

        assertTrue(staged.commit())
        assertTrue(id in AndroidThemeAssetStore(context).imageAssets())

        staged.discard()
        assertFalse(id in AndroidThemeAssetStore(context).imageAssets())
    }

    @Test
    fun `discarded backup image stage never becomes visible`() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val id = StableKey.parse("discard-image-${UUID.randomUUID().toString().replace("-", "")}")
        val assets = AndroidThemeAssetStore(context)
        val staged = requireNotNull(
            assets.stageBackupAssets(
                listOf(ThemeAssetBackupEntry(id, ThemeAssetBackupKind.IMAGE, testPng())),
            ),
        )

        staged.discard()

        assertFalse(id in AndroidThemeAssetStore(context).imageAssets())
    }

    @Test
    fun `recreation cleanup removes an interrupted backup stage`() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val id = StableKey.parse("interrupted-image-${UUID.randomUUID().toString().replace("-", "")}")
        val assets = AndroidThemeAssetStore(context)
        requireNotNull(
            assets.stageBackupAssets(
                listOf(ThemeAssetBackupEntry(id, ThemeAssetBackupKind.IMAGE, testPng())),
            ),
        )
        val root = File(context.filesDir, "theme-assets")
        assertTrue(root.listFiles().orEmpty().any { it.name.startsWith("restore-") })

        val recreated = AndroidThemeAssetStore(context)
        recreated.deleteUnreferenced(emptySet())

        assertFalse(id in recreated.imageAssets())
        assertFalse(root.listFiles().orEmpty().any { it.name.startsWith("restore-") })
    }

    @Test
    fun `opaque future backup asset survives startup until it becomes a known reference`() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val id = StableKey.parse("future-image-${UUID.randomUUID().toString().replace("-", "")}")
        val identity = ThemeAssetBackupIdentity(id, ThemeAssetBackupKind.IMAGE)
        val assets = AndroidThemeAssetStore(context)
        val staged = requireNotNull(
            assets.stageBackupAssets(
                listOf(ThemeAssetBackupEntry(id, ThemeAssetBackupKind.IMAGE, testPng())),
                setOf(identity),
            ),
        )
        assertTrue(staged.commit())

        val recreated = AndroidThemeAssetStore(context)
        recreated.deleteUnreferenced(emptySet())
        assertTrue(id in recreated.imageAssets())

        recreated.deleteUnreferenced(setOf(id))
        recreated.deleteUnreferenced(emptySet())
        assertFalse(id in recreated.imageAssets())
    }

    private fun testPng(): ByteArray {
        val bitmap = Bitmap.createBitmap(2, 2, Bitmap.Config.ARGB_8888)
        return try {
            ByteArrayOutputStream().use { output ->
                check(bitmap.compress(Bitmap.CompressFormat.PNG, 100, output))
                output.toByteArray()
            }
        } finally {
            bitmap.recycle()
        }
    }
}
