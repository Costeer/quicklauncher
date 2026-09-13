package org.quicklauncher.host.platform.actions

import android.app.admin.DevicePolicyManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.ApplicationInfo
import android.content.pm.LauncherApps
import android.net.Uri
import android.os.Bundle
import android.os.Process
import android.os.UserManager
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.quicklauncher.contracts.domain.AppActivityIdentity
import org.quicklauncher.contracts.domain.ProfileSerial
import org.quicklauncher.host.runtime.actions.ItemActionPlatform
import org.quicklauncher.host.runtime.actions.ItemActionEligibility
import org.quicklauncher.host.runtime.actions.ItemActionPermission
import org.quicklauncher.host.runtime.actions.ItemPlatformResult
import org.quicklauncher.host.runtime.actions.ProfileItemAction

/** Revalidates profile and package state before routing each host-owned item action. */
class AndroidItemActionPlatform internal constructor(
    private val backend: ItemActionBackend,
    private val dispatcher: CoroutineDispatcher = Dispatchers.IO,
) : ItemActionPlatform {
    constructor(
        context: Context,
        dispatcher: CoroutineDispatcher = Dispatchers.IO,
    ) : this(FrameworkItemActionBackend(context.applicationContext), dispatcher)

    override suspend fun eligibility(identity: AppActivityIdentity): ItemActionEligibility =
        withContext(dispatcher) {
            try {
                backend.eligibility(identity).toRuntime()
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: RuntimeException) {
                ItemActionEligibility.None
            }
        }

    override suspend fun openDetails(identity: AppActivityIdentity): ItemPlatformResult =
        route(identity, backend::openDetails)

    override suspend fun uninstall(identity: AppActivityIdentity): ItemPlatformResult =
        destructiveRoute(identity, { it.uninstall }, backend::requestUninstall)

    override suspend fun disable(identity: AppActivityIdentity): ItemPlatformResult =
        destructiveRoute(identity, { it.disable }, backend::openDisableSettings)

    override suspend fun changeProfileMode(
        profile: ProfileSerial,
        action: ProfileItemAction,
    ): ItemPlatformResult = withContext(dispatcher) {
        invokeBackend {
            if (action == ProfileItemAction.NONE || !backend.isManagedProfile(profile)) {
                BackendItemRouteResult.UNAVAILABLE
            } else {
                backend.requestQuietMode(profile, quiet = action == ProfileItemAction.PAUSE)
            }
        }
    }

    private suspend fun route(
        identity: AppActivityIdentity,
        action: (AppActivityIdentity) -> BackendItemRouteResult,
    ): ItemPlatformResult = withContext(dispatcher) {
        try {
            if (!backend.resolve(identity)) return@withContext ItemPlatformResult.Unavailable
            map(action(identity))
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: SecurityException) {
            ItemPlatformResult.Denied
        } catch (_: RuntimeException) {
            ItemPlatformResult.Failed
        }
    }

    private suspend fun destructiveRoute(
        identity: AppActivityIdentity,
        permission: (BackendItemActionEligibility) -> BackendItemPermission,
        action: (AppActivityIdentity) -> BackendItemRouteResult,
    ): ItemPlatformResult = withContext(dispatcher) {
        try {
            when (permission(backend.eligibility(identity))) {
                BackendItemPermission.ALLOWED -> {
                    if (!backend.resolve(identity)) ItemPlatformResult.Unavailable else map(action(identity))
                }
                BackendItemPermission.DENIED -> ItemPlatformResult.Denied
                BackendItemPermission.UNAVAILABLE -> ItemPlatformResult.Unavailable
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: SecurityException) {
            ItemPlatformResult.Denied
        } catch (_: RuntimeException) {
            ItemPlatformResult.Failed
        }
    }

    private fun invokeBackend(action: () -> BackendItemRouteResult): ItemPlatformResult = try {
        map(action())
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (_: SecurityException) {
        ItemPlatformResult.Denied
    } catch (_: RuntimeException) {
        ItemPlatformResult.Failed
    }

    private fun map(result: BackendItemRouteResult): ItemPlatformResult = when (result) {
        BackendItemRouteResult.COMPLETED -> ItemPlatformResult.Completed
        BackendItemRouteResult.DENIED -> ItemPlatformResult.Denied
        BackendItemRouteResult.UNAVAILABLE -> ItemPlatformResult.Unavailable
        BackendItemRouteResult.FAILED -> ItemPlatformResult.Failed
    }
}

private fun BackendItemActionEligibility.toRuntime() = ItemActionEligibility(
    uninstall = uninstall.toRuntime(),
    disable = disable.toRuntime(),
)

private fun BackendItemPermission.toRuntime(): ItemActionPermission = when (this) {
    BackendItemPermission.ALLOWED -> ItemActionPermission.ALLOWED
    BackendItemPermission.DENIED -> ItemActionPermission.DENIED
    BackendItemPermission.UNAVAILABLE -> ItemActionPermission.UNAVAILABLE
}

internal enum class BackendItemRouteResult { COMPLETED, DENIED, UNAVAILABLE, FAILED }

internal enum class BackendItemPermission { ALLOWED, DENIED, UNAVAILABLE }

internal data class BackendItemActionEligibility(
    val uninstall: BackendItemPermission,
    val disable: BackendItemPermission,
) {
    companion object {
        val None = BackendItemActionEligibility(
            BackendItemPermission.UNAVAILABLE,
            BackendItemPermission.UNAVAILABLE,
        )
    }
}

internal data class FrameworkItemEligibilityFacts(
    val packageAndActivityAvailable: Boolean,
    val devicePolicyKnown: Boolean,
    val uninstallRestricted: Boolean,
    val appsControlRestricted: Boolean,
    val uninstallBlockedByDevicePolicy: Boolean,
    val deviceAdminOrOwner: Boolean,
    val systemApp: Boolean,
    val updatedSystemApp: Boolean,
    val uninstallHandlerAvailable: Boolean,
)

internal fun evaluateFrameworkEligibility(
    facts: FrameworkItemEligibilityFacts,
): BackendItemActionEligibility {
    if (!facts.packageAndActivityAvailable || !facts.devicePolicyKnown) {
        return BackendItemActionEligibility.None
    }
    return BackendItemActionEligibility(
        uninstall = if (!facts.uninstallRestricted &&
            !facts.uninstallBlockedByDevicePolicy &&
            !facts.deviceAdminOrOwner &&
            (!facts.systemApp || facts.updatedSystemApp) &&
            facts.uninstallHandlerAvailable
        ) BackendItemPermission.ALLOWED else BackendItemPermission.DENIED,
        disable = if (!facts.appsControlRestricted && !facts.deviceAdminOrOwner && facts.systemApp) {
            BackendItemPermission.ALLOWED
        } else {
            BackendItemPermission.DENIED
        },
    )
}

internal interface ItemActionBackend {
    fun eligibility(identity: AppActivityIdentity): BackendItemActionEligibility
    fun resolve(identity: AppActivityIdentity): Boolean
    fun openDetails(identity: AppActivityIdentity): BackendItemRouteResult
    fun requestUninstall(identity: AppActivityIdentity): BackendItemRouteResult
    fun openDisableSettings(identity: AppActivityIdentity): BackendItemRouteResult
    fun isManagedProfile(profile: ProfileSerial): Boolean
    fun requestQuietMode(profile: ProfileSerial, quiet: Boolean): BackendItemRouteResult
}

private class FrameworkItemActionBackend(context: Context) : ItemActionBackend {
    private val applicationContext = context.applicationContext
    private val launcherApps = applicationContext.getSystemService(LauncherApps::class.java)
    private val userManager = applicationContext.getSystemService(UserManager::class.java)
    private val devicePolicyManager = applicationContext.getSystemService(DevicePolicyManager::class.java)

    override fun eligibility(identity: AppActivityIdentity): BackendItemActionEligibility {
        val user = userManager.getUserForSerialNumber(identity.profile.value)
            ?: return BackendItemActionEligibility.None
        val component = ComponentName(identity.packageName.value, identity.activityName.value)
        val application = try {
            launcherApps.getApplicationInfo(identity.packageName.value, 0, user)
        } catch (_: RuntimeException) {
            return BackendItemActionEligibility.None
        }
        val exactActivityAvailable = runCatching {
            launcherApps.isPackageEnabled(identity.packageName.value, user) &&
                launcherApps.isActivityEnabled(component, user) &&
                launcherApps.getActivityList(identity.packageName.value, user).any {
                    it.componentName == component
                }
        }.getOrDefault(false)
        if (!application.enabled || !exactActivityAvailable) return BackendItemActionEligibility.None
        val restrictions = try {
            userManager.getUserRestrictions(user)
        } catch (_: SecurityException) {
            return BackendItemActionEligibility.None
        }
        val uninstallRestricted = restrictions.getBoolean(UserManager.DISALLOW_UNINSTALL_APPS)
        val appsControlRestricted = restrictions.getBoolean(UserManager.DISALLOW_APPS_CONTROL)
        val devicePolicy = if (user == Process.myUserHandle()) {
            try {
                CurrentUserDevicePolicy(
                    known = true,
                    uninstallBlocked = devicePolicyManager.isUninstallBlocked(
                        null,
                        identity.packageName.value,
                    ),
                    adminOrOwner = devicePolicyManager.getActiveAdmins()
                        ?.any { it.packageName == identity.packageName.value } == true ||
                        devicePolicyManager.isDeviceOwnerApp(identity.packageName.value) ||
                        devicePolicyManager.isProfileOwnerApp(identity.packageName.value),
                )
            } catch (_: SecurityException) {
                CurrentUserDevicePolicy.Unknown
            }
        } else {
            // Public launcher APIs do not expose package-specific device policy for another user.
            CurrentUserDevicePolicy.Unknown
        }
        val isSystem = application.flags and ApplicationInfo.FLAG_SYSTEM != 0
        val isUpdatedSystem = application.flags and ApplicationInfo.FLAG_UPDATED_SYSTEM_APP != 0
        val uninstallHandlerAvailable = Intent(
            Intent.ACTION_DELETE,
            Uri.fromParts("package", identity.packageName.value, null),
        ).resolveActivity(applicationContext.packageManager) != null

        return evaluateFrameworkEligibility(
            FrameworkItemEligibilityFacts(
                packageAndActivityAvailable = application.enabled && exactActivityAvailable,
                devicePolicyKnown = devicePolicy.known,
                uninstallRestricted = uninstallRestricted,
                appsControlRestricted = appsControlRestricted,
                uninstallBlockedByDevicePolicy = devicePolicy.uninstallBlocked,
                deviceAdminOrOwner = devicePolicy.adminOrOwner,
                systemApp = isSystem,
                updatedSystemApp = isUpdatedSystem,
                uninstallHandlerAvailable = uninstallHandlerAvailable,
            ),
        )
    }

    private data class CurrentUserDevicePolicy(
        val known: Boolean,
        val uninstallBlocked: Boolean,
        val adminOrOwner: Boolean,
    ) {
        companion object {
            val Unknown = CurrentUserDevicePolicy(
                known = false,
                uninstallBlocked = true,
                adminOrOwner = true,
            )
        }
    }

    override fun resolve(identity: AppActivityIdentity): Boolean {
        val user = userManager.getUserForSerialNumber(identity.profile.value) ?: return false
        return runCatching {
            val component = ComponentName(identity.packageName.value, identity.activityName.value)
            launcherApps.getApplicationInfo(identity.packageName.value, 0, user).enabled &&
                launcherApps.isPackageEnabled(identity.packageName.value, user) &&
                launcherApps.isActivityEnabled(component, user) &&
                launcherApps.getActivityList(identity.packageName.value, user).any {
                    it.componentName == component
                }
        }.getOrDefault(false)
    }

    override fun openDetails(identity: AppActivityIdentity): BackendItemRouteResult {
        val user = userManager.getUserForSerialNumber(identity.profile.value)
            ?: return BackendItemRouteResult.UNAVAILABLE
        launcherApps.startAppDetailsActivity(
            ComponentName(identity.packageName.value, identity.activityName.value),
            user,
            null,
            Bundle.EMPTY,
        )
        return BackendItemRouteResult.COMPLETED
    }

    override fun requestUninstall(identity: AppActivityIdentity): BackendItemRouteResult {
        val user = userManager.getUserForSerialNumber(identity.profile.value)
            ?: return BackendItemRouteResult.UNAVAILABLE
        val intent = Intent(
            Intent.ACTION_DELETE,
            Uri.fromParts("package", identity.packageName.value, null),
        ).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            putExtra(Intent.EXTRA_USER, user)
        }
        if (intent.resolveActivity(applicationContext.packageManager) == null) {
            return BackendItemRouteResult.UNAVAILABLE
        }
        applicationContext.startActivity(intent)
        return BackendItemRouteResult.COMPLETED
    }

    override fun openDisableSettings(identity: AppActivityIdentity): BackendItemRouteResult =
        openDetails(identity)

    override fun isManagedProfile(profile: ProfileSerial): Boolean {
        val user = userManager.getUserForSerialNumber(profile.value) ?: return false
        return launcherApps.getLauncherUserInfo(user)?.userType == UserManager.USER_TYPE_PROFILE_MANAGED
    }

    override fun requestQuietMode(
        profile: ProfileSerial,
        quiet: Boolean,
    ): BackendItemRouteResult {
        val user = userManager.getUserForSerialNumber(profile.value)
            ?: return BackendItemRouteResult.UNAVAILABLE
        return if (userManager.requestQuietModeEnabled(quiet, user)) {
            BackendItemRouteResult.COMPLETED
        } else {
            BackendItemRouteResult.FAILED
        }
    }
}
