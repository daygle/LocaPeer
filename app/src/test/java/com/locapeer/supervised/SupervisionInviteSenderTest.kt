package com.locapeer.supervised

import com.locapeer.crypto.CryptoUtils
import com.locapeer.crypto.KeyManager
import com.locapeer.nostr.NostrEvent
import com.locapeer.nostr.NostrEventKind
import com.locapeer.nostr.NostrRelayClient
import com.locapeer.settings.AppPreferences
import com.locapeer.settings.AppSettings
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.mockito.Mockito
import org.mockito.Mockito.mock
import org.mockito.Mockito.`when`
import org.mockito.stubbing.Answer
import java.util.concurrent.atomic.AtomicInteger

class SupervisionInviteSenderTest {
    private val peer = "a".repeat(64)
    private val relayUrl = "wss://relay.example"

    /** The persisted invite queue as the sender sees it. `@Volatile`: it writes from its own
     *  dispatcher and the test polls here. */
    private class Store(@Volatile var json: String? = null)

    private fun prefs(store: Store): AppPreferences {
        val prefs = mock(AppPreferences::class.java, Answer { invocation ->
            when (invocation.method.name) {
                "getPendingSupervisionInvitesJson" -> store.json
                "setPendingSupervisionInvitesJson" -> {
                    store.json = invocation.arguments[0] as String?
                    Unit
                }
                else -> Mockito.RETURNS_DEFAULTS.answer(invocation)
            }
        })
        `when`(prefs.settings).thenReturn(flowOf(AppSettings(displayName = "Supervisor")))
        return prefs
    }

    private fun keyManager(): KeyManager = mock(KeyManager::class.java, Answer { invocation ->
        if (invocation.method.name == "ensureKeypair") "00" to "11"
        else Mockito.RETURNS_DEFAULTS.answer(invocation)
    })

    private fun crypto(): CryptoUtils = mock(CryptoUtils::class.java, Answer { invocation ->
        when (invocation.method.name) {
            "hexToBytes", "sha256", "schnorrSign" -> byteArrayOf(0)
            "nip44Encrypt" -> "encrypted"
            "bytesToHex" -> "event"
            else -> Mockito.RETURNS_DEFAULTS.answer(invocation)
        }
    })

    private fun connected() = MutableStateFlow(mapOf(relayUrl to true))

    private fun relay(
        status: MutableStateFlow<Map<String, Boolean>> = connected(),
        onPublish: (NostrEvent) -> Unit = {},
    ): NostrRelayClient = mock(NostrRelayClient::class.java, Answer { invocation ->
        when (invocation.method.name) {
            "relayStatus", "getRelayStatus" -> status
            // Answer for a void method: returning Unit makes Mockito reject the call.
            "publishEvent" -> {
                onPublish(invocation.arguments[0] as NostrEvent)
                null
            }
            else -> Mockito.RETURNS_DEFAULTS.answer(invocation)
        }
    })

    private fun sender(store: Store, relay: NostrRelayClient) =
        SupervisionInviteSender(prefs(store), keyManager(), crypto(), relay)

    @Test fun `an invite whose publish failed is kept and sent on reconnect`() = runBlocking {
        val store = Store()
        // Starts offline: the invite is recorded with no relay, and the single false -> true
        // edge at the end is the reconnect the sender must react to.
        val status = MutableStateFlow(mapOf(relayUrl to false))
        var failing = true
        val published = AtomicInteger()
        val relay = relay(status = status, onPublish = {
            if (failing) throw IllegalStateException("relay down")
            published.incrementAndGet()
        })
        val sender = sender(store, relay)

        sender.send(peer, relayUrl)

        withTimeout(2_000) { while (store.json == null) delay(5) }
        delay(100)
        assertEquals(peer, persisted(store).single().peerPubkey)
        assertEquals(0, published.get())

        failing = false
        status.value = mapOf(relayUrl to true)

        withTimeout(2_000) { while (published.get() == 0) delay(5) }
        withTimeout(2_000) { while (store.json != null) delay(5) }
    }

    @Test fun `an invite recorded before a crash is published at start and then cleared`() = runBlocking {
        val invite = SupervisionInviteSender.PendingInvite(
            peer, relayUrl, recordedAt = System.currentTimeMillis()
        )
        val store = Store(Json.encodeToString(listOf(invite)))
        val published = AtomicInteger()
        val kind = AtomicInteger(-1)
        val relay = relay(onPublish = {
            kind.set(it.kind)
            published.incrementAndGet()
        })

        // Held in a val: the sender is the only owner of the scope that delivers it.
        sender(store, relay)

        withTimeout(2_000) { while (published.get() == 0) delay(5) }
        withTimeout(2_000) { while (store.json != null) delay(5) }
        assertNull(store.json)
        assertEquals(NostrEventKind.SUPERVISED_INVITE, kind.get())
    }

    @Test fun `turning the switch back off withdraws an unsent invite`() = runBlocking {
        val store = Store()
        val published = AtomicInteger()
        // Still undelivered: with a reachable relay the invite would be published and cleared
        // before the test could withdraw it, so the publish has to fail for this case to exist.
        val sender = sender(
            store,
            relay(
                status = MutableStateFlow(mapOf(relayUrl to false)),
                onPublish = { throw IllegalStateException("relay down") },
            ),
        )

        sender.send(peer, relayUrl)
        withTimeout(2_000) { while (store.json == null) delay(5) }
        assertEquals(peer, persisted(store).single().peerPubkey)
        sender.cancel(peer)

        withTimeout(2_000) { while (store.json != null) delay(5) }
        delay(100)
        assertEquals(0, published.get())
    }

    @Test fun `a withdrawn invite is not sent by a later retry pass`() = runBlocking {
        val store = Store()
        val attempts = AtomicInteger()
        val status = MutableStateFlow(mapOf(relayUrl to false))
        var failing = true
        val sender = sender(
            store,
            relay(status = status, onPublish = {
                attempts.incrementAndGet()
                if (failing) throw IllegalStateException("relay down")
            }),
        )

        sender.send(peer, relayUrl)
        withTimeout(2_000) { while (store.json == null) delay(5) }
        sender.cancel(peer)
        withTimeout(2_000) { while (store.json != null) delay(5) }
        assertEquals(1, attempts.get())

        // The reconnect pass must not resurrect the invitation the user withdrew.
        failing = false
        status.value = mapOf(relayUrl to true)
        delay(250)
        assertEquals(1, attempts.get())
        assertNull(store.json)
    }

    private fun persisted(store: Store): List<SupervisionInviteSender.PendingInvite> =
        Json.decodeFromString(store.json!!)
}
