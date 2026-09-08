package org.quicklauncher.host.platform.role

import android.app.role.RoleManager
import android.content.Intent
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.result.contract.ActivityResultContracts
import kotlin.coroutines.resume
import kotlinx.coroutines.CancellableContinuation
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.quicklauncher.host.runtime.permissions.HomeRoleGateway
import org.quicklauncher.host.runtime.permissions.HomeRoleRequestDispatch
import org.quicklauncher.host.runtime.permissions.HomeRoleRequestReason
import org.quicklauncher.host.runtime.permissions.HomeRoleState
import org.quicklauncher.host.runtime.permissions.HomeSettingsOpenResult

/** Android Home-role adapter. Construct during Activity creation, before it reaches STARTED. */
class AndroidHomeRoleGateway private constructor(
    private val bridge: HomeRoleAndroidBridge,
) : HomeRoleGateway {
    constructor(activity: ComponentActivity) : this(ActivityHomeRoleBridge(activity))

    override fun currentState(): HomeRoleState = bridge.currentState()

    override suspend fun requestHomeRole(
        reason: HomeRoleRequestReason,
    ): HomeRoleRequestDispatch = bridge.request(reason)

    override fun openHomeSettings(): HomeSettingsOpenResult = bridge.openHomeSettings()

    companion object {
        internal fun forTesting(
            state: () -> HomeRoleState,
            request: suspend (HomeRoleRequestReason) -> HomeRoleRequestDispatch,
            openSettings: () -> HomeSettingsOpenResult,
        ): AndroidHomeRoleGateway = AndroidHomeRoleGateway(
            object : HomeRoleAndroidBridge {
                override fun currentState(): HomeRoleState = state()

                override suspend fun request(
                    reason: HomeRoleRequestReason,
                ): HomeRoleRequestDispatch = request(reason)

                override fun openHomeSettings(): HomeSettingsOpenResult = openSettings()
            },
        )
    }
}

private interface HomeRoleAndroidBridge {
    fun currentState(): HomeRoleState

    suspend fun request(reason: HomeRoleRequestReason): HomeRoleRequestDispatch

    fun openHomeSettings(): HomeSettingsOpenResult
}

private class ActivityHomeRoleBridge(
    private val activity: ComponentActivity,
) : HomeRoleAndroidBridge {
    private val roleManager: RoleManager? = activity.getSystemService(RoleManager::class.java)
    private val requestMutex = Mutex()
    private val pendingLock = Any()
    private var pendingResult: CancellableContinuation<HomeRoleRequestDispatch>? = null
    private val requestLauncher = activity.registerForActivityResult(
        ActivityResultContracts.StartActivityForResult(),
    ) {
        completePending(HomeRoleRequestDispatch.Returned)
    }

    override fun currentState(): HomeRoleState {
        val manager = roleManager ?: return HomeRoleState.UNAVAILABLE
        return try {
            when {
                !manager.isRoleAvailable(RoleManager.ROLE_HOME) -> HomeRoleState.UNAVAILABLE
                manager.isRoleHeld(RoleManager.ROLE_HOME) -> HomeRoleState.HELD
                else -> HomeRoleState.AVAILABLE_NOT_HELD
            }
        } catch (_: RuntimeException) {
            HomeRoleState.UNAVAILABLE
        }
    }

    override suspend fun request(
        reason: HomeRoleRequestReason,
    ): HomeRoleRequestDispatch = requestMutex.withLock {
        val manager = roleManager
        if (manager == null || currentState() == HomeRoleState.UNAVAILABLE) {
            return@withLock HomeRoleRequestDispatch.Unavailable
        }
        suspendCancellableCoroutine<HomeRoleRequestDispatch> { continuation ->
            synchronized(pendingLock) {
                check(pendingResult == null) { "A Home-role request is already pending" }
                pendingResult = continuation
            }
            continuation.invokeOnCancellation {
                synchronized(pendingLock) {
                    if (pendingResult === continuation) pendingResult = null
                }
            }
            activity.runOnUiThread {
                if (!continuation.isActive) return@runOnUiThread
                try {
                    requestLauncher.launch(manager.createRequestRoleIntent(RoleManager.ROLE_HOME))
                } catch (_: RuntimeException) {
                    completePending(HomeRoleRequestDispatch.Unavailable)
                }
            }
        }
    }

    override fun openHomeSettings(): HomeSettingsOpenResult = try {
        activity.startActivity(Intent(Settings.ACTION_HOME_SETTINGS))
        HomeSettingsOpenResult.Opened
    } catch (_: RuntimeException) {
        HomeSettingsOpenResult.Unavailable
    }

    private fun completePending(result: HomeRoleRequestDispatch) {
        val continuation = synchronized(pendingLock) {
            pendingResult.also { pendingResult = null }
        }
        if (continuation?.isActive == true) continuation.resume(result)
    }
}
