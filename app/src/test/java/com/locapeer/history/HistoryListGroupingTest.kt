package com.locapeer.history

import com.locapeer.data.entity.HeartbeatEntity
import com.locapeer.data.entity.ReadingStatus
import com.locapeer.data.entity.RejectReason
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class HistoryListGroupingTest {

    private fun reading(id: Long, reason: RejectReason? = null) = HeartbeatEntity(
        id = id,
        deviceId = "dev",
        displayName = "Test",
        timestamp = id * 60_000L,
        // No-position attempts carry placeholder coordinates.
        lat = if (reason == RejectReason.NO_FIX || reason == RejectReason.PERMISSION_DENIED) 0.0 else -37.8,
        lng = if (reason == RejectReason.NO_FIX || reason == RejectReason.PERMISSION_DENIED) 0.0 else 144.9,
        accuracy = 10f,
        battery = 80,
        motionState = "STATIONARY",
        expectedIntervalSeconds = 300,
        status = if (reason == null) ReadingStatus.ACCEPTED.dbValue else ReadingStatus.REJECTED.dbValue,
        rejectReason = reason?.code,
    )

    private fun shape(entries: List<HistoryListEntry>) = entries.map {
        when (it) {
            is HistoryListEntry.Reading -> "R${it.ping.id}"
            is HistoryListEntry.Gap -> "G${it.count}"
        }
    }

    @Test
    fun `run of no-fix attempts collapses into one gap between readings`() {
        val entries = HistoryListGrouping.group(
            listOf(
                reading(1),
                reading(2, RejectReason.NO_FIX),
                reading(3, RejectReason.NO_FIX),
                reading(4, RejectReason.NO_FIX),
                reading(5),
            )
        )
        assertEquals(listOf("R1", "G3", "R5"), shape(entries))
        val gap = entries[1] as HistoryListEntry.Gap
        assertEquals(RejectReason.NO_FIX, gap.reason)
        assertEquals(2 * 60_000L, gap.firstMs)
        assertEquals(4 * 60_000L, gap.lastMs)
    }

    @Test
    fun `a change of reason starts a new gap`() {
        val entries = HistoryListGrouping.group(
            listOf(
                reading(1, RejectReason.NO_FIX),
                reading(2, RejectReason.NO_FIX),
                reading(3, RejectReason.PERMISSION_DENIED),
            )
        )
        assertEquals(listOf("G2", "G1"), shape(entries))
    }

    @Test
    fun `rejected readings that have a position stay as their own rows`() {
        val entries = HistoryListGrouping.group(
            listOf(
                reading(1, RejectReason.NO_FIX),
                reading(2, RejectReason.TOO_IMPRECISE),
                reading(3, RejectReason.STALE),
                reading(4, RejectReason.NO_FIX),
            )
        )
        assertEquals(listOf("G1", "R2", "R3", "G1"), shape(entries))
    }

    @Test
    fun `keys are unique and never collide with reading ids`() {
        val entries = HistoryListGrouping.group(
            listOf(reading(1, RejectReason.NO_FIX), reading(2), reading(3, RejectReason.NO_FIX))
        )
        val keys = entries.map { it.key }
        assertEquals(keys.size, keys.toSet().size)
        entries.filterIsInstance<HistoryListEntry.Gap>().forEach { assertTrue(it.key < 0) }
    }

    @Test
    fun `empty list stays empty`() {
        assertEquals(emptyList<HistoryListEntry>(), HistoryListGrouping.group(emptyList()))
    }
}
