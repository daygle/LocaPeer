package com.locapeer.settings

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class LeftCircleStoreTest {

    @Test
    fun `round trip keeps the owner and leave time`() {
        val record = LeftCircle("c1", "a".repeat(64), 1_700_000_000L)
        val decoded = decodeLeftCircles(encodeLeftCircles(listOf(record)))
        assertEquals(record, decoded["c1"])
    }

    @Test
    fun `a legacy bare id decodes without an owner or time`() {
        val decoded = decodeLeftCircles("c1")
        assertEquals(LeftCircle("c1", "", 0L), decoded["c1"])
    }

    @Test
    fun `duplicate ids keep the most recent leave`() {
        val encoded = encodeLeftCircles(
            listOf(
                LeftCircle("c1", "old", 100L),
                LeftCircle("c1", "new", 200L),
                LeftCircle("c2", "", 50L)
            )
        )
        val decoded = decodeLeftCircles(encoded)
        assertEquals(2, decoded.size)
        assertEquals(LeftCircle("c1", "new", 200L), decoded["c1"])
        assertEquals(LeftCircle("c2", "", 50L), decoded["c2"])
    }

    @Test
    fun `encoding caps the list at the record limit, dropping the oldest`() {
        val many = (1..(MAX_LEFT_CIRCLE_RECORDS + 50)).map { i -> LeftCircle("circle$i", "", i.toLong()) }
        val decoded = decodeLeftCircles(encodeLeftCircles(many))
        assertEquals(MAX_LEFT_CIRCLE_RECORDS, decoded.size)
        // The newest survived, the oldest did not.
        assertEquals(MAX_LEFT_CIRCLE_RECORDS + 50L, decoded["circle${MAX_LEFT_CIRCLE_RECORDS + 50}"]?.leftAt)
        assertNull(decoded["circle1"])
    }

    @Test
    fun `decoding a legacy oversized value is bounded`() {
        val raw = (1..(MAX_LEFT_CIRCLE_RECORDS * 3)).joinToString(",") { "c$it" }
        assertEquals(MAX_LEFT_CIRCLE_RECORDS, decodeLeftCircles(raw).size)
    }
}
