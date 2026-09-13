package org.quicklauncher.host.runtime.catalog

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertTrue
import org.junit.Test
import org.quicklauncher.contracts.domain.ActivityName
import org.quicklauncher.contracts.domain.AppActivityIdentity
import org.quicklauncher.contracts.domain.PackageName
import org.quicklauncher.contracts.domain.ProfileSerial

@OptIn(ExperimentalCoroutinesApi::class)
class DefaultAppCatalogTest {
    @Test
    fun `refresh applies visibility and label overrides then sorts deterministically`() = runTest {
        val personal = profile(0, AppProfileKind.PERSONAL)
        val work = profile(10, AppProfileKind.WORK)
        val alpha = activity(personal.serial, "org.example.alpha", "Zebra")
        val beta = activity(personal.serial, "org.example.beta", "beta")
        val workApp = activity(work.serial, "org.example.work", "Alpha")
        val platform = FakeAppPlatform(
            AppPlatformSnapshot(
                profiles = listOf(work, personal),
                activities = listOf(alpha, workApp, beta),
            ),
        )
        val overrides = FakeOverrideSource(
            listOf(
                AppCatalogOverride(alpha.identity, customLabel = "aardvark"),
                AppCatalogOverride(beta.identity, collectionVisible = false),
            ),
        )
        val catalog = DefaultAppCatalog(platform, overrides, backgroundScope)

        catalog.refresh()

        assertEquals(AppCatalogStatus.READY, catalog.state.value.status)
        assertEquals(listOf(personal.serial, work.serial), catalog.state.value.profiles.map { it.serial })
        assertEquals(
            listOf("aardvark", "Alpha"),
            catalog.state.value.profiles
                .flatMap { it.apps }
                .filter { it.collectionVisible }
                .map { it.label },
        )
        assertEquals(null, catalog.state.value.profiles.first().badgeText)
        assertEquals("Work", catalog.state.value.profiles.last().badgeText)
        catalog.close()
    }

    @Test
    fun `catalog snapshots and icon bytes cannot be mutated by callers`() = runTest {
        val profile = profile(0, AppProfileKind.PERSONAL)
        val sourceBytes = byteArrayOf(1, 2, 3)
        val platform = FakeAppPlatform(
            AppPlatformSnapshot(
                profiles = listOf(profile),
                activities = listOf(activity(profile.serial, "org.example.alpha", "Alpha", sourceBytes)),
            ),
        )
        val catalog = DefaultAppCatalog(platform, FakeOverrideSource(), backgroundScope)

        catalog.refresh()
        sourceBytes[0] = 99
        val snapshot = catalog.state.value
        val firstRead = snapshot.profiles.single().apps.single().icon.bytes()
        firstRead[1] = 99
        val secondRead = snapshot.profiles.single().apps.single().icon.bytes()

        assertEquals(listOf<Byte>(1, 2, 3), secondRead.toList())
        assertNotSame(firstRead, secondRead)
        assertTrue(runCatching { (snapshot.profiles as MutableList<*>).clear() }.isFailure)
        assertTrue(runCatching { (snapshot.profiles.single().apps as MutableList<*>).clear() }.isFailure)
        catalog.close()
    }

    @Test
    fun `platform invalidation refreshes the catalog once the new snapshot is available`() = runTest {
        val profile = profile(0, AppProfileKind.PERSONAL)
        val platform = FakeAppPlatform(AppPlatformSnapshot(listOf(profile), emptyList()))
        val catalogScope = CoroutineScope(StandardTestDispatcher(testScheduler))
        val catalog = DefaultAppCatalog(platform, FakeOverrideSource(), catalogScope)
        advanceUntilIdle()
        platform.nextSnapshot = AppPlatformSnapshot(
            profiles = listOf(profile),
            activities = listOf(activity(profile.serial, "org.example.alpha", "Alpha")),
        )

        platform.invalidate()
        advanceUntilIdle()

        assertEquals("Alpha", catalog.state.value.profiles.single().apps.single().label)
        catalog.close()
    }

    @Test
    fun `platform failure clears previously available profile metadata`() = runTest {
        val work = profile(10, AppProfileKind.WORK)
        val platform = FakeAppPlatform(
            AppPlatformSnapshot(
                profiles = listOf(work),
                activities = listOf(activity(work.serial, "org.example.work", "Work")),
            ),
        )
        val catalog = DefaultAppCatalog(platform, FakeOverrideSource(), backgroundScope)
        catalog.refresh()
        assertEquals("Work", catalog.state.value.profiles.single().apps.single().label)
        platform.snapshotFailure = IllegalStateException("profile unavailable")

        catalog.refresh()

        assertEquals(AppCatalogStatus.ERROR, catalog.state.value.status)
        assertTrue(catalog.state.value.profiles.isEmpty())
        assertEquals("app_catalog_refresh_failed", catalog.state.value.errorCode)
        catalog.close()
    }

    @Test
    fun `cold override failure fails closed instead of exposing unfiltered platform apps`() = runTest {
        val personal = profile(0, AppProfileKind.PERSONAL)
        val alpha = activity(personal.serial, "org.example.alpha", "Alpha")
        val platform = FakeAppPlatform(AppPlatformSnapshot(listOf(personal), listOf(alpha)))
        val overrides = FakeOverrideSource(failure = IllegalStateException("store unavailable"))
        val catalog = DefaultAppCatalog(platform, overrides, backgroundScope)

        catalog.refresh()

        assertEquals(AppCatalogStatus.ERROR, catalog.state.value.status)
        val recoveryApp = catalog.state.value.profiles.single().apps.single()
        assertEquals("Alpha", recoveryApp.label)
        assertEquals(false, recoveryApp.collectionVisible)
        assertEquals("app_catalog_overrides_unavailable", catalog.state.value.errorCode)
        catalog.close()
    }

    @Test
    fun `hidden activity stays out of collections but remains launchable from Settings recovery`() = runTest {
        val personal = profile(0, AppProfileKind.PERSONAL)
        val lockedWork = profile(10, AppProfileKind.WORK, quiet = true, unlocked = false)
        val hidden = activity(personal.serial, "org.example.hidden", "Hidden")
        val locked = activity(lockedWork.serial, "org.example.work", "Work")
        val visible = activity(personal.serial, "org.example.visible", "Visible")
        val platform = FakeAppPlatform(
            AppPlatformSnapshot(listOf(personal, lockedWork), listOf(hidden, visible)),
        )
        val catalog = DefaultAppCatalog(
            platform,
            FakeOverrideSource(listOf(AppCatalogOverride(hidden.identity, collectionVisible = false))),
            backgroundScope,
        )
        catalog.refresh()

        assertEquals(
            false,
            catalog.state.value.profiles.first().apps.single { it.identity == hidden.identity }.collectionVisible,
        )
        assertEquals(AppLaunchResult.Launched, catalog.launch(hidden.identity))
        assertEquals(AppLaunchResult.ProfileLocked, catalog.launch(locked.identity))
        assertEquals(AppLaunchResult.Launched, catalog.launch(visible.identity))
        assertEquals(listOf(hidden.identity, visible.identity), platform.launches)
        catalog.close()
    }

    @Test
    fun `close cancels refresh collection and releases the platform`() = runTest {
        val profile = profile(0, AppProfileKind.PERSONAL)
        val platform = FakeAppPlatform(AppPlatformSnapshot(listOf(profile), emptyList()))
        val catalogScope = CoroutineScope(StandardTestDispatcher(testScheduler))
        val catalog = DefaultAppCatalog(platform, FakeOverrideSource(), catalogScope)
        advanceUntilIdle()
        val refreshesBeforeClose = platform.snapshotReads

        catalog.close()
        platform.invalidate()
        advanceUntilIdle()

        assertTrue(platform.closed)
        assertEquals(refreshesBeforeClose, platform.snapshotReads)
        assertEquals(AppLaunchResult.Closed, catalog.launch(activity(profile.serial, "org.example.x", "X").identity))
    }

    private fun profile(
        serial: Long,
        kind: AppProfileKind,
        quiet: Boolean = false,
        unlocked: Boolean = true,
    ): AppPlatformProfile = AppPlatformProfile(ProfileSerial.of(serial), kind, quiet, unlocked)

    private fun activity(
        profile: ProfileSerial,
        packageName: String,
        label: String,
        icon: ByteArray = byteArrayOf(1),
    ): AppPlatformActivity = AppPlatformActivity(
        identity = AppActivityIdentity(
            profile = profile,
            packageName = PackageName.parse(packageName),
            activityName = ActivityName.parse("$packageName.MainActivity"),
        ),
        label = label,
        icon = AppIcon.of(icon),
    )

    private class FakeOverrideSource(
        var overrides: List<AppCatalogOverride> = emptyList(),
        var failure: RuntimeException? = null,
    ) : AppCatalogOverrideSource {
        override suspend fun read(): List<AppCatalogOverride> {
            failure?.let { throw it }
            return overrides
        }
    }

    private class FakeAppPlatform(
        var nextSnapshot: AppPlatformSnapshot,
    ) : AppPlatform {
        private val changes = MutableSharedFlow<AppPlatformInvalidation>(extraBufferCapacity = 1)

        override val invalidations: Flow<AppPlatformInvalidation> = changes
        val launches = mutableListOf<AppActivityIdentity>()
        var snapshotReads = 0
        var closed = false
        var snapshotFailure: RuntimeException? = null

        override suspend fun snapshot(): AppPlatformSnapshot {
            snapshotReads += 1
            snapshotFailure?.let { throw it }
            return nextSnapshot
        }

        override suspend fun launch(identity: AppActivityIdentity): AppLaunchResult {
            launches += identity
            return AppLaunchResult.Launched
        }

        fun invalidate() {
            changes.tryEmit(AppPlatformInvalidation.Changed)
        }

        override fun close() {
            closed = true
        }
    }
}
