package org.quicklauncher.host.platform.notifications

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Test
import org.quicklauncher.contracts.domain.PackageName
import org.quicklauncher.contracts.domain.ProfilePackageIdentity
import org.quicklauncher.contracts.domain.ProfileSerial
import org.quicklauncher.host.runtime.notifications.NotificationListenerStatus
import org.quicklauncher.host.runtime.notifications.NotificationPlatformActionResult

class AndroidNotificationPlatformTest {
    @Test
    fun `only available personal and work aggregates cross the adapter`() = runTest {
        val backend = FakeNotificationBackend().apply {
            listenerStatus = NotificationListenerStatus.CONNECTED
            values = listOf(
                count(0, SYSTEM, available = true, "org.example.personal", 2),
                count(10, MANAGED, available = true, "org.example.work", 4),
                count(11, MANAGED, available = false, "org.example.paused", 7),
                count(20, PRIVATE, available = true, "org.example.private", 8),
                count(30, "vendor.ambiguous", available = true, "org.example.unknown", 9),
            )
        }
        val platform = AndroidNotificationPlatform(backend, Dispatchers.Unconfined)

        assertEquals(
            listOf(
                identity(0, "org.example.personal") to 2,
                identity(10, "org.example.work") to 4,
            ),
            platform.counts().counts.map { it.identity to it.count },
        )
        platform.close()
    }

    @Test
    fun `access and recovery actions remain typed`() = runTest {
        val backend = FakeNotificationBackend()
        val platform = AndroidNotificationPlatform(backend, Dispatchers.Unconfined)
        assertEquals(NotificationPlatformActionResult.OPENED, platform.requestAccess())
        assertEquals(
            NotificationPlatformActionResult.OPENED,
            platform.openRestrictedSettingsRecovery(),
        )
        assertEquals(1, backend.accessRequests)
        assertEquals(1, backend.recoveryRequests)
        platform.close()
    }

    private class FakeNotificationBackend : NotificationBackend {
        override val invalidations = MutableSharedFlow<Unit>(extraBufferCapacity = 1)
        var listenerStatus = NotificationListenerStatus.NO_ACCESS
        var values: List<PlatformNotificationCount> = emptyList()
        var accessRequests = 0
        var recoveryRequests = 0

        override fun status(): NotificationListenerStatus = listenerStatus
        override fun counts(): List<PlatformNotificationCount> = values
        override fun openAccessSettings(): Boolean {
            accessRequests += 1
            return true
        }
        override fun openRestrictedSettingsRecovery(): Boolean {
            recoveryRequests += 1
            return true
        }
        override fun close() = Unit
    }

    private fun count(
        serial: Long,
        userType: String,
        available: Boolean,
        packageName: String,
        count: Int,
    ) = PlatformNotificationCount(serial, userType, available, packageName, count)

    private fun identity(serial: Long, packageName: String) = ProfilePackageIdentity(
        ProfileSerial.of(serial),
        PackageName.parse(packageName),
    )

    private companion object {
        const val SYSTEM = "android.os.usertype.full.SYSTEM"
        const val MANAGED = "android.os.usertype.profile.MANAGED"
        const val PRIVATE = "android.os.usertype.profile.PRIVATE"
    }
}
