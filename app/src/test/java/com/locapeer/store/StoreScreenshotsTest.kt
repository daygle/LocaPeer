package com.locapeer.store

import android.app.Application
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onRoot
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.github.takahirom.roborazzi.captureRoboImage
import com.locapeer.contacts.ContactItem
import com.locapeer.contacts.ContactUpdateState
import com.locapeer.contacts.ContactsScreen
import com.locapeer.contacts.ContactsViewModel
import com.locapeer.data.entity.CircleEntity
import com.locapeer.data.entity.DeliveryState
import com.locapeer.data.entity.HeartbeatEntity
import com.locapeer.data.entity.MessageEntity
import com.locapeer.data.entity.PeerEntity
import com.locapeer.data.entity.PeerSharingConfig
import com.locapeer.messaging.ChatScreen
import com.locapeer.messaging.ConversationListScreen
import com.locapeer.messaging.ConversationSummary
import com.locapeer.messaging.GroupConversationSummary
import com.locapeer.messaging.MessagingViewModel
import com.locapeer.messaging.SortOrder
import com.locapeer.settings.AppPreferences
import com.locapeer.settings.AppSettings
import com.locapeer.settings.SettingsScreen
import com.locapeer.settings.SettingsViewModel
import com.locapeer.supervised.SupervisedModeManager
import com.locapeer.ui.theme.LocaPeerTheme
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import org.junit.Assume
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.Mockito
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * Renders real screens of the app with a fabricated cast of contacts, messages and locations, for
 * use as store images on Google Play and daygle.net.
 *
 * This is not a golden-image test: it writes PNGs into `store-screenshots/phone/` at the repository
 * root and never compares them, so run it by name:
 *
 *     ./gradlew :app:testDebugUnitTest --tests 'com.locapeer.store.StoreScreenshotsTest' \
 *         -Proborazzi.test.record=true
 *
 * 411x731dp at xxhdpi is 1233x2193 px (9:16). The emulator route captures the map screens at the
 * same `wm size 1233x2193` / `wm density 480`, so every store image in `store-screenshots/phone/`
 * shares one viewport. Each screen is composed standalone and fed a mocked ViewModel holding the
 * fake data below, so no database, relay or network is involved. The bottom navigation bar is part of
 * the app's NavHost (it needs a NavController and a settings flow), so it is not in these images,
 * exactly as in AboutScreenScreenshotTest.
 *
 * The map screens (Map, Location History) are not here: they render a MapLibre GLSurfaceView, and
 * MapLibre's native renderer does not run on the JVM. Capture those from an emulator instead - the
 * debug-only `DemoSeedActivity` (app/src/debug) writes the same fabricated cast through the real
 * encrypted database and preferences, and the `allowScreenshots` launch extra lifts FLAG_SECURE
 * for that debug run only:
 *
 *     adb shell am start -n com.locapeer/.store.DemoSeedActivity --ez seed true
 *     adb exec-out screencap -p > store-screenshots/phone/map.png
 */
@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(
    qualifiers = "w411dp-h731dp-xxhdpi",
    // The real Application opens SQLCipher in onCreate, whose native library doesn't load on the JVM.
    application = Application::class,
    sdk = [35],
)
class StoreScreenshotsTest {

    @get:Rule
    val compose = createComposeRule()

    /**
     * These images have no golden to compare against, so they must stay out of the ordinary
     * `testDebugUnitTest` run (which the build configures for Roborazzi *verify*): an unverified
     * capture would fail CI on a file it never had. They render only when explicitly recording.
     */
    @Before
    fun onlyWhenRecording() {
        Assume.assumeTrue(
            "store screenshots are rendered only with -Proborazzi.test.record=true",
            java.lang.Boolean.getBoolean("roborazzi.test.record"),
        )
    }

    // ---- Screens ---------------------------------------------------------

    @Test
    fun contacts() = capture("contacts") {
        LocaPeerTheme(dynamicColor = false) {
            ContactsScreen(
                onNavigateToChat = { _, _ -> },
                onNavigateToInvite = {},
                vm = contactsViewModel(),
            )
        }
    }

    @Test
    fun conversations() = capture("conversations") {
        LocaPeerTheme(dynamicColor = false) {
            ConversationListScreen(
                onOpenChat = { _, _ -> },
                vm = messagingViewModel(),
            )
        }
    }

    @Test
    fun chat() = capture("chat") {
        LocaPeerTheme(dynamicColor = false) {
            ChatScreen(
                peerId = MAYA_ID,
                peerName = "Maya Chen",
                onNavigateBack = {},
                vm = messagingViewModel(),
            )
        }
    }

    @Test
    fun chatDark() = capture("chat-dark") {
        LocaPeerTheme(darkTheme = true, dynamicColor = false) {
            ChatScreen(
                peerId = MAYA_ID,
                peerName = "Maya Chen",
                onNavigateBack = {},
                vm = messagingViewModel(),
            )
        }
    }

    @Test
    fun settings() = capture("settings") {
        LocaPeerTheme(dynamicColor = false) {
            SettingsScreen(vm = settingsViewModel())
        }
    }

    // ---- Harness ---------------------------------------------------------

    private fun capture(name: String, content: @androidx.compose.runtime.Composable () -> Unit) {
        compose.setContent(content)
        compose.waitForIdle()
        compose.onRoot().captureRoboImage("../store-screenshots/phone/$name.png")
    }

    /** "Just now" / "12 min ago" / "1 hour ago", mirroring DisplayFormat.relativeTimestamp. */
    private fun relative(timestampMs: Long): String {
        val minutes = (System.currentTimeMillis() - timestampMs) / 60_000
        return when {
            minutes < 1 -> "Just now"
            minutes < 60 -> "$minutes min ago"
            minutes < 120 -> "1 hour ago"
            minutes < 1440 -> "${minutes / 60} hours ago"
            else -> "${minutes / 1440} days ago"
        }
    }

    // ---- Fabricated data -------------------------------------------------

    private fun contactsViewModel(): ContactsViewModel {
        val vm = Mockito.mock(ContactsViewModel::class.java)
        Mockito.`when`(vm.contacts).thenReturn(MutableStateFlow(fakeContactItems()))
        Mockito.`when`(vm.pendingRequestCount).thenReturn(MutableStateFlow(3))
        Mockito.`when`(vm.messages).thenReturn(MutableSharedFlow())
        Mockito.`when`(vm.formatLastSeen(Mockito.anyLong())).thenAnswer { invocation ->
            relative(invocation.getArgument(0))
        }
        return vm
    }

    private fun messagingViewModel(): MessagingViewModel {
        val vm = Mockito.mock(MessagingViewModel::class.java)
        val conversations = fakeConversations()
        Mockito.`when`(vm.conversations).thenReturn(MutableStateFlow(conversations))
        Mockito.`when`(vm.archivedConversations).thenReturn(MutableStateFlow(emptyList()))
        Mockito.`when`(vm.groupConversations).thenReturn(MutableStateFlow(fakeCircles()))
        Mockito.`when`(vm.archivedGroupConversations).thenReturn(MutableStateFlow(emptyList()))
        Mockito.`when`(vm.peers).thenReturn(MutableStateFlow(fakePeers()))
        Mockito.`when`(vm.unreadCounts).thenReturn(
            MutableStateFlow(mapOf(MAYA_ID to 0, SAM_ID to 2, PRIYA_ID to 1)),
        )
        Mockito.`when`(vm.chatsUnreadTotal).thenReturn(MutableStateFlow(3))
        Mockito.`when`(vm.circlesUnreadTotal).thenReturn(MutableStateFlow(1))
        Mockito.`when`(vm.searchQuery).thenReturn(MutableStateFlow(""))
        Mockito.`when`(vm.sortOrder).thenReturn(MutableStateFlow(SortOrder.DATE))
        Mockito.`when`(vm.mutedConversations).thenReturn(MutableStateFlow(emptySet()))
        Mockito.`when`(vm.typingPeers).thenReturn(MutableStateFlow(emptyMap()))
        Mockito.`when`(vm.isRecording).thenReturn(MutableStateFlow(false))
        Mockito.`when`(vm.playingMessageId).thenReturn(MutableStateFlow<String?>(null))
        Mockito.`when`(vm.mediaError).thenReturn(MutableSharedFlow())
        Mockito.`when`(vm.appSettings).thenReturn(MutableStateFlow(AppSettings(displayName = "Alex Rivera")))
        Mockito.`when`(vm.observePeer(Mockito.anyString())).thenAnswer { invocation ->
            val peerId = invocation.getArgument<String>(0)
            MutableStateFlow(fakePeers().firstOrNull { it.deviceId == peerId }) as Flow<PeerEntity?>
        }
        Mockito.`when`(vm.observeLastHeartbeat(Mockito.anyString())).thenAnswer {
            MutableStateFlow(fakeHeartbeats()[MAYA_ID]) as Flow<HeartbeatEntity?>
        }
        Mockito.`when`(vm.getMessages(Mockito.anyString(), Mockito.anyInt()))
            .thenReturn(MutableStateFlow(fakeMessages()) as Flow<List<MessageEntity>>)
        return vm
    }

    private fun settingsViewModel(): SettingsViewModel {
        val vm = Mockito.mock(SettingsViewModel::class.java)
        Mockito.`when`(vm.settings).thenReturn(
            MutableStateFlow(AppSettings(displayName = "Alex Rivera", onboardingComplete = true)),
        )
        Mockito.`when`(vm.publicKeyHex).thenReturn(
            MutableStateFlow("7d4c1f9b2e5a8c3d6f0b9e2a5c8d1f4b7e0a3c6d9f2b5e8a1c4d7f0b3e6a9c2d"),
        )
        Mockito.`when`(vm.profileQr).thenReturn(
            MutableStateFlow<android.graphics.Bitmap?>(null),
        )
        Mockito.`when`(vm.activeTempShares).thenReturn(MutableStateFlow(emptyList()))
        Mockito.`when`(vm.unlockState).thenReturn(MutableStateFlow(SupervisedModeManager.UnlockState.Idle))
        return vm
    }

    private fun fakePeers(): List<PeerEntity> = listOf(
        peer(MAYA_ID, "Maya Chen"),
        peer(SAM_ID, "Sam Okafor"),
        peer(PRIYA_ID, "Priya Raman", PeerEntity.ROLE_RECEIVE),
        peer(JONAS_ID, "Jonas Weber"),
        peer(DANA_ID, "Dana Whitfield", PeerEntity.ROLE_NONE),
    )

    private fun fakeHeartbeats(): Map<String, HeartbeatEntity> {
        val now = System.currentTimeMillis()
        // Distinct ages per contact so the list does not read as a copy-paste row.
        val minutesAgo = mapOf(MAYA_ID to 2L, SAM_ID to 7L, PRIYA_ID to 31L, JONAS_ID to 95L)
        return minutesAgo.mapValues { (id, minutes) ->
            HeartbeatEntity(
                deviceId = id,
                displayName = id,
                timestamp = now - minutes * 60_000,
                lat = 51.5074,
                lng = -0.1278,
                accuracy = 8f,
                battery = 76,
                motionState = "walking",
                receivedAt = now - minutes * 60_000,
                expectedIntervalSeconds = 300,
            )
        }
    }

    private fun fakeContactItems(): List<ContactItem> {
        val now = System.currentTimeMillis()
        val heartbeats = fakeHeartbeats()
        return listOf(
            ContactItem(
                peer = peer(MAYA_ID, "Maya Chen"),
                lastHeartbeat = heartbeats[MAYA_ID],
                config = PeerSharingConfig(peerDeviceId = MAYA_ID, isSosContact = true),
                updateState = ContactUpdateState.LAST_UPDATED,
                evaluatedAtMs = now,
            ),
            ContactItem(
                peer = peer(SAM_ID, "Sam Okafor"),
                lastHeartbeat = heartbeats[SAM_ID],
                config = PeerSharingConfig(peerDeviceId = SAM_ID),
                updateState = ContactUpdateState.LAST_UPDATED,
                evaluatedAtMs = now,
            ),
            ContactItem(
                peer = peer(PRIYA_ID, "Priya Raman", PeerEntity.ROLE_RECEIVE),
                lastHeartbeat = heartbeats[PRIYA_ID],
                config = PeerSharingConfig(peerDeviceId = PRIYA_ID, precisionMode = "SUBURB"),
                updateState = ContactUpdateState.LAST_UPDATED,
                evaluatedAtMs = now,
            ),
            ContactItem(
                peer = peer(JONAS_ID, "Jonas Weber"),
                lastHeartbeat = heartbeats.getValue(JONAS_ID),
                config = PeerSharingConfig(peerDeviceId = JONAS_ID, sharingEnabled = false),
                updateState = ContactUpdateState.STOPPED_SHARING,
                evaluatedAtMs = now,
            ),
            ContactItem(
                peer = peer(DANA_ID, "Dana Whitfield", PeerEntity.ROLE_NONE),
                lastHeartbeat = null,
                config = PeerSharingConfig(peerDeviceId = DANA_ID),
                updateState = ContactUpdateState.NO_UPDATE_YET,
                evaluatedAtMs = now,
            ),
        )
    }

    private fun fakeConversations(): List<ConversationSummary> {
        val peers = fakePeers().associateBy { it.deviceId }
        return listOf(
            ConversationSummary(
                peer = peers.getValue(MAYA_ID),
                lastMessage = message(
                    id = "m1", peerId = MAYA_ID, content = "Just parked — see you in five.",
                    mine = false, minutesAgo = 1,
                ),
            ),
            ConversationSummary(
                peer = peers.getValue(SAM_ID),
                lastMessage = message(
                    id = "m2", peerId = SAM_ID, content = "Perfect, thanks!", mine = true,
                    minutesAgo = 24,
                ),
            ),
            ConversationSummary(
                peer = peers.getValue(PRIYA_ID),
                lastMessage = message(
                    id = "m3", peerId = PRIYA_ID,
                    content = "https://www.openstreetmap.org/?mlat=51.5074&mlon=-0.1278",
                    mine = false, minutesAgo = 68,
                ),
            ),
            ConversationSummary(
                peer = peers.getValue(JONAS_ID),
                lastMessage = message(
                    id = "m4", peerId = JONAS_ID, content = "I've paused sharing for now — nothing wrong, just need some quiet.",
                    mine = false, minutesAgo = 190,
                ),
            ),
            ConversationSummary(
                peer = peers.getValue(DANA_ID),
                lastMessage = message(
                    id = "m5", peerId = DANA_ID, content = "Thanks for the help yesterday!",
                    mine = false, minutesAgo = 1450,
                ),
            ),
        )
    }

    private fun fakeCircles(): List<GroupConversationSummary> = listOf(
        GroupConversationSummary(
            circle = CircleEntity(id = "circle-weekend", name = "Weekend hike", creatorPubkey = SELF),
            lastMessage = message(
                id = "c1", peerId = "circle-weekend", content = "Trailhead at 9?",
                mine = false, minutesAgo = 12, groupId = "circle-weekend",
            ),
            memberCount = 4,
            unread = 1,
        ),
        GroupConversationSummary(
            circle = CircleEntity(id = "circle-family", name = "Family", creatorPubkey = SELF),
            lastMessage = message(
                id = "c2", peerId = "circle-family", content = "Everyone home safe.",
                mine = true, minutesAgo = 300, groupId = "circle-family",
            ),
            memberCount = 3,
            unread = 0,
        ),
    )

    private fun fakeMessages(): List<MessageEntity> {
        val pin = "https://www.openstreetmap.org/?mlat=51.5074&mlon=-0.1278#map=17/51.5074/-0.1278"
        return listOf(
            message("h1", MAYA_ID, "Morning! Are we still on for the market?", false, 96),
            message("h2", MAYA_ID, "Yes — leaving in ten minutes.", true, 92),
            message("h3", MAYA_ID, "I'll be by the flower stand.", false, 74),
            message("h4", MAYA_ID, "Here's where I am.", false, 12),
            message("h5", MAYA_ID, pin, false, 11),
            message("h6", MAYA_ID, "Got it, two minutes away.", true, 4),
        )
    }

    private fun message(
        id: String,
        peerId: String,
        content: String,
        mine: Boolean,
        minutesAgo: Long,
        groupId: String? = null,
    ): MessageEntity = MessageEntity(
        id = id,
        peerId = peerId,
        senderPublicKeyHex = if (mine) SELF else peerId,
        content = content,
        timestamp = System.currentTimeMillis() - minutesAgo * 60_000,
        isMine = mine,
        deliveryState = if (mine) DeliveryState.READ.name else DeliveryState.SENT.name,
        isRead = mine,
        nostrEventId = "",
        groupId = groupId,
    )

    private fun peer(id: String, name: String, role: String = PeerEntity.ROLE_SEND_RECEIVE) = PeerEntity(
        deviceId = id,
        displayName = name,
        publicKeyHex = id.padEnd(64, '0').take(64),
        relayUrl = "wss://relay.daygle.net",
        locationRole = role,
        messagingEnabled = true,
    )

    private companion object {
        const val SELF = "self"
        const val MAYA_ID = "maya"
        const val SAM_ID = "sam"
        const val PRIYA_ID = "priya"
        const val JONAS_ID = "jonas"
        const val DANA_ID = "dana"
    }
}
