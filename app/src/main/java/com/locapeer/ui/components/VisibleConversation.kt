package com.locapeer.ui.components

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.locapeer.util.AppNotifications

/**
 * Marks [conversationId] (contact device id or circle id) as the chat on screen while this
 * screen is resumed, so a message arriving in it gets no system notification - it is already
 * visible and marked read. Cleared when the screen pauses (app backgrounded, another screen on
 * top) or leaves, so notifications resume as soon as the chat isn't actually being looked at.
 */
@Composable
fun VisibleConversation(conversationId: String) {
    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner, conversationId) {
        fun clear() {
            if (AppNotifications.visibleConversation == conversationId) AppNotifications.visibleConversation = null
        }
        if (lifecycleOwner.lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)) {
            AppNotifications.visibleConversation = conversationId
        }
        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_RESUME -> AppNotifications.visibleConversation = conversationId
                Lifecycle.Event.ON_PAUSE -> clear()
                else -> {}
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose {
            lifecycleOwner.lifecycle.removeObserver(observer)
            clear()
        }
    }
}
