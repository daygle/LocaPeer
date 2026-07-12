package com.locapeer.sharing

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Validates the precedence the per-peer "should I share right now" check uses for
 * the one-off temp-share override. The function returns true when EITHER:
 *
 *  - the temp share is active ([nowEpochSeconds] < [tempEndsAtEpochSeconds]), OR
 *  - the recurring [ScheduleRule]s match the current day/minute.
 *
 * Setting [tempEndsAtEpochSeconds] to null means there is no active temp share and
 * the schedule alone decides. The "less than" is strict so a temp share ending at
 * the current second has already expired.
 */
class SharingScheduleTempShareTest {
    /** Rule with the day-bitmask set to 0 so [SharingSchedule.isActive] can never
     *  match it. Used to verify that the only thing flipping the result on/off is the
     *  temp-share override. */
    private val neverMatchingRules = listOf(newScheduleRule().copy(days = 0))
    private val dayIndex = 0
    private val currentMinute = 600

    @Test
    fun `temp share active overrides non-matching schedule`() {
        assertTrue(
            SharingSchedule.isPeerSharingActive(
                rules = neverMatchingRules,
                dayIndex = dayIndex,
                currentMinute = currentMinute,
                tempEndsAtEpochSeconds = 1000L,
                nowEpochSeconds = 500L
            )
        )
    }

    @Test
    fun `expired temp share with non-matching schedule stays off`() {
        // nowSec >= tempEnds, so the temp override doesn't apply; the schedule is off
        assertFalse(
            SharingSchedule.isPeerSharingActive(
                rules = neverMatchingRules,
                dayIndex = dayIndex,
                currentMinute = currentMinute,
                tempEndsAtEpochSeconds = 100L,
                nowEpochSeconds = 100L
            )
        )
        assertFalse(
            SharingSchedule.isPeerSharingActive(
                rules = neverMatchingRules,
                dayIndex = dayIndex,
                currentMinute = currentMinute,
                tempEndsAtEpochSeconds = 100L,
                nowEpochSeconds = 200L
            )
        )
    }

    @Test
    fun `null temp share with non-matching schedule stays off`() {
        assertFalse(
            SharingSchedule.isPeerSharingActive(
                rules = neverMatchingRules,
                dayIndex = dayIndex,
                currentMinute = currentMinute,
                tempEndsAtEpochSeconds = null,
                nowEpochSeconds = 1000L
            )
        )
    }

    @Test
    fun `empty rules with null temp share means always on`() {
        // The ScheduleRule list's "empty" sentinel means "no schedule -> always on";
        // verify that path returns true when the temp share is also inactive.
        assertTrue(
            SharingSchedule.isPeerSharingActive(
                rules = emptyList(),
                dayIndex = dayIndex,
                currentMinute = currentMinute,
                tempEndsAtEpochSeconds = null,
                nowEpochSeconds = 1000L
            )
        )
    }

    @Test
    fun `temp share end equals nowEpochSeconds is not active (strict less than)`() {
        // Strict < so the moment the deadline arrives, sharing stops; this matches the
        // semantics used by the HeartbeatService per-peer check inside broadcastHeartbeat.
        assertFalse(
            SharingSchedule.isPeerSharingActive(
                rules = neverMatchingRules,
                dayIndex = dayIndex,
                currentMinute = currentMinute,
                tempEndsAtEpochSeconds = 100L,
                nowEpochSeconds = 100L
            )
        )
    }
}
