package com.locapeer.util

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDateTime
import java.time.ZoneId

class QuietHoursTest {

    // A fixed zone with no DST transitions around the test instants; all instants are
    // constructed from local times in this zone so the minute-of-day math is exact.
    private val zone = ZoneId.of("UTC")

    private fun millisAt(h: Int, m: Int): Long =
        LocalDateTime.of(2026, 6, 15, h, m).atZone(zone).toInstant().toEpochMilli()

    @Test
    fun `normal daytime window is quiet inside and awake outside`() {
        // 08:00 .. 22:00
        assertTrue(QuietHours.isQuietNow(480, 1320, zone, millisAt(9, 0)))
        assertFalse(QuietHours.isQuietNow(480, 1320, zone, millisAt(22, 0)))
        assertFalse(QuietHours.isQuietNow(480, 1320, zone, millisAt(7, 59)))
    }

    @Test
    fun `window wrapping midnight covers both edges`() {
        // 22:00 .. 08:00
        assertTrue(QuietHours.isQuietNow(1320, 480, zone, millisAt(23, 0)))
        assertTrue(QuietHours.isQuietNow(1320, 480, zone, millisAt(3, 30)))
        assertTrue(QuietHours.isQuietNow(1320, 480, zone, millisAt(7, 59)))
        assertFalse(QuietHours.isQuietNow(1320, 480, zone, millisAt(8, 0)))
        assertFalse(QuietHours.isQuietNow(1320, 480, zone, millisAt(12, 0)))
        assertFalse(QuietHours.isQuietNow(1320, 480, zone, millisAt(21, 59)))
        assertTrue(QuietHours.isQuietNow(1320, 480, zone, millisAt(22, 0)))
    }

    @Test
    fun `zero-length window means no quiet time`() {
        // start == end must not accidentally mute the entire day.
        for (hour in 0..23) {
            assertFalse(QuietHours.isQuietNow(600, 600, zone, millisAt(hour, 0)))
        }
    }

    @Test
    fun `values are clamped to valid minute range`() {
        assertFalse(QuietHours.isQuietNow(-100, 500, zone, millisAt(12, 0)))
        assertTrue(QuietHours.isQuietNow(0, 500, zone, millisAt(7, 30)))
        assertFalse(QuietHours.isQuietNow(0, 500, zone, millisAt(9, 30)))
        assertTrue(QuietHours.isQuietNow(1440, 500, zone, millisAt(23, 59)))
        assertFalse(QuietHours.isQuietNow(1440, 500, zone, millisAt(12, 0)))
    }

    @Test
    fun `end is exclusive`() {
        // 12:00 .. 13:00: quiet at 12:59, awake again at exactly 13:00.
        assertTrue(QuietHours.isQuietNow(720, 780, zone, millisAt(12, 59)))
        assertFalse(QuietHours.isQuietNow(720, 780, zone, millisAt(13, 0)))
    }
}
