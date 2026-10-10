package com.locapeer.util

import java.io.ByteArrayInputStream
import org.junit.Assert.*
import org.junit.Test

class BoundedInputTest {
    @Test fun `reads empty and exact limit without closing the caller stream`() {
        assertArrayEquals(ByteArray(0), readCapped(ByteArrayInputStream(ByteArray(0)), 0))
        val data = ByteArray(10000) { it.toByte() }
        assertArrayEquals(data, readCapped(ByteArrayInputStream(data), data.size))
    }

    @Test fun `oversized stream stops after one probe byte`() {
        val input = ByteArrayInputStream(ByteArray(20000))
        try { readCapped(input, 10000); fail("Accepted oversized stream") }
        catch (_: IllegalArgumentException) { }
        assertEquals(9999, input.available())
    }
}
