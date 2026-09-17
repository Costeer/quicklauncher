package org.quicklauncher.app

import android.app.job.JobParameters
import android.app.job.JobService
import java.io.File
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import org.quicklauncher.host.backup.android.AndroidBackupAuthorizationStore
import org.quicklauncher.host.backup.android.AndroidChargingBackupScheduler
import org.quicklauncher.host.backup.android.AndroidSafBackupFolder
import org.quicklauncher.host.backup.library.PortableBackupLibrary
import org.quicklauncher.host.backup.support.BackupDiagnosticCode
import org.quicklauncher.host.backup.support.BackupDiagnosticEvent
import org.quicklauncher.host.data.store.RegistryPlacementPolicy
import org.quicklauncher.host.data.store.registryConfigurationResolver
import org.quicklauncher.host.platform.persistence.AndroidLauncherStoreFactory
import org.quicklauncher.host.platform.theme.AndroidThemeAssetStore
import org.quicklauncher.host.runtime.SafeLayoutIds
import org.quicklauncher.registry.production.productionRegistry

/** Executes only Android's persisted charging-constrained job; it never keeps a passphrase. */
class AutomaticBackupJobService : JobService() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var running: Job? = null
    private val diagnosticLog by lazy {
        PrivateBackupDiagnosticLog(File(noBackupFilesDir, PrivateBackupDiagnosticLog.FILE_NAME))
    }

    override fun onStartJob(params: JobParameters): Boolean {
        val authorization = AndroidBackupAuthorizationStore(this).current()
        val decision = automaticBackupJobDecision(
            authorization?.automaticEnabled == true,
            backupScheduler().isInNightlyWindow(),
        )
        if (decision == AutomaticBackupJobDecision.DISABLED) return false
        running = scope.launch {
            executeAutomaticBackupJob(
                operation = {
                    if (decision == AutomaticBackupJobDecision.LATE) return@executeAutomaticBackupJob null
                    val activeAuthorization = requireNotNull(authorization)
                    val store = AndroidLauncherStoreFactory(this@AutomaticBackupJobService).open(
                        MainActivity.DATABASE_NAME,
                        registryConfigurationResolver(productionRegistry),
                        RegistryPlacementPolicy(productionRegistry),
                    )
                    try {
                        val webAdapters = PersistedWebAdapterBackupPort(this@AutomaticBackupJobService)
                        val library = PortableBackupLibrary(
                            AndroidSafBackupFolder(contentResolver, activeAuthorization.treeUri),
                            store,
                            knownContribution = { id ->
                                id == SafeLayoutIds.CONTRIBUTION ||
                                    productionRegistry.entries.any { entry -> entry.descriptor.metadata.id == id }
                            },
                            sectionSource = CompositeBackupSectionSource(
                                listOf(
                                    ThemeAssetBackupSectionSource(
                                        AndroidThemeAssetStore(this@AutomaticBackupJobService),
                                    ),
                                    webAdapters,
                                ),
                            ),
                            launcherExtensionSource = webAdapters,
                        )
                        automaticBackupDiagnosticCode(library.createAutomatic())
                    } finally {
                        runCatching { store.close() }
                    }
                },
                record = { outcome ->
                    diagnosticLog.record(
                        BackupDiagnosticEvent(System.currentTimeMillis().coerceAtLeast(0L), outcome),
                    )
                },
                scheduleSuccessor = ::scheduleNextNight,
                finish = { jobFinished(params, false) },
            )
        }
        return true
    }

    override fun onStopJob(params: JobParameters): Boolean {
        running?.cancel()
        running = null
        scheduleNextNight()
        return false
    }

    override fun onDestroy() {
        scope.cancel()
        super.onDestroy()
    }

    private fun scheduleNextNight() {
        val authorizationStore = AndroidBackupAuthorizationStore(this)
        val authorization = authorizationStore.current()
        if (authorization?.automaticEnabled != true) return
        val scheduled = AutomaticBackupPreferencePolicy.afterEnableRequest {
            runCatching { backupScheduler().schedule() }.getOrDefault(false)
        }
        if (!scheduled) authorizationStore.setAutomaticEnabled(false)
    }

    private fun backupScheduler(): AndroidChargingBackupScheduler = AndroidChargingBackupScheduler(
        getSystemService(android.app.job.JobScheduler::class.java),
        android.content.ComponentName(this, AutomaticBackupJobService::class.java),
    )
}

internal enum class AutomaticBackupJobDecision { DISABLED, LATE, RUN }

internal fun automaticBackupJobDecision(
    automaticEnabled: Boolean,
    inNightlyWindow: Boolean,
): AutomaticBackupJobDecision = when {
    !automaticEnabled -> AutomaticBackupJobDecision.DISABLED
    !inNightlyWindow -> AutomaticBackupJobDecision.LATE
    else -> AutomaticBackupJobDecision.RUN
}

internal suspend fun executeAutomaticBackupJob(
    operation: suspend () -> BackupDiagnosticCode?,
    record: (BackupDiagnosticCode) -> Unit,
    scheduleSuccessor: () -> Unit,
    finish: () -> Unit,
) {
    var interrupted = false
    try {
        operation()?.let(record)
    } catch (cancelled: CancellationException) {
        interrupted = true
        throw cancelled
    } catch (_: Exception) {
        record(BackupDiagnosticCode.EXPORT_FAILED)
    } finally {
        scheduleSuccessor()
        if (!interrupted) finish()
    }
}
