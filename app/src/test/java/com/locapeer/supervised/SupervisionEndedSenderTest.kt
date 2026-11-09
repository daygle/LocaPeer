package com.locapeer.supervised

import com.locapeer.crypto.CryptoUtils
import com.locapeer.crypto.KeyManager
import com.locapeer.nostr.NostrEvent
import com.locapeer.nostr.NostrEventKind
import com.locapeer.nostr.NostrRelayClient
import com.locapeer.settings.AppPreferences
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.mockito.Mockito
import org.mockito.Mockito.mock
import org.mockito.stubbing.Answer
import java.util.concurrent.atomic.AtomicInteger

class SupervisionEndedSenderTest {
    private val peer = "d".repeat(64)
    private val relayUrl = "wss://relay.example"

    /** The persisted notice queue as the sender sees it. `@Volatile`: it writes from its own
     *  dispatcher and the test polls here. */
    private class Store(@Volatile var json: String? = null)

    private fun prefs(store: Store): AppPreferences = mock(AppPreferences::class.java, Answer { invocation ->
        when (invocation.method.name) {
            "getPendingSupervisionEndNoticesJson" -> store.json
            "setPendingSupervisionEndNoticesJson" -> {
                store.json = invocation.arguments[0] as String?
                Unit
            }
            else -> Mockito.RETURNS_DEFAULTS.answer(invocation)
        }
    })

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
        SupervisionEndedSender(prefs(store), keyManager(), crypto(), relay)

    @Test fun `a failed publish keeps the notice and a reconnect sends it`() = runBlocking {
        val store = Store()
        // Starts offline: the notice is recorded with no relay to send it to, and the single
        // false -> true edge at the end is the reconnect the sender must react to.
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
        // The publish threw, so the notice is still owed - and still on disk.
        assertEquals(peer, persisted(store).single().peerPubkey)
        assertEquals(0, published.get())

        failing = false
        status.value = mapOf(relayUrl to true)

        withTimeout(2_000) { while (published.get() == 0) delay(5) }
        withTimeout(2_000) { while (store.json != null) delay(5) }
    }

    @Test fun `a notice recorded before a crash is published at start and then cleared`() = runBlocking {
        val notice = SupervisionEndedSender.PendingEndNotice(
            peer, relayUrl, recordedAt = System.currentTimeMillis()
        )
        val store = Store(Json.encodeToString(listOf(notice)))
        val published = AtomicInteger()
        val kinds = java.util.Collections.synchronizedList(mutableListOf<Int>())
        val relay = relay(onPublish = {
            kinds.add(it.kind)
            published.incrementAndGet()
        })

        // Held in a val: the sender is the only owner of the scope that delivers it.
        sender(store, relay)

        withTimeout(2_000) { while (published.get() == 0) delay(5) }
        withTimeout(2_000) { while (store.json != null) delay(5) }
        assertNull(store.json)
        assertEquals(listOf(NostrEventKind.SUPERVISED_SUPERVISOR_END), kinds.toList())
    }

    @Test fun `ending supervision twice for the same peer keeps one notice`() = runBlocking {
        val store = Store()
        val relay = relay(onPublish = { throw IllegalStateException("relay down") })
        val sender = sender(store, relay)

        sender.send(peer, relayUrl)
        withTimeout(2_000) { while (store.json == null) delay(5) }
        delay(50)
        sender.send(peer, relayUrl)

        withTimeout(2_000) { while (persisted(store).size > 1 || store.json == null) delay(5) }
        assertEquals(1, persisted(store).size)
    }

    @Test fun `turning supervision back on withdraws an unsent ended notice`() = runBlocking {
        val store = Store()
        val published = AtomicInteger()
        // Still undelivered: with a reachable relay the notice would be published and cleared
        // before the test could withdraw it.
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

    private fun persisted(store: Store): List<SupervisionEndedSender.PendingEndNotice> =
        Json.decodeFromString(store.json!!)
}
