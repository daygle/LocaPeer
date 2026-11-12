package com.locapeer.supervised

import com.locapeer.crypto.CryptoUtils
import com.locapeer.crypto.KeyManager
import com.locapeer.data.dao.PeerDao
import com.locapeer.data.dao.PeerSharingConfigDao
import com.locapeer.nostr.NostrRelayClient
import com.locapeer.settings.AppPreferences
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.mockito.Mockito
import org.mockito.Mockito.mock
import org.mockito.Mockito.never
import org.mockito.Mockito.verify
import org.mockito.Mockito.`when`
import org.mockito.stubbing.Answer
import java.util.concurrent.atomic.AtomicInteger

class SupervisionResponseSenderTest {

    // Answer-based stub: `ensureKeypair` is suspend, so it cannot be stubbed with `when`
    // from a non-suspend helper.
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

    private fun sharingConfig(steps: MutableList<String>? = null): PeerSharingConfigDao =
        mock(PeerSharingConfigDao::class.java, Answer { invocation ->
            if (steps != null && invocation.method.name == "upsert") steps.add("config")
            Mockito.RETURNS_DEFAULTS.answer(invocation)
        })

    private fun peers(steps: MutableList<String>? = null): PeerDao =
        mock(PeerDao::class.java, Answer { invocation ->
            if (steps != null && invocation.method.name == "upsertPeer") steps.add("config")
            Mockito.RETURNS_DEFAULTS.answer(invocation)
        })

    private fun connected() = MutableStateFlow(mapOf("wss://relay.example" to true))

    /**
     * The sender observes [NostrRelayClient.relayStatus] to retry pending answers, so every
     * mocked relay must expose a status flow; a Mockito default of null would break the
     * observer rather than model an offline relay.
     */
    private fun fakeRelay(
        status: MutableStateFlow<Map<String, Boolean>> = MutableStateFlow(emptyMap()),
        publishError: Exception? = null,
        onPublish: () -> Unit = {},
    ): NostrRelayClient = mock(NostrRelayClient::class.java, Answer { invocation ->
        when (invocation.method.name) {
            "relayStatus", "getRelayStatus" -> status
            "publishEvent" -> {
                onPublish()
                publishError?.let { throw it }
            }
            else -> Mockito.RETURNS_DEFAULTS.answer(invocation)
        }
    })

    // Answer-based mock with an in-memory store: the accessors are suspend, so they cannot be
    // stubbed with `when` from a non-suspend helper, and the sender reads back what it wrote.
    private fun prefsRecording(steps: MutableList<String>, initial: String?): AppPreferences {
        var store = initial
        return mock(AppPreferences::class.java, Answer { invocation ->
            when (invocation.method.name) {
                "getPendingRegisterResponsesJson" -> store
                "setPendingRegisterResponsesJson" -> {
                    val json = invocation.arguments[0] as String?
                    val wasEmpty = store == null
                    store = json
                    steps.add(
                        when {
                            json == null -> "clear"
                            // The first write for a request is the record; a later one on a
                            // non-empty store is the accept's "configuration applied" mark.
                            wasEmpty -> "record"
                            else -> "mark"
                        }
                    )
                    Unit
                }
                else -> Mockito.RETURNS_DEFAULTS.answer(invocation)
            }
        })
    }

    @Test fun `an accept is recorded before the supervisor config and published after`() = runBlocking {
        val steps = java.util.Collections.synchronizedList(mutableListOf<String>())
        val prefs = prefsRecording(steps, initial = null)
        val relay = fakeRelay(onPublish = { steps.add("publish") })

        SupervisionResponseSender(
            prefs, keyManager(), crypto(), relay, sharingConfig(steps), peers(steps)
        ).accept("peer", "wss://relay.example", 123L)

        withTimeout(2_000) { while (steps.size < 5) delay(5) }
        // The record comes first: a crash anywhere after it is recoverable.
        assertEquals(listOf("record", "config", "mark", "publish", "clear"), steps.toList())
    }

    @Test fun `a decline is recorded with nothing left to configure`() = runBlocking {
        val steps = java.util.Collections.synchronizedList(mutableListOf<String>())
        val prefs = prefsRecording(steps, initial = null)
        val relay = fakeRelay(onPublish = { steps.add("publish") })

        SupervisionResponseSender(
            prefs, keyManager(), crypto(), relay, sharingConfig(), peers()
        ).decline("peer", "wss://relay.example", 123L)

        withTimeout(2_000) { while (steps.size < 3) delay(5) }
        assertEquals(listOf("record", "publish", "clear"), steps.toList())
    }

    @Test fun `an answer whose publish failed is kept for the next start`() = runBlocking {
        val pending = Json.encodeToString(
            listOf(
                SupervisionResponseSender.PendingResponse(
                    "peer", "wss://relay.example", false, 5L, configApplied = true
                )
            )
        )
        val prefs = mock(AppPreferences::class.java)
        `when`(prefs.getPendingRegisterResponsesJson()).thenReturn(pending)
        val attempts = AtomicInteger()
        val relay = fakeRelay(
            status = connected(),
            publishError = IllegalStateException("relay down"),
            onPublish = { attempts.incrementAndGet() },
        )

        SupervisionResponseSender(prefs, keyManager(), crypto(), relay, sharingConfig(), peers())

        withTimeout(2_000) { while (attempts.get() == 0) delay(5) }
        delay(100)
        // The record must survive a failed publish; otherwise the answer is lost for good.
        verify(prefs, never()).setPendingRegisterResponsesJson(null)
    }

    @Test fun `an unconfigured accept is finished at startup before the answer is sent`() = runBlocking {
        val steps = java.util.Collections.synchronizedList(mutableListOf<String>())
        val pending = Json.encodeToString(
            listOf(
                SupervisionResponseSender.PendingResponse(
                    "peer", "wss://relay.example", true, 7L, configApplied = false
                )
            )
        )
        val prefs = prefsRecording(steps, initial = pending)
        val relay = fakeRelay(status = connected(), onPublish = { steps.add("publish") })

        SupervisionResponseSender(
            prefs, keyManager(), crypto(), relay, sharingConfig(steps), peers(steps)
        )

        withTimeout(2_000) { while (steps.size < 5) delay(5) }
        // The leading "mark" is the stamp written for a record from an older build that had no
        // timestamp (see the expiry in load): it is persisted once, before the pass runs.
        assertEquals(listOf("mark", "config", "mark", "publish", "clear"), steps.toList())
    }

    @Test fun `a recorded answer is resent at startup and then cleared`() = runBlocking {
        val pending = Json.encodeToString(
            listOf(
                SupervisionResponseSender.PendingResponse(
                    "peer", "wss://relay.example", true, 9L, configApplied = true
                )
            )
        )
        val prefs = mock(AppPreferences::class.java)
        `when`(prefs.getPendingRegisterResponsesJson()).thenReturn(pending)
        val published = AtomicInteger()
        val relay = fakeRelay(status = connected(), onPublish = { published.incrementAndGet() })

        SupervisionResponseSender(prefs, keyManager(), crypto(), relay, sharingConfig(), peers())

        withTimeout(2_000) { while (published.get() == 0) delay(5) }
        withTimeout(2_000) {
            while (true) {
                try {
                    verify(prefs).setPendingRegisterResponsesJson(null)
                    break
                } catch (_: AssertionError) {
                    delay(5)
                }
            }
        }
        assertTrue(published.get() >= 1)
    }

    @Test fun `a pending answer is retried when the relay reconnects`() = runBlocking {
        val pending = Json.encodeToString(
            listOf(
                SupervisionResponseSender.PendingResponse(
                    "peer", "wss://relay.example", false, 11L, configApplied = true
                )
            )
        )
        val prefs = mock(AppPreferences::class.java)
        `when`(prefs.getPendingRegisterResponsesJson()).thenReturn(pending)
        val status = MutableStateFlow(mapOf("wss://relay.example" to false))
        val published = AtomicInteger()
        val relay = fakeRelay(status = status, onPublish = { published.incrementAndGet() })

        SupervisionResponseSender(prefs, keyManager(), crypto(), relay, sharingConfig(), peers())
        delay(150)
        // Offline at construction: the record stays put instead of being dropped.
        assertEquals(0, published.get())

        status.value = mapOf("wss://relay.example" to true)
        withTimeout(2_000) { while (published.get() == 0) delay(5) }
    }

    @Test fun `an answer past its expiry is dropped instead of retried forever`() = runBlocking {
        val expired = Json.encodeToString(
            listOf(
                SupervisionResponseSender.PendingResponse(
                    requesterPubkey = "peer",
                    requesterRelay = "wss://relay.example",
                    accepted = true,
                    requestCreatedAt = 9L,
                    configApplied = true,
                    recordedAt = System.currentTimeMillis() - 48 * 60 * 60_000L
                )
            )
        )
        val steps = java.util.Collections.synchronizedList(mutableListOf<String>())
        val prefs = prefsRecording(steps, initial = expired)
        val published = AtomicInteger()
        val relay = fakeRelay(status = connected(), onPublish = { published.incrementAndGet() })

        SupervisionResponseSender(prefs, keyManager(), crypto(), relay, sharingConfig(), peers())

        // The requesting device stopped waiting days ago, so the record is pruned, not sent.
        withTimeout(2_000) { while (steps.isEmpty()) delay(5) }
        assertEquals(listOf("clear"), steps.toList())
        delay(100)
        assertEquals(0, published.get())
    }
}
