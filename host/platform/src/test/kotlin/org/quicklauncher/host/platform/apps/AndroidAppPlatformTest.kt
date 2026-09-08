package org.quicklauncher.host.platform.apps

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.quicklauncher.contracts.domain.ActivityName
import org.quicklauncher.contracts.domain.AppActivityIdentity
import org.quicklauncher.contracts.domain.PackageName
import org.quicklauncher.contracts.domain.ProfileSerial
import org.quicklauncher.host.runtime.catalog.AppLaunchResult
import org.quicklauncher.host.runtime.catalog.AppPlatformInvalidation

@OptIn(ExperimentalCoroutinesApi::class)
class AndroidAppPlatformTest {
    @Test
    fun `missing user metadata fails closed for secondary profiles`() {
        assertEquals(null, resolveLauncherUserType(advertisedType = null, isCurrentUser = false))
        assertEquals(
            PERSONAL_TYPE,
            resolveLauncherUserType(advertisedType = PERSONAL_TYPE, isCurrentUser = false),
        )
        assertEquals(
            "android.os.usertype.full.SYSTEM",
            resolveLauncherUserType(advertisedType = null, isCurrentUser = true),
        )
    }

    @Test
    fun `snapshot filters private profiles before reading their activity data`() = runTest {
        val backend = FakeBackend(
            profiles = listOf(
                profile(0, PERSONAL_TYPE),
                profile(10, PRIVATE_TYPE),
                profile(11, WORK_TYPE),
            ),
            activities = mapOf(
                0L to listOf(activity(0, "org.example.personal", "Personal")),
                10L to listOf(activity(10, "org.example.private", "Secret")),
                11L to listOf(activity(11, "org.example.work", "Work")),
            ),
        )
        val platform = AndroidAppPlatform(backend, StandardTestDispatcher(testScheduler))

        val snapshot = platform.snapshot()

        assertEquals(listOf(0L, 11L), snapshot.profiles.map { it.serial.value })
        assertEquals(listOf("Personal", "Work"), snapshot.activities.map { it.label })
        assertEquals(listOf(0L, 11L), backend.activityReads)
        assertFalse(10L in backend.activityReads)
        platform.close()
    }

    @Test
    fun `snapshot retains a locked work profile without reading its app labels`() = runTest {
        val backend = FakeBackend(
            profiles = listOf(profile(11, WORK_TYPE, quiet = true, unlocked = false)),
            activities = mapOf(11L to listOf(activity(11, "org.example.work", "Work"))),
        )
        val platform = AndroidAppPlatform(backend, StandardTestDispatcher(testScheduler))

        val snapshot = platform.snapshot()

        assertTrue(snapshot.profiles.single().quiet)
        assertTrue(snapshot.activities.isEmpty())
        assertTrue(backend.activityReads.isEmpty())
        platform.close()
    }

    @Test
    fun `launch resolves current profile policy and activity availability before dispatch`() = runTest {
        val identity = identity(0, "org.example.personal")
        val backend = FakeBackend(profiles = listOf(profile(0, PERSONAL_TYPE)))
        val platform = AndroidAppPlatform(backend, StandardTestDispatcher(testScheduler))

        assertEquals(AppLaunchResult.Launched, platform.launch(identity))
        assertEquals(listOf(identity), backend.enabledChecks)
        assertEquals(listOf(identity), backend.starts)
        platform.close()
    }

    @Test
    fun `launch rejects missing private locked and disabled targets without dispatch`() = runTest {
        val backend = FakeBackend(
            profiles = listOf(
                profile(10, PRIVATE_TYPE),
                profile(11, WORK_TYPE, quiet = true, unlocked = false),
                profile(12, PERSONAL_TYPE),
            ),
            enabled = false,
        )
        val platform = AndroidAppPlatform(backend, StandardTestDispatcher(testScheduler))

        assertEquals(AppLaunchResult.MissingProfile, platform.launch(identity(99, "org.example.missing")))
        assertEquals(AppLaunchResult.SecurityDenied, platform.launch(identity(10, "org.example.private")))
        assertEquals(AppLaunchResult.ProfileLocked, platform.launch(identity(11, "org.example.work")))
        assertEquals(AppLaunchResult.ActivityUnavailable, platform.launch(identity(12, "org.example.disabled")))
        assertTrue(backend.starts.isEmpty())
        platform.close()
    }

    @Test
    fun `framework launch failures become typed results`() = runTest {
        val identity = identity(0, "org.example.personal")
        val backend = FakeBackend(profiles = listOf(profile(0, PERSONAL_TYPE)))
        val platform = AndroidAppPlatform(backend, StandardTestDispatcher(testScheduler))

        backend.startFailure = AndroidLaunchFailure.SECURITY
        assertEquals(AppLaunchResult.SecurityDenied, platform.launch(identity))
        backend.startFailure = AndroidLaunchFailure.PROFILE_LOCKED
        assertEquals(AppLaunchResult.ProfileLocked, platform.launch(identity))
        backend.startFailure = AndroidLaunchFailure.ACTIVITY_MISSING
        assertEquals(AppLaunchResult.ActivityUnavailable, platform.launch(identity))
        backend.startFailure = AndroidLaunchFailure.OTHER
        assertEquals(AppLaunchResult.Failed("android_launch_failed"), platform.launch(identity))
        platform.close()
    }

    @Test
    fun `backend callbacks emit one platform invalidation and close unregisters once`() = runTest {
        val backend = FakeBackend(emptyList())
        val platform = AndroidAppPlatform(backend, StandardTestDispatcher(testScheduler))
        val invalidation = async { platform.invalidations.first() }
        runCurrent()

        backend.invalidate()

        assertEquals(AppPlatformInvalidation.Changed, invalidation.await())
        platform.close()
        platform.close()
        assertEquals(1, backend.closeCalls)
        assertEquals(AppLaunchResult.Closed, platform.launch(identity(0, "org.example.closed")))
    }

    private fun profile(
        serial: Long,
        type: String,
        quiet: Boolean = false,
        unlocked: Boolean = true,
    ) = AndroidProfileRecord(serial, type, quiet, unlocked)

    private fun activity(
        serial: Long,
        packageName: String,
        label: String,
    ) = AndroidActivityRecord(identity(serial, packageName), label, byteArrayOf(1, 2, 3))

    private fun identity(serial: Long, packageName: String) = AppActivityIdentity(
        profile = ProfileSerial.of(serial),
        packageName = PackageName.parse(packageName),
        activityName = ActivityName.parse("$packageName.MainActivity"),
    )

    private class FakeBackend(
        var profiles: List<AndroidProfileRecord>,
        var activities: Map<Long, List<AndroidActivityRecord>> = emptyMap(),
        var enabled: Boolean = true,
    ) : LauncherAppsBackend {
        private var invalidated: (() -> Unit)? = null
        val activityReads = mutableListOf<Long>()
        val enabledChecks = mutableListOf<AppActivityIdentity>()
        val starts = mutableListOf<AppActivityIdentity>()
        var startFailure: AndroidLaunchFailure? = null
        var closeCalls = 0

        override fun start(onInvalidated: () -> Unit) {
            invalidated = onInvalidated
        }

        override fun profiles(): List<AndroidProfileRecord> = profiles

        override fun activities(profileSerial: Long): List<AndroidActivityRecord> {
            activityReads += profileSerial
            return activities[profileSerial].orEmpty()
        }

        override fun profile(profileSerial: Long): AndroidProfileRecord? =
            profiles.firstOrNull { it.serial == profileSerial }

        override fun isActivityEnabled(identity: AppActivityIdentity): Boolean {
            enabledChecks += identity
            return enabled
        }

        override fun startMainActivity(identity: AppActivityIdentity) {
            startFailure?.let { throw it }
            starts += identity
        }

        fun invalidate() {
            invalidated?.invoke()
        }

        override fun close() {
            closeCalls += 1
            invalidated = null
        }
    }

    private companion object {
        const val PERSONAL_TYPE = "android.os.usertype.full.SYSTEM"
        const val WORK_TYPE = "android.os.usertype.profile.MANAGED"
        const val PRIVATE_TYPE = "android.os.usertype.profile.PRIVATE"
    }
}
