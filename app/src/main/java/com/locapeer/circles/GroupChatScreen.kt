package com.locapeer.circles

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.DeleteSweep
import androidx.compose.material.icons.filled.Group
import androidx.compose.material.icons.filled.MyLocation
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Share
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import com.locapeer.R
import com.locapeer.data.entity.MessageEntity
import com.locapeer.messaging.LocationPinCard
import com.locapeer.messaging.MessagingViewModel
import com.locapeer.messaging.detectLocationPin
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
    val circle by remember(circleId) { vm.observeCircle(circleId) }.collectAsState(initial = null)
    val messages by remember(circleId) { vm.getGroupMessages(circleId) }.collectAsState(initial = emptyList())
    val members by remember(circleId) { circlesVm.observeMembers(circleId) }.collectAsState(initial = emptyList())
    val peers by vm.peers.collectAsState()
    val nameByPubkey = remember(peers) { peers.associate { it.deviceId to it.displayName } }

    var inputText by remember { mutableStateOf("") }
    var showMenu by remember { mutableStateOf(false) }
    var showShareSheet by remember { mutableStateOf(false) }
    var showDeleteDialog by remember { mutableStateOf(false) }
    val listState = rememberLazyListState()
    val circleName = circle?.name ?: stringResource(R.string.circles_title)

    LaunchedEffect(circleId) { vm.markReadGroup(circleId) }
    LaunchedEffect(messages.size) {
        if (messages.isNotEmpty()) listState.animateScrollToItem(messages.size - 1)
    }

    if (showShareSheet) {
        CircleShareLocationSheet(
            onPick = { minutes ->
                circlesVm.shareLocationWithCircle(circleId, minutes)
                showShareSheet = false
            },
            onDismiss = { showShareSheet = false }
        )
    }

    if (showDeleteDialog) {
        AlertDialog(
            onDismissRequest = { showDeleteDialog = false },
            icon = { Icon(Icons.Default.DeleteSweep, contentDescription = null) },
            title = { Text(stringResource(R.string.circles_delete_title)) },
            text = { Text(stringResource(R.string.circles_delete_message, circleName)) },
            confirmButton = {
                TextButton(onClick = {
                    circlesVm.deleteCircle(circleId)
                    vm.deleteGroupConversation(circleId)
                    showDeleteDialog = false
                    onNavigateBack()
                }) { Text(stringResource(R.string.common_delete), color = MaterialTheme.colorScheme.error) }
            },
            dismissButton = {
                TextButton(onClick = { showDeleteDialog = false }) { Text(stringResource(R.string.common_cancel)) }
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
                                text = { Text(stringResource(R.string.circles_manage_members)) },
                                leadingIcon = { Icon(Icons.Default.Group, null) },
                                onClick = { showMenu = false; onManageMembers(circleId) }
                            )
                            DropdownMenuItem(
                                text = { Text(stringResource(R.string.circles_share_location)) },
                                leadingIcon = { Icon(Icons.Default.Share, null) },
                                onClick = { showMenu = false; showShareSheet = true }
                            )
                            HorizontalDivider()
                            DropdownMenuItem(
                                text = { Text(stringResource(R.string.circles_delete), color = MaterialTheme.colorScheme.error) },
                                leadingIcon = { Icon(Icons.Default.DeleteSweep, null, tint = MaterialTheme.colorScheme.error) },
                                onClick = { showMenu = false; showDeleteDialog = true }
                            )
                        }
                    }
                }
            )
        },
        bottomBar = {
            Surface(shadowElevation = 4.dp) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 8.dp, vertical = 4.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    IconButton(onClick = { vm.sendGroupLocation(circleId) }) {
                        Icon(
                            Icons.Default.MyLocation,
                            contentDescription = stringResource(R.string.chat_cd_share_location),
                            tint = MaterialTheme.colorScheme.primary
                        )
                    }
                    OutlinedTextField(
                        value = inputText,
                        onValueChange = { inputText = it },
                        placeholder = { Text(stringResource(R.string.chat_input_placeholder)) },
                        modifier = Modifier.weight(1f),
                        maxLines = 4
                    )
                    Spacer(Modifier.width(8.dp))
                    IconButton(
                        onClick = {
                            if (inputText.isNotBlank()) {
                                vm.sendGroupMessage(circleId, inputText.trim())
                                inputText = ""
                            }
                        },
                        enabled = inputText.isNotBlank()
                    ) {
                        Icon(Icons.AutoMirrored.Filled.Send, contentDescription = stringResource(R.string.chat_cd_send))
                    }
                }
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
                GroupMessageBubble(
                    msg = msg,
                    senderName = nameByPubkey[msg.senderPublicKeyHex] ?: stringResource(R.string.fallback_unknown),
                    onNavigateToMap = onNavigateToMap
                )
            }
        }
    }
}

@Composable
private fun GroupMessageBubble(
    msg: MessageEntity,
    senderName: String,
    onNavigateToMap: (Double, Double) -> Unit
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
            Column {
                if (!msg.isMine) {
                    Text(
                        senderName,
                        style = MaterialTheme.typography.labelMedium,
                        fontWeight = FontWeight.SemiBold,
                        color = MaterialTheme.colorScheme.primary
                    )
                }
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

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun CircleShareLocationSheet(onPick: (Int) -> Unit, onDismiss: () -> Unit) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = sheetState) {
        Column(modifier = Modifier.padding(24.dp)) {
            Text(stringResource(R.string.circles_share_location), style = MaterialTheme.typography.titleMedium)
            Spacer(Modifier.height(4.dp))
            Text(
                stringResource(R.string.circles_share_location_desc),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Spacer(Modifier.height(16.dp))
            val options = listOf(
                15 to stringResource(R.string.peer_temp_share_chip_15m),
                60 to stringResource(R.string.peer_temp_share_chip_1h),
                180 to stringResource(R.string.peer_temp_share_chip_3h),
                360 to stringResource(R.string.peer_temp_share_chip_6h)
            )
            options.forEach { (minutes, label) ->
                OutlinedButton(
                    onClick = { onPick(minutes) },
                    modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp)
                ) { Text(label) }
            }
            Spacer(Modifier.height(8.dp))
        }
    }
}

private fun formatTime(millis: Long): String =
    com.locapeer.util.DisplayFormat.timeFormat().format(Date(millis))
