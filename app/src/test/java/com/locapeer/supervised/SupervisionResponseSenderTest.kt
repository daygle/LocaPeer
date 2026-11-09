package com.locapeer.supervised

import com.locapeer.crypto.CryptoUtils
import com.locapeer.crypto.KeyManager
import com.locapeer.data.dao.PeerDao
import com.locapeer.data.dao.PeerSharingConfigDao
import com.locapeer.nostr.NostrRelayClient
import com.locapeer.settings.AppPreferences
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.mockito.ArgumentMatchers.any
import org.mockito.Mockito
import org.mockito.Mockito.mock
import org.mockito.Mockito.never
import org.mockito.Mockito.verify
import org.mockito.Mockito.`when`
import org.mockito.stubbing.Answer

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
        val relay = mock(NostrRelayClient::class.java, Answer { invocation ->
            if (invocation.method.name == "publishEvent") steps.add("publish")
            Mockito.RETURNS_DEFAULTS.answer(invocation)
        })

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
        val relay = mock(NostrRelayClient::class.java, Answer { invocation ->
            if (invocation.method.name == "publishEvent") steps.add("publish")
            Mockito.RETURNS_DEFAULTS.answer(invocation)
        })

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
        var attempts = 0
        val relay = mock(NostrRelayClient::class.java, Answer { invocation ->
            if (invocation.method.name == "publishEvent") {
                attempts++
                throw IllegalStateException("relay down")
            }
            Mockito.RETURNS_DEFAULTS.answer(invocation)
        })

        SupervisionResponseSender(prefs, keyManager(), crypto(), relay, sharingConfig(), peers())

        withTimeout(2_000) { while (attempts == 0) delay(5) }
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
        val relay = mock(NostrRelayClient::class.java, Answer { invocation ->
            if (invocation.method.name == "publishEvent") steps.add("publish")
            Mockito.RETURNS_DEFAULTS.answer(invocation)
        })

        SupervisionResponseSender(
            prefs, keyManager(), crypto(), relay, sharingConfig(steps), peers(steps)
        )

        withTimeout(2_000) { while (steps.size < 4) delay(5) }
        assertEquals(listOf("config", "mark", "publish", "clear"), steps.toList())
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
        val published = java.util.concurrent.atomic.AtomicInteger()
        val relay = mock(NostrRelayClient::class.java, Answer { invocation ->
            if (invocation.method.name == "publishEvent") published.incrementAndGet()
            Mockito.RETURNS_DEFAULTS.answer(invocation)
        })

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
}
