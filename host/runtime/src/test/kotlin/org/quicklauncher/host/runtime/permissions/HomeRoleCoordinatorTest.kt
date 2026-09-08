package org.quicklauncher.host.runtime.permissions

import java.util.concurrent.CancellationException
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class HomeRoleCoordinatorTest {
    @Test
    fun `refresh observes ownership without dispatching a request`() = runBlocking {
        val gateway = FakeHomeRoleGateway(HomeRoleState.AVAILABLE_NOT_HELD)
        val coordinator = HomeRoleCoordinator(gateway)

        assertEquals(HomeRoleState.AVAILABLE_NOT_HELD, coordinator.refresh())
        gateway.state = HomeRoleState.HELD
        assertEquals(HomeRoleState.HELD, coordinator.refresh())
        assertEquals(0, gateway.requestCount)
    }

    @Test
    fun `unavailable and already-held states do not dispatch a request`() = runBlocking {
        val unavailable = FakeHomeRoleGateway(HomeRoleState.UNAVAILABLE)
        val held = FakeHomeRoleGateway(HomeRoleState.HELD)

        assertEquals(
            HomeRoleRequestOutcome.Unavailable,
            HomeRoleCoordinator(unavailable).request(onboardingRequest()),
        )
        assertEquals(
            HomeRoleRequestOutcome.AlreadyHeld,
            HomeRoleCoordinator(held).request(settingsRequest()),
        )
        assertEquals(0, unavailable.requestCount)
        assertEquals(0, held.requestCount)
    }

    @Test
    fun `contextual request rechecks role ownership after the platform result`() = runBlocking {
        val gateway = FakeHomeRoleGateway(HomeRoleState.AVAILABLE_NOT_HELD).apply {
            afterRequest = HomeRoleState.HELD
        }

        val outcome = HomeRoleCoordinator(gateway).request(onboardingRequest())

        assertEquals(HomeRoleRequestOutcome.Granted, outcome)
        assertEquals(1, gateway.requestCount)
        assertEquals(HomeRoleRequestReason.ONBOARDING_PREVIEW_CONFIRMED, gateway.lastReason)
    }

    @Test
    fun `request returning without ownership requires the public settings fallback`() = runBlocking {
        val gateway = FakeHomeRoleGateway(HomeRoleState.AVAILABLE_NOT_HELD)
        val coordinator = HomeRoleCoordinator(gateway)

        assertEquals(
            HomeRoleRequestOutcome.FallbackRequired,
            coordinator.request(settingsRequest()),
        )
        assertEquals(HomeRoleState.AVAILABLE_NOT_HELD, coordinator.refresh())
        assertEquals(HomeSettingsOpenResult.Opened, coordinator.openHomeSettingsFallback())
        assertEquals(1, gateway.settingsOpenCount)
    }

    @Test
    fun `missing platform request surface also requires the public settings fallback`() = runBlocking {
        val gateway = FakeHomeRoleGateway(HomeRoleState.AVAILABLE_NOT_HELD).apply {
            dispatch = HomeRoleRequestDispatch.Unavailable
        }

        assertEquals(
            HomeRoleRequestOutcome.FallbackRequired,
            HomeRoleCoordinator(gateway).request(onboardingRequest()),
        )
    }

    @Test
    fun `request cancellation propagates without opening settings`() {
        val gateway = FakeHomeRoleGateway(HomeRoleState.AVAILABLE_NOT_HELD).apply {
            requestFailure = CancellationException("test cancellation")
        }

        assertThrows(CancellationException::class.java) {
            runBlocking { HomeRoleCoordinator(gateway).request(onboardingRequest()) }
        }
        assertEquals(0, gateway.settingsOpenCount)
    }

    private fun onboardingRequest() = ContextualHomeRoleRequest(
        HomeRoleRequestReason.ONBOARDING_PREVIEW_CONFIRMED,
    )

    private fun settingsRequest() = ContextualHomeRoleRequest(
        HomeRoleRequestReason.SETTINGS_USER_ACTION,
    )
}

private class FakeHomeRoleGateway(initialState: HomeRoleState) : HomeRoleGateway {
    var state: HomeRoleState = initialState
    var afterRequest: HomeRoleState? = null
    var requestFailure: CancellationException? = null
    var requestCount: Int = 0
    var settingsOpenCount: Int = 0
    var lastReason: HomeRoleRequestReason? = null
    var dispatch: HomeRoleRequestDispatch = HomeRoleRequestDispatch.Returned

    override fun currentState(): HomeRoleState = state

    override suspend fun requestHomeRole(reason: HomeRoleRequestReason): HomeRoleRequestDispatch {
        requestCount += 1
        lastReason = reason
        requestFailure?.let { throw it }
        afterRequest?.let { state = it }
        return dispatch
    }

    override fun openHomeSettings(): HomeSettingsOpenResult {
        settingsOpenCount += 1
        return HomeSettingsOpenResult.Opened
    }
}
