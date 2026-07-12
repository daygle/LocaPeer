package com.locapeer.messaging

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.*
import androidx.compose.foundation.Image
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
import androidx.compose.material.icons.filled.AccessTime
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.Archive
import androidx.compose.material.icons.filled.Block
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.DeleteSweep
import androidx.compose.material.icons.filled.Done
import androidx.compose.material.icons.filled.DoneAll
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Image
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.MyLocation
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.Place
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Unarchive
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.locapeer.data.entity.MessageType
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.LinkAnnotation
import androidx.compose.ui.text.TextLinkStyles
import androidx.compose.ui.text.withLink
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import com.locapeer.R
import com.locapeer.data.entity.DeliveryState
import com.locapeer.data.entity.MessageEntity
import android.content.Intent
import androidx.core.net.toUri
import android.util.Patterns
import java.util.Date

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ChatScreen(
    peerId: String,
    peerName: String,
    onNavigateBack: () -> Unit,
    onNavigateToMap: (Double, Double) -> Unit = { _, _ -> },
    vm: MessagingViewModel = hiltViewModel()
) {
    val messagesFlow = remember(peerId) { vm.getMessages(peerId) }
    val messages by messagesFlow.collectAsState(initial = emptyList())
    val typingPeers by vm.typingPeers.collectAsState()
    val isPeerTyping = typingPeers.containsKey(peerId)
    val peerFlow = remember(peerId) { vm.observePeer(peerId) }
    val peer by peerFlow.collectAsState(initial = null)
    val messagingEnabled = peer?.messagingEnabled ?: true
    val isArchived = peer?.isArchived ?: false
    var inputText by remember { mutableStateOf("") }
    val listState = rememberLazyListState()
    var showOptionsMenu by remember { mutableStateOf(false) }
    var showClearChatDialog by remember { mutableStateOf(false) }

    val context = LocalContext.current
    val isRecording by vm.isRecording.collectAsState()
    val playingMessageId by vm.playingMessageId.collectAsState()
    var fullscreenImage by remember { mutableStateOf<String?>(null) }

    val imagePicker = rememberLauncherForActivityResult(
        ActivityResultContracts.PickVisualMedia()
    ) { uri -> if (uri != null) vm.sendImage(peerId, uri) }

    val micPermissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted -> if (granted) vm.startRecording(RecordTarget.Peer(peerId)) }

    val onStartRecording: () -> Unit = {
        val granted = androidx.core.content.ContextCompat.checkSelfPermission(
            context, android.Manifest.permission.RECORD_AUDIO
        ) == android.content.pm.PackageManager.PERMISSION_GRANTED
        // 1:1 chats stamp Peer(id); circle chats stamp Circle(id). The ViewModel stores this
        // key internally so stopRecordingAndSend() can dispatch to the right send pipeline
        // without taking a destination parameter.
        if (granted) vm.startRecording(RecordTarget.Peer(peerId))
        else micPermissionLauncher.launch(android.Manifest.permission.RECORD_AUDIO)
    }

    fullscreenImage?.let { b64 ->
        ImageViewerDialog(base64 = b64, onDismiss = { fullscreenImage = null })
    }

    LaunchedEffect(peerId) { vm.markRead(peerId) }
    LaunchedEffect(messages.size) {
        if (messages.isNotEmpty()) listState.animateScrollToItem(messages.size - 1)
    }

    if (showClearChatDialog) {
        AlertDialog(
            onDismissRequest = { showClearChatDialog = false },
            icon = { Icon(Icons.Default.DeleteSweep, contentDescription = null) },
            title = { Text(stringResource(R.string.chat_clear_title)) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(stringResource(R.string.chat_clear_message, peerName))

                    ListItem(
                        headlineContent = { Text(stringResource(R.string.chat_clear_locally)) },
                        supportingContent = { Text(stringResource(R.string.conv_delete_locally_sub)) },
                        modifier = Modifier.clickable {
                            vm.deleteConversation(peerId)
                            showClearChatDialog = false
                        },
                        colors = ListItemDefaults.colors(containerColor = Color.Transparent)
                    )

                    ListItem(
                        headlineContent = { Text(stringResource(R.string.chat_clear_both)) },
                        supportingContent = { Text(stringResource(R.string.chat_clear_both_sub, peerName)) },
                        modifier = Modifier.clickable {
                            vm.deleteConversation(peerId)
                            vm.deleteConversationFromRemote(peerId)
                            showClearChatDialog = false
                        },
                        colors = ListItemDefaults.colors(containerColor = Color.Transparent)
                    )
                }
            },
            confirmButton = {},
            dismissButton = {
                TextButton(onClick = { showClearChatDialog = false }) { Text(stringResource(R.string.common_cancel)) }
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
                                .background(MaterialTheme.colorScheme.primaryContainer),
                            contentAlignment = Alignment.Center
                        ) {
                            Text(
                                peerName.firstOrNull()?.uppercaseChar()?.toString() ?: "?",
                                style = MaterialTheme.typography.titleSmall,
                                fontWeight = FontWeight.Bold,
                                color = MaterialTheme.colorScheme.onPrimaryContainer
                            )
                        }
                        Text(peerName, fontWeight = FontWeight.SemiBold)
                    }
                },
                navigationIcon = {
                    IconButton(onClick = onNavigateBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(R.string.cd_back))
                    }
                },
                actions = {
                    Box {
                        IconButton(onClick = { showOptionsMenu = true }) {
                            Icon(Icons.Default.MoreVert, contentDescription = stringResource(R.string.chat_cd_options))
                        }
                        DropdownMenu(
                            expanded = showOptionsMenu,
                            onDismissRequest = { showOptionsMenu = false }
                        ) {
                            DropdownMenuItem(
                                text = { Text(if (isArchived) stringResource(R.string.conv_unarchive) else stringResource(R.string.conv_archive)) },
                                leadingIcon = { Icon(if (isArchived) Icons.Default.Unarchive else Icons.Default.Archive, null) },
                                onClick = {
                                    showOptionsMenu = false
                                    vm.archiveConversation(peerId, !isArchived)
                                }
                            )
                            HorizontalDivider()
                            DropdownMenuItem(
                                text = { Text(stringResource(R.string.chat_clear_history_menu), color = MaterialTheme.colorScheme.error) },
                                leadingIcon = { Icon(Icons.Default.DeleteSweep, null, tint = MaterialTheme.colorScheme.error) },
                                onClick = {
                                    showOptionsMenu = false
                                    showClearChatDialog = true
                                }
                            )
                        }
                    }
                }
            )
        },
        bottomBar = {
            Column {
                AnimatedVisibility(
                    visible = isPeerTyping,
                    enter = fadeIn() + expandVertically(),
                    exit = fadeOut() + shrinkVertically()
                ) {
                    Text(
                        stringResource(R.string.chat_typing, peerName),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp)
                    )
                }
                AnimatedVisibility(
                    visible = !messagingEnabled,
                    enter = fadeIn() + expandVertically(),
                    exit = fadeOut() + shrinkVertically()
                ) {
                    Surface(color = MaterialTheme.colorScheme.errorContainer) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 16.dp, vertical = 10.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            Icon(
                                Icons.Default.Block,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.onErrorContainer,
                                modifier = Modifier.size(16.dp)
                            )
                            Text(
                                stringResource(R.string.chat_blocked_banner, peerName),
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onErrorContainer
                            )
                        }
                    }
                }
                ChatInputBar(
                    value = inputText,
                    onValueChange = { newText ->
                        inputText = newText
                        if (newText.isNotBlank()) vm.onTyping(peerId)
                    },
                    onSend = {
                        if (inputText.isNotBlank()) {
                            vm.sendMessage(peerId, inputText.trim())
                            inputText = ""
                        }
                    },
                    onLocationShare = { vm.sendLocation(peerId) },
                    onAttachImage = {
                        imagePicker.launch(
                            androidx.activity.result.PickVisualMediaRequest(
                                ActivityResultContracts.PickVisualMedia.ImageOnly
                            )
                        )
                    },
                    isRecording = isRecording,
                    onStartRecording = onStartRecording,
                    onStopSendRecording = { vm.stopRecordingAndSend() },
                    onCancelRecording = { vm.cancelRecording() }
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
                SwipeToDeleteMessage(
                    msg = msg,
                    onDeleteLocal = { vm.deleteMessage(msg) },
                    onDeleteRemote = { vm.deleteMessageFromRemote(msg) },
                    onNavigateToMap = onNavigateToMap,
                    isPlaying = playingMessageId == msg.id,
                    onToggleAudio = { b64 -> vm.toggleAudioPlayback(msg.id, b64) },
                    onViewImage = { b64 -> fullscreenImage = b64 }
                )
            }
        }
    }
}

@OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class, ExperimentalMaterial3Api::class)
@Composable
private fun SwipeToDeleteMessage(
    msg: MessageEntity,
    onDeleteLocal: () -> Unit,
    onDeleteRemote: () -> Unit,
    onNavigateToMap: (Double, Double) -> Unit,
    isPlaying: Boolean = false,
    onToggleAudio: (String) -> Unit = {},
    onViewImage: (String) -> Unit = {}
) {
    val dismissState = rememberSwipeToDismissBoxState(
        positionalThreshold = { it * 0.4f }
    )
    var showDeleteDialog by remember { mutableStateOf(false) }

    LaunchedEffect(dismissState.currentValue) {
        if (dismissState.currentValue == SwipeToDismissBoxValue.EndToStart) {
            showDeleteDialog = true
            dismissState.snapTo(SwipeToDismissBoxValue.Settled)
        }
    }

    if (showDeleteDialog) {
        AlertDialog(
            onDismissRequest = { showDeleteDialog = false },
            icon = { Icon(Icons.Default.Delete, contentDescription = null) },
            title = { Text(stringResource(R.string.chat_delete_msg_title)) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(stringResource(R.string.chat_delete_msg_message))

                    ListItem(
                        headlineContent = { Text(stringResource(R.string.conv_delete_locally)) },
                        supportingContent = { Text(stringResource(R.string.conv_delete_locally_sub)) },
                        modifier = Modifier.clickable {
                            onDeleteLocal()
                            showDeleteDialog = false
                        },
                        colors = ListItemDefaults.colors(containerColor = Color.Transparent)
                    )

                    if (msg.isMine) {
                        ListItem(
                            headlineContent = { Text(stringResource(R.string.chat_delete_from_contact)) },
                            supportingContent = { Text(stringResource(R.string.chat_delete_from_contact_sub)) },
                            modifier = Modifier.clickable {
                                onDeleteRemote()
                                showDeleteDialog = false
                            },
                            colors = ListItemDefaults.colors(containerColor = Color.Transparent)
                        )
                        ListItem(
                            headlineContent = { Text(stringResource(R.string.conv_delete_both)) },
                            supportingContent = { Text(stringResource(R.string.chat_delete_both_sub)) },
                            modifier = Modifier.clickable { 
                                onDeleteLocal()
                                onDeleteRemote()
                                showDeleteDialog = false
                            },
                            colors = ListItemDefaults.colors(containerColor = Color.Transparent)
                        )
                    }
                }
            },
            confirmButton = {},
            dismissButton = {
                TextButton(onClick = { showDeleteDialog = false }) { Text(stringResource(R.string.common_cancel)) }
            }
        )
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
        // `.fillMaxWidth()` makes the long-press hit area cover the whole row, and
        // `combinedClickable` lives OUTSIDE the SwipeToDismissBox's `pointerInput`
        // capture so long-press isn't intercepted by the swipe detector. Inner-content
        // taps (image / voice / location pin / delivery-row long-press) still route
        // through their own nested gesture modifiers on `MessageBubble`.
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .combinedClickable(onClick = {}, onLongClick = { showDeleteDialog = true })
                .background(MaterialTheme.colorScheme.surface)
        ) {
            MessageBubble(
                msg,
                onNavigateToMap = onNavigateToMap,
                isPlaying = isPlaying,
                onToggleAudio = onToggleAudio,
                onViewImage = onViewImage
            )
        }
    }
}

@OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)
@Composable
private fun MessageBubble(
    msg: MessageEntity,
    onNavigateToMap: (Double, Double) -> Unit = { _, _ -> },
    isPlaying: Boolean = false,
    onToggleAudio: (String) -> Unit = {},
    onViewImage: (String) -> Unit = {}
) {
    val alignment = if (msg.isMine) Alignment.End else Alignment.Start
    val bubbleColor = if (msg.isMine)
        MaterialTheme.colorScheme.primaryContainer
    else
        MaterialTheme.colorScheme.surfaceVariant

    Column(
        modifier = Modifier.fillMaxWidth(),
        horizontalAlignment = alignment
    ) {
        Box(
            modifier = Modifier
                .widthIn(max = 280.dp)
                // Intentionally NO combinedClickable on the bubble Box. The SwipeToDismissBox
                // wrapper's `pointerInput` blocks nested combinedClickable long-press detection;
                // long-press on the message body is owned by the wrapper's outer Box (above
                // the swipe detector). Inner-content taps (image / voice / location pin) and
                // the delivery-row long-press (delivery status sheet) keep their own nested
                // gesture modifiers.
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
            Column {
                val contentColor = if (msg.isMine) MaterialTheme.colorScheme.onPrimaryContainer
                    else MaterialTheme.colorScheme.onSurfaceVariant
                when (msg.contentType) {
                    MessageType.IMAGE -> ImageMessageContent(
                        base64 = msg.mediaBase64,
                        onView = { msg.mediaBase64?.let(onViewImage) }
                    )
                    MessageType.AUDIO -> VoiceMessageContent(
                        base64 = msg.mediaBase64,
                        durationMs = msg.mediaDurationMs,
                        isPlaying = isPlaying,
                        contentColor = contentColor,
                        onToggle = { msg.mediaBase64?.let(onToggleAudio) }
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
                            LinkifiedText(
                                text = msg.content,
                                style = MaterialTheme.typography.bodyMedium.copy(color = contentColor),
                                onNavigateToMap = onNavigateToMap
                            )
                        }
                    }
                }
                if (msg.isMine) {
                    var deliverySheetOpen by remember { mutableStateOf(false) }
                    Row(
                        modifier = Modifier
                            .align(Alignment.End)
                            .combinedClickable(
                                onClick = {},
                                onLongClick = { deliverySheetOpen = true }
                            ),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(2.dp)
                    ) {
                        Text(
                            formatTime(msg.timestamp),
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        val (statusIcon, statusTint) = when (msg.deliveryState) {
                            DeliveryState.SENDING.name ->
                                Icons.Default.AccessTime to MaterialTheme.colorScheme.onSurfaceVariant
                            DeliveryState.SENT.name ->
                                Icons.Default.Done to MaterialTheme.colorScheme.onSurfaceVariant
                            DeliveryState.READ.name ->
                                Icons.Default.DoneAll to MaterialTheme.colorScheme.primary
                            else -> // DELIVERED
                                Icons.Default.DoneAll to MaterialTheme.colorScheme.onSurfaceVariant
                        }
                        Icon(
                            statusIcon,
                            contentDescription = null,
                            modifier = Modifier.size(12.dp),
                            tint = statusTint
                        )
                    }
                    if (deliverySheetOpen) {
                        DeliveryStatusSheet(msg.deliveryState) { deliverySheetOpen = false }
                    }
                } else {
                    Text(
                        formatTime(msg.timestamp),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.align(Alignment.End)
                    )
                }
            }
        }
    }
}

@Composable
private fun LinkifiedText(
    text: String,
    style: androidx.compose.ui.text.TextStyle,
    onNavigateToMap: (Double, Double) -> Unit
) {
    val context = LocalContext.current
    val annotatedString = remember(text, style.color) {
        buildAnnotatedString {
            val matcher = Patterns.WEB_URL.matcher(text)
            var lastIndex = 0
            while (matcher.find()) {
                append(text.substring(lastIndex, matcher.start()))
                val url = text.substring(matcher.start(), matcher.end())
                
                val linkInteractionListener = { _: LinkAnnotation ->
                    val uri = url.toUri()
                    val mlat = uri.getQueryParameter("mlat")?.toDoubleOrNull()
                    val mlon = uri.getQueryParameter("mlon")?.toDoubleOrNull()
                    
                    if (mlat != null && mlon != null) {
                        onNavigateToMap(mlat, mlon)
                    } else {
                        try {
                            val intent = Intent(Intent.ACTION_VIEW, uri)
                            context.startActivity(intent)
                        } catch (e: Exception) {
                            // ignore
                        }
                    }
                }

                val link = LinkAnnotation.Clickable(
                    tag = "URL",
                    styles = TextLinkStyles(
                        style = SpanStyle(
                            color = Color(0xFF0000EE),
                            textDecoration = TextDecoration.Underline
                        )
                    ),
                    linkInteractionListener = linkInteractionListener
                )

                withLink(link) {
                    append(url)
                }
                lastIndex = matcher.end()
            }
            append(text.substring(lastIndex))
        }
    }

    Text(
        text = annotatedString,
        style = style
    )
}

@Composable
internal fun ChatInputBar(
    value: String,
    onValueChange: (String) -> Unit,
    onSend: () -> Unit,
    onLocationShare: () -> Unit,
    onAttachImage: () -> Unit,
    isRecording: Boolean,
    onStartRecording: () -> Unit,
    onStopSendRecording: () -> Unit,
    onCancelRecording: () -> Unit
) {
    Surface(shadowElevation = 4.dp) {
        if (isRecording) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 8.dp, vertical = 4.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                IconButton(onClick = onCancelRecording) {
                    Icon(
                        Icons.Default.Close,
                        contentDescription = stringResource(R.string.chat_cd_cancel_recording),
                        tint = MaterialTheme.colorScheme.error
                    )
                }
                Row(
                    modifier = Modifier.weight(1f),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Box(
                        modifier = Modifier
                            .size(10.dp)
                            .clip(CircleShape)
                            .background(MaterialTheme.colorScheme.error)
                    )
                    Text(stringResource(R.string.chat_recording), style = MaterialTheme.typography.bodyMedium)
                }
                IconButton(onClick = onStopSendRecording) {
                    Icon(
                        Icons.AutoMirrored.Filled.Send,
                        contentDescription = stringResource(R.string.chat_cd_stop_send_recording),
                        tint = MaterialTheme.colorScheme.primary
                    )
                }
            }
        } else {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 8.dp, vertical = 4.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                IconButton(onClick = onLocationShare) {
                    Icon(
                        Icons.Default.MyLocation,
                        contentDescription = stringResource(R.string.chat_cd_share_location),
                        tint = MaterialTheme.colorScheme.primary
                    )
                }
                IconButton(onClick = onAttachImage) {
                    Icon(
                        Icons.Default.Image,
                        contentDescription = stringResource(R.string.chat_cd_attach_image),
                        tint = MaterialTheme.colorScheme.primary
                    )
                }
                OutlinedTextField(
                    value = value,
                    onValueChange = onValueChange,
                    placeholder = { Text(stringResource(R.string.chat_input_placeholder)) },
                    modifier = Modifier.weight(1f),
                    maxLines = 4
                )
                Spacer(Modifier.width(8.dp))
                if (value.isBlank()) {
                    IconButton(onClick = onStartRecording) {
                        Icon(
                            Icons.Default.Mic,
                            contentDescription = stringResource(R.string.chat_cd_record_voice),
                            tint = MaterialTheme.colorScheme.primary
                        )
                    }
                } else {
                    IconButton(onClick = onSend) {
                        Icon(Icons.AutoMirrored.Filled.Send, contentDescription = stringResource(R.string.chat_cd_send))
                    }
                }
            }
        }
    }
}

/** Inline image thumbnail; tap to open the full-screen viewer. */
@Composable
internal fun ImageMessageContent(base64: String?, onView: () -> Unit) {
    val bitmap = remember(base64) { base64?.let { MediaUtils.decodeBase64ToBitmap(it) } }
    if (bitmap == null) {
        Text(stringResource(R.string.chat_preview_photo), style = MaterialTheme.typography.bodyMedium)
        return
    }
    Image(
        bitmap = bitmap.asImageBitmap(),
        contentDescription = stringResource(R.string.chat_cd_view_image),
        contentScale = ContentScale.Fit,
        modifier = Modifier
            .sizeIn(maxWidth = 240.dp, maxHeight = 320.dp)
            .clip(RoundedCornerShape(10.dp))
            .clickable(onClick = onView)
    )
}

/** Voice-note bubble: play/pause toggle plus duration. */
@Composable
internal fun VoiceMessageContent(
    base64: String?,
    durationMs: Long?,
    isPlaying: Boolean,
    contentColor: Color,
    onToggle: () -> Unit
) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        modifier = Modifier.clickable(enabled = base64 != null, onClick = onToggle)
    ) {
        Icon(
            if (isPlaying) Icons.Default.Pause else Icons.Default.PlayArrow,
            contentDescription = stringResource(
                if (isPlaying) R.string.chat_cd_pause_voice else R.string.chat_cd_play_voice
            ),
            tint = contentColor
        )
        Icon(Icons.Default.Mic, contentDescription = null, modifier = Modifier.size(16.dp), tint = contentColor)
        Text(formatDuration(durationMs ?: 0L), style = MaterialTheme.typography.bodyMedium, color = contentColor)
    }
}

/** Full-screen image viewer shown when a photo message is tapped. */
@Composable
internal fun ImageViewerDialog(base64: String, onDismiss: () -> Unit) {
    val bitmap = remember(base64) { MediaUtils.decodeBase64ToBitmap(base64) }
    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(Color.Black.copy(alpha = 0.92f))
                .clickable(onClick = onDismiss),
            contentAlignment = Alignment.Center
        ) {
            if (bitmap != null) {
                Image(
                    bitmap = bitmap.asImageBitmap(),
                    contentDescription = null,
                    contentScale = ContentScale.Fit,
                    modifier = Modifier.fillMaxSize().padding(16.dp)
                )
            }
            IconButton(
                onClick = onDismiss,
                modifier = Modifier.align(Alignment.TopEnd).padding(8.dp)
            ) {
                Icon(Icons.Default.Close, contentDescription = stringResource(R.string.chat_cd_close_image), tint = Color.White)
            }
        }
    }
}

private fun formatDuration(ms: Long): String {
    val totalSec = (ms / 1000).toInt()
    return "%d:%02d".format(totalSec / 60, totalSec % 60)
}

private fun formatTime(millis: Long): String =
    com.locapeer.util.DisplayFormat.timeFormat().format(Date(millis))

/**
 * Detects a shared-location message by finding the first OpenStreetMap URL carrying mlat/mlon
 * query params (the format [MessagingViewModel.sendLocation] / sendGroupLocation produce). Returns
 * the (lat, lng) pair, or null for an ordinary text message. Rendering a card off this keeps the
 * pin backward-compatible: older clients still see a tappable link, newer ones see a card.
 */
internal fun detectLocationPin(content: String): Pair<Double, Double>? {
    val matcher = Patterns.WEB_URL.matcher(content)
    while (matcher.find()) {
        val url = content.substring(matcher.start(), matcher.end())
        val uri = try { url.toUri() } catch (e: Exception) { continue }
        val lat = uri.getQueryParameter("mlat")?.toDoubleOrNull()
        val lng = uri.getQueryParameter("mlon")?.toDoubleOrNull()
        if (lat != null && lng != null) return lat to lng
    }
    return null
}

/** Compact tappable "shared location" card used for location-pin messages in 1:1 and group chats. */
@Composable
internal fun LocationPinCard(
    lat: Double,
    lng: Double,
    contentColor: Color,
    onClick: () -> Unit
) {
    Row(
        modifier = Modifier
            .clip(RoundedCornerShape(10.dp))
            .clickable(onClick = onClick)
            .padding(vertical = 2.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        Box(
            modifier = Modifier
                .size(40.dp)
                .clip(RoundedCornerShape(10.dp))
                .background(contentColor.copy(alpha = 0.15f)),
            contentAlignment = Alignment.Center
        ) {
            Icon(
                Icons.Default.Place,
                contentDescription = null,
                tint = contentColor
            )
        }
        Column {
            Text(
                stringResource(R.string.chat_shared_location),
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.SemiBold,
                color = contentColor
            )
            Text(
                String.format(java.util.Locale.US, "%.5f, %.5f", lat, lng),
                style = MaterialTheme.typography.labelSmall,
                color = contentColor.copy(alpha = 0.7f)
            )
            Text(
                stringResource(R.string.chat_tap_to_open_map),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.primary
            )
        }
    }
}

/**
 * Bottom-sheet shown on long-press of a sent message's delivery row. Surfaces the
 * state machine the small checkmark icon encodes so the user can confirm exactly
 * what "delivered" or "read" means (relay accepted vs. delivered vs. read receipt),
 * and so a "SENDING…" bubble that's been stuck gives a clear "failed / queued"
 * surface beyond the tiny clock icon. The sheet stays local to the bubble and
 * dismisses on a system drag or button tap.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun DeliveryStatusSheet(deliveryState: String, onDismiss: () -> Unit) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = sheetState) {
        Column(modifier = Modifier.padding(24.dp)) {
            Text(
                stringResource(R.string.chat_delivery_details_title),
                style = MaterialTheme.typography.titleMedium
            )
            Spacer(Modifier.height(12.dp))
            val (icon, tint, body) = when (deliveryState) {
                DeliveryState.SENDING.name -> Triple(
                    Icons.Default.AccessTime, MaterialTheme.colorScheme.onSurfaceVariant,
                    stringResource(R.string.chat_delivery_state_sending)
                )
                DeliveryState.SENT.name -> Triple(
                    Icons.Default.Done, MaterialTheme.colorScheme.onSurfaceVariant,
                    stringResource(R.string.chat_delivery_state_sent)
                )
                DeliveryState.DELIVERED.name -> Triple(
                    Icons.Default.DoneAll, MaterialTheme.colorScheme.onSurfaceVariant,
                    stringResource(R.string.chat_delivery_state_delivered)
                )
                DeliveryState.READ.name -> Triple(
                    Icons.Default.DoneAll, MaterialTheme.colorScheme.primary,
                    stringResource(R.string.chat_delivery_state_read)
                )
                else -> Triple(
                    Icons.Default.Warning, MaterialTheme.colorScheme.error,
                    stringResource(R.string.chat_delivery_state_failed)
                )
            }
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Icon(icon, contentDescription = null, tint = tint)
                Text(body, style = MaterialTheme.typography.bodyMedium)
            }
            Spacer(Modifier.height(16.dp))
            Button(onClick = onDismiss, modifier = Modifier.fillMaxWidth()) {
                Text(stringResource(R.string.common_close))
            }
        }
    }
}
