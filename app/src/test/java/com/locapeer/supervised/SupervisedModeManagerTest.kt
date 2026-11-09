package com.locapeer.supervised

import com.locapeer.crypto.CryptoUtils
import com.locapeer.crypto.KeyManager
import com.locapeer.nostr.NostrRelayClient
import com.locapeer.settings.AppPreferences
import com.locapeer.settings.AppSettings
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Test
import org.mockito.Mockito
import org.mockito.Mockito.doAnswer
import org.mockito.Mockito.mock
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

    @Test fun `an unlock approval arriving while the request is publishing is not lost`() = runBlocking {
        lateinit var manager: SupervisedModeManager
        val relay = mock(NostrRelayClient::class.java, Answer { invocation ->
            if (invocation.method.name == "publishEvent") {
                (manager.unlockState.value as? SupervisedModeManager.UnlockState.Requesting)
                    ?.let { manager.handleResponse(it.requestId, true) }
            }
            Mockito.RETURNS_DEFAULTS.answer(invocation)
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
        val relay = mock(NostrRelayClient::class.java, Answer { invocation ->
            if (invocation.method.name == "publishEvent") published.incrementAndGet()
            Mockito.RETURNS_DEFAULTS.answer(invocation)
        })
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
        val relay = mock(NostrRelayClient::class.java, Answer { invocation ->
            if (invocation.method.name == "publishEvent") {
                (manager.disableState.value as? SupervisedModeManager.DisableState.Requesting)
                    ?.let { manager.handleDisableResponse(it.requestId, true) }
            }
            Mockito.RETURNS_DEFAULTS.answer(invocation)
        })
        manager = SupervisedModeManager(keyManager(), crypto(), relay, prefs)

        manager.requestDisable()

        withTimeout(2_000) { released.await() }
        assertEquals(SupervisedModeManager.DisableState.Idle, manager.disableState.value)
    }

    @Test fun `a pending request is cancelled locally without contacting the supervisor`() = runBlocking {
        val published = AtomicInteger()
        val relay = mock(NostrRelayClient::class.java, Answer { invocation ->
            if (invocation.method.name == "publishEvent") published.incrementAndGet()
            Mockito.RETURNS_DEFAULTS.answer(invocation)
        })
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
