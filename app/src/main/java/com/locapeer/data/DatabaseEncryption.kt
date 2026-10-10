package com.locapeer.data

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Log
import net.zetetic.database.sqlcipher.SQLiteDatabase
import java.io.File
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
     * then swap it in. The original stays in place until the final rename, so an interruption at
     * any earlier point leaves a usable (still unencrypted) database to retry on the next launch.
     */
    private fun encryptExisting(dbFile: File, tmpFile: File, rawKey: ByteArray) {
        val plain = SQLiteDatabase.openDatabase(dbFile.path, "", null, SQLiteDatabase.OPEN_READWRITE, null, null)
        try {
            // sqlcipher_export copies the schema and data but not user_version, which Room
            // reads to decide on migrations, so carry it over explicitly.
            val version = plain.version
            plain.rawExecSQL(
                "ATTACH DATABASE '${tmpFile.path.replace("'", "''")}' AS encrypted KEY \"${String(rawKey, Charsets.US_ASCII)}\""
            )
            plain.rawExecSQL("SELECT sqlcipher_export('encrypted')")
            plain.rawExecSQL("PRAGMA encrypted.user_version = $version")
            plain.rawExecSQL("DETACH DATABASE encrypted")
        } finally {
            plain.close()
        }
        // The old WAL/journal belong to the unencrypted file; SQLite must never replay them
        // against the encrypted one.
        listOf("-wal", "-shm", "-journal").forEach { File(dbFile.path + it).delete() }
        if (!tmpFile.renameTo(dbFile)) {
            tmpFile.delete()
            error("Could not replace the database with its encrypted copy")
        }
        Log.i(TAG, "Encrypted the existing database")
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

    private fun deleteDatabaseFiles(dbFile: File) {
        listOf("", "-wal", "-shm", "-journal").forEach { File(dbFile.path + it).delete() }
    }

    private fun newKey(): ByteArray = ByteArray(DB_KEY_BYTES).also { SecureRandom().nextBytes(it) }

    private fun writeWrappedKey(keyFile: File, key: ByteArray) {
        val cipher = Cipher.getInstance(AES_GCM)
        cipher.init(Cipher.ENCRYPT_MODE, getOrCreateWrapKey())
        val blob = cipher.iv + cipher.doFinal(key)
        // Write then rename, so a crash can't leave a truncated key file behind.
        val tmp = File(keyFile.path + ".tmp")
        tmp.writeBytes(blob)
        if (!tmp.renameTo(keyFile)) error("Could not store the database key")
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
