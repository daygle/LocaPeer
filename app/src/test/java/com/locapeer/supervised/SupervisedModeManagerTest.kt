package com.locapeer.supervised

import com.locapeer.crypto.CryptoUtils
import com.locapeer.crypto.KeyManager
import com.locapeer.nostr.NostrRelayClient
import com.locapeer.settings.AppPreferences
import com.locapeer.settings.AppSettings
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Test
import org.mockito.Mockito
import org.mockito.Mockito.doAnswer
import org.mockito.Mockito.mock
import org.mockito.Mockito.verify
import org.mockito.Mockito.`when`
import org.mockito.stubbing.Answer
import java.util.concurrent.atomic.AtomicInteger

class SupervisedModeManagerTest {
    private val supervisor = "a".repeat(64)

    private fun crypto(): CryptoUtils = mock(CryptoUtils::class.java, Answer { invocation ->
        when (invocation.method.name) {
            "hexToBytes", "sha256", "schnorrSign" -> byteArrayOf(0)
            "nip44Encrypt" -> "encrypted"
            "bytesToHex" -> "event"
            else -> Mockito.RETURNS_DEFAULTS.answer(invocation)
        }
    })

    private fun activePrefs(): AppPreferences = mock(AppPreferences::class.java).also {
        `when`(it.settings).thenReturn(
            flowOf(AppSettings(supervisedModeEnabled = true, supervisorPubkey = supervisor))
        )
    }

    private suspend fun keyManager(): KeyManager = mock(KeyManager::class.java).also {
        `when`(it.ensureKeypair()).thenReturn("00" to "11")
    }

    private fun connected() = MutableStateFlow(mapOf("wss://relay.example" to true))

    /**
     * The manager observes [NostrRelayClient.relayStatus] to retry pending registration work,
     * so every mocked relay must expose a status flow - a Mockito default of null would break
     * the observer instead of modelling an offline relay.
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

    @Test fun `an unlock approval arriving while the request is publishing is not lost`() = runBlocking {
        lateinit var manager: SupervisedModeManager
        val relay = fakeRelay(onPublish = {
            (manager.unlockState.value as? SupervisedModeManager.UnlockState.Requesting)
                ?.let { manager.handleResponse(it.requestId, true) }
        })
        manager = SupervisedModeManager(keyManager(), crypto(), relay, activePrefs())

        manager.requestAccess()

        withTimeout(2_000) {
            while (manager.unlockState.value !is SupervisedModeManager.UnlockState.Approved) delay(5)
        }
        assertEquals(SupervisedModeManager.UnlockState.Approved, manager.unlockState.value)
    }

    @Test fun `repeated unlock requests while one is pending publish only once`() = runBlocking {
        val published = AtomicInteger()
        val relay = fakeRelay(onPublish = { published.incrementAndGet() })
        val manager = SupervisedModeManager(keyManager(), crypto(), relay, activePrefs())

        manager.requestAccess()
        manager.requestAccess()

        withTimeout(2_000) { while (published.get() == 0) delay(5) }
        delay(100)
        assertEquals(1, published.get())
    }

    @Test fun `a disable approval arriving while the request is publishing releases supervision`() = runBlocking {
        val prefs = activePrefs()
        val released = CompletableDeferred<Unit>()
        doAnswer { _ -> released.complete(Unit); null }.`when`(prefs).clearSupervisedMode()
        lateinit var manager: SupervisedModeManager
        val relay = fakeRelay(onPublish = {
            (manager.disableState.value as? SupervisedModeManager.DisableState.Requesting)
                ?.let { manager.handleDisableResponse(it.requestId, true) }
        })
        manager = SupervisedModeManager(keyManager(), crypto(), relay, prefs)

        manager.requestDisable()

        withTimeout(2_000) { released.await() }
        assertEquals(SupervisedModeManager.DisableState.Idle, manager.disableState.value)
    }

    @Test fun `a failed register publish is kept for the next start`() = runBlocking {
        val prefs = mock(AppPreferences::class.java)
        `when`(prefs.settings).thenReturn(flowOf(AppSettings(
            supervisedModeEnabled = true,
            supervisorPubkey = "sup",
            supervisionPending = true,
            supervisionRequestedAt = 123L,
            supervisionRequestSent = false,
        )))
        val attempts = AtomicInteger()
        val relay = fakeRelay(
            status = connected(),
            // A relay failure must not escape the background scope and crash the process.
            publishError = IllegalStateException("relay down"),
            onPublish = { attempts.incrementAndGet() },
        )

        SupervisedModeManager(keyManager(), crypto(), relay, prefs)

        withTimeout(2_000) { while (attempts.get() == 0) delay(5) }
        delay(100)
        verify(prefs, org.mockito.Mockito.never()).markSupervisionRequestSent()
    }

    @Test fun `an unpublished register request is resent at startup`() = runBlocking {
        val prefs = mock(AppPreferences::class.java)
        `when`(prefs.settings).thenReturn(flowOf(AppSettings(
            supervisedModeEnabled = true,
            supervisorPubkey = "sup",
            supervisionPending = true,
            supervisionRequestedAt = 123L,
            supervisionRequestSent = false,
        )))
        val sent = CompletableDeferred<Unit>()
        val relay = fakeRelay(status = connected(), onPublish = { sent.complete(Unit) })

        SupervisedModeManager(keyManager(), crypto(), relay, prefs)

        withTimeout(2_000) { sent.await() }
        withTimeout(2_000) {
            while (true) {
                try {
                    verify(prefs).markSupervisionRequestSent()
                    break
                } catch (_: AssertionError) {
                    delay(5)
                }
            }
        }
    }

    @Test fun `an unsent register request is retried when the relay reconnects`() = runBlocking {
        val prefs = mock(AppPreferences::class.java)
        `when`(prefs.settings).thenReturn(flowOf(AppSettings(
            supervisedModeEnabled = true,
            supervisorPubkey = "sup",
            supervisionPending = true,
            supervisionRequestedAt = 123L,
            supervisionRequestSent = false,
        )))
        val status = MutableStateFlow(mapOf("wss://relay.example" to false))
        val attempts = AtomicInteger()
        val relay = fakeRelay(status = status, onPublish = { attempts.incrementAndGet() })

        SupervisedModeManager(keyManager(), crypto(), relay, prefs)
        delay(150)
        assertEquals(0, attempts.get())

        status.value = mapOf("wss://relay.example" to true)
        withTimeout(2_000) { while (attempts.get() == 0) delay(5) }
    }

    @Test fun `an approved disable persists the release before clearing the flags`() = runBlocking {
        // A crash between the two writes must leave the marker behind, not a still-supervised
        // device that the supervisor believes it released.
        val prefs = activePrefs()
        val steps = java.util.Collections.synchronizedList(mutableListOf<String>())
        doAnswer { _ -> steps.add("mark"); null }.`when`(prefs).markSupervisionReleaseApproved()
        doAnswer { _ -> steps.add("clear"); null }.`when`(prefs).clearSupervisedMode()
        lateinit var manager: SupervisedModeManager
        val relay = fakeRelay(onPublish = {
            (manager.disableState.value as? SupervisedModeManager.DisableState.Requesting)
                ?.let { manager.handleDisableResponse(it.requestId, true) }
        })
        manager = SupervisedModeManager(keyManager(), crypto(), relay, prefs)

        manager.requestDisable()

        withTimeout(2_000) { while (steps.size < 2) delay(5) }
        assertEquals(listOf("mark", "clear"), steps.toList())
    }

    @Test fun `a pending request is cancelled locally without contacting the supervisor`() = runBlocking {
        val published = AtomicInteger()
        val relay = fakeRelay(onPublish = { published.incrementAndGet() })
        val prefs = mock(AppPreferences::class.java).also {
            // Not supervised: a disable request must not be sent at all.
            `when`(it.settings).thenReturn(flowOf(AppSettings()))
        }
        val manager = SupervisedModeManager(keyManager(), crypto(), relay, prefs)

        manager.requestDisable()

        withTimeout(2_000) {
            while (manager.disableState.value !is SupervisedModeManager.DisableState.Idle) delay(5)
        }
        assertEquals(0, published.get())
    }
}
