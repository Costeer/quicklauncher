package org.quicklauncher.host.backup.android

import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.time.ZoneId
import java.time.ZoneOffset
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class NightlyBackupScheduleTest {
    @Test
    fun `run window includes three through six local time`() {
        assertTrue(NightlyBackupSchedule.isInRunWindow(clockAt("2026-09-17T03:00:00Z")))
        assertTrue(NightlyBackupSchedule.isInRunWindow(clockAt("2026-09-17T05:59:59Z")))
    }

    @Test
    fun `run window excludes times before three and from six onward`() {
        assertFalse(NightlyBackupSchedule.isInRunWindow(clockAt("2026-09-17T02:59:59Z")))
        assertFalse(NightlyBackupSchedule.isInRunWindow(clockAt("2026-09-17T06:00:00Z")))
    }

    @Test
    fun `next run starts at three in the current local night`() {
        val clock = Clock.fixed(Instant.parse("2026-09-17T02:15:00Z"), ZoneOffset.UTC)

        val delay = NightlyBackupSchedule.delayUntilNextRunMillis(clock)

        assertEquals(Duration.ofMinutes(45).toMillis(), delay)
    }

    @Test
    fun `a run at the nightly boundary advances to the following night`() {
        val clock = Clock.fixed(Instant.parse("2026-09-17T03:00:00Z"), ZoneOffset.UTC)

        val delay = NightlyBackupSchedule.delayUntilNextRunMillis(clock)

        assertEquals(Duration.ofHours(24).toMillis(), delay)
    }

    @Test
    fun `spring clock change keeps the next run at three local time`() {
        val zone = ZoneId.of("America/New_York")
        val clock = Clock.fixed(Instant.parse("2026-03-07T08:00:00Z"), zone)

        val delay = NightlyBackupSchedule.delayUntilNextRunMillis(clock)

        assertEquals(Duration.ofHours(23).toMillis(), delay)
    }

    @Test
    fun `fall clock change keeps the next run at three local time`() {
        val zone = ZoneId.of("America/New_York")
        val clock = Clock.fixed(Instant.parse("2026-10-31T07:00:00Z"), zone)

        val delay = NightlyBackupSchedule.delayUntilNextRunMillis(clock)

        assertEquals(Duration.ofHours(25).toMillis(), delay)
    }

    private fun clockAt(instant: String): Clock =
        Clock.fixed(Instant.parse(instant), ZoneOffset.UTC)
}
