package org.quicklauncher.host.platform.widgets

import android.app.Activity
import android.appwidget.AppWidgetHost
import android.appwidget.AppWidgetManager
import android.content.Context
import android.content.Intent
import android.os.Bundle

/** Host-aware provider configuration bridge; no framework launch object leaves :host:platform. */
internal class WidgetConfigurationActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (savedInstanceState != null) return
        val appWidgetId = intent.getIntExtra(
            AppWidgetManager.EXTRA_APPWIDGET_ID,
            AppWidgetManager.INVALID_APPWIDGET_ID,
        )
        if (appWidgetId == AppWidgetManager.INVALID_APPWIDGET_ID) {
            finishCancelled()
            return
        }
        try {
            AppWidgetHost(this, QUICKLAUNCHER_WIDGET_HOST_ID)
                .startAppWidgetConfigureActivityForResult(
                    this,
                    appWidgetId,
                    0,
                    CONFIGURE_REQUEST,
                    null,
                )
        } catch (_: RuntimeException) {
            finishCancelled()
        }
    }

    @Deprecated("Framework callback required by AppWidgetHost")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode != CONFIGURE_REQUEST) return
        setResult(resultCode, data)
        finish()
    }

    private fun finishCancelled() {
        setResult(RESULT_CANCELED)
        finish()
    }

    companion object {
        private const val CONFIGURE_REQUEST = 0x5143

        fun intent(context: Context, appWidgetId: Int): Intent =
            Intent(context, WidgetConfigurationActivity::class.java).apply {
                putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, appWidgetId)
            }
    }
}
