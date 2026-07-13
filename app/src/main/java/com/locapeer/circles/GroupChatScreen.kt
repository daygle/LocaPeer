package com.locapeer.circles

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.Logout
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.Archive
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.DeleteSweep
import androidx.compose.material.icons.filled.Group
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Unarchive
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import com.locapeer.R
import com.locapeer.data.entity.MessageEntity
import com.locapeer.data.entity.MessageType
import com.locapeer.messaging.ChatInputBar
import com.locapeer.messaging.FileMessageContent
import com.locapeer.messaging.ImageMessageContent
import com.locapeer.messaging.ImageViewerDialog
import com.locapeer.messaging.LocationPinCard
import com.locapeer.messaging.MediaUtils
import com.locapeer.messaging.MessagingViewModel
import com.locapeer.messaging.RecordTarget
import com.locapeer.messaging.VoiceMessageContent
import com.locapeer.messaging.detectLocationPin
import android.widget.Toast
import java.util.Date

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun GroupChatScreen(
    circleId: String,
    onNavigateBack: () -> Unit,
    onNavigateToMap: (Double, Double) -> Unit = { _, _ -> },
    onManageMembers: (String) -> Unit = {},
    vm: MessagingViewModel = hiltViewModel(),
    circlesVm: CirclesViewModel = hiltViewModel()
) {
    val circle by remember(circleId) { vm.observeCircle(circleId) }.collectAsStateWithLifecycle(initialValue = null)
    val messages by remember(circleId) { vm.getGroupMessages(circleId) }.collectAsStateWithLifecycle(initialValue = emptyList())
    val members by remember(circleId) { circlesVm.observeMembers(circleId) }.collectAsStateWithLifecycle(initialValue = emptyList())
    val peers by vm.peers.collectAsStateWithLifecycle()
    val myPub by vm.myPubkeyHex.collectAsStateWithLifecycle()
    val isRecording by vm.isRecording.collectAsStateWithLifecycle()
    val playingMessageId by vm.playingMessageId.collectAsStateWithLifecycle()
    val nameByPubkey = remember(peers) { peers.associate { it.deviceId to it.displayName } }
    // Only the owner may rename the circle or change its members; a non-owner opens the member
    // screen read-only, so the menu entry reads "View members" for them.
    val canEditCircle = circlesVm.canEditCircle(circle, myPub)

    var inputText by remember { mutableStateOf("") }
    var showMenu by remember { mutableStateOf(false) }
    var showDeleteDialog by remember { mutableStateOf(false) }
    var showLeaveDialog by remember { mutableStateOf(false) }
    // Message the user is about to delete via long-press or left-swipe. Both gestures
    // converge on the same AlertDialog so the delete options stay identical to 1:1 chat.
    var pendingDeleteMsg by remember { mutableStateOf<MessageEntity?>(null) }
    // Used by the full-screen image viewer dialog below. Persists across recompositions
    // but is screen-local; circles share no global viewer state with 1:1 chat (a viewer only
    // covers the chat that opened it).
    var fullscreenImage by remember { mutableStateOf<String?>(null) }
    val listState = rememberLazyListState()
    val circleName = circle?.name ?: stringResource(R.string.circles_title)

    val context = LocalContext.current

    // Pick an image from the system Photo Picker on Android 13+ (fallback to GET_CONTENT on
    // older OEM ROMs). On success, fan out to the circle via vm.sendGroupImage.
    val imagePicker = rememberLauncherForActivityResult(
        ActivityResultContracts.PickVisualMedia()
    ) { uri -> if (uri != null) vm.sendGroupImage(circleId, uri) }

    // Any-type document picker: fan the capped file out to the circle. Same size-cap/reject
    // handling as 1:1 chat lives in the ViewModel.
    val filePicker = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri -> if (uri != null) vm.sendGroupFile(circleId, uri) }

    // ViewModel emits already-localized strings, so no LocalContext resource read happens here.
    LaunchedEffect(Unit) {
        vm.mediaError.collect { message -> Toast.makeText(context, message, Toast.LENGTH_SHORT).show() }
    }

    // Mic permission gate - circles follow the same 1:1 pattern: probe at the moment of the
    // tap (not at first message) so the prompt only shows if the user explicitly opts in.
    val micPermissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted -> if (granted) vm.startRecording(RecordTarget.Circle(circleId)) }

    val onStartRecording: () -> Unit = {
        val granted = androidx.core.content.ContextCompat.checkSelfPermission(
            context, android.Manifest.permission.RECORD_AUDIO
        ) == android.content.pm.PackageManager.PERMISSION_GRANTED
        // Stamp Circle so stopRecordingAndSend() routes to the circle fan-out pipeline.
        if (granted) vm.startRecording(RecordTarget.Circle(circleId))
        else micPermissionLauncher.launch(android.Manifest.permission.RECORD_AUDIO)
    }

    fullscreenImage?.let { b64 ->
        ImageViewerDialog(base64 = b64, onDismiss = { fullscreenImage = null })
    }

    LaunchedEffect(circleId) { vm.markReadGroup(circleId) }
    LaunchedEffect(messages.size) {
        if (messages.isNotEmpty()) {
            // Also mark messages arriving while the screen is open, otherwise a circle the
            // user is actively reading still accrues an unread badge for later.
            vm.markReadGroup(circleId)
            listState.animateScrollToItem(messages.size - 1)
        }
    }

    if (showDeleteDialog) {
        AlertDialog(
            onDismissRequest = { showDeleteDialog = false },
            icon = { Icon(Icons.Default.DeleteSweep, contentDescription = null) },
            title = { Text(stringResource(R.string.circles_delete_title)) },
            text = { Text(stringResource(R.string.circles_delete_message, circleName)) },
            confirmButton = {
                TextButton(onClick = {
                    // Removes the circle, its membership rows and its whole message thread.
                    circlesVm.deleteCircle(circleId)
                    showDeleteDialog = false
                    onNavigateBack()
                }) { Text(stringResource(R.string.common_delete), color = MaterialTheme.colorScheme.error) }
            },
            dismissButton = {
                TextButton(onClick = { showDeleteDialog = false }) { Text(stringResource(R.string.common_cancel)) }
            }
        )
    }

    // Leave dialog (non-owners). Notifies the owner (who drops you from the circle) and removes your
    // local copy; the two options mirror the message-delete menu - keep your sent messages on the
    // other members' devices, or delete them there too (NIP-09, your own messages only).
    if (showLeaveDialog) {
        AlertDialog(
            onDismissRequest = { showLeaveDialog = false },
            icon = { Icon(Icons.Default.Delete, contentDescription = null) },
            title = { Text(stringResource(R.string.circles_leave_title)) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(stringResource(R.string.circles_leave_message, circleName))
                    ListItem(
                        headlineContent = { Text(stringResource(R.string.circles_leave_local)) },
                        supportingContent = { Text(stringResource(R.string.circles_leave_local_sub)) },
                        modifier = Modifier.clickable {
                            vm.leaveCircle(circleId, alsoDeleteRemote = false)
                            showLeaveDialog = false
                            onNavigateBack()
                        },
                        colors = ListItemDefaults.colors(containerColor = Color.Transparent)
                    )
                    ListItem(
                        headlineContent = { Text(stringResource(R.string.circles_leave_remote)) },
                        supportingContent = { Text(stringResource(R.string.circles_leave_remote_sub)) },
                        modifier = Modifier.clickable {
                            vm.leaveCircle(circleId, alsoDeleteRemote = true)
                            showLeaveDialog = false
                            onNavigateBack()
                        },
                        colors = ListItemDefaults.colors(containerColor = Color.Transparent)
                    )
                }
            },
            confirmButton = {},
            dismissButton = {
                TextButton(onClick = { showLeaveDialog = false }) { Text(stringResource(R.string.common_cancel)) }
            }
        )
    }

    // Long-press or left-swipe on a circle message bubble surfaces the same AlertDialog that
    // ChatScreen uses for 1:1 messages. Reusing the existing strings keeps the UX consistent
    // across both surfaces - "Delete from contact" / "Delete both" only show for messages I sent
    // (NIP-09 remote deletion is sender-only).
    pendingDeleteMsg?.let { msg ->
        AlertDialog(
            onDismissRequest = { pendingDeleteMsg = null },
            icon = { Icon(Icons.Default.Delete, contentDescription = null) },
            title = { Text(stringResource(R.string.chat_delete_msg_title)) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(stringResource(R.string.chat_delete_msg_message))

                    ListItem(
                        headlineContent = { Text(stringResource(R.string.conv_delete_locally)) },
                        supportingContent = { Text(stringResource(R.string.conv_delete_locally_sub)) },
                        modifier = Modifier.clickable {
                            vm.deleteMessage(msg)
                            pendingDeleteMsg = null
                        },
                        colors = ListItemDefaults.colors(containerColor = Color.Transparent)
                    )

                    // Remote / NIP-09 deletion is sender-only - we can only ask the recipient(s)
                    // to drop a message we originally signed. Gated on `isMine` alone so the menu
                    // is identical to ChatScreen's 1:1 long-press menu. Rows with no tracked relay
                    // event id (pre-v9 circle messages, or a self-only circle whose fanout had
                    // zero non-self targets) fall through to [vm.deleteMessageFromRemote]'s
                    // defensive no-op, same as a 1:1 row whose nostrEventId never got stamped.
                    if (msg.isMine) {
                        ListItem(
                            headlineContent = { Text(stringResource(R.string.chat_delete_from_contact)) },
                            supportingContent = { Text(stringResource(R.string.chat_delete_from_contact_sub)) },
                            modifier = Modifier.clickable {
                                vm.deleteMessageFromRemote(msg)
                                pendingDeleteMsg = null
                            },
                            colors = ListItemDefaults.colors(containerColor = Color.Transparent)
                        )
                        ListItem(
                            headlineContent = { Text(stringResource(R.string.conv_delete_both)) },
                            supportingContent = { Text(stringResource(R.string.chat_delete_both_sub)) },
                            modifier = Modifier.clickable {
                                vm.deleteMessage(msg)
                                vm.deleteMessageFromRemote(msg)
                                pendingDeleteMsg = null
                            },
                            colors = ListItemDefaults.colors(containerColor = Color.Transparent)
                        )
                    }
                }
            },
            confirmButton = {},
            dismissButton = {
                TextButton(onClick = { pendingDeleteMsg = null }) { Text(stringResource(R.string.common_cancel)) }
            }
        )
    }

    Scaffold(
        modifier = Modifier.imePadding(),
        topBar = {
            TopAppBar(
                title = {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        Box(
                            modifier = Modifier
                                .size(36.dp)
                                .clip(CircleShape)
                                .background(MaterialTheme.colorScheme.tertiaryContainer),
                            contentAlignment = Alignment.Center
                        ) {
                            Icon(
                                Icons.Default.Group,
                                contentDescription = null,
                                modifier = Modifier.size(20.dp),
                                tint = MaterialTheme.colorScheme.onTertiaryContainer
                            )
                        }
                        Column {
                            Text(circleName, fontWeight = FontWeight.SemiBold, style = MaterialTheme.typography.titleMedium)
                            Text(
                                stringResource(R.string.circles_member_count, members.size),
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                },
                navigationIcon = {
                    IconButton(onClick = onNavigateBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(R.string.cd_back))
                    }
                },
                actions = {
                    Box {
                        IconButton(onClick = { showMenu = true }) {
                            Icon(Icons.Default.MoreVert, contentDescription = stringResource(R.string.chat_cd_options))
                        }
                        DropdownMenu(expanded = showMenu, onDismissRequest = { showMenu = false }) {
                            DropdownMenuItem(
                                text = {
                                    Text(stringResource(
                                        if (canEditCircle) R.string.circles_manage_members
                                        else R.string.circles_view_members
                                    ))
                                },
                                leadingIcon = { Icon(Icons.Default.Group, null) },
                                onClick = { showMenu = false; onManageMembers(circleId) }
                            )
                            // Note: no "Share Location" entry here - the chat input bar's location
                            // button (onLocationShare below) already covers sharing a location with
                            // the circle, so the menu doesn't duplicate it.
                            // Archive/unarchive parity with ChatScreen's 1:1 options menu. The
                            // circle drops off (or returns to) the Circles tab; archived circles
                            // live on the Archived tab of the Messages screen.
                            val isCircleArchived = circle?.isArchived ?: false
                            DropdownMenuItem(
                                text = { Text(if (isCircleArchived) stringResource(R.string.conv_unarchive) else stringResource(R.string.conv_archive)) },
                                leadingIcon = { Icon(if (isCircleArchived) Icons.Default.Unarchive else Icons.Default.Archive, null) },
                                onClick = {
                                    showMenu = false
                                    vm.archiveCircle(circleId, !isCircleArchived)
                                }
                            )
                            HorizontalDivider()
                            // Owner (or a legacy circle with no recorded owner) disbands their copy
                            // via "Delete Circle"; a non-owner member "Leaves" - which notifies the
                            // owner to drop them and removes their local copy.
                            if (canEditCircle) {
                                DropdownMenuItem(
                                    text = { Text(stringResource(R.string.circles_delete), color = MaterialTheme.colorScheme.error) },
                                    leadingIcon = { Icon(Icons.Default.DeleteSweep, null, tint = MaterialTheme.colorScheme.error) },
                                    onClick = { showMenu = false; showDeleteDialog = true }
                                )
                            } else {
                                DropdownMenuItem(
                                    text = { Text(stringResource(R.string.circles_leave), color = MaterialTheme.colorScheme.error) },
                                    leadingIcon = { Icon(Icons.AutoMirrored.Filled.Logout, null, tint = MaterialTheme.colorScheme.error) },
                                    onClick = { showMenu = false; showLeaveDialog = true }
                                )
                            }
                        }
                    }
                }
            )
        },
        bottomBar = {
            // Shared with ChatScreen 1:1 input. Wraps the same Surface + Row layout so the
            // bottom bar in circles and 1:1 chats feel identical, including the recording
            // state UI (close/mic-stop row) and the replace-mic-with-send mental model.
            Surface(shadowElevation = 4.dp) {
                ChatInputBar(
                    value = inputText,
                    onValueChange = { inputText = it },
                    onSend = {
                        if (inputText.isNotBlank()) {
                            vm.sendGroupMessage(circleId, inputText.trim())
                            inputText = ""
                        }
                    },
                    onLocationShare = { vm.sendGroupLocation(circleId) },
                    onAttachImage = {
                        imagePicker.launch(
                            androidx.activity.result.PickVisualMediaRequest(
                                ActivityResultContracts.PickVisualMedia.ImageOnly
                            )
                        )
                    },
                    onAttachFile = { filePicker.launch(arrayOf("*/*")) },
                    isRecording = isRecording,
                    onStartRecording = onStartRecording,
                    onStopSendRecording = { vm.stopRecordingAndSend() },
                    onCancelRecording = { vm.cancelRecording() },
                )
            }
        }
    ) { padding ->
        LazyColumn(
            state = listState,
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(horizontal = 8.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp),
            contentPadding = PaddingValues(vertical = 8.dp)
        ) {
            items(messages, key = { it.id }) { msg ->
                SwipeToDeleteGroupMessage(
                    msg = msg,
                    senderName = nameByPubkey[msg.senderPublicKeyHex] ?: stringResource(R.string.fallback_unknown),
                    // Both the swipe gesture (caught by SwipeToDeleteGroupMessage) and the
                    // long-press (caught by GroupMessageBubble.combinedClickable) call
                    // onLongPressRequest to flip pendingDeleteMsg, which surfaces the same
                    // AlertDialog ChatScreen uses for 1:1 messages. Identical UX on both
                    // surfaces - the wrapper is intentionally a near-clone.
                    onLongPressRequest = { pendingDeleteMsg = msg },
                    onNavigateToMap = onNavigateToMap,
                    // Single global playingMessageId in MessagingViewModel means a user can only
                    // hear one voice note at a time across 1:1 and circle chats - this is what we
                    // want so audio doesn't overlap.
                    isPlaying = playingMessageId == msg.id,
                    onToggleAudio = { b64 -> vm.toggleAudioPlayback(msg.id, b64) },
                    onViewImage = { b64 -> fullscreenImage = b64 },
                    onOpenFile = { vm.openFile(msg) },
                )
            }
        }
    }
}

/**
 * Bubble for a single circle message. Mirrors `MessageBubble` (1:1) but adds the
 * sender-name label for incoming messages, since circles can have multiple participants
 * and the recipient needs to know who said what. IMAGE / AUDIO / TEXT / location-pin all
 * route through shared helpers so the rendering stays consistent across chat surfaces.
 *
 * Long-press is intentionally NOT wired on the bubble Box itself. The `SwipeToDismissBox`
 * wrapper in [SwipeToDeleteGroupMessage] has its own `pointerInput` that intercepts
 * press-down events for swipe detection, and that pointer-input capture interferes with
 * `combinedClickable` long-press on a nested Box. Long-press is owned by the wrapper's
 * outer Box (above the swipe detector), which covers the entire row including the
 * empty space around a narrow bubble. Clickable targets inside the bubble (image / voice
 * / location pin) still get their own taps via nested `Modifier.clickable` handlers.
 */
@Composable
private fun GroupMessageBubble(
    msg: MessageEntity,
    senderName: String,
    onNavigateToMap: (Double, Double) -> Unit,
    isPlaying: Boolean,
    onToggleAudio: (String) -> Unit,
    onViewImage: (String) -> Unit,
    onOpenFile: () -> Unit,
) {
    val alignment = if (msg.isMine) Alignment.End else Alignment.Start
    val bubbleColor = if (msg.isMine)
        MaterialTheme.colorScheme.primaryContainer
    else
        MaterialTheme.colorScheme.surfaceVariant
    val contentColor = if (msg.isMine)
        MaterialTheme.colorScheme.onPrimaryContainer
    else
        MaterialTheme.colorScheme.onSurfaceVariant

    Column(modifier = Modifier.fillMaxWidth(), horizontalAlignment = alignment) {
        Box(
            modifier = Modifier
                .widthIn(max = 280.dp)
                .background(
                    bubbleColor,
                    RoundedCornerShape(
                        topStart = 16.dp,
                        topEnd = 16.dp,
                        bottomStart = if (msg.isMine) 16.dp else 4.dp,
                        bottomEnd = if (msg.isMine) 4.dp else 16.dp
                    )
                )
                .padding(horizontal = 12.dp, vertical = 8.dp)
        ) {
            // Note: long-press is NOT wired here on the bubble Box. The `SwipeToDismissBox`
            // wrapper above has its own `pointerInput` that intercepts press-down events to
            // implement its drag detection, and that pointer-input capture interferes with
            // `combinedClickable` long-press detection on a nested Box. Long-press is owned
            // by the wrapper (an outer Box wrapping the SwipeToDismissBox) so it lives
            // *above* the swipe detector's pointer-input capture. Clickable targets within
            // the bubble (image / voice / location pin) still get taps via their own nested
            // `Modifier.clickable`.
            //
            Column {
                if (!msg.isMine) {
                    Text(
                        senderName,
                        style = MaterialTheme.typography.labelMedium,
                        fontWeight = FontWeight.SemiBold,
                        color = MaterialTheme.colorScheme.primary
                    )
                }
                when (msg.contentType) {
                    MessageType.IMAGE -> {
                        msg.mediaBase64?.let { b64 ->
                            ImageMessageContent(
                                base64 = b64,
                                onView = { onViewImage(b64) },
                            )
                        } ?: Text(msg.content, color = contentColor)
                    }
                    MessageType.AUDIO -> {
                        msg.mediaBase64?.let { b64 ->
                            VoiceMessageContent(
                                base64 = b64,
                                durationMs = msg.mediaDurationMs,
                                isPlaying = isPlaying,
                                contentColor = contentColor,
                                onToggle = { onToggleAudio(b64) },
                            )
                        } ?: Text(msg.content, color = contentColor)
                    }
                    MessageType.FILE -> FileMessageContent(
                        filename = msg.mediaFilename,
                        sizeBytes = msg.mediaBase64?.let { MediaUtils.approxDecodedSize(it) },
                        contentColor = contentColor,
                        onOpen = onOpenFile,
                    )
                    else -> {
                        val pin = remember(msg.content) { detectLocationPin(msg.content) }
                        if (pin != null) {
                            LocationPinCard(
                                lat = pin.first,
                                lng = pin.second,
                                contentColor = contentColor,
                                onClick = { onNavigateToMap(pin.first, pin.second) }
                            )
                        } else {
                            Text(
                                msg.content,
                                style = MaterialTheme.typography.bodyMedium,
                                color = contentColor
                            )
                        }
                    }
                }
                Text(
                    formatTime(msg.timestamp),
                    style = MaterialTheme.typography.labelSmall,
                    color = contentColor.copy(alpha = 0.7f),
                    modifier = Modifier.align(Alignment.End)
                )
            }
        }
    }
}

private fun formatTime(millis: Long): String =
    com.locapeer.util.DisplayFormat.timeFormat().format(Date(millis))

/**
 * Wraps a [GroupMessageBubble] with `SwipeToDismissBox` (left-only) plus the bubble's own
 * long-press. Both gestures flip [onLongPressRequest] inside the parent which surfaces the
 * single delete AlertDialog. Same UX as ChatScreen's `SwipeToDeleteMessage` for 1:1 chats -
 * the only divergence is the wrapped bubble (GroupMessageBubble vs MessageBubble). Keeping
 * these mirrored rather than extracting a shared helper avoids cross-package coupling.
 */
@OptIn(ExperimentalFoundationApi::class, ExperimentalMaterial3Api::class)
@Composable
private fun SwipeToDeleteGroupMessage(
    msg: MessageEntity,
    senderName: String,
    onLongPressRequest: () -> Unit,
    onNavigateToMap: (Double, Double) -> Unit,
    isPlaying: Boolean = false,
    onToggleAudio: (String) -> Unit = {},
    onViewImage: (String) -> Unit = {},
    onOpenFile: () -> Unit = {}
) {
    val dismissState = rememberSwipeToDismissBoxState(
        positionalThreshold = { it * 0.4f }
    )

    // Swipe-to-end (leftward) opens the same handler as long-press. Snap back so the bubble
    // is visible while the AlertDialog is on top - the user must pick an option explicitly,
    // matching ChatScreen's behaviour.
    LaunchedEffect(dismissState.currentValue) {
        if (dismissState.currentValue == SwipeToDismissBoxValue.EndToStart) {
            onLongPressRequest()
            dismissState.snapTo(SwipeToDismissBoxValue.Settled)
        }
    }

    SwipeToDismissBox(
        state = dismissState,
        enableDismissFromStartToEnd = false,
        backgroundContent = {
            val alignment = Alignment.CenterEnd
            val color = MaterialTheme.colorScheme.errorContainer
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(color, RoundedCornerShape(12.dp))
                    .padding(horizontal = 20.dp),
                contentAlignment = alignment
            ) {
                Icon(
                    Icons.Default.Delete,
                    contentDescription = stringResource(R.string.common_delete),
                    tint = MaterialTheme.colorScheme.onErrorContainer
                )
            }
        }
    ) {
        // Outer Box wrapping the SwipeToDismissBox's CONTENT slot (not its background).
        // `.fillMaxWidth()` makes the long-press hit area cover the whole row, including
        // the empty space around a narrow bubble, and `combinedClickable` lives OUTSIDE
        // the SwipeToDismissBox's `pointerInput` capture so long-press isn't intercepted
        // by the swipe detector. Inner-content taps (image / voice / location pin) still
        // route through their own nested `Modifier.clickable`.
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .combinedClickable(onClick = {}, onLongClick = onLongPressRequest)
                .background(MaterialTheme.colorScheme.surface)
        ) {
            GroupMessageBubble(
                msg = msg,
                senderName = senderName,
                onNavigateToMap = onNavigateToMap,
                isPlaying = isPlaying,
                onToggleAudio = onToggleAudio,
                onViewImage = onViewImage,
                onOpenFile = onOpenFile
            )
        }
    }
}
