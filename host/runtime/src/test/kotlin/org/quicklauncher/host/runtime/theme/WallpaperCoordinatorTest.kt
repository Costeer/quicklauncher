package org.quicklauncher.host.runtime.theme

import java.io.IOException
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Test
import org.quicklauncher.contracts.domain.StableKey

class WallpaperCoordinatorTest {
    @Test
    fun `target confirmation executes exactly once after current revalidation`() = runTest {
        val platform = FakeWallpaperPlatform()
        val coordinator = WallpaperCoordinator(platform)
        val preview = requireNotNull(
            coordinator.preview(StableKey.parse("image-one"), WallpaperCrop(0f, 0f, 1f, 1f)),
        )
        val command = WallpaperCommand(preview.previewId, preview.generation, WallpaperTarget.BOTH, true)

        assertEquals(WallpaperResult.Success, coordinator.execute(command))
        assertEquals(WallpaperResult.Stale, coordinator.execute(command))
        assertEquals(listOf(WallpaperTarget.BOTH), platform.applied)
        assertEquals(1, platform.previewValidations)
    }

    @Test
    fun `cancellation stale preview denial and failure cause no unintended apply`() = runTest {
        val platform = FakeWallpaperPlatform()
        val coordinator = WallpaperCoordinator(platform)
        val preview = requireNotNull(
            coordinator.preview(StableKey.parse("image-two"), WallpaperCrop(0f, 0f, 1f, 1f)),
        )
        assertEquals(
            WallpaperResult.Cancelled,
            coordinator.execute(
                WallpaperCommand(preview.previewId, preview.generation, WallpaperTarget.HOME, false),
            ),
        )
        assertEquals(emptyList<WallpaperTarget>(), platform.applied)
        assertEquals(WallpaperResult.Cancelled, coordinator.cancel(preview.previewId))
        assertEquals(
            WallpaperResult.Stale,
            coordinator.execute(
                WallpaperCommand(preview.previewId, preview.generation, WallpaperTarget.HOME, true),
            ),
        )

        val denied = requireNotNull(
            coordinator.preview(StableKey.parse("image-three"), WallpaperCrop(0f, 0f, 1f, 1f)),
        )
        platform.supported = false
        assertEquals(
            WallpaperResult.Unavailable,
            coordinator.execute(
                WallpaperCommand(denied.previewId, denied.generation, WallpaperTarget.LOCK, true),
            ),
        )
        assertEquals(emptyList<WallpaperTarget>(), platform.applied)
    }

    @Test
    fun `revoked ownership stale preview denial and recoverable failure stay typed`() = runTest {
        val platform = FakeWallpaperPlatform()
        val coordinator = WallpaperCoordinator(platform)
        val stale = requireNotNull(
            coordinator.preview(StableKey.parse("image-stale"), WallpaperCrop(0f, 0f, 1f, 1f)),
        )
        platform.owned = false
        assertEquals(
            WallpaperResult.Stale,
            coordinator.execute(WallpaperCommand(stale.previewId, stale.generation, WallpaperTarget.HOME, true)),
        )
        assertEquals(emptyList<WallpaperTarget>(), platform.applied)

        platform.owned = true
        val denied = requireNotNull(
            coordinator.preview(StableKey.parse("image-denied"), WallpaperCrop(0f, 0f, 1f, 1f)),
        )
        platform.result = WallpaperPlatformResult.Denied
        assertEquals(
            WallpaperResult.Denied,
            coordinator.execute(WallpaperCommand(denied.previewId, denied.generation, WallpaperTarget.LOCK, true)),
        )

        val failed = requireNotNull(
            coordinator.preview(StableKey.parse("image-failed"), WallpaperCrop(0f, 0f, 1f, 1f)),
        )
        platform.result = WallpaperPlatformResult.RecoverableFailure
        assertEquals(
            WallpaperResult.RecoverableFailure,
            coordinator.execute(WallpaperCommand(failed.previewId, failed.generation, WallpaperTarget.BOTH, true)),
        )
    }

    @Test
    fun `process recreation and checked platform failures never apply stale wallpaper`() = runTest {
        val platform = FakeWallpaperPlatform()
        val first = WallpaperCoordinator(platform)
        val preview = requireNotNull(
            first.preview(StableKey.parse("image-recreated"), WallpaperCrop(0f, 0f, 1f, 1f)),
        )
        first.close()

        val recreated = WallpaperCoordinator(platform)
        val command = WallpaperCommand(preview.previewId, preview.generation, WallpaperTarget.HOME, true)
        assertEquals(WallpaperResult.Stale, recreated.execute(command))
        assertEquals(emptyList<WallpaperTarget>(), platform.applied)

        val current = requireNotNull(
            recreated.preview(StableKey.parse("image-io-failure"), WallpaperCrop(0f, 0f, 1f, 1f)),
        )
        platform.supportFailure = IOException("synthetic platform failure")
        assertEquals(
            WallpaperResult.RecoverableFailure,
            recreated.execute(
                WallpaperCommand(current.previewId, current.generation, WallpaperTarget.BOTH, true),
            ),
        )
        assertEquals(emptyList<WallpaperTarget>(), platform.applied)
    }

    @Test
    fun `platform cancellation is returned as a typed cancellation`() = runTest {
        val platform = FakeWallpaperPlatform()
        val coordinator = WallpaperCoordinator(platform)
        val preview = requireNotNull(
            coordinator.preview(StableKey.parse("image-cancelled"), WallpaperCrop(0f, 0f, 1f, 1f)),
        )
        platform.applyFailure = kotlinx.coroutines.CancellationException("synthetic platform cancellation")

        assertEquals(
            WallpaperResult.Cancelled,
            coordinator.execute(
                WallpaperCommand(preview.previewId, preview.generation, WallpaperTarget.HOME, true),
            ),
        )
        assertEquals(emptyList<WallpaperTarget>(), platform.applied)
    }

    private class FakeWallpaperPlatform : WallpaperPlatform {
        var supported = true
        var owned = true
        var previewValidations = 0
        var result: WallpaperPlatformResult = WallpaperPlatformResult.Applied
        var supportFailure: Exception? = null
        var applyFailure: Exception? = null
        val applied = mutableListOf<WallpaperTarget>()

        override suspend fun isSupported(target: WallpaperTarget): Boolean {
            supportFailure?.let { throw it }
            return supported
        }
        override suspend fun validateOwnedImage(assetId: StableKey): Boolean = owned
        override suspend fun createPreview(assetId: StableKey, crop: WallpaperCrop): StableKey =
            StableKey.parse("wallpaper-preview")

        override suspend fun validatePreview(
            previewId: StableKey,
            assetId: StableKey,
            crop: WallpaperCrop,
        ): Boolean {
            previewValidations += 1
            return owned
        }

        override suspend fun apply(
            assetId: StableKey,
            crop: WallpaperCrop,
            target: WallpaperTarget,
        ): WallpaperPlatformResult {
            applyFailure?.let { throw it }
            applied += target
            return result
        }
    }
}
