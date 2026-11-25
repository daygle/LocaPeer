package com.locapeer.messaging

import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import com.locapeer.data.entity.MessageEntity
import com.locapeer.data.entity.MessageType
import kotlinx.coroutines.flow.MutableStateFlow

/**
 * How many messages a chat loads at a time. A chat opens on the newest page and loads the next
 * older one each time the user scrolls to the top, so a long history is never read (and re-read
 * on every delivery tick or read receipt) in full.
 */
internal const val CHAT_PAGE_SIZE = 200

/**
 * Grows [limit] by a page when the list is scrolled to its top and the last query filled the
 * whole window (so older messages may remain). [ready] holds it back until the screen has done its
 * initial jump to the newest message; before that the list briefly sits at index 0. The list keeps
 * its position by item key when older rows are added above, so the reader stays where they were.
 */
@Composable
internal fun LoadOlderMessagesAtTop(
    listState: LazyListState,
    loadedCount: Int,
    limit: MutableStateFlow<Int>,
    ready: Boolean,
) {
    val atTop by remember(listState) { derivedStateOf { listState.firstVisibleItemIndex == 0 } }
    LaunchedEffect(atTop, loadedCount, ready) {
        if (ready && atTop && loadedCount >= limit.value) limit.value += CHAT_PAGE_SIZE
    }
}

/**
 * [msg] with its media bytes filled in. Chat lists are queried without the (up to ~200 KB per row)
 * Base64 media so a thread reload stays cheap; each media bubble loads its own bytes by id when it
 * is shown. [cached] is a synchronous cache peek so a bubble scrolled back into view doesn't flash
 * its placeholder; [load] fetches (and caches) the bytes otherwise.
 */
@Composable
internal fun withMedia(
    msg: MessageEntity,
    cached: (String) -> String?,
    load: suspend (String) -> String?,
): MessageEntity {
    val needsMedia = msg.contentType != MessageType.TEXT && msg.mediaBase64 == null
    val media by produceState(initialValue = if (needsMedia) cached(msg.id) else null, msg.id, needsMedia) {
        if (needsMedia && value == null) value = load(msg.id)
    }
    return remember(msg, media) { if (media == null) msg else msg.copy(mediaBase64 = media) }
}
