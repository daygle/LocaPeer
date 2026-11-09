package com.locapeer.supervised

import com.locapeer.crypto.CryptoUtils
import com.locapeer.crypto.KeyManager
import com.locapeer.data.dao.PeerSharingConfigDao
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

class SupervisionApprovalManagerTest {
    private val supervised = "c".repeat(64)

    /** The persisted answer queue, as the manager sees it through [AppPreferences].
     *  `@Volatile`: the manager writes from its own dispatcher and the test polls here. */
    private class Store(@Volatile var json: String? = null)

    private fun prefs(store: Store): AppPreferences = mock(AppPreferences::class.java, Answer { invocation ->
        when (invocation.method.name) {
            "getPendingSupervisionAnswersJson" -> store.json                "setPendingSupervisionAnswersJson" -> {
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

    private fun configDao(steps: MutableList<String>? = null): PeerSharingConfigDao =
        mock(PeerSharingConfigDao::class.java, Answer { invocation ->
            if (steps != null && invocation.method.name == "clearSupervision") steps.add("clear")
            Mockito.RETURNS_DEFAULTS.answer(invocation)
        })

    private fun connected() = MutableStateFlow(mapOf("wss://relay.example" to true))

    private fun relay(
        status: MutableStateFlow<Map<String, Boolean>> = connected(),
        publishError: Exception? = null,
        onPublish: (NostrEvent) -> Unit = {},
    ): NostrRelayClient = mock(NostrRelayClient::class.java, Answer { invocation ->
        when (invocation.method.name) {
            "relayStatus", "getRelayStatus" -> status
            "publishEvent" -> {
                onPublish(invocation.arguments[0] as NostrEvent)
                publishError?.let { throw it }
            }
            else -> Mockito.RETURNS_DEFAULTS.answer(invocation)
        }
    })

    private fun unlockRequest(id: String) =
        SupervisionApprovalManager.PendingRequest(supervised, "Device", id)

    @Test fun `a failed publish keeps the answer and a reconnect sends it`() = runBlocking {
        val store = Store()
        val status = connected()
        var failing = true
        val published = AtomicInteger()
        val relay = relay(status = status, onPublish = {
            if (failing) throw IllegalStateException("relay down")
            published.incrementAndGet()
        })
        val manager = SupervisionApprovalManager(
            keyManager(), crypto(), relay, prefs(store), configDao()
        )

        val request = unlockRequest("req-1")
        manager.setPending(request)
        manager.respond(request, true)

        withTimeout(2_000) { while (store.json == null) delay(5) }
        delay(100)
        // The publish threw, so the decision is still owed - and still on disk.
        assertEquals("req-1", persisted(store).single().request.requestId)
        assertEquals(0, published.get())

        failing = false
        // StateFlow conflates: let the observer see the drop before the reconnect, or
        // distinctUntilChanged can collapse both assignments and never retry.
        status.value = mapOf("wss://relay.example" to false)
        delay(100)
        status.value = mapOf("wss://relay.example" to true)

        withTimeout(2_000) { while (published.get() == 0) delay(5) }
        withTimeout(2_000) { while (store.json != null) delay(5) }
    }

    @Test fun `a disable approval is published before the release is applied`() = runBlocking {
        val store = Store()
        val steps = java.util.Collections.synchronizedList(mutableListOf<String>())
        val manager = SupervisionApprovalManager(
            keyManager(), crypto(), relay(onPublish = { steps.add("publish") }), prefs(store),
            configDao(steps)
        )

        val request = SupervisionApprovalManager.PendingRequest(
            supervised, "Device", "req-2", SupervisionApprovalManager.RequestType.DISABLE
        )
        manager.setPending(request)
        manager.respond(request, true)

        withTimeout(2_000) { while (steps.size < 2) delay(5) }
        assertEquals(listOf("publish", "clear"), steps.toList())
        // The record is dropped after the release is applied, on the manager's dispatcher.
        withTimeout(2_000) { while (store.json != null) delay(5) }
        assertNull(store.json)
    }

    @Test fun `an answer recorded before a crash is republished at start`() = runBlocking {
        val answer = SupervisionApprovalManager.StoredAnswer(
            SupervisionApprovalManager.PendingRequest(
                supervised, "Device", "req-3", SupervisionApprovalManager.RequestType.DISABLE
            ),
            approved = true,
            recordedAt = System.currentTimeMillis(),
        )
        val store = Store(Json.encodeToString(listOf(answer)))
        val steps = java.util.Collections.synchronizedList(mutableListOf<String>())

        SupervisionApprovalManager(
            keyManager(), crypto(), relay(onPublish = { steps.add("publish") }), prefs(store),
            configDao(steps)
        )

        withTimeout(2_000) { while (steps.size < 2) delay(5) }
        assertEquals(listOf("publish", "clear"), steps.toList())
        withTimeout(2_000) { while (store.json != null) delay(5) }
    }

    private fun persisted(store: Store): List<SupervisionApprovalManager.StoredAnswer> =
        Json.decodeFromString(store.json!!)
}
