package com.locapeer.messaging

import androidx.compose.animation.*
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
import androidx.compose.material.icons.filled.MyLocation
import androidx.compose.material.icons.filled.Unarchive
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
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
                    onLocationShare = { vm.sendLocation(peerId) }
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
                    onNavigateToMap = onNavigateToMap
                )
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SwipeToDeleteMessage(
    msg: MessageEntity,
    onDeleteLocal: () -> Unit,
    onDeleteRemote: () -> Unit,
    onNavigateToMap: (Double, Double) -> Unit
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
        Box(modifier = Modifier.background(MaterialTheme.colorScheme.surface)) {
            MessageBubble(msg, onLongClick = { showDeleteDialog = true }, onNavigateToMap = onNavigateToMap)
        }
    }
}

@OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)
@Composable
private fun MessageBubble(msg: MessageEntity, onLongClick: () -> Unit = {}, onNavigateToMap: (Double, Double) -> Unit = { _, _ -> }) {
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
                .combinedClickable(onClick = {}, onLongClick = onLongClick)
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
                LinkifiedText(
                    text = msg.content,
                    style = MaterialTheme.typography.bodyMedium.copy(
                        color = if (msg.isMine) MaterialTheme.colorScheme.onPrimaryContainer else MaterialTheme.colorScheme.onSurfaceVariant
                    ),
                    onNavigateToMap = onNavigateToMap
                )
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
private fun ChatInputBar(
    value: String,
    onValueChange: (String) -> Unit,
    onSend: () -> Unit,
    onLocationShare: () -> Unit
) {
    Surface(shadowElevation = 4.dp) {
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
            OutlinedTextField(
                value = value,
                onValueChange = onValueChange,
                placeholder = { Text(stringResource(R.string.chat_input_placeholder)) },
                modifier = Modifier.weight(1f),
                maxLines = 4
            )
            Spacer(Modifier.width(8.dp))
            IconButton(
                onClick = onSend,
                enabled = value.isNotBlank()
            ) {
                Icon(Icons.AutoMirrored.Filled.Send, contentDescription = stringResource(R.string.chat_cd_send))
            }
        }
    }
}

private fun formatTime(millis: Long): String =
    com.locapeer.util.DisplayFormat.timeFormat().format(Date(millis))

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
