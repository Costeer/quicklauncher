package org.quicklauncher.app

import android.app.job.JobScheduler
import android.content.ComponentName
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import java.time.Duration
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.quicklauncher.host.backup.android.AndroidChargingBackupScheduler

@RunWith(AndroidJUnit4::class)
class AutomaticBackupSchedulerInstrumentedTest {
    private val context = ApplicationProvider.getApplicationContext<android.content.Context>()
    private val jobs = context.getSystemService(JobScheduler::class.java)

    @Before
    fun clearAutomaticBackupJobBeforeTest() {
        jobs.cancel(AndroidChargingBackupScheduler.JOB_ID)
    }

    @After
    fun clearAutomaticBackupJobAfterTest() {
        jobs.cancel(AndroidChargingBackupScheduler.JOB_ID)
    }

    @Test
    fun automaticBackupIsOneShotPersistedAndChargingOnly() {
        val service = ComponentName(context, AutomaticBackupJobService::class.java)

        assertTrue(AndroidChargingBackupScheduler(jobs, service).schedule())
        val pending = jobs.getPendingJob(AndroidChargingBackupScheduler.JOB_ID)

        assertNotNull(pending)
        requireNotNull(pending)
        assertEquals(service, pending.service)
        assertTrue(pending.isRequireCharging)
        assertTrue(pending.isPersisted)
        assertFalse(pending.isPeriodic)
        assertTrue(pending.minLatencyMillis > 0L)
        assertTrue(pending.minLatencyMillis <= Duration.ofHours(25).toMillis())
    }
}
