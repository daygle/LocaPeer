package com.locapeer.supervised

import com.locapeer.crypto.CryptoUtils
import com.locapeer.crypto.KeyManager
import com.locapeer.nostr.NostrEvent
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

class SupervisionChangeReporterTest {
    private val supervisor = "b".repeat(64)

    /** The persisted buffer, as the reporter sees it through [AppPreferences].
     *  `@Volatile`: the reporter writes from its own dispatcher and the test polls here. */
    private class Store(@Volatile var json: String? = null)

    private fun prefs(
        store: Store,
        settings: AppSettings = AppSettings(supervisedModeEnabled = true, supervisorPubkey = supervisor),
    ): AppPreferences {
        val prefs = mock(AppPreferences::class.java, Answer { invocation ->
            when (invocation.method.name) {
                "getSupervisionChangeBufferJson" -> store.json
                "setSupervisionChangeBufferJson" -> {
                    store.json = invocation.arguments[0] as String?
                    Unit
                }
                else -> Mockito.RETURNS_DEFAULTS.answer(invocation)
            }
        })
        `when`(prefs.settings).thenReturn(flowOf(settings))
        return prefs
    }

    private fun keyManager(): KeyManager = mock(KeyManager::class.java, Answer { invocation ->
        if (invocation.method.name == "ensureKeypair") "00" to "11"
        else Mockito.RETURNS_DEFAULTS.answer(invocation)
    })

    /** Returns the plaintext, so the published summary can be read back in tests. */
    private fun crypto(): CryptoUtils = mock(CryptoUtils::class.java, Answer { invocation ->
        when (invocation.method.name) {
            "hexToBytes", "sha256", "schnorrSign" -> byteArrayOf(0)
            "nip44Encrypt" -> invocation.arguments[2] as String
            "bytesToHex" -> "event"
            else -> Mockito.RETURNS_DEFAULTS.answer(invocation)
        }
    })

    private fun relay(
        publishError: Exception? = null,
        onPublish: (NostrEvent) -> Unit = {},
        status: MutableStateFlow<Map<String, Boolean>> = MutableStateFlow(emptyMap()),
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

    @Test fun `a summary recorded under a previous supervisor is dropped, not sent to the new one`() = runBlocking {
        val oldSupervisor = "c".repeat(64)
        val entry = SettingsChangeEntry(
            area = SupervisionChangeReporter.Areas.ROLE, supervisorPubkey = oldSupervisor
        )
        val store = Store(Json.encodeToString(listOf(entry)))
        val published = AtomicInteger()

        SupervisionChangeReporter(
            keyManager(), crypto(), relay(onPublish = { published.incrementAndGet() }), prefs(store)
        )

        // Sent to whoever is supervisor NOW it would describe changes made under someone
        // else's supervision, so it is discarded with that relationship.
        withTimeout(2_000) { while (store.json != null) delay(5) }
        delay(100)
        assertEquals(0, published.get())
    }

    @Test fun `a queued summary is dropped once supervision has ended`() = runBlocking {
        val entry = SettingsChangeEntry(
            area = SupervisionChangeReporter.Areas.PAUSE, supervisorPubkey = supervisor
        )
        val store = Store(Json.encodeToString(listOf(entry)))
        val published = AtomicInteger()

        SupervisionChangeReporter(
            keyManager(), crypto(), relay(onPublish = { published.incrementAndGet() }),
            prefs(store, AppSettings(supervisedModeEnabled = false, supervisorPubkey = supervisor)),
        )

        withTimeout(2_000) { while (store.json != null) delay(5) }
        delay(100)
        assertEquals(0, published.get())
    }

    private fun entriesOf(event: NostrEvent): List<SettingsChangeEntry> =
        Json.decodeFromString<SettingsChangeSummaryPayload>(event.content).entries

    private fun storedEntries(store: Store): List<SettingsChangeEntry> =
        Json.decodeFromString(store.json!!)

    @Test fun `a summary interrupted by process death is published at start`() = runBlocking {
        val interrupted = SettingsChangeEntry(
            area = SupervisionChangeReporter.Areas.ROLE, peerName = "Bob"
        )
        val store = Store(Json.encodeToString(listOf(interrupted)))
        val published = mutableListOf<NostrEvent>()

        SupervisionChangeReporter(
            keyManager(), crypto(), relay(onPublish = { published.add(it) }), prefs(store)
        )

        withTimeout(2_000) { while (published.isEmpty()) delay(5) }
        assertEquals(listOf(interrupted), entriesOf(published.first()))
        // Only after the publish may the persisted copy go away.
        withTimeout(2_000) { while (store.json != null) delay(5) }
    }

    @Test fun `a reported change is persisted immediately and waits for the debounce`() = runBlocking {
        val store = Store()
        val published = AtomicInteger()
        val reporter = SupervisionChangeReporter(
            keyManager(), crypto(), relay(onPublish = { published.incrementAndGet() }), prefs(store)
        )
        // Let the (empty) restore flush settle before reporting.
        delay(100)

        reporter.report(SupervisionChangeReporter.Areas.PAUSE, "Bob")

        withTimeout(2_000) { while (store.json == null) delay(5) }
        val persisted = storedEntries(store)
        assertEquals(SupervisionChangeReporter.Areas.PAUSE, persisted.single().area)
        assertEquals("Bob", persisted.single().peerName)
        // The 10 s debounce has not elapsed: nothing may have been published yet.
        assertEquals(0, published.get())
    }

    @Test fun `a failed publish keeps the entries persisted for the next attempt`() = runBlocking {
        val entry = SettingsChangeEntry(area = SupervisionChangeReporter.Areas.PRECISION)
        val store = Store(Json.encodeToString(listOf(entry)))

        SupervisionChangeReporter(
            keyManager(), crypto(),
            relay(publishError = IllegalStateException("relay down")),
            prefs(store),
        )

        delay(400)
        // The publish threw, so the summary is still owed - and still on disk.
        assertEquals(listOf(entry), storedEntries(store))
    }

    @Test fun `a summary whose publish failed is flushed when a relay reconnects`() = runBlocking {
        val entry = SettingsChangeEntry(area = SupervisionChangeReporter.Areas.PRECISION)
        val store = Store(Json.encodeToString(listOf(entry)))
        // Offline at construction: the start flush throws, then the single false -> true edge
        // below is the reconnect the reporter must react to without another change.
        val status = MutableStateFlow(mapOf("wss://relay.example" to false))
        var failing = true
        val published = AtomicInteger()
        val reporter = SupervisionChangeReporter(
            keyManager(), crypto(),
            relay(
                status = status,
                onPublish = {
                    if (failing) throw IllegalStateException("relay down")
                    published.incrementAndGet()
                },
            ),
            prefs(store),
        )

        delay(200)
        assertEquals(0, published.get())
        assertEquals(listOf(entry), storedEntries(store))

        failing = false
        status.value = mapOf("wss://relay.example" to true)

        withTimeout(2_000) { while (published.get() == 0) delay(5) }
        withTimeout(2_000) { while (store.json != null) delay(5) }
    }

    @Test fun `an unreadable buffer is discarded instead of retried forever`() = runBlocking {
        val store = Store("not json")
        val published = AtomicInteger()

        SupervisionChangeReporter(
            keyManager(), crypto(), relay(onPublish = { published.incrementAndGet() }), prefs(store)
        )

        withTimeout(2_000) { while (store.json != null) delay(5) }
        assertEquals(0, published.get())
        assertNull(store.json)
    }
}
