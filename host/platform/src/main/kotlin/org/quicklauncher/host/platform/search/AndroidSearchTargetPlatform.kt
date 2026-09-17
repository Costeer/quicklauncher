package org.quicklauncher.host.platform.search

import android.app.Activity
import android.content.ActivityNotFoundException
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.provider.Settings
import java.util.Locale
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.quicklauncher.host.platform.notifications.QuicklauncherNotificationListenerService
import org.quicklauncher.host.runtime.catalog.AppCatalog
import org.quicklauncher.host.runtime.catalog.AppLaunchResult
import org.quicklauncher.host.runtime.search.SearchExecutionResult
import org.quicklauncher.host.runtime.search.SearchExecutionTarget
import org.quicklauncher.host.runtime.search.SearchRecoveryAction
import org.quicklauncher.host.runtime.search.SearchSettingsRoute
import org.quicklauncher.host.runtime.search.SearchSettingsAvailability
import org.quicklauncher.host.runtime.search.SearchTargetPlatform
import org.quicklauncher.host.runtime.search.WebProviderId
import org.quicklauncher.host.runtime.shortcuts.ShortcutCoordinator
import org.quicklauncher.host.runtime.shortcuts.ShortcutLaunchResult

fun interface ContactTargetLauncher {
    suspend fun launch(target: SearchExecutionTarget.Contact): SearchExecutionResult
}

fun interface FileTargetLauncher {
    suspend fun launch(target: SearchExecutionTarget.File): SearchExecutionResult
}

fun interface SearchRecoveryLauncher {
    suspend fun launch(action: SearchRecoveryAction): SearchExecutionResult
}

class AndroidSearchTargetPlatform(
    context: Context,
    private val appCatalog: AppCatalog,
    private val shortcuts: ShortcutCoordinator,
    private val contacts: ContactTargetLauncher,
    private val files: FileTargetLauncher,
    private val recovery: SearchRecoveryLauncher,
) : SearchTargetPlatform {
    private val settings = AndroidSettingsRouteLauncher(context)
    private val web = AndroidWebSearchLauncher(context)

    override suspend fun execute(target: SearchExecutionTarget): SearchExecutionResult = try {
        when (target) {
            is SearchExecutionTarget.App -> appCatalog.launch(target.identity).toSearchResult()
            is SearchExecutionTarget.Shortcut -> shortcuts.launch(target.identity).toSearchResult()
            is SearchExecutionTarget.Contact -> contacts.launch(target)
            is SearchExecutionTarget.File -> files.launch(target)
            is SearchExecutionTarget.Setting -> settings.launch(target)
            is SearchExecutionTarget.Web -> web.launch(target)
            is SearchExecutionTarget.Information -> recovery.launch(target.recovery)
        }
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (_: RuntimeException) {
        SearchExecutionResult.RecoverableFailure
    }

    private fun AppLaunchResult.toSearchResult(): SearchExecutionResult = when (this) {
        AppLaunchResult.Launched -> SearchExecutionResult.Succeeded
        AppLaunchResult.ProfileLocked -> SearchExecutionResult.MissingAccess
        AppLaunchResult.MissingProfile,
        AppLaunchResult.ActivityUnavailable,
        AppLaunchResult.Closed,
        -> SearchExecutionResult.Unavailable
        AppLaunchResult.SecurityDenied -> SearchExecutionResult.MissingAccess
        is AppLaunchResult.Failed -> SearchExecutionResult.RecoverableFailure
    }

    private fun ShortcutLaunchResult.toSearchResult(): SearchExecutionResult = when (this) {
        ShortcutLaunchResult.Launched -> SearchExecutionResult.Succeeded
        is ShortcutLaunchResult.Unavailable -> SearchExecutionResult.Unavailable
        ShortcutLaunchResult.Closed -> SearchExecutionResult.Unavailable
    }
}

class AndroidSettingsRouteLauncher(private val context: Context) : SearchSettingsAvailability {
    suspend fun launch(target: SearchExecutionTarget.Setting): SearchExecutionResult =
        withContext(Dispatchers.Main.immediate) {
            val requested = buildIntent(target) ?: return@withContext SearchExecutionResult.Rejected
            if (isCallable(requested.intent)) {
                try {
                    context.startActivity(requested.intent.addNewTaskIfNeeded())
                    return@withContext SearchExecutionResult.Succeeded
                } catch (_: ActivityNotFoundException) {
                    // Resolution raced with launch. A declared public fallback is revalidated below.
                } catch (_: SecurityException) {
                    // GrapheneOS private routes may be exported but privileged; use their public parent.
                }
            }
            val fallback = requested.fallback ?: return@withContext SearchExecutionResult.Unavailable
            if (!isCallable(fallback)) return@withContext SearchExecutionResult.Unavailable
            try {
                context.startActivity(fallback.addNewTaskIfNeeded())
                SearchExecutionResult.Succeeded
            } catch (_: ActivityNotFoundException) {
                SearchExecutionResult.Unavailable
            } catch (_: SecurityException) {
                SearchExecutionResult.MissingAccess
            }
        }

    override fun isCallable(target: SearchExecutionTarget.Setting): Boolean {
        val route = buildIntent(target) ?: return false
        return isCallable(route.intent) || route.fallback?.let(::isCallable) == true
    }

    internal fun requestedIntent(target: SearchExecutionTarget.Setting): Intent? =
        buildIntent(target)?.intent

    private fun buildIntent(target: SearchExecutionTarget.Setting): RouteIntent? {
        val packageUri = target.packageName?.let { Uri.Builder().scheme("package").opaquePart(it.value).build() }
        val publicIntent = when (target.route) {
            SearchSettingsRoute.ROOT -> Intent(Settings.ACTION_SETTINGS)
            SearchSettingsRoute.APPLICATION_DETAILS -> packageUri?.let {
                Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, it)
            }
            SearchSettingsRoute.WIFI -> Intent(Settings.ACTION_WIFI_SETTINGS)
            SearchSettingsRoute.BLUETOOTH -> Intent(Settings.ACTION_BLUETOOTH_SETTINGS)
            SearchSettingsRoute.SECURITY -> Intent(Settings.ACTION_SECURITY_SETTINGS)
            SearchSettingsRoute.PRIVACY -> Intent(Settings.ACTION_PRIVACY_SETTINGS)
            SearchSettingsRoute.ACCESSIBILITY -> Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)
            SearchSettingsRoute.NOTIFICATION_LISTENER -> Intent(
                Settings.ACTION_NOTIFICATION_LISTENER_DETAIL_SETTINGS,
            ).putExtra(
                Settings.EXTRA_NOTIFICATION_LISTENER_COMPONENT_NAME,
                ComponentName(context, QuicklauncherNotificationListenerService::class.java).flattenToString(),
            )
            SearchSettingsRoute.GRAPHENE_NATIVE_DEBUGGING -> null
            SearchSettingsRoute.GRAPHENE_HARDENED_MALLOC -> null
        }
        if (publicIntent != null) return RouteIntent(publicIntent, null)
        if (packageUri == null) return null
        val privateAction = when (target.route) {
            SearchSettingsRoute.GRAPHENE_NATIVE_DEBUGGING ->
                "android.settings.OPEN_APP_NATIVE_DEBUGGING_SETTINGS"
            SearchSettingsRoute.GRAPHENE_HARDENED_MALLOC ->
                "android.settings.OPEN_APP_HARDENED_MALLOC_SETTINGS"
            else -> return null
        }
        val privateComponent = when (target.route) {
            SearchSettingsRoute.GRAPHENE_NATIVE_DEBUGGING ->
                "com.android.settings.Settings\$AppNativeDebuggingActivity"
            SearchSettingsRoute.GRAPHENE_HARDENED_MALLOC ->
                "com.android.settings.Settings\$AppHardenedMallocActivity"
            else -> return null
        }
        val fallback = target.fallbackRoute?.let { fallbackRoute ->
            buildIntent(SearchExecutionTarget.Setting(fallbackRoute, target.packageName))?.intent
        }
        return RouteIntent(
            intent = Intent(privateAction, packageUri).setComponent(
                ComponentName(SETTINGS_PACKAGE, privateComponent),
            ),
            fallback = fallback,
        )
    }

    private fun isCallable(intent: Intent): Boolean {
        val resolved = context.packageManager.resolveActivity(intent, PackageManager.MATCH_DEFAULT_ONLY)
            ?: return false
        val info = resolved.activityInfo ?: return false
        if (!info.enabled || !info.exported) return false
        if (intent.`package` != null && info.packageName != intent.`package`) return false
        if (intent.component != null &&
            intent.component != ComponentName(info.packageName, info.name)
        ) return false
        val permission = info.permission
        return permission == null || context.checkSelfPermission(permission) == PackageManager.PERMISSION_GRANTED
    }

    private fun Intent.addNewTaskIfNeeded(): Intent = apply {
        if (context !is Activity) addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    }

    private data class RouteIntent(
        val intent: Intent,
        val fallback: Intent?,
    )

    private companion object {
        const val SETTINGS_PACKAGE = "com.android.settings"
    }
}

class AndroidWebSearchLauncher(private val context: Context) {
    suspend fun launch(target: SearchExecutionTarget.Web): SearchExecutionResult =
        withContext(Dispatchers.Main.immediate) {
            val uri = buildUri(target) ?: return@withContext SearchExecutionResult.Rejected
            val intent = Intent(Intent.ACTION_VIEW, uri).addCategory(Intent.CATEGORY_BROWSABLE)
            if (context.packageManager.resolveActivity(intent, PackageManager.MATCH_DEFAULT_ONLY) == null) {
                return@withContext SearchExecutionResult.Unavailable
            }
            if (context !is Activity) intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            try {
                context.startActivity(intent)
                SearchExecutionResult.Succeeded
            } catch (_: ActivityNotFoundException) {
                SearchExecutionResult.Unavailable
            } catch (_: SecurityException) {
                SearchExecutionResult.MissingAccess
            }
        }

    internal fun buildUri(target: SearchExecutionTarget.Web): Uri? {
        val catalog = when (target.provider) {
            WebProviderId.DUCKDUCKGO -> WebCatalog("duckduckgo.com", "/", "q")
            WebProviderId.BRAVE -> WebCatalog("search.brave.com", "/search", "q")
        }
        val uri = Uri.Builder()
            .scheme("https")
            .authority(catalog.host)
            .path(catalog.path)
            .appendQueryParameter(catalog.parameter, target.query.value)
            .build()
        return uri.takeIf { validate(it, catalog) }
    }

    internal fun validate(uri: Uri, catalog: WebCatalog): Boolean {
        if (uri.scheme?.lowercase(Locale.ROOT) != "https") return false
        if (uri.host?.lowercase(Locale.ROOT) != catalog.host) return false
        if (uri.port != -1 && uri.port != 443) return false
        if (uri.userInfo != null || uri.fragment != null) return false
        if (uri.path != catalog.path) return false
        if (uri.encodedQuery.isNullOrBlank() || uri.toString().length > MAX_URI_LENGTH) return false
        if (uri.toString().any { it.code < 0x20 }) return false
        return true
    }

    internal data class WebCatalog(val host: String, val path: String, val parameter: String)

    private companion object {
        const val MAX_URI_LENGTH = 4_096
    }
}
