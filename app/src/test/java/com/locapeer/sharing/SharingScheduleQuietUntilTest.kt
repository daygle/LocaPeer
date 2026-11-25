package com.locapeer.sharing

import com.locapeer.subscriber.isInPlannedGap
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.ZoneOffset
import java.time.ZonedDateTime

/**
 * [SharingSchedule.quietUntilMs] tells a contact when a planned gap in updates ends, and
 * [isInPlannedGap] is how their app uses it to hold back a missed-update alert.
 * Times are in UTC; 2026-11-23 is a Monday (day index 0).
 */
class SharingScheduleQuietUntilTest {

    private val utc = ZoneOffset.UTC
    private val tenMinutes = 10 * 60_000L

    private fun at(day: Int, hour: Int, minute: Int = 0): Long =
        ZonedDateTime.of(2026, 11, day, hour, minute, 0, 0, utc).toInstant().toEpochMilli()

    private fun rule(days: Int, start: Int, end: Int) =
        newScheduleRule().copy(days = days, startMinute = start, endMinute = end)

    private val workHours = listOf(rule(0b1111111, 9 * 60, 17 * 60)) // 09:00-17:00 inclusive

    @Test
    fun `no schedules means updates never stop`() {
        assertEquals(0L, SharingSchedule.quietUntilMs(emptyList(), emptyList(), null, at(23, 12), tenMinutes, utc))
    }

    @Test
    fun `mid-window with the end beyond the horizon is not quiet`() {
        assertEquals(0L, SharingSchedule.quietUntilMs(workHours, emptyList(), null, at(23, 12), tenMinutes, utc))
    }

    @Test
    fun `window closing within the horizon reports when it reopens`() {
        // 16:55 Monday: 17:00 is still inside (inclusive end), 17:01 is the first minute off,
        // and sending resumes at 09:00 Tuesday.
        assertEquals(at(24, 9), SharingSchedule.quietUntilMs(workHours, emptyList(), null, at(23, 16, 55), tenMinutes, utc))
    }

    @Test
    fun `the contact's own schedule counts too`() {
        val peerMornings = listOf(rule(0b1111111, 6 * 60, 12 * 60))
        assertEquals(
            at(24, 6),
            SharingSchedule.quietUntilMs(emptyList(), peerMornings, null, at(23, 11, 55), tenMinutes, utc)
        )
    }

    @Test
    fun `a running temporary share keeps updates going past the schedule`() {
        val tempEndsSec = at(23, 18) / 1000L
        assertEquals(0L, SharingSchedule.quietUntilMs(workHours, emptyList(), tempEndsSec, at(23, 16, 55), tenMinutes, utc))
    }

    @Test
    fun `a temporary share ending inside the horizon reports the next window`() {
        val tempEndsSec = at(23, 18) / 1000L
        assertEquals(at(24, 9), SharingSchedule.quietUntilMs(workHours, emptyList(), tempEndsSec, at(23, 17, 55), tenMinutes, utc))
    }

    @Test
    fun `a schedule that never reopens is quiet indefinitely`() {
        val noDays = listOf(rule(0, 0, 1439))
        assertEquals(
            SharingSchedule.QUIET_INDEFINITELY,
            SharingSchedule.quietUntilMs(noDays, emptyList(), null, at(23, 12), tenMinutes, utc)
        )
    }

    @Test
    fun `alerts are held back during the gap and for the grace period after it`() {
        val until = at(24, 9)
        assertTrue(isInPlannedGap(until, at(23, 20), graceMs = tenMinutes))
        assertTrue(isInPlannedGap(until, at(24, 9, 5), graceMs = tenMinutes))
        assertFalse(isInPlannedGap(until, at(24, 9, 15), graceMs = tenMinutes))
    }

    @Test
    fun `no announced gap and an indefinite one`() {
        assertFalse(isInPlannedGap(0L, at(23, 12), tenMinutes))
        assertTrue(isInPlannedGap(SharingSchedule.QUIET_INDEFINITELY, at(23, 12), tenMinutes))
    }

    @Test
    fun `a huge peer-supplied value does not overflow`() {
        assertTrue(isInPlannedGap(Long.MAX_VALUE - 1, at(23, 12), tenMinutes))
    }
}
