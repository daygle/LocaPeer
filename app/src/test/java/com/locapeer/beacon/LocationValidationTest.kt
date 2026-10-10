package com.locapeer.beacon

import org.junit.Assert.*
import org.junit.Test

class LocationValidationTest {
    @Test fun `invalid fixes never seed or poison either location baseline`() {
        val filter = LocationFilter()
        val selector = FixSelector()
        val bad = listOf(Triple(Double.NaN, 0.0, 1f), Triple(91.0, 0.0, 1f),
            Triple(0.0, 181.0, 1f), Triple(0.0, 0.0, Float.NaN), Triple(0.0, 0.0, -1f))
        for ((lat, lng, accuracy) in bad) {
            assertFalse(filter.accept(lat, lng, accuracy, 1))
            assertFalse(selector.select(lat, lng, accuracy, 1))
        }
        assertTrue(filter.accept(0.0, 0.0, 5f, 2))
        assertTrue(selector.select(0.0, 0.0, 5f, 2))
    }

    @Test fun `sharper out of order fix cannot replace current selection`() {
        val selector = FixSelector()
        assertTrue(selector.select(0.0, 0.0, 10f, 100))
        assertFalse(selector.select(1.0, 1.0, 1f, 99))
        assertFalse(selector.select(1.0, 1.0, 1f, 100))
        assertTrue(selector.select(0.0, 0.0, 5f, 101))
    }
}
