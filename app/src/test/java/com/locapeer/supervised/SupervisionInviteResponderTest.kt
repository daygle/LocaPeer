package com.locapeer.supervised

import com.locapeer.crypto.CryptoUtils
import com.locapeer.crypto.KeyManager
import com.locapeer.nostr.NostrEvent
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

class SupervisionInviteResponderTest {
    private val supervisor = "c".repeat(64)

    /** The persisted answer queue, as the responder sees it through [AppPreferences].
     *  `@Volatile`: the responder writes from its own dispatcher and the test polls here. */
    private class Store(@Volatile var json: String? = null)

    private fun prefs(store: Store): AppPreferences = mock(AppPreferences::class.java, Answer { invocation ->
        when (invocation.method.name) {
            "getPendingSupervisionInviteResponsesJson" -> store.json
            "setPendingSupervisionInviteResponsesJson" -> {
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

    private fun connected() = MutableStateFlow(mapOf("wss://relay.example" to true))

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

    private fun responder(
        store: Store,
        relay: NostrRelayClient,
    ) = SupervisionInviteResponder(keyManager(), crypto(), relay, prefs(store))

    @Test fun `a failed publish keeps the answer and a reconnect sends it`() = runBlocking {
        val store = Store()
        // Starts offline: the answer below is recorded with no relay to send it to, and the
        // single false -> true edge at the end is the reconnect the responder must react to.
        val status = MutableStateFlow(mapOf("wss://relay.example" to false))
        var failing = true
        val published = AtomicInteger()
        val relay = relay(status = status, onPublish = {
            if (failing) throw IllegalStateException("relay down")
            published.incrementAndGet()
        })
        val responder = responder(store, relay)

        responder.respond(supervisor, accepted = true)

        withTimeout(2_000) { while (store.json == null) delay(5) }
        delay(100)
        // The publish threw, so the decline is still owed - and still on disk.
        assertEquals(true, persisted(store).single().accepted)
        assertEquals(0, published.get())

        failing = false
        status.value = mapOf("wss://relay.example" to true)

        withTimeout(2_000) { while (published.get() == 0) delay(5) }
        withTimeout(2_000) { while (store.json != null) delay(5) }
    }

    @Test fun `an answer recorded before a crash is published at start`() = runBlocking {
        val response = SupervisionInviteResponder.PendingInviteResponse(
            supervisor, accepted = false, recordedAt = System.currentTimeMillis()
        )
        val store = Store(Json.encodeToString(listOf(response)))
        val published = AtomicInteger()

        // Held in a val: the responder is the only owner of the scope that delivers it.
        val responder = responder(store, relay(onPublish = { published.incrementAndGet() }))

        withTimeout(2_000) { while (published.get() == 0) delay(5) }
        withTimeout(2_000) { while (store.json != null) delay(5) }
        assertNull(store.json)
    }

    @Test fun `a newer decision replaces an undelivered one for the same supervisor`() = runBlocking {
        val store = Store()
        var failing = true
        val relay = relay(onPublish = {
            if (failing) throw IllegalStateException("relay down")
        })
        val responder = responder(store, relay)

        responder.respond(supervisor, accepted = false)
        withTimeout(2_000) { while (store.json == null) delay(5) }
        responder.respond(supervisor, accepted = true)

        val stored = persisted(store)
        assertEquals(1, stored.size)
        assertEquals(true, stored.single().accepted)
        failing = false
    }

    private fun persisted(store: Store): List<SupervisionInviteResponder.PendingInviteResponse> =
        Json.decodeFromString(store.json!!)
}
