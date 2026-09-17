package org.quicklauncher.host.platform.theme

import android.provider.DocumentsContract
import java.io.File
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.quicklauncher.host.runtime.theme.FontImportResult
import org.quicklauncher.host.runtime.theme.ImageImportResult
import org.quicklauncher.host.runtime.theme.WallpaperCoordinator
import org.quicklauncher.host.runtime.theme.WallpaperCrop
import org.quicklauncher.host.runtime.theme.WallpaperResult

@RunWith(AndroidJUnit4::class)
class AndroidThemeAssetStoreInstrumentedTest {
    @Test
    fun imageImportRecreationPreviewAndCleanupUseOnlyPrivateOpaqueAssets() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().context
        val uri = DocumentsContract.buildDocumentUri(
            PhaseSevenFixtureDocumentsProvider.AUTHORITY,
            PhaseSevenFixtureDocumentsProvider.IMAGE_DOCUMENT,
        )
        val first = AndroidThemeAssetStore(context)

        val imported = first.importImage(uri, retainSource = true) as ImageImportResult.Imported
        assertEquals(32, imported.metadata.width)
        assertEquals(24, imported.metadata.height)
        assertNotNull(first.loadPreview(imported.metadata.previewId))
        assertTrue(imported.metadata.assetId in first.imageAssets())

        val recreated = AndroidThemeAssetStore(context)
        assertTrue(imported.metadata.assetId in recreated.imageAssets())
        recreated.deleteUnreferenced(emptySet())
        assertFalse(imported.metadata.assetId in recreated.imageAssets())
        assertEquals(null, recreated.loadPreview(imported.metadata.previewId))
    }

    @Test
    fun cancellationMalformedFontAndStaleWallpaperNeverCallWallpaperApi() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().context
        val assets = AndroidThemeAssetStore(context)
        val malformed = DocumentsContract.buildDocumentUri(
            PhaseSevenFixtureDocumentsProvider.AUTHORITY,
            PhaseSevenFixtureDocumentsProvider.MALFORMED_DOCUMENT,
        )
        assertEquals(FontImportResult.Cancelled, assets.importFont(null))
        assertTrue(assets.importFont(malformed) is FontImportResult.Malformed)

        val coordinator = WallpaperCoordinator(assets)
        val missing = org.quicklauncher.contracts.domain.StableKey.parse("missing-owned-image")
        assertEquals(null, coordinator.preview(missing, WallpaperCrop(0f, 0f, 1f, 1f)))
        assertEquals(
            WallpaperResult.Cancelled,
            coordinator.cancel(org.quicklauncher.contracts.domain.StableKey.parse("missing-preview")),
        )
        coordinator.close()
    }

    @Test
    fun revokedDocumentAccessReturnsTypedDenial() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().context
        val revoked = DocumentsContract.buildDocumentUri(
            PhaseSevenFixtureDocumentsProvider.AUTHORITY,
            PhaseSevenFixtureDocumentsProvider.REVOKED_DOCUMENT,
        )

        assertEquals(FontImportResult.Denied, AndroidThemeAssetStore(context).importFont(revoked))
    }

    @Test
    fun fontPrivateCopySurvivesRecreationAndCorruptionFallsBackBeforeCleanup() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().context
        val uri = DocumentsContract.buildDocumentUri(
            PhaseSevenFixtureDocumentsProvider.AUTHORITY,
            PhaseSevenFixtureDocumentsProvider.FONT_DOCUMENT,
        )
        val first = AndroidThemeAssetStore(context)
        val imported = first.importFont(uri) as FontImportResult.Imported

        assertTrue(imported.metadata.assetId in first.fontAssets())
        assertNotNull(first.loadTypeface(imported.metadata.assetId, 400, false))

        val recreated = AndroidThemeAssetStore(context)
        assertTrue(imported.metadata.assetId in recreated.fontAssets())
        val privateCopy = File(
            context.filesDir,
            "theme-assets/fonts/${imported.metadata.assetId.value}.font",
        )
        privateCopy.writeBytes(ByteArray(64))

        assertEquals(null, recreated.loadTypeface(imported.metadata.assetId, 400, false))
        assertFalse(imported.metadata.assetId in recreated.fontAssets())
        recreated.deleteUnreferenced(emptySet())
        assertFalse(privateCopy.exists())
    }

    @Test
    fun imageDerivedImportAppliesOrientationAndRetainsOnlyBoundedPreview() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().context
        val uri = DocumentsContract.buildDocumentUri(
            PhaseSevenFixtureDocumentsProvider.AUTHORITY,
            PhaseSevenFixtureDocumentsProvider.ORIENTED_IMAGE_DOCUMENT,
        )
        val assets = AndroidThemeAssetStore(context)
        val imported = assets.importImage(uri, retainSource = false) as ImageImportResult.Imported

        assertEquals(24, imported.metadata.width)
        assertEquals(32, imported.metadata.height)
        assertEquals(imported.metadata.previewId, imported.metadata.assetId)
        assertNotNull(assets.loadPreview(imported.metadata.previewId))
        assertFalse(assets.validateOwnedImage(imported.metadata.assetId))

        assets.deleteUnreferenced(emptySet())
        assertEquals(null, assets.loadPreview(imported.metadata.previewId))
    }

    @Test
    fun imageImportReturnsTypedResultsForEveryRejectedInputClass() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().context
        val assets = AndroidThemeAssetStore(context)
        fun uri(document: String) = DocumentsContract.buildDocumentUri(
            PhaseSevenFixtureDocumentsProvider.AUTHORITY,
            document,
        )

        assertEquals(ImageImportResult.Cancelled, assets.importImage(null, retainSource = true))
        assertEquals(
            ImageImportResult.Unsupported,
            assets.importImage(
                uri(PhaseSevenFixtureDocumentsProvider.UNSUPPORTED_DOCUMENT),
                retainSource = true,
            ),
        )
        assertEquals(
            ImageImportResult.Malformed,
            assets.importImage(
                uri(PhaseSevenFixtureDocumentsProvider.MALFORMED_IMAGE_DOCUMENT),
                retainSource = true,
            ),
        )
        assertEquals(
            ImageImportResult.Malformed,
            assets.importImage(
                uri(PhaseSevenFixtureDocumentsProvider.OVERSIZED_DIMENSION_IMAGE_DOCUMENT),
                retainSource = true,
            ),
        )
        assertEquals(
            ImageImportResult.Oversized,
            assets.importImage(
                uri(PhaseSevenFixtureDocumentsProvider.OVERSIZED_IMAGE_DOCUMENT),
                retainSource = true,
            ),
        )
    }
}
