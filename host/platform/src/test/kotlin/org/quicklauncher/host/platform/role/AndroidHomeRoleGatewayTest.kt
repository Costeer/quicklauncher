package org.quicklauncher.host.platform.role

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Test
import org.quicklauncher.host.runtime.permissions.HomeRoleRequestDispatch
import org.quicklauncher.host.runtime.permissions.HomeRoleRequestReason
import org.quicklauncher.host.runtime.permissions.HomeRoleState
import org.quicklauncher.host.runtime.permissions.HomeSettingsOpenResult

class AndroidHomeRoleGatewayTest {
    @Test
    fun `adapter exposes typed state request and public settings results`() = runBlocking {
        var state = HomeRoleState.AVAILABLE_NOT_HELD
        var requestedReason: HomeRoleRequestReason? = null
        var settingsCount = 0
        val gateway = AndroidHomeRoleGateway.forTesting(
            state = { state },
            request = { reason ->
                requestedReason = reason
                state = HomeRoleState.HELD
                HomeRoleRequestDispatch.Returned
            },
            openSettings = {
                settingsCount += 1
                HomeSettingsOpenResult.Opened
            },
        )

        assertEquals(HomeRoleState.AVAILABLE_NOT_HELD, gateway.currentState())
        assertEquals(
            HomeRoleRequestDispatch.Returned,
            gateway.requestHomeRole(HomeRoleRequestReason.ONBOARDING_PREVIEW_CONFIRMED),
        )
        assertEquals(HomeRoleRequestReason.ONBOARDING_PREVIEW_CONFIRMED, requestedReason)
        assertEquals(HomeRoleState.HELD, gateway.currentState())
        assertEquals(HomeSettingsOpenResult.Opened, gateway.openHomeSettings())
        assertEquals(1, settingsCount)
    }
}
