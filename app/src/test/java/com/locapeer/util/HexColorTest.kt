package com.locapeer.util

import org.junit.Assert.assertEquals
import org.junit.Test

class HexColorTest {
    @Test fun `well formed colors pass through unchanged`() {
        assertEquals("#12aBcF", normalizeHexColor("#12aBcF"))
        assertEquals("#8012aBcF", normalizeHexColor("#8012aBcF"))
    }

    @Test fun `malformed colors normalize to no color so renders fall back`() {
        assertEquals("", normalizeHexColor("red"))
        assertEquals("", normalizeHexColor("#12345"))
        assertEquals("", normalizeHexColor("#1234567"))
        assertEquals("", normalizeHexColor("#12345678ff"))
        assertEquals("", normalizeHexColor("#GGGGGG"))
        assertEquals("", normalizeHexColor(""))
        assertEquals("", normalizeHexColor(" #123456"))
    }
}
