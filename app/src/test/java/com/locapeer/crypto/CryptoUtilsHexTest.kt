package com.locapeer.crypto

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.fail
import org.junit.Test

/**
 * Hex helpers sit on the relay hot path (event ids, signatures, keys) and parse peer-supplied
 * input, so they must round-trip exactly and reject anything that is not plain ASCII hex.
 * These paths never touch the secp256k1 native library, so they run as plain JVM tests.
 */
class CryptoUtilsHexTest {
    private val crypto = CryptoUtils()

    @Test
    fun `bytesToHex matches the reference lowercase format for every byte value`() {
        val bytes = ByteArray(256) { it.toByte() }
        assertEquals(bytes.joinToString("") { "%02x".format(it) }, crypto.bytesToHex(bytes))
    }

    @Test
    fun `hex round-trips in both cases`() {
        val bytes = ByteArray(256) { (255 - it).toByte() }
        val hex = crypto.bytesToHex(bytes)
        assertArrayEquals(bytes, crypto.hexToBytes(hex))
        assertArrayEquals(bytes, crypto.hexToBytes(hex.uppercase()))
    }

    @Test
    fun `empty input round-trips`() {
        assertEquals("", crypto.bytesToHex(ByteArray(0)))
        assertArrayEquals(ByteArray(0), crypto.hexToBytes(""))
    }

    @Test
    fun `hexToBytes rejects signs, non-hex and non-ASCII digits`() {
        // String.toInt(16) accepts "+f" and "-1", and Character.digit accepts non-ASCII digits.
        for (bad in listOf("+f", "-1", "0g", "zz", "٣٣", "１１")) {
            try {
                crypto.hexToBytes(bad)
                fail("accepted \"$bad\"")
            } catch (_: IllegalArgumentException) {
            }
        }
    }

    @Test(expected = IllegalArgumentException::class)
    fun `hexToBytes rejects odd length`() {
        crypto.hexToBytes("abc")
    }
}
