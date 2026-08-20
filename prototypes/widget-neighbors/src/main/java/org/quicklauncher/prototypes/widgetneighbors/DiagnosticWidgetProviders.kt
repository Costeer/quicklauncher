package org.quicklauncher.prototypes.widgetneighbors

import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.Context
import android.os.SystemClock
import android.widget.RemoteViews

abstract class DiagnosticWidgetProvider : AppWidgetProvider() {
    protected abstract val diagnosticLabel: String

    override fun onUpdate(
        context: Context,
        appWidgetManager: AppWidgetManager,
        appWidgetIds: IntArray,
    ) {
        appWidgetIds.forEach { appWidgetId ->
            val views = RemoteViews(context.packageName, R.layout.diagnostic_widget).apply {
                setTextViewText(R.id.identity, "$diagnosticLabel\nID $appWidgetId")
                setChronometer(R.id.live_clock, SystemClock.elapsedRealtime(), "live %s", true)
            }
            appWidgetManager.updateAppWidget(appWidgetId, views)
        }
    }
}

class CurrentDiagnosticWidgetProvider : DiagnosticWidgetProvider() {
    override val diagnosticLabel: String = "CURRENT TEST WIDGET"
}

class NeighborDiagnosticWidgetProvider : DiagnosticWidgetProvider() {
    override val diagnosticLabel: String = "NEIGHBOR TEST WIDGET"
}
