package org.quicklauncher.host.runtime.profile

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.quicklauncher.contracts.domain.ActivityName
import org.quicklauncher.contracts.domain.AppActivityIdentity
import org.quicklauncher.contracts.domain.PackageName
import org.quicklauncher.contracts.domain.ProfileSerial
import org.quicklauncher.host.runtime.catalog.AppIcon

@OptIn(ExperimentalCoroutinesApi::class)
class ProfileCoordinatorTest {
    @Test
    fun `private entry point policy survives refresh as immutable host state`() = runTest {
        val private = ProfileSerial.of(20)
        val platform = FakeProfilePlatform(
            profiles = listOf(
                ProfileRecord(
                    private,
                    ProfileKind.PRIVATE,
                    ProfileAvailability.LOCKED,
                    PrivateSpaceEntryPoint.HIDDEN,
                ),
            ),
        )
        val coordinator = DefaultProfileCoordinator(platform, backgroundScope)

        coordinator.refresh()

        assertEquals(
            PrivateSpaceEntryPoint.HIDDEN,
            coordinator.state.value.profile(private)?.privateSpaceEntryPoint,
        )
        coordinator.close()
    }

    @Test
    fun `work pause remains transitional until an availability callback`() = runTest {
        val work = ProfileSerial.of(10)
        val platform = FakeProfilePlatform(
            profiles = listOf(profile(work, ProfileKind.WORK, ProfileAvailability.AVAILABLE)),
        )
        val coordinator = DefaultProfileCoordinator(
            platform,
            backgroundScope,
            StandardTestDispatcher(testScheduler),
        )

        coordinator.refresh()
        assertEquals(WorkModeResult.Requested, coordinator.setWorkMode(work, enabled = false))
        assertEquals(ProfileTransition.PAUSING, coordinator.state.value.profile(work)?.transition)

        platform.profiles = listOf(profile(work, ProfileKind.WORK, ProfileAvailability.QUIET))
        platform.invalidations.emit(ProfileInvalidation.AvailabilityChanged(work))
        runCurrent()

        assertEquals(ProfileTransition.IDLE, coordinator.state.value.profile(work)?.transition)
        assertEquals(ProfileAvailability.QUIET, coordinator.state.value.profile(work)?.availability)
        coordinator.close()
    }

    @Test
    fun `private metadata is read only after validated unlock and is purged on lock callback`() =
        runTest {
            val private = ProfileSerial.of(20)
            val platform = FakeProfilePlatform(
                profiles = listOf(profile(private, ProfileKind.PRIVATE, ProfileAvailability.LOCKED)),
            )
            val coordinator = DefaultProfileCoordinator(
                platform,
                backgroundScope,
                StandardTestDispatcher(testScheduler),
            )

            coordinator.refresh()
            assertEquals(0, platform.privateReads)
            assertTrue(coordinator.state.value.privateSpace.items.isEmpty())

            platform.profiles = listOf(
                profile(private, ProfileKind.PRIVATE, ProfileAvailability.AVAILABLE),
            )
            platform.privateItems = listOf(privateItem(private, "Vault"))
            platform.invalidations.emit(ProfileInvalidation.AvailabilityChanged(private))
            runCurrent()
            assertEquals(listOf("Vault"), coordinator.state.value.privateSpace.items.map { it.label })
            assertEquals(1, platform.privateReads)

            platform.profiles = listOf(profile(private, ProfileKind.PRIVATE, ProfileAvailability.LOCKED))
            platform.invalidations.emit(ProfileInvalidation.AvailabilityChanged(private))
            runCurrent()
            assertTrue(coordinator.state.value.privateSpace.items.isEmpty())
            assertEquals(1, platform.privateReads)
            coordinator.close()
        }

    @Test
    fun `unresolved profile classification fails closed without private reads`() = runTest {
        val private = ProfileSerial.of(20)
        val platform = FakeProfilePlatform(
            profiles = listOf(profile(private, ProfileKind.PRIVATE, ProfileAvailability.AVAILABLE)),
        )
        platform.privateItems = listOf(privateItem(private, "Secret"))
        val coordinator = DefaultProfileCoordinator(
            platform,
            backgroundScope,
            StandardTestDispatcher(testScheduler),
        )
        coordinator.refresh()
        assertEquals(1, platform.privateReads)

        platform.profiles = emptyList()
        platform.invalidations.emit(ProfileInvalidation.AvailabilityChanged(private))
        runCurrent()

        assertTrue(coordinator.state.value.privateSpace.items.isEmpty())
        assertTrue(coordinator.state.value.profiles.isEmpty())
        coordinator.close()
    }

    @Test
    fun `private read failure clears prior metadata and fails closed`() = runTest {
        val private = ProfileSerial.of(20)
        val platform = FakeProfilePlatform(
            profiles = listOf(profile(private, ProfileKind.PRIVATE, ProfileAvailability.AVAILABLE)),
        ).apply { privateItems = listOf(privateItem(private, "Vault")) }
        val coordinator = DefaultProfileCoordinator(platform, backgroundScope)

        coordinator.refresh()
        assertEquals(listOf("Vault"), coordinator.state.value.privateSpace.items.map { it.label })

        platform.privateFailure = SecurityException("access revoked")
        coordinator.refresh()

        assertTrue(coordinator.state.value.privateSpace.items.isEmpty())
        assertEquals(ProfileAvailability.UNRESOLVED, coordinator.state.value.privateSpace.availability)
        assertEquals("PrivateProfileItem(redacted)", privateItem(private, "Vault").toString())
        coordinator.close()
    }

    @Test
    fun `locking private space purges metadata before the platform callback`() = runTest {
        val private = ProfileSerial.of(20)
        val platform = FakeProfilePlatform(
            profiles = listOf(profile(private, ProfileKind.PRIVATE, ProfileAvailability.AVAILABLE)),
        ).apply { privateItems = listOf(privateItem(private, "Vault")) }
        val coordinator = DefaultProfileCoordinator(platform, backgroundScope)
        coordinator.refresh()

        assertEquals(
            PrivateSpaceActionResult.Requested,
            coordinator.setPrivateSpaceLocked(locked = true),
        )

        assertTrue(coordinator.state.value.privateSpace.items.isEmpty())
        assertEquals(ProfileTransition.LOCKING, coordinator.state.value.privateSpace.transition)
        coordinator.close()
    }

    @Test
    fun `private lock purges metadata before a slow framework request completes`() = runTest {
        val private = ProfileSerial.of(20)
        val platform = FakeProfilePlatform(
            profiles = listOf(profile(private, ProfileKind.PRIVATE, ProfileAvailability.AVAILABLE)),
        ).apply {
            privateItems = listOf(privateItem(private, "Vault"))
            quietRequestStarted = CompletableDeferred()
            quietRequestRelease = CompletableDeferred()
        }
        val coordinator = DefaultProfileCoordinator(
            platform,
            backgroundScope,
            StandardTestDispatcher(testScheduler),
        )
        coordinator.refresh()

        val lock = async { coordinator.setPrivateSpaceLocked(locked = true) }
        runCurrent()
        platform.quietRequestStarted?.await()

        assertTrue(coordinator.state.value.privateSpace.items.isEmpty())
        assertEquals(ProfileTransition.LOCKING, coordinator.state.value.privateSpace.transition)
        assertTrue(!lock.isCompleted)

        platform.quietRequestRelease?.complete(Unit)
        assertEquals(PrivateSpaceActionResult.Requested, lock.await())
        coordinator.close()
    }

    @Test
    fun `rejected private lock remains purged and fails closed`() = runTest {
        val private = ProfileSerial.of(20)
        val platform = FakeProfilePlatform(
            profiles = listOf(profile(private, ProfileKind.PRIVATE, ProfileAvailability.AVAILABLE)),
        ).apply {
            privateItems = listOf(privateItem(private, "Vault"))
            quietResult = WorkModeRequestResult.DENIED
        }
        val coordinator = DefaultProfileCoordinator(platform, backgroundScope)
        coordinator.refresh()

        assertEquals(PrivateSpaceActionResult.Denied, coordinator.setPrivateSpaceLocked(locked = true))

        assertTrue(coordinator.state.value.privateSpace.items.isEmpty())
        assertEquals(ProfileAvailability.UNRESOLVED, coordinator.state.value.privateSpace.availability)
        assertEquals(ProfileTransition.IDLE, coordinator.state.value.privateSpace.transition)
        coordinator.close()
    }

    @Test
    fun `lock callback cannot let an in-flight metadata read republish private values`() = runTest {
        val private = ProfileSerial.of(20)
        val platform = FakeProfilePlatform(
            profiles = listOf(profile(private, ProfileKind.PRIVATE, ProfileAvailability.AVAILABLE)),
        ).apply {
            privateItems = listOf(privateItem(private, "Vault"))
            privateReadStarted = CompletableDeferred()
            privateReadRelease = CompletableDeferred()
        }
        val coordinator = DefaultProfileCoordinator(
            platform,
            backgroundScope,
            StandardTestDispatcher(testScheduler),
        )

        val refresh = async { coordinator.refresh() }
        runCurrent()
        platform.privateReadStarted?.await()

        platform.profiles = listOf(profile(private, ProfileKind.PRIVATE, ProfileAvailability.LOCKED))
        platform.invalidations.emit(ProfileInvalidation.AvailabilityChanged(private))
        runCurrent()
        assertTrue(coordinator.state.value.privateSpace.items.isEmpty())

        refresh.await()
        runCurrent()

        assertTrue(platform.privateReadCancelled)
        assertTrue(coordinator.state.value.privateSpace.items.isEmpty())
        assertEquals(ProfileAvailability.LOCKED, coordinator.state.value.privateSpace.availability)
        coordinator.close()
    }

    @Test
    fun `unresolved private profile rejects lock and unlock requests`() = runTest {
        val private = ProfileSerial.of(20)
        val platform = FakeProfilePlatform(
            profiles = listOf(profile(private, ProfileKind.PRIVATE, ProfileAvailability.AVAILABLE)),
        ).apply { privateFailure = SecurityException("access revoked") }
        val coordinator = DefaultProfileCoordinator(platform, backgroundScope)
        coordinator.refresh()

        assertEquals(
            PrivateSpaceActionResult.Unavailable,
            coordinator.setPrivateSpaceLocked(locked = false),
        )
        assertEquals(
            PrivateSpaceActionResult.Unavailable,
            coordinator.setPrivateSpaceLocked(locked = true),
        )
        assertTrue(platform.quietRequests.isEmpty())
        coordinator.close()
    }

    @Test
    fun `private launch delegates to the revalidating platform seam`() = runTest {
        val private = ProfileSerial.of(20)
        val item = privateItem(private, "Vault")
        val platform = FakeProfilePlatform(
            profiles = listOf(profile(private, ProfileKind.PRIVATE, ProfileAvailability.AVAILABLE)),
        )
        val coordinator = DefaultProfileCoordinator(platform, backgroundScope)

        assertEquals(PrivateLaunchResult.LAUNCHED, coordinator.launchPrivate(item.identity))
        assertEquals(listOf(item.identity), platform.launches)
        coordinator.close()
    }

    @Test
    fun `private space settings delegates through the coordinator seam`() = runTest {
        val platform = FakeProfilePlatform(emptyList()).apply {
            privateSpaceSettingsResult = PrivateSpaceSettingsResult.Launched
        }
        val coordinator = DefaultProfileCoordinator(platform, backgroundScope)

        assertEquals(
            PrivateSpaceSettingsResult.Launched,
            coordinator.openPrivateSpaceSettings(),
        )
        assertEquals(1, platform.privateSpaceSettingsRequests)
        coordinator.close()
    }

    @Test
    fun `private space settings coordinator preserves cancellation`() = runTest {
        val platform = FakeProfilePlatform(emptyList()).apply {
            privateSpaceSettingsFailure = CancellationException("cancelled")
        }
        val coordinator = DefaultProfileCoordinator(platform, backgroundScope)

        val failure = runCatching { coordinator.openPrivateSpaceSettings() }.exceptionOrNull()

        assertTrue(failure is CancellationException)
        coordinator.close()
    }

    @Test
    fun `private space settings coordinator types unexpected platform failure`() = runTest {
        val platform = FakeProfilePlatform(emptyList()).apply {
            privateSpaceSettingsFailure = IllegalStateException("framework failed")
        }
        val coordinator = DefaultProfileCoordinator(platform, backgroundScope)

        assertEquals(
            PrivateSpaceSettingsResult.Rejected,
            coordinator.openPrivateSpaceSettings(),
        )
        coordinator.close()
    }

    @Test
    fun `closed coordinator does not open private space settings`() = runTest {
        val platform = FakeProfilePlatform(emptyList())
        val coordinator = DefaultProfileCoordinator(platform, backgroundScope)
        coordinator.close()

        assertEquals(PrivateSpaceSettingsResult.Closed, coordinator.openPrivateSpaceSettings())
        assertEquals(0, platform.privateSpaceSettingsRequests)
    }

    private class FakeProfilePlatform(
        var profiles: List<ProfileRecord>,
    ) : ProfilePlatform {
        override val invalidations = MutableSharedFlow<ProfileInvalidation>(extraBufferCapacity = 1)
        var privateItems: List<PrivateProfileItem> = emptyList()
        var privateFailure: RuntimeException? = null
        var privateReads = 0
        var privateReadStarted: CompletableDeferred<Unit>? = null
        var privateReadRelease: CompletableDeferred<Unit>? = null
        var privateReadCancelled = false
        var quietRequestStarted: CompletableDeferred<Unit>? = null
        var quietRequestRelease: CompletableDeferred<Unit>? = null
        var quietResult: WorkModeRequestResult = WorkModeRequestResult.REQUESTED
        val launches = mutableListOf<AppActivityIdentity>()
        val quietRequests = mutableListOf<Pair<ProfileSerial, Boolean>>()
        var privateSpaceSettingsResult: PrivateSpaceSettingsResult =
            PrivateSpaceSettingsResult.Unavailable
        var privateSpaceSettingsFailure: RuntimeException? = null
        var privateSpaceSettingsRequests = 0

        override suspend fun profiles(): List<ProfileRecord> = profiles

        override suspend fun privateItems(profile: ProfileSerial): PrivateItemsResult {
            privateReads += 1
            privateReadStarted?.complete(Unit)
            try {
                privateReadRelease?.await()
            } catch (cancelled: CancellationException) {
                privateReadCancelled = true
                throw cancelled
            }
            privateFailure?.let { throw it }
            return PrivateItemsResult.Available(privateItems)
        }

        override suspend fun setQuietMode(
            profile: ProfileSerial,
            quiet: Boolean,
        ): WorkModeRequestResult {
            quietRequests += profile to quiet
            quietRequestStarted?.complete(Unit)
            quietRequestRelease?.await()
            return quietResult
        }

        override suspend fun launchPrivate(identity: AppActivityIdentity): PrivateLaunchResult {
            launches += identity
            return PrivateLaunchResult.LAUNCHED
        }

        override suspend fun openPrivateSpaceSettings(): PrivateSpaceSettingsResult {
            privateSpaceSettingsRequests += 1
            privateSpaceSettingsFailure?.let { throw it }
            return privateSpaceSettingsResult
        }

        override fun close() = Unit
    }

    private fun profile(
        serial: ProfileSerial,
        kind: ProfileKind,
        availability: ProfileAvailability,
    ) = ProfileRecord(serial, kind, availability)

    private fun privateItem(profile: ProfileSerial, label: String) = PrivateProfileItem(
        identity = AppActivityIdentity(
            profile,
            PackageName.parse("org.example.private"),
            ActivityName.parse("org.example.private.Main"),
        ),
        label = label,
        icon = AppIcon.of(byteArrayOf(1)),
    )
}
