package org.quicklauncher.host.runtime.permissions

enum class HomeRoleState {
    UNAVAILABLE,
    AVAILABLE_NOT_HELD,
    HELD,
}

enum class HomeRoleRequestReason {
    ONBOARDING_PREVIEW_CONFIRMED,
    SETTINGS_USER_ACTION,
}

/** Proof that a Home-role request was initiated by a user-visible, contextual action. */
data class ContextualHomeRoleRequest(val reason: HomeRoleRequestReason)

sealed interface HomeRoleRequestDispatch {
    data object Returned : HomeRoleRequestDispatch
    data object Unavailable : HomeRoleRequestDispatch
}

sealed interface HomeRoleRequestOutcome {
    data object Unavailable : HomeRoleRequestOutcome
    data object AlreadyHeld : HomeRoleRequestOutcome
    data object Granted : HomeRoleRequestOutcome
    data object FallbackRequired : HomeRoleRequestOutcome
}

sealed interface HomeSettingsOpenResult {
    data object Opened : HomeSettingsOpenResult
    data object Unavailable : HomeSettingsOpenResult
}

/** Platform port. Implementations keep RoleManager and request intents behind this seam. */
interface HomeRoleGateway {
    fun currentState(): HomeRoleState

    suspend fun requestHomeRole(reason: HomeRoleRequestReason): HomeRoleRequestDispatch

    fun openHomeSettings(): HomeSettingsOpenResult
}

/** Owns contextual Home-role behavior without automatically showing system UI. */
class HomeRoleCoordinator(private val gateway: HomeRoleGateway) {
    /** Rechecks ownership after activity resume without dispatching a request. */
    fun refresh(): HomeRoleState = gateway.currentState()

    suspend fun request(request: ContextualHomeRoleRequest): HomeRoleRequestOutcome {
        return when (gateway.currentState()) {
            HomeRoleState.UNAVAILABLE -> HomeRoleRequestOutcome.Unavailable
            HomeRoleState.HELD -> HomeRoleRequestOutcome.AlreadyHeld
            HomeRoleState.AVAILABLE_NOT_HELD -> {
                gateway.requestHomeRole(request.reason)
                if (gateway.currentState() == HomeRoleState.HELD) {
                    HomeRoleRequestOutcome.Granted
                } else {
                    HomeRoleRequestOutcome.FallbackRequired
                }
            }
        }
    }

    /** Opens only Android's public Home Settings surface, after explicit user confirmation. */
    fun openHomeSettingsFallback(): HomeSettingsOpenResult = gateway.openHomeSettings()
}
