package com.locapeer.util

import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.util.Log
import com.locapeer.R

/**
 * Shared notification plumbing: the channel ids every poster uses, channel creation, and the
 * clean-up that keeps the tray in step with what the app shows (a conversation that was read,
 * or a contact or circle that no longer exists).
 */
object AppNotifications {
    const val CHANNEL_ALERTS = "locapeer_alerts"
    const val CHANNEL_MESSAGES = "locapeer_messages"

    /** Notification id of every message notification; the conversation id is the tag. */
    const val ID_MESSAGE = 10000

    /**
     * The conversation (contact device id or circle id) whose chat is on screen right now, or
     * null. A message arriving there is already visible and marked read, so it gets no
     * notification. Set and cleared by the chat screens from their own lifecycle.
     */
    @Volatile var visibleConversation: String? = null

    /**
     * Creates (or re-labels, after a language change) the alert and message channels. Cheap and
     * idempotent, and called synchronously at app start: a channel that doesn't exist yet makes
     * Android drop the notification, so a worker that runs before the app's background start-up
     * finishes must still find them.
     */
    fun ensureChannels(context: Context) {
        val nm = context.getSystemService(NotificationManager::class.java) ?: return
        nm.createNotificationChannel(
            NotificationChannel(
                CHANNEL_ALERTS,
                context.getString(R.string.channel_name_alerts),
                NotificationManager.IMPORTANCE_HIGH
            ).apply {
                description = context.getString(R.string.channel_desc_alerts)
                // Redact contact names / coordinates on the device lock screen by default.
                // Takes effect on new installs (Android ignores channel-property changes after
                // creation); the per-notification setVisibility on the sensitive alerts covers
                // existing installs too.
                lockscreenVisibility = android.app.Notification.VISIBILITY_PRIVATE
            }
        )
        nm.createNotificationChannel(
            NotificationChannel(
                CHANNEL_MESSAGES,
                context.getString(R.string.channel_name_messages),
                NotificationManager.IMPORTANCE_HIGH
            ).apply {
                description = context.getString(R.string.channel_desc_messages)
                lockscreenVisibility = android.app.Notification.VISIBILITY_PRIVATE
            }
        )
    }

    /** Clears the message notification for [conversationId] (contact device id or circle id). */
    fun cancelConversation(context: Context, conversationId: String) {
        context.getSystemService(NotificationManager::class.java)?.cancel(conversationId, ID_MESSAGE)
    }

    /**
     * Clears every notification about [deviceId] once the contact is gone: messages, SOS, alerts,
     * requests and the like are tagged with the contact's id, and geofence alerts with
     * "<fence id>:<device id>". Left in the tray they would open a contact that no longer exists.
     */
    fun cancelForContact(context: Context, deviceId: String) {
        val nm = context.getSystemService(NotificationManager::class.java) ?: return
        try {
            nm.activeNotifications
                .filter { val tag = it.tag ?: return@filter false; tag == deviceId || tag.endsWith(":$deviceId") }
                .forEach { nm.cancel(it.tag, it.id) }
        } catch (e: Exception) {
            Log.w("AppNotifications", "Failed to clear notifications for a removed contact", e)
        }
    }
}
