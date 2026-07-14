package com.locapeer.messaging

import android.content.Context
import android.util.Log
import java.io.File

/**
 * Clears the plaintext, decrypted copies of received media that [MessagingViewModel]
 * writes into the app cache to hand off to a system viewer / MediaPlayer:
 *
 *  - `cacheDir/attachments/<name>`  (files opened via FileProvider in `openFile`)
 *  - `cacheDir/play_<messageId>.m4a` (voice notes staged for `MediaPlayer`)
 *
 * These are derived, recreated on demand from the encrypted-at-rest DB row, so wiping
 * them wholesale on any message/contact deletion is safe. Without this, a message
 * deleted by retention, a purge request, or a contact removal left its decrypted bytes
 * sitting in the cache until Android happened to evict them - defeating the deletion the
 * user (or a peer's purge) asked for.
 */
object MediaCache {

    fun clearDecryptedMedia(context: Context) {
        try {
            File(context.cacheDir, "attachments").listFiles()?.forEach { it.delete() }
            context.cacheDir.listFiles { _, name -> name.startsWith("play_") && name.endsWith(".m4a") }
                ?.forEach { it.delete() }
        } catch (e: Exception) {
            Log.w("MediaCache", "Failed to clear decrypted media cache", e)
        }
    }
}
