package com.locapeer.util

import android.content.Context
import android.util.Log
import java.io.File
import java.security.MessageDigest
import java.security.SecureRandom

/**
 * A random per-install secret that proves an intent asking MainActivity to dismiss a
 * notification came from one of our own notification actions.
 *
 * MainActivity is exported, so any installed app can launch it with the cancel extras. Our
 * notification PendingIntents carry this token; other apps cannot read PendingIntent extras,
 * so they cannot learn it. MainActivity dismisses a notification only when the token matches.
 *
 * The token is kept in a private file in `noBackupFilesDir`, so it survives process death:
 * notifications posted before a restart must still match. It is never backed up. If the file
 * is lost, a new token is made, and notifications posted earlier simply stop dismissing
 * themselves on tap.
 */
object NotificationCancelToken {
    private const val TAG = "NotificationCancelToken"
    private const val FILE_NAME = "notif_cancel_token"
    private const val TOKEN_BYTES = 32

    @Volatile private var cached: String? = null

    /** The install's token, created on first use. */
    fun get(context: Context): String {
        cached?.let { return it }
        synchronized(this) {
            cached?.let { return it }
            val file = File(context.applicationContext.noBackupFilesDir, FILE_NAME)
            val stored = try {
                file.takeIf { it.exists() }?.readText()?.trim()?.takeIf { it.length == TOKEN_BYTES * 2 }
            } catch (e: Exception) {
                Log.w(TAG, "Failed to read the notification cancel token", e)
                null
            }
            val token = stored ?: newToken().also { write(file, it) }
            cached = token
            return token
        }
    }

    /** True only if [candidate] equals this install's token (constant-time comparison). */
    fun matches(context: Context, candidate: String?): Boolean {
        if (candidate.isNullOrEmpty()) return false
        return MessageDigest.isEqual(
            candidate.toByteArray(Charsets.US_ASCII),
            get(context).toByteArray(Charsets.US_ASCII)
        )
    }

    private fun newToken(): String {
        val bytes = ByteArray(TOKEN_BYTES).also { SecureRandom().nextBytes(it) }
        return bytes.joinToString("") { "%02x".format(it) }
    }

    /** Write then rename, so a crash can't leave a truncated token behind. A failed write
     *  still leaves the token cached for this process. */
    private fun write(file: File, token: String) {
        try {
            val tmp = File(file.path + ".tmp")
            tmp.writeText(token)
            if (!tmp.renameTo(file)) Log.w(TAG, "Could not store the notification cancel token")
        } catch (e: Exception) {
            Log.w(TAG, "Failed to store the notification cancel token", e)
        }
    }
}
