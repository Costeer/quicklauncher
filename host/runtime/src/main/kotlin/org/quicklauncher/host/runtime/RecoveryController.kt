package org.quicklauncher.host.runtime

import java.security.MessageDigest
import java.util.concurrent.CancellationException
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext
import org.quicklauncher.contracts.domain.CrashMarkerId
import org.quicklauncher.contracts.domain.ModuleInstanceId
import org.quicklauncher.contracts.domain.StableKey
import org.quicklauncher.host.data.store.CommitResult
import org.quicklauncher.host.data.store.CrashMarkerRecord
import org.quicklauncher.host.data.store.LauncherEdit
import org.quicklauncher.host.data.store.LauncherSnapshot
import org.quicklauncher.host.data.store.LauncherStore
import org.quicklauncher.host.data.store.LauncherTransaction
import org.quicklauncher.host.data.store.StoreRejection
import org.quicklauncher.host.data.store.StoreRejectionCode
import org.quicklauncher.host.runtime.diagnostics.DiagnosticErrorClass
import org.quicklauncher.host.runtime.diagnostics.DiagnosticEvent
import org.quicklauncher.host.runtime.diagnostics.DiagnosticEventCode
import org.quicklauncher.host.runtime.diagnostics.DiagnosticLog
import org.quicklauncher.host.runtime.diagnostics.DiagnosticStackFrame

fun interface RecoveryClock {
    fun nowEpochMillis(): Long
}

sealed interface RendererRestoreResult {
    data object Restored : RendererRestoreResult

    data class SafeFallback(
        val failedInstanceId: ModuleInstanceId,
        val code: String,
    ) : RendererRestoreResult

    data class Rejected(val rejection: StoreRejection) : RendererRestoreResult
}

class RecoveryController(
    private val store: LauncherStore,
    private val diagnostics: DiagnosticLog,
    private val appVersion: String,
    private val clock: RecoveryClock = RecoveryClock(System::currentTimeMillis),
) {
    init {
        require(appVersion.isNotBlank()) { "Recovery app version must not be blank" }
    }

    suspend fun restoreSelectedLayout(
        instanceId: ModuleInstanceId,
        restore: suspend () -> Unit,
    ): RendererRestoreResult {
        val before = store.read()
        val unresolved = before.crashMarkers.singleOrNull {
            it.moduleInstanceId == instanceId && it.outcome == null
        }
        if (unresolved != null && unresolved.startupAttempt >= CRASH_LOOP_THRESHOLD) {
            safeDiagnostic(DiagnosticEvent(DiagnosticEventCode.CRASH_LOOP_DETECTED, instanceId))
            return quarantine(
                instanceId = instanceId,
                failure = null,
                code = CRASH_LOOP_CODE,
                outcome = CRASH_LOOP_OUTCOME,
            )
        }
        val now = clock.nowEpochMillis()
        val marker = CrashMarkerRecord(
            id = unresolved?.id ?: markerId(instanceId),
            moduleInstanceId = instanceId,
            appVersion = appVersion,
            startupAttempt = (unresolved?.startupAttempt ?: 0) + 1,
            timestampEpochMillis = now,
            outcome = null,
            firstObservedAtEpochMillis = unresolved?.firstObservedAtEpochMillis
                ?: now,
        )
        val started = commit(before, listOf(LauncherEdit.BeginStartupRestore(marker)))
        if (started is CommitAttempt.Rejected) return RendererRestoreResult.Rejected(started.rejection)
        safeDiagnostic(DiagnosticEvent(DiagnosticEventCode.STARTUP_ATTEMPT, instanceId))

        return try {
            restore()
            val completed = commitLatest(LauncherEdit.CompleteStartupRestore(instanceId))
            if (completed is CommitAttempt.Rejected) {
                RendererRestoreResult.Rejected(completed.rejection)
            } else {
                safeDiagnostic(DiagnosticEvent(DiagnosticEventCode.STARTUP_COMPLETED, instanceId))
                RendererRestoreResult.Restored
            }
        } catch (cancelled: CancellationException) {
            withContext(NonCancellable) {
                commitLatest(LauncherEdit.CompleteStartupRestore(instanceId))
            }
            throw cancelled
        } catch (failure: RuntimeException) {
            quarantine(
                instanceId = instanceId,
                failure = failure,
                code = RENDERER_FAILURE_CODE,
                outcome = RENDERER_FAILURE_OUTCOME,
            )
        }
    }

    /** Records an in-process renderer failure against the instance that actually threw. */
    suspend fun reportRendererFailure(
        instanceId: ModuleInstanceId,
        failure: RuntimeException,
    ): StoreRejection? {
        val snapshot = store.read()
        val instance = snapshot.moduleInstances.singleOrNull { it.id == instanceId }
            ?: return StoreRejection(
                StoreRejectionCode.RECOVERY_STATE_MISMATCH,
                "Renderer recovery requires a known module instance",
            )
        if (instance.contributionId == SafeLayoutIds.CONTRIBUTION) {
            return StoreRejection(
                StoreRejectionCode.RECOVERY_STATE_MISMATCH,
                "The host-owned safe layout cannot be quarantined",
            )
        }
        val existingQuarantine = instance.status as? org.quicklauncher.host.data.store.ModuleInstanceStatus.Quarantined
        if (existingQuarantine?.origin == org.quicklauncher.host.data.store.ModuleQuarantineOrigin.RENDERER) {
            return null
        }
        val selectedRoot = snapshot.destinationLayouts.any {
            it.layoutInstanceId == instanceId && it.selected
        }
        if (selectedRoot) {
            return when (
                val result = quarantine(
                    instanceId = instanceId,
                    failure = failure,
                    code = RENDERER_FAILURE_CODE,
                    outcome = RENDERER_FAILURE_OUTCOME,
                )
            ) {
                RendererRestoreResult.Restored -> null
                is RendererRestoreResult.SafeFallback -> null
                is RendererRestoreResult.Rejected -> result.rejection
            }
        }
        val unresolved = snapshot.crashMarkers.singleOrNull {
            it.moduleInstanceId == instanceId && it.outcome == null
        }
        val now = clock.nowEpochMillis()
        val edits = buildList {
            if (unresolved == null) {
                add(
                    LauncherEdit.BeginStartupRestore(
                        CrashMarkerRecord(
                            id = markerId(instanceId),
                            moduleInstanceId = instanceId,
                            appVersion = appVersion,
                            startupAttempt = 1,
                            timestampEpochMillis = now,
                            outcome = null,
                        ),
                    ),
                )
            }
            add(
                LauncherEdit.QuarantineRenderer(
                    moduleInstanceId = instanceId,
                    code = RENDERER_FAILURE_CODE,
                    message = "Contribution renderer failed",
                    outcome = RENDERER_FAILURE_OUTCOME,
                ),
            )
        }
        val committed = commit(
            snapshot,
            edits,
        )
        return when (committed) {
            is CommitAttempt.Rejected -> committed.rejection
            is CommitAttempt.Committed -> {
                safeDiagnostic(rendererFailureEvent(instanceId, failure))
                safeDiagnostic(DiagnosticEvent(DiagnosticEventCode.INSTANCE_QUARANTINED, instanceId))
                null
            }
        }
    }

    private suspend fun quarantine(
        instanceId: ModuleInstanceId,
        failure: RuntimeException?,
        code: String,
        outcome: StableKey,
    ): RendererRestoreResult {
        val snapshot = store.read()
        val selected = snapshot.destinationLayouts.firstOrNull {
            it.layoutInstanceId == instanceId && it.selected
        } ?: return RendererRestoreResult.Rejected(
            StoreRejection(
                StoreRejectionCode.RECOVERY_STATE_MISMATCH,
                "Renderer recovery requires the selected layout root",
            ),
        )
        val safe = snapshot.destinationLayouts.firstOrNull {
            it.destinationId == selected.destinationId &&
                it.layoutContributionId == SafeLayoutIds.CONTRIBUTION
        } ?: return RendererRestoreResult.Rejected(
            StoreRejection(
                StoreRejectionCode.RECOVERY_STATE_MISMATCH,
                "Renderer recovery requires the retained safe layout",
            ),
        )
        val unresolved = snapshot.crashMarkers.singleOrNull {
            it.moduleInstanceId == instanceId && it.outcome == null
        }
        val edits = buildList {
            if (unresolved == null) {
                val previous = snapshot.crashMarkers.singleOrNull { it.moduleInstanceId == instanceId }
                val now = clock.nowEpochMillis()
                add(
                    LauncherEdit.BeginStartupRestore(
                        CrashMarkerRecord(
                            id = previous?.id ?: markerId(instanceId),
                            moduleInstanceId = instanceId,
                            appVersion = appVersion,
                            startupAttempt = (previous?.startupAttempt ?: 0) + 1,
                            timestampEpochMillis = now,
                            outcome = null,
                            firstObservedAtEpochMillis = previous?.firstObservedAtEpochMillis ?: now,
                        ),
                    ),
                )
            }
            add(LauncherEdit.QuarantineRenderer(
                moduleInstanceId = instanceId,
                code = code,
                message = "Renderer failed during startup restoration",
                outcome = outcome,
            ))
            add(LauncherEdit.SelectLayout(selected.destinationId, safe.layoutInstanceId))
        }
        return when (val committed = commit(snapshot, edits)) {
            is CommitAttempt.Rejected -> RendererRestoreResult.Rejected(committed.rejection)
            is CommitAttempt.Committed -> {
                if (failure != null) safeDiagnostic(rendererFailureEvent(instanceId, failure))
                safeDiagnostic(DiagnosticEvent(DiagnosticEventCode.INSTANCE_QUARANTINED, instanceId))
                RendererRestoreResult.SafeFallback(instanceId, code)
            }
        }
    }

    private fun rendererFailureEvent(
        instanceId: ModuleInstanceId,
        failure: RuntimeException,
    ): DiagnosticEvent = DiagnosticEvent(
        code = DiagnosticEventCode.RENDERER_FAILED,
        instanceId = instanceId,
        errorClass = DiagnosticErrorClass.parse(failure.javaClass.name),
        stackFrames = failure.stackTrace
            .asSequence()
            .mapNotNull { frame ->
                runCatching {
                    DiagnosticStackFrame.parse(frame.className, frame.methodName, frame.lineNumber)
                }.getOrNull()
            }
            .take(MAXIMUM_DIAGNOSTIC_FRAMES)
            .toList()
            .ifEmpty {
                listOf(
                    DiagnosticStackFrame.parse(
                        "org.quicklauncher.host.runtime.RecoveryController",
                        "restoreSelectedLayout",
                        -1,
                    ),
                )
            },
    )

    private suspend fun safeDiagnostic(event: DiagnosticEvent) {
        try {
            diagnostics.record(event)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: RuntimeException) {
            // Diagnostics must never prevent safe startup or recovery.
        }
    }

    private suspend fun commitLatest(edit: LauncherEdit): CommitAttempt =
        commit(store.read(), listOf(edit))

    private suspend fun commit(
        snapshot: LauncherSnapshot,
        edits: List<LauncherEdit>,
    ): CommitAttempt = when (val result = store.commit(LauncherTransaction(snapshot.revision, edits))) {
        is CommitResult.Committed -> CommitAttempt.Committed(result.state)
        is CommitResult.Rejected -> CommitAttempt.Rejected(result.reason)
    }

    private fun markerId(instanceId: ModuleInstanceId): CrashMarkerId {
        val suffix = MessageDigest.getInstance("SHA-256")
            .digest(instanceId.value.toByteArray(Charsets.UTF_8))
            .take(10)
            .joinToString(separator = "") { byte -> "%02x".format(byte) }
        return CrashMarkerId.parse("org.quicklauncher.crash/m$suffix")
    }

    private sealed interface CommitAttempt {
        data class Committed(val snapshot: LauncherSnapshot) : CommitAttempt
        data class Rejected(val rejection: StoreRejection) : CommitAttempt
    }

    private companion object {
        const val RENDERER_FAILURE_CODE = "renderer.exception"
        const val CRASH_LOOP_CODE = "renderer.crash-loop"
        const val CRASH_LOOP_THRESHOLD = 2
        const val MAXIMUM_DIAGNOSTIC_FRAMES = 24
        val RENDERER_FAILURE_OUTCOME: StableKey = StableKey.parse("renderer-failed")
        val CRASH_LOOP_OUTCOME: StableKey = StableKey.parse("crash-loop-detected")
    }
}
