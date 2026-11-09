package com.locapeer.invite

import android.content.Context
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

class TrackResponseSenderTest {
    private val peer = "e".repeat(64)
    private val relayUrl = "wss://relay.example"

    /** The persisted answer queue as the sender sees it. `@Volatile`: it writes from its own
     *  dispatcher and the test polls here. */
    private class Store(@Volatile var json: String? = null)

    private fun prefs(store: Store): AppPreferences {
        val prefs = mock(AppPreferences::class.java, Answer { invocation ->
            when (invocation.method.name) {
                "getPendingTrackResponsesJson" -> store.json
                "setPendingTrackResponsesJson" -> {
                    store.json = invocation.arguments[0] as String?
                    Unit
                }
                else -> Mockito.RETURNS_DEFAULTS.answer(invocation)
            }
        })
        `when`(prefs.settings).thenReturn(flowOf(AppSettings(displayName = "Me")))
        return prefs
    }

    private fun keyManager(): KeyManager = mock(KeyManager::class.java, Answer { invocation ->
        if (invocation.method.name == "ensureKeypair") "00" to "11"
        else Mockito.RETURNS_DEFAULTS.answer(invocation)
    })

    /** Returns the plaintext, so a published payload can be read back in tests. */
    private fun crypto(): CryptoUtils = mock(CryptoUtils::class.java, Answer { invocation ->
        when (invocation.method.name) {
            "hexToBytes", "sha256", "schnorrSign" -> byteArrayOf(0)
            "nip44Encrypt" -> invocation.arguments[2] as String
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
        TrackResponseSender(mock(Context::class.java), keyManager(), crypto(), relay, prefs(store))

    @Test fun `an accept whose publish failed is kept and sent on reconnect`() = runBlocking {
        val store = Store()
        // Starts offline: the accept is recorded with no relay to send it to, and the single
        // false -> true edge at the end is the reconnect the sender must react to.
        val status = MutableStateFlow(mapOf(relayUrl to false))
        var failing = true
        val published = AtomicInteger()
        val relay = relay(status = status, onPublish = {
            if (failing) throw IllegalStateException("relay down")
            published.incrementAndGet()
        })
        val sender = sender(store, relay)

        // Must not throw: a caller that treats a reply failure as fatal would drop the answer.
        sender.sendAccept(peer, relayUrl, "RECEIVE")

        withTimeout(2_000) { while (store.json == null) delay(5) }
        delay(100)
        assertEquals("RECEIVE", persisted(store).single().acceptedRole)
        assertEquals(0, published.get())

        failing = false
        status.value = mapOf(relayUrl to true)

        withTimeout(2_000) { while (published.get() == 0) delay(5) }
        withTimeout(2_000) { while (store.json != null) delay(5) }
    }

    @Test fun `a decline that could not be published is kept and sent on reconnect`() = runBlocking {
        val store = Store()
        val status = MutableStateFlow(mapOf(relayUrl to false))
        var failing = true
        val relay = relay(status = status, onPublish = {
            if (failing) {
                // Fail only this first attempt: the reconnect below must succeed.
                failing = false
                throw IllegalStateException("relay down")
            }
        })
        val sender = sender(store, relay)

        sender.sendDecline(peer, relayUrl, isRoleChange = true)

        withTimeout(2_000) { while (store.json == null) delay(5) }
        delay(100)
        // The decline is still owed, and it remembers it was a role change (the requester
        // keeps the contact in that case).
        val owed = persisted(store).single()
        assertNull(owed.acceptedRole)
        assertEquals(true, owed.isRoleChange)

        status.value = mapOf(relayUrl to true)

        withTimeout(2_000) { while (store.json != null) delay(5) }
    }

    @Test fun `a later decline replaces an unsent accept for the same peer`() = runBlocking {
        val store = Store()
        val relay = relay(
            status = MutableStateFlow(mapOf(relayUrl to false)),
            onPublish = { throw IllegalStateException("relay down") },
        )
        val sender = sender(store, relay)

        sender.sendAccept(peer, relayUrl, "RECEIVE")
        withTimeout(2_000) { while (store.json == null) delay(5) }
        sender.sendDecline(peer, relayUrl, isRoleChange = false)

        withTimeout(2_000) { while (persisted(store).single().acceptedRole != null) delay(5) }
        assertEquals(1, persisted(store).size)
    }

    @Test fun `an answer recorded before a crash is published at start and then cleared`() = runBlocking {
        val response = TrackResponseSender.PendingResponse(
            peer, relayUrl, "RECEIVE", recordedAt = System.currentTimeMillis()
        )
        val store = Store(Json.encodeToString(listOf(response)))
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
        assertEquals(NostrEventKind.TRACK_ACCEPT, kind.get())
    }

    @Test fun `an answer past its expiry is dropped instead of retried forever`() = runBlocking {
        val response = TrackResponseSender.PendingResponse(
            peer, relayUrl, null,
            recordedAt = System.currentTimeMillis() - 48 * 60 * 60_000L
        )
        val store = Store(Json.encodeToString(listOf(response)))
        val published = AtomicInteger()

        sender(store, relay(onPublish = { published.incrementAndGet() }))

        withTimeout(2_000) { while (store.json != null) delay(5) }
        delay(100)
        assertEquals(0, published.get())
    }

    private fun persisted(store: Store): List<TrackResponseSender.PendingResponse> =
        Json.decodeFromString(store.json!!)
}
