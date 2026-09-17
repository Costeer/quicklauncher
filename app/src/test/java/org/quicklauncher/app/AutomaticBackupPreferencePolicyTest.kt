package org.quicklauncher.app

import java.util.concurrent.CancellationException
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.quicklauncher.host.backup.support.BackupDiagnosticCode

class AutomaticBackupPreferencePolicyTest {
    @Test
    fun `job decision rejects disabled and late executions`() {
        assertEquals(
            AutomaticBackupJobDecision.DISABLED,
            automaticBackupJobDecision(automaticEnabled = false, inNightlyWindow = true),
        )
        assertEquals(
            AutomaticBackupJobDecision.LATE,
            automaticBackupJobDecision(automaticEnabled = true, inNightlyWindow = false),
        )
        assertEquals(
            AutomaticBackupJobDecision.RUN,
            automaticBackupJobDecision(automaticEnabled = true, inNightlyWindow = true),
        )
    }

    @Test
    fun `job failure records a typed outcome and always schedules and finishes`() = runTest {
        val events = mutableListOf<String>()

        executeAutomaticBackupJob(
            operation = { error("injected") },
            record = { events += "record:$it" },
            scheduleSuccessor = { events += "schedule" },
            finish = { events += "finish" },
        )

        assertEquals(
            listOf("record:${BackupDiagnosticCode.EXPORT_FAILED}", "schedule", "finish"),
            events,
        )
    }

    @Test
    fun `late job skips export but schedules and finishes`() = runTest {
        val events = mutableListOf<String>()

        executeAutomaticBackupJob(
            operation = { events += "skip"; null },
            record = { events += "record:$it" },
            scheduleSuccessor = { events += "schedule" },
            finish = { events += "finish" },
        )

        assertEquals(listOf("skip", "schedule", "finish"), events)
    }

    @Test
    fun `job interruption schedules a successor without finishing a stopped job`() = runTest {
        val events = mutableListOf<String>()

        try {
            executeAutomaticBackupJob(
                operation = { throw CancellationException("injected") },
                record = { events += "record:$it" },
                scheduleSuccessor = { events += "schedule" },
                finish = { events += "finish" },
            )
        } catch (_: CancellationException) {
            events += "cancelled"
        }

        assertEquals(listOf("schedule", "cancelled"), events)
    }

    @Test
    fun `enable remains false when Android rejects the job`() {
        assertFalse(AutomaticBackupPreferencePolicy.afterEnableRequest { false })
        assertTrue(AutomaticBackupPreferencePolicy.afterEnableRequest { true })
    }

    @Test
    fun `persisted enable reuses an existing job without shifting its schedule`() {
        var scheduleCalls = 0

        val enabled = AutomaticBackupPreferencePolicy.reconcile(
            persistedEnabled = true,
            hasScheduledJob = true,
            schedule = {
                scheduleCalls += 1
                false
            },
        )

        assertTrue(enabled)
        assertTrue(scheduleCalls == 0)
    }

    @Test
    fun `persisted enable is restored only when a missing job can be scheduled`() {
        assertTrue(
            AutomaticBackupPreferencePolicy.reconcile(true, hasScheduledJob = false) { true },
        )
        assertFalse(
            AutomaticBackupPreferencePolicy.reconcile(true, hasScheduledJob = false) { false },
        )
        assertFalse(
            AutomaticBackupPreferencePolicy.reconcile(false, hasScheduledJob = false) {
                error("disabled preference must not schedule")
            },
        )
    }
}
