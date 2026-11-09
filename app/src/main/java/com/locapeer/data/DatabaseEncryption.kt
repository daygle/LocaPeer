package com.locapeer.data

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.system.Os
import android.system.OsConstants
import android.util.Log
import net.zetetic.database.sqlcipher.SQLiteDatabase
import java.io.File
import java.io.FileOutputStream
import java.security.KeyStore
import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

private const val TAG = "DatabaseEncryption"
private const val ANDROID_KEYSTORE = "AndroidKeyStore"
private const val WRAP_KEY_ALIAS = "locapeer_db_wrap_key"
private const val KEY_FILE = "db_key.bin"
private const val AES_GCM = "AES/GCM/NoPadding"
private const val IV_LENGTH = 12
private const val GCM_TAG_BITS = 128
private const val DB_KEY_BYTES = 32
private const val UNWRAP_ATTEMPTS = 3
private const val UNWRAP_RETRY_MS = 250L

/** The first 16 bytes of every unencrypted SQLite file. An SQLCipher file is random from byte 0. */
private val SQLITE_HEADER = "SQLite format 3\u0000".toByteArray(Charsets.US_ASCII)

/**
 * Encrypts the Room database at rest with SQLCipher.
 *
 * A random 256-bit database key is generated once and stored in `noBackupFilesDir`, wrapped by an
 * Android Keystore AES key that needs no user authentication (the database must open in background
 * services while the device is locked). The key is passed to SQLCipher as a raw key, skipping
 * its per-open passphrase derivation.
 *
 * An existing unencrypted database is converted once, on the first open after upgrading, with
 * `sqlcipher_export`. If the database key can no longer be unwrapped (the Keystore entry was lost)
 * the encrypted database is unreadable, so it is deleted and the app starts with an empty one. The
 * identity key is wrapped by the same Keystore, so that failure already loses the identity today.
 */
object DatabaseEncryption {

    /** Prepare [dbName] for SQLCipher and return the key to open it with. Call before Room opens it. */
    fun prepare(context: Context, dbName: String): ByteArray {
        System.loadLibrary("sqlcipher")
        val dbFile = context.getDatabasePath(dbName)
        val tmpFile = File(dbFile.path + ".enc-tmp")
        // Leftover from a conversion interrupted before its final rename: the original is intact.
        if (tmpFile.exists()) tmpFile.delete()

        val keyFile = File(context.noBackupFilesDir, KEY_FILE)
        val existingKey = if (keyFile.exists()) unwrapKeyWithRetry(keyFile) else null
        if (keyFile.exists() && existingKey == null && dbFile.exists() && !isPlaintextSqlite(dbFile)) {
            Log.e(TAG, "Database key can no longer be unwrapped; deleting the unreadable encrypted database")
            deleteDatabaseFiles(dbFile)
        }
        val key = existingKey ?: newKey().also { writeWrappedKey(keyFile, it) }
        val rawKey = rawKeyLiteral(key).toByteArray(Charsets.US_ASCII)

        if (dbFile.exists() && isPlaintextSqlite(dbFile)) {
            encryptExisting(dbFile, tmpFile, rawKey)
        } else if (dbFile.exists() && existingKey == null) {
            // An encrypted database with no key file at all can never be opened.
            Log.e(TAG, "Encrypted database has no key; deleting it")
            deleteDatabaseFiles(dbFile)
        }
        return rawKey
    }

    /**
     * One-time conversion of the unencrypted database: copy everything into a new encrypted file,
     * flush it to disk, then swap it in. The original stays in place until the final rename, so an
     * interruption at any earlier point leaves a usable (still unencrypted) database to retry on
     * the next launch. A crash can no longer destroy it either: the old sidecars are only discarded
     * once they hold no committed data, and the encrypted copy is verified and fsynced first.
     */
    private fun encryptExisting(dbFile: File, tmpFile: File, rawKey: ByteArray) {
        dbFile.parentFile?.mkdirs()
        if (tmpFile.exists()) tmpFile.delete()
        tmpFile.createNewFile()
        try {
            val plain = SQLiteDatabase.openDatabase(dbFile.path, "", null, SQLiteDatabase.OPEN_READWRITE, null, null)
            try {
                // Fold any committed WAL frames back into the main file first: the export reads
                // through the WAL anyway, but this makes discarding the sidecars below safe. A
                // failure here is not fatal - [hasPendingSidecarData] still guards the deletion.
                runCatching { plain.rawExecSQL("PRAGMA wal_checkpoint(TRUNCATE)") }
                // sqlcipher_export copies the schema and data but not user_version, which Room
                // reads to decide on migrations, so carry it over explicitly.
                val version = plain.version
                plain.rawExecSQL(
                    "ATTACH DATABASE '${tmpFile.path.replace("'", "''")}' AS encrypted KEY \"${String(rawKey, Charsets.US_ASCII)}\""
                )
                try {
                    plain.rawExecSQL("SELECT sqlcipher_export('encrypted')")
                    plain.rawExecSQL("PRAGMA encrypted.user_version = $version")
                } finally {
                    plain.rawExecSQL("DETACH DATABASE encrypted")
                }
            } finally {
                plain.close()
            }
            // Somebody else's connection (or a failed checkpoint) can still leave committed data
            // in the old WAL/journal. Refuse to delete it: aborting keeps the original usable.
            check(!hasPendingSidecarData(dbFile)) {
                "Refusing to discard uncheckpointed database data while encrypting"
            }
            // A partial export (disk full, interrupted write) must never replace a good database.
            check(isExportedCopyUsable(tmpFile)) {
                "Encrypted copy is incomplete; keeping the original database"
            }
            // Without this a power cut after the rename could leave a truncated file in place of
            // the only copy.
            fsyncFile(tmpFile)
            if (!tmpFile.renameTo(dbFile)) error("Could not replace the database with its encrypted copy")
            fsyncDirectory(dbFile.parentFile)
            // Empty/shm-only leftovers belong to the unencrypted file; SQLite must never replay
            // them against the encrypted one.
            listOf("-wal", "-shm", "-journal").forEach { File(dbFile.path + it).delete() }
            Log.i(TAG, "Encrypted the existing database")
        } catch (e: Exception) {
            // The original is untouched; drop the partial copy so the next launch retries cleanly.
            tmpFile.delete()
            throw e
        }
    }

    /** True when an old `-wal` or `-journal` still holds data that deleting would lose. */
    internal fun hasPendingSidecarData(dbFile: File): Boolean =
        listOf("-wal", "-journal").any { File(dbFile.path + it).length() > 0 }

    /** The exported copy must exist, be non-empty, and actually be encrypted. */
    internal fun isExportedCopyUsable(tmpFile: File): Boolean =
        tmpFile.exists() && tmpFile.length() > 0 && !isPlaintextSqlite(tmpFile)

    /** Flush [file]'s contents so a rename committed before a power loss can't truncate it. */
    private fun fsyncFile(file: File) {
        FileOutputStream(file, true).use { it.fd.sync() }
    }

    /** Best effort: makes the rename itself durable. Filesystems that refuse are not fatal. */
    private fun fsyncDirectory(dir: File?) {
        if (dir == null) return
        try {
            val fd = Os.open(dir.path, OsConstants.O_RDONLY, 0)
            try {
                Os.fsync(fd)
            } finally {
                Os.close(fd)
            }
        } catch (e: Exception) {
            Log.w(TAG, "Could not fsync ${dir.name}", e)
        }
    }

    internal fun isPlaintextSqlite(file: File): Boolean {
        if (file.length() < SQLITE_HEADER.size) return false
        val header = ByteArray(SQLITE_HEADER.size)
        file.inputStream().use { if (it.read(header) != header.size) return false }
        return isPlaintextSqliteHeader(header)
    }

    internal fun isPlaintextSqliteHeader(header: ByteArray): Boolean =
        header.size >= SQLITE_HEADER.size && header.copyOf(SQLITE_HEADER.size).contentEquals(SQLITE_HEADER)

    /** SQLCipher raw-key syntax: the 32 key bytes used directly, with no passphrase derivation. */
    internal fun rawKeyLiteral(key: ByteArray): String {
        require(key.size == DB_KEY_BYTES) { "Database key must be $DB_KEY_BYTES bytes" }
        return "x'" + key.joinToString("") { "%02x".format(it) } + "'"
    }

    /**
     * Full local-data wipe used by Settings -> "Delete identity & erase all data": delete the
     * database files and the wrapping key file. The caller MUST have closed the Room connection
     * first (deleting a file behind an open SQLCipher handle leaves handles pointing at removed
     * inodes, and a mid-flush failure can strand a -wal that references an unrecoverable page
     * set); and MUST restart the process afterwards so the heuristics in [prepare] run against
     * a fresh filesystem instead of a half-deleted one. The per-version Keystore alias
     * [WRAP_KEY_ALIAS] regenerates on next launch, which is why the caller deletes it too.
     */
    fun eraseAll(context: Context, dbName: String) {
        val dbFile = context.getDatabasePath(dbName)
        deleteDatabaseFiles(dbFile)
        File(dbFile.path + ".enc-tmp").delete()
        File(context.noBackupFilesDir, KEY_FILE).delete()
        try {
            KeyStore.getInstance(ANDROID_KEYSTORE).also { it.load(null) }.deleteEntry(WRAP_KEY_ALIAS)
        } catch (e: Exception) {
            android.util.Log.w(TAG, "Failed to delete the database wrap key", e)
        }
    }

    private fun deleteDatabaseFiles(dbFile: File) {
        listOf("", "-wal", "-shm", "-journal").forEach { File(dbFile.path + it).delete() }
    }

    private fun newKey(): ByteArray = ByteArray(DB_KEY_BYTES).also { SecureRandom().nextBytes(it) }

    private fun writeWrappedKey(keyFile: File, key: ByteArray) {
        val cipher = Cipher.getInstance(AES_GCM)
        cipher.init(Cipher.ENCRYPT_MODE, getOrCreateWrapKey())
        val blob = cipher.iv + cipher.doFinal(key)
        // Write, flush, then rename: a crash can't leave a truncated key file behind, which would
        // make the encrypted database look unrecoverable on the next launch.
        val tmp = File(keyFile.path + ".tmp")
        tmp.outputStream().use {
            it.write(blob)
            it.flush()
            it.fd.sync()
        }
        if (!tmp.renameTo(keyFile)) error("Could not store the database key")
        fsyncDirectory(keyFile.parentFile)
    }

    /**
     * Returns the database key, or null only when it is definitely gone: the Keystore entry is
     * missing, the stored key fails authentication, or the key was permanently invalidated. Any
     * other failure may be transient (the Keystore can briefly fail, e.g. right after boot), so it
     * is retried and then thrown: deleting the user's data on a transient error would be far
     * worse than failing this launch.
     */
    private fun unwrapKeyWithRetry(keyFile: File): ByteArray? {
        var last: Exception? = null
        repeat(UNWRAP_ATTEMPTS) { attempt ->
            try {
                return unwrapKey(keyFile)
            } catch (e: javax.crypto.AEADBadTagException) {
                Log.e(TAG, "Stored database key failed authentication", e)
                return null
            } catch (e: android.security.keystore.KeyPermanentlyInvalidatedException) {
                Log.e(TAG, "Database wrap key permanently invalidated", e)
                return null
            } catch (e: Exception) {
                last = e
                Log.w(TAG, "Unwrapping the database key failed (attempt ${attempt + 1})", e)
                Thread.sleep(UNWRAP_RETRY_MS)
            }
        }
        throw IllegalStateException("Database key is temporarily unavailable", last)
    }

    private fun unwrapKey(keyFile: File): ByteArray? {
        val blob = keyFile.readBytes()
        if (blob.size <= IV_LENGTH) return null
        val wrapKey = KeyStore.getInstance(ANDROID_KEYSTORE).also { it.load(null) }
            .getKey(WRAP_KEY_ALIAS, null) as? SecretKey ?: return null
        val cipher = Cipher.getInstance(AES_GCM)
        cipher.init(Cipher.DECRYPT_MODE, wrapKey, GCMParameterSpec(GCM_TAG_BITS, blob, 0, IV_LENGTH))
        return cipher.doFinal(blob, IV_LENGTH, blob.size - IV_LENGTH).takeIf { it.size == DB_KEY_BYTES }
    }

    private fun getOrCreateWrapKey(): SecretKey {
        val ks = KeyStore.getInstance(ANDROID_KEYSTORE).also { it.load(null) }
        (ks.getKey(WRAP_KEY_ALIAS, null) as? SecretKey)?.let { return it }
        val keyGen = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, ANDROID_KEYSTORE)
        keyGen.init(
            KeyGenParameterSpec.Builder(WRAP_KEY_ALIAS, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256)
                .build()
        )
        return keyGen.generateKey()
    }
}
