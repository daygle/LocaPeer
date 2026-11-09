package com.locapeer.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class DatabaseEncryptionTest {
    @Test fun `recognises an unencrypted SQLite file by its header`() {
        val header = "SQLite format 3\u0000".toByteArray(Charsets.US_ASCII)
        assertTrue(DatabaseEncryption.isPlaintextSqliteHeader(header + ByteArray(84)))
        assertFalse(DatabaseEncryption.isPlaintextSqliteHeader(ByteArray(100) { (it * 37).toByte() }))
        assertFalse(DatabaseEncryption.isPlaintextSqliteHeader(header.copyOf(10)))
    }

    @Test fun `empty or short files are not treated as unencrypted databases`() {
        val file = File.createTempFile("dbheader", null)
        try {
            assertFalse(DatabaseEncryption.isPlaintextSqlite(file))
            file.writeBytes("SQLite".toByteArray())
            assertFalse(DatabaseEncryption.isPlaintextSqlite(file))
            file.writeBytes("SQLite format 3\u0000".toByteArray(Charsets.US_ASCII) + ByteArray(84))
            assertTrue(DatabaseEncryption.isPlaintextSqlite(file))
        } finally {
            file.delete()
        }
    }

    @Test fun `raw key literal is SQLCipher's x-quoted hex of all 32 bytes`() {
        val key = ByteArray(32) { (it - 16).toByte() }
        val literal = DatabaseEncryption.rawKeyLiteral(key)
        assertEquals(64 + 3, literal.length)
        assertEquals("x'f0f1f2", literal.take(8))
        assertTrue(literal.endsWith("0f'"))
    }

    @Test(expected = IllegalArgumentException::class)
    fun `raw key literal rejects a key of the wrong size`() {
        DatabaseEncryption.rawKeyLiteral(ByteArray(16))
    }

    @Test fun `pending sidecar data is detected from a non-empty wal or journal`() {
        val db = File.createTempFile("locapeer", ".db")
        try {
            assertFalse(DatabaseEncryption.hasPendingSidecarData(db))
            // A truncated (zero-length) WAL and the shared-memory file hold no data.
            File(db.path + "-wal").writeBytes(ByteArray(0))
            File(db.path + "-shm").writeBytes(ByteArray(32))
            assertFalse(DatabaseEncryption.hasPendingSidecarData(db))
            File(db.path + "-wal").writeBytes(ByteArray(4096))
            assertTrue(DatabaseEncryption.hasPendingSidecarData(db))
            File(db.path + "-wal").writeBytes(ByteArray(0))
            File(db.path + "-journal").writeBytes(ByteArray(512))
            assertTrue(DatabaseEncryption.hasPendingSidecarData(db))
        } finally {
            listOf("", "-wal", "-shm", "-journal").forEach { File(db.path + it).delete() }
        }
    }

    @Test fun `an exported copy is only usable when it is non-empty and encrypted`() {
        val copy = File.createTempFile("locapeer-enc", ".tmp")
        try {
            copy.writeBytes(ByteArray(0))
            assertFalse(DatabaseEncryption.isExportedCopyUsable(copy))
            // A plaintext SQLite file means the export wrote nothing encrypted.
            copy.writeBytes("SQLite format 3\u0000".toByteArray(Charsets.US_ASCII) + ByteArray(4096))
            assertFalse(DatabaseEncryption.isExportedCopyUsable(copy))
            copy.writeBytes(ByteArray(4096) { (it * 31).toByte() })
            assertTrue(DatabaseEncryption.isExportedCopyUsable(copy))
            assertFalse(DatabaseEncryption.isExportedCopyUsable(File(copy.path + ".missing")))
        } finally {
            copy.delete()
        }
    }
}
