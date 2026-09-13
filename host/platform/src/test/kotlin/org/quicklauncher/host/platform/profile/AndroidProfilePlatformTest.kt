package org.quicklauncher.host.platform.profile

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.quicklauncher.contracts.domain.ActivityName
import org.quicklauncher.contracts.domain.AppActivityIdentity
import org.quicklauncher.contracts.domain.PackageName
import org.quicklauncher.contracts.domain.ProfileSerial
import org.quicklauncher.host.runtime.profile.PrivateItemsResult
import org.quicklauncher.host.runtime.profile.PrivateLaunchResult
import org.quicklauncher.host.runtime.profile.PrivateSpaceEntryPoint
import org.quicklauncher.host.runtime.profile.PrivateSpaceSettingsResult
import org.quicklauncher.host.runtime.profile.ProfileAvailability
import org.quicklauncher.host.runtime.profile.ProfileInvalidation
import org.quicklauncher.host.runtime.profile.ProfileKind
import org.quicklauncher.host.runtime.profile.WorkModeRequestResult

@OptIn(ExperimentalCoroutinesApi::class)
class AndroidProfilePlatformTest {
    @Test
    fun `unknown profile types fail closed`() = runTest {
        val backend = FakeProfileBackend(
            listOf(
                record(0, SYSTEM, quiet = false, unlocked = true),
                record(10, MANAGED, quiet = false, unlocked = true),
                record(20, PRIVATE, quiet = true, unlocked = false),
                record(30, "vendor.profile.ambiguous", quiet = false, unlocked = true),
            ),
        )
        val platform = AndroidProfilePlatform(backend, Dispatchers.Unconfined)

        assertEquals(
            listOf(ProfileKind.PERSONAL, ProfileKind.WORK, ProfileKind.PRIVATE),
            platform.profiles().map { it.kind },
        )
        assertEquals(
            ProfileAvailability.LOCKED,
            platform.profiles().single { it.serial == ProfileSerial.of(20) }.availability,
        )
        platform.close()
    }

    @Test
    fun `private profile exposes the system entry point policy without metadata reads`() = runTest {
        val backend = FakeProfileBackend(
            listOf(
                record(
                    20,
                    PRIVATE,
                    quiet = true,
                    unlocked = false,
                    privateSpaceEntryPoint = PrivateSpaceEntryPoint.HIDDEN,
                ),
            ),
        )
        val platform = AndroidProfilePlatform(backend, Dispatchers.Unconfined)

        val profile = platform.profiles().single()

        assertEquals(PrivateSpaceEntryPoint.HIDDEN, profile.privateSpaceEntryPoint)
        assertEquals(0, backend.activityReads)
        platform.close()
    }

    @Test
    fun `private space settings action launches entirely behind the platform seam`() = runTest {
        val backend = FakeProfileBackend(emptyList()).apply {
            settingsResult = PlatformPrivateSpaceSettingsResult.LAUNCHED
        }
        val platform = AndroidProfilePlatform(backend, Dispatchers.Unconfined)

        assertEquals(PrivateSpaceSettingsResult.Launched, platform.openPrivateSpaceSettings())
        assertEquals(1, backend.settingsRequests)
        platform.close()
    }

    @Test
    fun `private space settings role or permission denial is typed`() = runTest {
        val backend = FakeProfileBackend(emptyList()).apply {
            settingsFailure = SecurityException("not the active launcher")
        }
        val platform = AndroidProfilePlatform(backend, Dispatchers.Unconfined)

        assertEquals(PrivateSpaceSettingsResult.Denied, platform.openPrivateSpaceSettings())
        platform.close()
    }

    @Test
    fun `missing private space settings route is unavailable`() = runTest {
        val platform = AndroidProfilePlatform(FakeProfileBackend(emptyList()), Dispatchers.Unconfined)

        assertEquals(PrivateSpaceSettingsResult.Unavailable, platform.openPrivateSpaceSettings())
        platform.close()
    }

    @Test
    fun `private space settings framework failure is rejected`() = runTest {
        val backend = FakeProfileBackend(emptyList()).apply {
            settingsFailure = IllegalStateException("framework route failed")
        }
        val platform = AndroidProfilePlatform(backend, Dispatchers.Unconfined)

        assertEquals(PrivateSpaceSettingsResult.Rejected, platform.openPrivateSpaceSettings())
        platform.close()
    }

    @Test
    fun `private space settings cancellation propagates`() = runTest {
        val backend = FakeProfileBackend(emptyList()).apply {
            settingsFailure = CancellationException("cancelled")
        }
        val platform = AndroidProfilePlatform(backend, Dispatchers.Unconfined)

        val failure = runCatching { platform.openPrivateSpaceSettings() }.exceptionOrNull()

        assertTrue(failure is CancellationException)
        platform.close()
    }

    @Test
    fun `closed profile platform rejects private space settings without a framework call`() = runTest {
        val backend = FakeProfileBackend(emptyList())
        val platform = AndroidProfilePlatform(backend, Dispatchers.Unconfined)
        platform.close()

        assertEquals(PrivateSpaceSettingsResult.Closed, platform.openPrivateSpaceSettings())
        assertEquals(0, backend.settingsRequests)
    }

    @Test
    fun `private metadata read revalidates kind and availability before activity query`() = runTest {
        val backend = FakeProfileBackend(listOf(record(20, PRIVATE, quiet = true, unlocked = false)))
        backend.activities = listOf(activity(20, "Secret"))
        val platform = AndroidProfilePlatform(backend, Dispatchers.Unconfined)

        assertEquals(PrivateItemsResult.Locked, platform.privateItems(ProfileSerial.of(20)))
        assertEquals(0, backend.activityReads)

        backend.records = listOf(record(20, PRIVATE, quiet = false, unlocked = true))
        val result = platform.privateItems(ProfileSerial.of(20)) as PrivateItemsResult.Available
        assertEquals(listOf("Secret"), result.items.map { it.label })
        assertEquals(1, backend.activityReads)

        backend.records = listOf(record(20, MANAGED, quiet = false, unlocked = true))
        assertEquals(PrivateItemsResult.SecurityDenied, platform.privateItems(ProfileSerial.of(20)))
        assertEquals(1, backend.activityReads)
        platform.close()
    }

    @Test
    fun `quiet mode request revalidates serial and profile kind`() = runTest {
        val backend = FakeProfileBackend(listOf(record(10, MANAGED, quiet = false, unlocked = true)))
        val platform = AndroidProfilePlatform(backend, Dispatchers.Unconfined)

        assertEquals(
            WorkModeRequestResult.REQUESTED,
            platform.setQuietMode(ProfileSerial.of(10), quiet = true),
        )
        assertEquals(listOf(10L to true), backend.quietRequests)

        backend.records = listOf(record(10, SYSTEM, quiet = false, unlocked = true))
        assertEquals(
            WorkModeRequestResult.WRONG_PROFILE_KIND,
            platform.setQuietMode(ProfileSerial.of(10), quiet = true),
        )
        assertTrue(backend.quietRequests.size == 1)
        platform.close()
    }

    @Test
    fun `private unlock requiring authentication remains an asynchronous request`() = runTest {
        val backend = FakeProfileBackend(
            listOf(record(10, PRIVATE, quiet = true, unlocked = false)),
        ).apply { quietRequestAccepted = false }
        val platform = AndroidProfilePlatform(backend, Dispatchers.Unconfined)

        assertEquals(
            WorkModeRequestResult.REQUESTED,
            platform.setQuietMode(ProfileSerial.of(10), quiet = false),
        )
        assertEquals(listOf(10L to false), backend.quietRequests)
        platform.close()
    }

    @Test
    fun `private launch revalidates kind lock state and activity`() = runTest {
        val identity = activity(20, "Secret").identity
        val backend = FakeProfileBackend(listOf(record(20, PRIVATE, quiet = true, unlocked = false)))
        backend.activities = listOf(activity(20, "Secret"))
        val platform = AndroidProfilePlatform(backend, Dispatchers.Unconfined)

        assertEquals(PrivateLaunchResult.LOCKED, platform.launchPrivate(identity))
        assertEquals(0, backend.activityReads)

        backend.records = listOf(record(20, PRIVATE, quiet = false, unlocked = true))
        assertEquals(PrivateLaunchResult.LAUNCHED, platform.launchPrivate(identity))
        assertEquals(listOf(identity), backend.launches)

        backend.records = listOf(record(20, MANAGED, quiet = false, unlocked = true))
        assertEquals(PrivateLaunchResult.WRONG_PROFILE_KIND, platform.launchPrivate(identity))
        assertEquals(1, backend.launches.size)
        platform.close()
    }

    private class FakeProfileBackend(
        var records: List<PlatformProfileRecord>,
    ) : ProfileBackend {
        override val invalidations = MutableSharedFlow<ProfileInvalidation>(extraBufferCapacity = 1)
        var activities: List<PlatformPrivateActivity> = emptyList()
        var activityReads = 0
        val quietRequests = mutableListOf<Pair<Long, Boolean>>()
        val launches = mutableListOf<AppActivityIdentity>()
        var settingsResult = PlatformPrivateSpaceSettingsResult.UNAVAILABLE
        var settingsFailure: RuntimeException? = null
        var settingsRequests = 0
        var quietRequestAccepted = true

        override fun profiles(): List<PlatformProfileRecord> = records
        override fun profile(serial: Long): PlatformProfileRecord? = records.firstOrNull { it.serial == serial }
        override fun privateActivities(serial: Long): List<PlatformPrivateActivity> {
            activityReads += 1
            return activities
        }
        override fun requestQuietMode(serial: Long, quiet: Boolean): Boolean {
            quietRequests += serial to quiet
            return quietRequestAccepted
        }
        override fun launchPrivate(identity: AppActivityIdentity): Boolean {
            launches += identity
            return true
        }
        override fun openPrivateSpaceSettings(): PlatformPrivateSpaceSettingsResult {
            settingsRequests += 1
            settingsFailure?.let { throw it }
            return settingsResult
        }
        override fun close() = Unit
    }

    private fun record(
        serial: Long,
        type: String,
        quiet: Boolean,
        unlocked: Boolean,
        privateSpaceEntryPoint: PrivateSpaceEntryPoint = if (type == PRIVATE) {
            PrivateSpaceEntryPoint.SYSTEM_DEFAULT
        } else {
            PrivateSpaceEntryPoint.NOT_PRIVATE
        },
    ) = PlatformProfileRecord(serial, type, quiet, unlocked, privateSpaceEntryPoint)

    private fun activity(serial: Long, label: String) = PlatformPrivateActivity(
        AppActivityIdentity(
            ProfileSerial.of(serial),
            PackageName.parse("org.example.private"),
            ActivityName.parse("org.example.private.Main"),
        ),
        label,
        byteArrayOf(1),
    )

    private companion object {
        const val SYSTEM = "android.os.usertype.full.SYSTEM"
        const val MANAGED = "android.os.usertype.profile.MANAGED"
        const val PRIVATE = "android.os.usertype.profile.PRIVATE"
    }
}
