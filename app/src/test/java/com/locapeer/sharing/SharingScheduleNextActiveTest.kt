package com.locapeer.sharing

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Validates [SharingSchedule.minutesUntilNextActive], which the heartbeat service uses
 * while suspended off-schedule to sleep until the window actually opens instead of
 * re-checking at the motion cadence all night. Day indexing is Monday = 0, matching
 * [SharingSchedule.isActive].
 */
class SharingScheduleNextActiveTest {

    private fun rule(days: Int, start: Int, end: Int) =
        newScheduleRule().copy(days = days, startMinute = start, endMinute = end)

    private val everyDay = 0b1111111
    private val mondayOnly = 0b0000001

    @Test
    fun `empty rules mean always active so zero minutes`() {
        assertEquals(0, SharingSchedule.minutesUntilNextActive(emptyList(), 0, 600))
    }

    @Test
    fun `active right now returns zero`() {
        val rules = listOf(rule(everyDay, 540, 1020)) // 09:00-17:00
        assertEquals(0, SharingSchedule.minutesUntilNextActive(rules, 2, 600)) // Wed 10:00
    }

    @Test
    fun `window later the same day`() {
        val rules = listOf(rule(everyDay, 540, 1020)) // 09:00-17:00
        // Monday 07:30 -> opens at 09:00, 90 minutes away.
        assertEquals(90, SharingSchedule.minutesUntilNextActive(rules, 0, 450))
    }

    @Test
    fun `window crosses to the next day`() {
        val rules = listOf(rule(everyDay, 540, 1020)) // 09:00-17:00
        // Monday 18:00 (just past close) -> Tuesday 09:00: 6h to midnight + 9h = 900 min.
        assertEquals(900, SharingSchedule.minutesUntilNextActive(rules, 0, 1080))
    }

    @Test
    fun `overnight rule active before midnight and matching inside the wrap`() {
        val rules = listOf(rule(everyDay, 1320, 300)) // 22:00-05:00 wrapping
        // 23:00 is inside the wrapped window.
        assertEquals(0, SharingSchedule.minutesUntilNextActive(rules, 3, 1380))
        // 02:00 is inside the wrapped tail on the same day-index too (isActive treats
        // the wrap per-day, so any scheduled day matches minutes <= endMinute).
        assertEquals(0, SharingSchedule.minutesUntilNextActive(rules, 3, 120))
        // 12:00 -> next open at 22:00 the same day: 600 minutes.
        assertEquals(600, SharingSchedule.minutesUntilNextActive(rules, 3, 720))
    }

    @Test
    fun `wraps across the end of the week`() {
        val rules = listOf(rule(mondayOnly, 540, 1020)) // Monday 09:00-17:00 only
        // Sunday (index 6) 18:00 -> Monday 09:00: 6h to midnight + 9h = 900 min.
        assertEquals(900, SharingSchedule.minutesUntilNextActive(rules, 6, 1080))
        // Monday 18:00 -> the following Monday 09:00: 6h + 6 days + 9h.
        assertEquals(360 + 6 * 1440 + 540, SharingSchedule.minutesUntilNextActive(rules, 0, 1080))
    }

    @Test
    fun `rules that can never match return null`() {
        val rules = listOf(rule(0, 540, 1020)) // empty day mask
        assertNull(SharingSchedule.minutesUntilNextActive(rules, 0, 600))
    }
}
