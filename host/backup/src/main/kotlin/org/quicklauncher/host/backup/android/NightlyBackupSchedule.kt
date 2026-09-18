package org.quicklauncher.host.backup.android

import java.time.Clock
import java.time.Duration
import java.time.LocalTime
import java.time.ZonedDateTime

/** Pure wall-clock policy for one backup opportunity per local night. */
object NightlyBackupSchedule {
    const val START_HOUR = 3
    const val END_HOUR = 6
    private val startTime = LocalTime.of(START_HOUR, 0)
    private val endTime = LocalTime.of(END_HOUR, 0)

    fun isInRunWindow(clock: Clock): Boolean {
        val time = ZonedDateTime.now(clock).toLocalTime()
        return !time.isBefore(startTime) && time.isBefore(endTime)
    }

    fun delayUntilNextRunMillis(clock: Clock): Long {
        val now = ZonedDateTime.now(clock)
        val todayStart = now.toLocalDate().atTime(startTime).atZone(now.zone)
        val nextStart = if (now.isBefore(todayStart)) todayStart else todayStart.plusDays(1)
        return Duration.between(now, nextStart).toMillis().coerceAtLeast(1L)
    }
}
