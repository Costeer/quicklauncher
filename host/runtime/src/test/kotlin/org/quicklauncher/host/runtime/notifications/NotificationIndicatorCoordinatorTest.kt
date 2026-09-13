package org.quicklauncher.host.runtime.notifications

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Test
import org.quicklauncher.contracts.domain.PackageName
import org.quicklauncher.contracts.domain.ProfilePackageIdentity
import org.quicklauncher.contracts.domain.ProfileSerial
import org.quicklauncher.contracts.ui.NotificationIndicator
import org.quicklauncher.host.data.preferences.NotificationStyle

@OptIn(ExperimentalCoroutinesApi::class)
class NotificationIndicatorCoordinatorTest {
    @Test
    fun `indicators follow preference and app eligibility without exposing exact large counts`() = runTest {
        val identity = identity(0, "org.example.mail")
        val platform = FakeNotificationPlatform().apply {
            status = NotificationListenerStatus.CONNECTED
            snapshot = NotificationCountSnapshot(listOf(NotificationCount(identity, 17)))
        }
        val style = MutableStateFlow(NotificationStyle.DOT)
        val coordinator = DefaultNotificationIndicatorCoordinator(
            platform,
            style,
            backgroundScope,
            StandardTestDispatcher(testScheduler),
        )
        coordinator.refresh(listOf(eligible(identity)))
        assertEquals(NotificationIndicator.Dot, coordinator.state.value.indicator(identity))

        style.value = NotificationStyle.APPROXIMATE_COUNT
        runCurrent()
        assertEquals(
            NotificationIndicator.ApproximateCount(20),
            coordinator.state.value.indicator(identity),
        )

        coordinator.refresh(listOf(eligible(identity, visible = false)))
        assertEquals(NotificationIndicator.None, coordinator.state.value.indicator(identity))
        coordinator.close()
    }

    @Test
    fun `unavailable work profile removes its indicator on refresh`() = runTest {
        val work = identity(10, "org.example.work")
        val platform = FakeNotificationPlatform().apply {
            status = NotificationListenerStatus.CONNECTED
            snapshot = NotificationCountSnapshot(listOf(NotificationCount(work, 2)))
        }
        val coordinator = DefaultNotificationIndicatorCoordinator(
            platform,
            MutableStateFlow(NotificationStyle.DOT),
            backgroundScope,
            StandardTestDispatcher(testScheduler),
        )

        coordinator.refresh(listOf(eligible(work, kind = IndicatorProfileKind.WORK)))
        assertEquals(NotificationIndicator.Dot, coordinator.state.value.indicator(work))

        coordinator.refresh(
            listOf(eligible(work, kind = IndicatorProfileKind.WORK, available = false)),
        )
        assertEquals(NotificationIndicator.None, coordinator.state.value.indicator(work))
        coordinator.close()
    }

    @Test
    fun `access is requested only by explicit action and denial revocation and disconnect are typed`() =
        runTest {
            val platform = FakeNotificationPlatform()
            val coordinator = DefaultNotificationIndicatorCoordinator(
                platform,
                MutableStateFlow(NotificationStyle.DOT),
                backgroundScope,
                StandardTestDispatcher(testScheduler),
            )

            coordinator.refresh(emptyList())
            assertEquals(NotificationAccessState.ACTION_REQUIRED, coordinator.state.value.access)
            assertEquals(0, platform.accessRequests)

            assertEquals(NotificationAccessAction.OpenedSettings, coordinator.requestAccess())
            assertEquals(1, platform.accessRequests)
            assertEquals(NotificationAccessState.AWAITING_DECISION, coordinator.state.value.access)

            coordinator.refreshAfterAccessSettings(emptyList())
            assertEquals(NotificationAccessState.DENIED, coordinator.state.value.access)

            platform.status = NotificationListenerStatus.CONNECTED
            coordinator.refresh(emptyList())
            assertEquals(NotificationAccessState.CONNECTED, coordinator.state.value.access)
            platform.status = NotificationListenerStatus.DISCONNECTED
            coordinator.refresh(emptyList())
            assertEquals(NotificationAccessState.DISCONNECTED, coordinator.state.value.access)
            platform.status = NotificationListenerStatus.NO_ACCESS
            coordinator.refresh(emptyList())
            assertEquals(NotificationAccessState.REVOKED, coordinator.state.value.access)
            coordinator.close()
        }

    private class FakeNotificationPlatform : NotificationPlatform {
        override val invalidations = MutableSharedFlow<Unit>(extraBufferCapacity = 1)
        var status = NotificationListenerStatus.NO_ACCESS
        var snapshot = NotificationCountSnapshot.Empty
        var accessRequests = 0

        override suspend fun status(): NotificationListenerStatus = status
        override suspend fun counts(): NotificationCountSnapshot = snapshot
        override suspend fun requestAccess(): NotificationPlatformActionResult {
            accessRequests += 1
            return NotificationPlatformActionResult.OPENED
        }
        override suspend fun openRestrictedSettingsRecovery(): NotificationPlatformActionResult =
            NotificationPlatformActionResult.OPENED
        override fun close() = Unit
    }

    private fun eligible(
        identity: ProfilePackageIdentity,
        kind: IndicatorProfileKind = IndicatorProfileKind.PERSONAL,
        available: Boolean = true,
        visible: Boolean = true,
    ) = IndicatorEligibleApp(identity, kind, available, visible, indicatorEligible = true)

    private fun identity(serial: Long, packageName: String) = ProfilePackageIdentity(
        ProfileSerial.of(serial),
        PackageName.parse(packageName),
    )
}
