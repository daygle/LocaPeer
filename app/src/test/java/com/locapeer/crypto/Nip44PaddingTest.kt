package com.locapeer.crypto

import java.nio.ByteBuffer
import org.junit.Assert.*
import org.junit.Test

class Nip44PaddingTest {
    private val crypto = CryptoUtils()

    @Test fun `official boundary lengths use the correct header and padding bucket`() {
        val vectors = mapOf(1 to 34, 32 to 34, 33 to 66, 256 to 258, 257 to 322,
            65535 to 65538, 65536 to 65542, 65537 to 81926)
        for ((length, paddedSize) in vectors) {
            val text = "a".repeat(length)
            val padded = crypto.nip44Pad(text)
            assertEquals(paddedSize, padded.size)
            assertEquals(if (length < 65536) length else 0, ByteBuffer.wrap(padded).short.toInt() and 0xffff)
            assertEquals(text, crypto.nip44Unpad(padded))
        }
    }

    @Test fun `length counts UTF-8 bytes not characters`() {
        val text = "😀é".repeat(100)
        val padded = crypto.nip44Pad(text)
        assertEquals(600, ByteBuffer.wrap(padded).short.toInt() and 0xffff)
        assertEquals(text, crypto.nip44Unpad(padded))
    }

    @Test fun `rejects truncated and extra padding and noncanonical extended headers`() {
        val padded = crypto.nip44Pad("hello")
        for (bad in listOf(padded.copyOf(padded.size - 1), padded.copyOf(padded.size + 1),
            ByteBuffer.allocate(38).putShort(0).putInt(1).put('a'.code.toByte()).array(),
            ByteBuffer.allocate(6).putShort(0).putInt(-1).array(), ByteArray(2))) {
            try { crypto.nip44Unpad(bad); fail("Accepted invalid padding") }
            catch (_: SecurityException) { }
        }
    }

    @Test fun `rejects oversized encryption and decryption inputs before key operations`() {
        try { crypto.nip44Pad("a".repeat(CryptoUtils.MAX_NIP44_PLAINTEXT_BYTES + 1)); fail("Accepted oversized plaintext") }
        catch (_: IllegalArgumentException) { }
        try { crypto.nip44Decrypt(ByteArray(32), "", "A".repeat(2 * 1024 * 1024)); fail("Accepted oversized payload") }
        catch (_: IllegalArgumentException) { }
    }
}
