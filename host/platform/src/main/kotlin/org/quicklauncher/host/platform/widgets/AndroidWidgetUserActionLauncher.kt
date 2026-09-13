package org.quicklauncher.host.platform.widgets

import android.app.Activity
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProviderInfo
import android.content.ComponentName
import android.content.Intent
import android.os.Bundle
import android.os.UserManager
import android.util.SizeF
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import org.quicklauncher.host.runtime.widgets.WidgetActionLaunchResult
import org.quicklauncher.host.runtime.widgets.WidgetSize
import org.quicklauncher.host.runtime.widgets.WidgetUserAction
import org.quicklauncher.host.runtime.widgets.WidgetUserActionLauncher
import org.quicklauncher.host.runtime.widgets.WidgetUserActionResult

/**
 * Owns bind-permission and provider-configuration activities. Callers receive typed results only;
 * the intents and profile handles never leave this module.
 */
@Composable
fun rememberAndroidWidgetUserActionLauncher(
    onResult: (WidgetUserActionResult) -> Unit,
): WidgetUserActionLauncher {
    val context = LocalContext.current
    val currentResultHandler by rememberUpdatedState(onResult)
    var pendingAction by remember { mutableStateOf<WidgetUserAction?>(null) }
    val activityLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.StartActivityForResult(),
    ) { result ->
        val completed = pendingAction
        pendingAction = null
        if (completed != null) {
            currentResultHandler(
                WidgetUserActionResult(
                    action = completed,
                    accepted = result.resultCode == Activity.RESULT_OK,
                ),
            )
        }
    }
    return remember(context, activityLauncher) {
        object : WidgetUserActionLauncher {
            override fun launch(action: WidgetUserAction): WidgetActionLaunchResult {
                if (pendingAction != null) return WidgetActionLaunchResult.Busy
                val manager = AppWidgetManager.getInstance(context)
                val intent = when (action) {
                    is WidgetUserAction.BindPermission -> {
                        val userManager = context.getSystemService(UserManager::class.java)
                        val user = userManager.getUserForSerialNumber(action.provider.profile.value)
                            ?: return WidgetActionLaunchResult.ProfileUnavailable
                        val component = ComponentName(
                            action.provider.packageName.value,
                            action.provider.className,
                        )
                        val present = manager.getInstalledProvidersForProfile(user).any {
                            it.provider == component &&
                                it.widgetCategory and AppWidgetProviderInfo.WIDGET_CATEGORY_HOME_SCREEN != 0
                        }
                        if (!present) return WidgetActionLaunchResult.ProviderUnavailable
                        Intent(AppWidgetManager.ACTION_APPWIDGET_BIND).apply {
                            putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, action.appWidgetId)
                            putExtra(AppWidgetManager.EXTRA_APPWIDGET_PROVIDER, component)
                            putExtra(AppWidgetManager.EXTRA_APPWIDGET_PROVIDER_PROFILE, user)
                            putExtra(AppWidgetManager.EXTRA_APPWIDGET_OPTIONS, action.size.options())
                        }
                    }
                    is WidgetUserAction.Configure -> {
                        if (manager.getAppWidgetInfo(action.appWidgetId)?.configure == null) {
                            return WidgetActionLaunchResult.ProviderUnavailable
                        }
                        WidgetConfigurationActivity.intent(context, action.appWidgetId)
                    }
                }
                pendingAction = action
                return try {
                    activityLauncher.launch(intent)
                    WidgetActionLaunchResult.Launched
                } catch (_: RuntimeException) {
                    pendingAction = null
                    WidgetActionLaunchResult.Failed("widget_system_activity_failed")
                }
            }
        }
    }
}

private fun WidgetSize.options(): Bundle = Bundle().apply {
    putInt(AppWidgetManager.OPTION_APPWIDGET_MIN_WIDTH, widthDp)
    putInt(AppWidgetManager.OPTION_APPWIDGET_MAX_WIDTH, widthDp)
    putInt(AppWidgetManager.OPTION_APPWIDGET_MIN_HEIGHT, heightDp)
    putInt(AppWidgetManager.OPTION_APPWIDGET_MAX_HEIGHT, heightDp)
    putInt(
        AppWidgetManager.OPTION_APPWIDGET_HOST_CATEGORY,
        AppWidgetProviderInfo.WIDGET_CATEGORY_HOME_SCREEN,
    )
    putParcelableArrayList(
        AppWidgetManager.OPTION_APPWIDGET_SIZES,
        arrayListOf(SizeF(widthDp.toFloat(), heightDp.toFloat())),
    )
}
