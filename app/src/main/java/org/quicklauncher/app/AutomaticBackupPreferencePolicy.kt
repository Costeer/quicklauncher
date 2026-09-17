package org.quicklauncher.app

internal object AutomaticBackupPreferencePolicy {
    fun afterEnableRequest(schedule: () -> Boolean): Boolean = schedule()

    fun reconcile(
        persistedEnabled: Boolean,
        hasScheduledJob: Boolean,
        schedule: () -> Boolean,
    ): Boolean = persistedEnabled && (hasScheduledJob || schedule())
}
