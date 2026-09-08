package org.quicklauncher.app

import java.util.concurrent.atomic.AtomicInteger
import org.quicklauncher.host.runtime.LauncherRuntimeState

/** Debug-only in-process lifecycle evidence used by API 35 Home-role instrumentation. */
internal object HomeEntryRecorder {
    private val nextInstance = AtomicInteger()
    private val lock = Any()
    private var snapshot = HomeEntrySnapshot(0, 0, 0, 0, null, null, "")

    internal fun newActivityInstance(): Int =
        if (BuildConfig.DEBUG) nextInstance.incrementAndGet() else 0

    internal fun recordHome(instanceId: Int, fromNewIntent: Boolean) {
        if (!BuildConfig.DEBUG) return
        synchronized(lock) {
            snapshot = snapshot.copy(
                activityInstanceId = instanceId,
                homeEntries = snapshot.homeEntries + 1,
                homeNewIntents = snapshot.homeNewIntents + if (fromNewIntent) 1 else 0,
            )
        }
    }

    internal fun recordAppIcon(instanceId: Int) {
        if (!BuildConfig.DEBUG) return
        synchronized(lock) {
            snapshot = snapshot.copy(
                activityInstanceId = instanceId,
                appIconEntries = snapshot.appIconEntries + 1,
            )
        }
    }

    internal fun recordRuntimeState(state: LauncherRuntimeState) {
        if (!BuildConfig.DEBUG) return
        synchronized(lock) {
            snapshot = snapshot.copy(
                destinationId = state.currentDestinationId?.value,
                surface = state.surface.name,
                localQuery = state.localQuery,
            )
        }
    }

    internal fun snapshot(): HomeEntrySnapshot = synchronized(lock) { snapshot }

    internal fun resetForTests() {
        check(BuildConfig.DEBUG) { "Home-entry evidence is available only in debug builds" }
        synchronized(lock) {
            snapshot = HomeEntrySnapshot(0, 0, 0, 0, null, null, "")
        }
    }
}

internal data class HomeEntrySnapshot(
    val activityInstanceId: Int,
    val homeEntries: Int,
    val homeNewIntents: Int,
    val appIconEntries: Int,
    val destinationId: String?,
    val surface: String?,
    val localQuery: String,
)
