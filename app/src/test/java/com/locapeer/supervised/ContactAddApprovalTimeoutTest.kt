package com.locapeer.supervised

import com.locapeer.crypto.CryptoUtils
import com.locapeer.crypto.KeyManager
import com.locapeer.data.dao.PeerSharingConfigDao
import com.locapeer.data.entity.PendingRequestEntity
import com.locapeer.nostr.NostrRelayClient
import com.locapeer.invite.ContactAddExecutor
import com.locapeer.invite.InviteData
import com.locapeer.settings.AppPreferences
import com.locapeer.settings.AppSettings
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.mockito.ArgumentMatchers.any
import org.mockito.Mockito.never
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.mockito.Mockito.mock
import org.mockito.Mockito.verify
import org.mockito.Mockito.verifyNoInteractions
import org.mockito.Mockito.`when`
import kotlinx.coroutines.runBlocking
import org.junit.Test

class ContactAddApprovalTimeoutTest {
    @Test fun `simultaneous approvals stay ordered and stale clicks cannot approve the next request`() {
        val manager = SupervisionApprovalManager(
            mock(KeyManager::class.java), mock(CryptoUtils::class.java), mock(NostrRelayClient::class.java),
            mock(PeerSharingConfigDao::class.java)
        )
        val first = SupervisionApprovalManager.PendingRequest("peer", "First", "1")
        val second = SupervisionApprovalManager.PendingRequest("peer", "Second", "2")
        manager.setPending(first)
        manager.setPending(second)
        manager.setPending(second) // retransmission is not a second dialog
        assertEquals(first, manager.pending.value)
        assertTrue(manager.claimPending(first))
        assertEquals(second, manager.pending.value)
        assertFalse(manager.claimPending(first))
        assertEquals(second, manager.pending.value)
        assertTrue(manager.claimPending(second))
        assertNull(manager.pending.value)
    }

    @Test fun `the disable answer is published before the supervisor clears its own flag`() = runBlocking {
        // Clearing first would leave a device gated after the supervisor released it, with
        // no request left for the supervisor to answer.
        val calls = java.util.Collections.synchronizedList(mutableListOf<String>())
        val dao = mock(PeerSharingConfigDao::class.java, org.mockito.stubbing.Answer { invocation ->
            if (invocation.method.name == "clearSupervision") calls.add("clear")
            org.mockito.Mockito.RETURNS_DEFAULTS.answer(invocation)
        })
        val keyManager = mock(KeyManager::class.java)
        `when`(keyManager.ensureKeypair()).thenReturn("00" to "11")
        val crypto = mock(CryptoUtils::class.java, org.mockito.stubbing.Answer { invocation ->
            when (invocation.method.name) {
                "hexToBytes", "sha256", "schnorrSign" -> byteArrayOf(0)
                "nip44Encrypt" -> "encrypted"
                "bytesToHex" -> "event"
                else -> org.mockito.Mockito.RETURNS_DEFAULTS.answer(invocation)
            }
        })
        val relay = mock(NostrRelayClient::class.java, org.mockito.stubbing.Answer { invocation ->
            if (invocation.method.name == "publishEvent") calls.add("publish")
            org.mockito.Mockito.RETURNS_DEFAULTS.answer(invocation)
        })
        val manager = SupervisionApprovalManager(keyManager, crypto, relay, dao)
        val disable = SupervisionApprovalManager.PendingRequest(
            "peer", "Device", "1", SupervisionApprovalManager.RequestType.DISABLE
        )
        manager.setPending(disable)
        manager.respond(disable, true)
        withTimeout(5_000) {
            while (calls.size < 2) kotlinx.coroutines.delay(10)
        }
        assertEquals(listOf("publish", "clear"), calls.toList())
    }

    @Test fun `only an approved disable clears supervision on the supervisor`() = runBlocking {
        val dao = mock(PeerSharingConfigDao::class.java)
        val manager = SupervisionApprovalManager(
            mock(KeyManager::class.java), mock(CryptoUtils::class.java), mock(NostrRelayClient::class.java), dao
        )
        val disable = SupervisionApprovalManager.PendingRequest(
            "peer", "Device", "1", SupervisionApprovalManager.RequestType.DISABLE
        )
        manager.applyApprovedRelease(disable, false)
        manager.applyApprovedRelease(disable.copy(type = SupervisionApprovalManager.RequestType.UNLOCK), true)
        verifyNoInteractions(dao)
        manager.applyApprovedRelease(disable, true)
        verify(dao).clearSupervision("peer")
    }

    @Test fun `contact adds still execute when supervision ends before the manager checks`() = runBlocking {
        val prefs = mock(AppPreferences::class.java)
        `when`(prefs.settings).thenReturn(flowOf(AppSettings()))
        val executor = mock(ContactAddExecutor::class.java)
        val manager = ContactAddApprovalManager(
            mock(KeyManager::class.java), mock(CryptoUtils::class.java), mock(NostrRelayClient::class.java),
            prefs, executor
        )
        val invite = InviteData("key", "Name", "relay", "key")
        val request = PendingRequestEntity("peer", "Peer", "relay")
        `when`(executor.performOutboundAdd(invite)).thenReturn(true)
        `when`(executor.completeInboundAccept(request, "RECEIVE", true)).thenReturn(true)
        var decisions = 0
        manager.requestAdd("key", "Name", ContactAddApprovalManager.AddAction.OutboundScan(invite)) {
            assertEquals(ContactAddApprovalManager.AddResult.COMPLETED, it)
            decisions++
        }
        verify(executor).performOutboundAdd(invite)
        manager.requestAdd("peer", "Peer", ContactAddApprovalManager.AddAction.InboundAccept(request, "RECEIVE", true)) {
            assertEquals(ContactAddApprovalManager.AddResult.COMPLETED, it)
            decisions++
        }
        verify(executor).completeInboundAccept(request, "RECEIVE", true)
        assertEquals(2, decisions)
    }

    @Test fun `failed direct execution does not report completion`() = runBlocking {
        val prefs = mock(AppPreferences::class.java)
        `when`(prefs.settings).thenReturn(flowOf(AppSettings()))
        val executor = mock(ContactAddExecutor::class.java)
        val manager = ContactAddApprovalManager(
            mock(KeyManager::class.java), mock(CryptoUtils::class.java), mock(NostrRelayClient::class.java),
            prefs, executor
        )
        val request = PendingRequestEntity("peer", "Peer", "relay")
        `when`(executor.completeInboundAccept(request, "RECEIVE", true)).thenReturn(false)
        var result: ContactAddApprovalManager.AddResult? = null
        manager.requestAdd("peer", "Peer", ContactAddApprovalManager.AddAction.InboundAccept(request, "RECEIVE", true)) {
            result = it
        }
        assertEquals(ContactAddApprovalManager.AddResult.FAILED, result)
    }

    @Test fun `cancel waits for persisted approvals to load`() = runBlocking {
        val prefs = mock(AppPreferences::class.java)
        val started = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        val now = System.currentTimeMillis()
        `when`(prefs.getContactAddIntentsJson()).thenAnswer {
            runBlocking {
                started.complete(Unit)
                release.await()
            }
            """[{"requestId":"saved","contactPubkeyHex":"peer","contactName":"Peer","requestedAt":$now}]"""
        }
        val manager = ContactAddApprovalManager(
            mock(KeyManager::class.java), mock(CryptoUtils::class.java), mock(NostrRelayClient::class.java),
            prefs, mock(ContactAddExecutor::class.java)
        )
        withTimeout(2_000) { started.await() }
        val cancel = async { manager.cancelPending("peer") }
        kotlinx.coroutines.yield()
        assertFalse(cancel.isCompleted)
        release.complete(Unit)
        withTimeout(2_000) { cancel.await() }
        verify(prefs).setContactAddIntentsJson(null)
        assertTrue(manager.pending.value.isEmpty())
    }

    @Test fun `approval waits for saved request and preserves reviewed inbound details`() = runBlocking {
        val prefs = mock(AppPreferences::class.java)
        val started = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        val now = System.currentTimeMillis()
        `when`(prefs.getContactAddIntentsJson()).thenAnswer {
            runBlocking {
                started.complete(Unit)
                release.await()
            }
            """[{"requestId":"saved","contactPubkeyHex":"peer","contactName":"Peer","accept":{"senderPubkey":"peer","locationRole":"SEND","messagingEnabled":true,"request":{"senderPubkey":"peer","senderName":"Peer","senderRelayUrl":"relay","receivedAt":123}},"requestedAt":$now}]"""
        }
        val executor = mock(ContactAddExecutor::class.java)
        val manager = ContactAddApprovalManager(
            mock(KeyManager::class.java), mock(CryptoUtils::class.java), mock(NostrRelayClient::class.java),
            prefs, executor
        )
        withTimeout(2_000) { started.await() }
        manager.handleResponse("saved", true)
        release.complete(Unit)
        withTimeout(5_000) {
            while (true) {
                try {
                    verify(executor).completeInboundAccept(
                        PendingRequestEntity("peer", "Peer", "relay", receivedAt = 123), "SEND", true, "saved"
                    )
                    break
                } catch (e: AssertionError) {
                    kotlinx.coroutines.delay(10)
                }
            }
        }
    }

    @Test fun `approval callback reports completion only after the add finishes`() = runBlocking {
        val prefs = mock(AppPreferences::class.java)
        `when`(prefs.settings).thenReturn(flowOf(AppSettings(
            supervisedModeEnabled = true, supervisorPubkey = "supervisor"
        )))
        val keyManager = mock(KeyManager::class.java)
        `when`(keyManager.ensureKeypair()).thenReturn("00" to "11")
        val crypto = mock(CryptoUtils::class.java, org.mockito.stubbing.Answer { invocation ->
            when (invocation.method.name) {
                "hexToBytes", "sha256", "schnorrSign" -> byteArrayOf(0)
                "nip44Encrypt" -> "encrypted"
                "bytesToHex" -> "event"
                else -> org.mockito.Mockito.RETURNS_DEFAULTS.answer(invocation)
            }
        })
        val executor = mock(ContactAddExecutor::class.java)
        val invite = InviteData("key", "Name", "relay", "key")
        val started = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        val manager = ContactAddApprovalManager(keyManager, crypto, mock(NostrRelayClient::class.java), prefs, executor)
        val result = CompletableDeferred<ContactAddApprovalManager.AddResult>()
        manager.requestAdd("key", "Name", ContactAddApprovalManager.AddAction.OutboundScan(invite)) {
            result.complete(it)
        }
        val requestId = manager.pending.value.single().requestId
        `when`(executor.performOutboundAdd(invite, requestId)).thenAnswer {
            runBlocking {
                started.complete(Unit)
                release.await()
            }
            true
        }
        manager.handleResponse(requestId, true)
        withTimeout(2_000) { started.await() }
        assertFalse(result.isCompleted)
        release.complete(Unit)
        assertEquals(ContactAddApprovalManager.AddResult.COMPLETED, withTimeout(5_000) { result.await() })
    }

    @Test fun `duplicate responses cannot remove an add while execution is running`() = runBlocking {
        val prefs = mock(AppPreferences::class.java)
        val now = System.currentTimeMillis()
        `when`(prefs.getContactAddIntentsJson()).thenReturn(
            """[{"requestId":"saved","contactPubkeyHex":"peer","contactName":"Peer","accept":{"senderPubkey":"peer","locationRole":"SEND","messagingEnabled":true,"request":{"senderPubkey":"peer","senderName":"Peer","senderRelayUrl":"relay","receivedAt":123}},"requestedAt":$now}]"""
        )
        val executor = mock(ContactAddExecutor::class.java)
        val request = PendingRequestEntity("peer", "Peer", "relay", receivedAt = 123)
        val started = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        `when`(executor.completeInboundAccept(request, "SEND", true, "saved")).thenAnswer {
            runBlocking {
                started.complete(Unit)
                release.await()
            }
            true
        }
        val manager = ContactAddApprovalManager(
            mock(KeyManager::class.java), mock(CryptoUtils::class.java), mock(NostrRelayClient::class.java),
            prefs, executor
        )
        manager.handleResponse("saved", true)
        withTimeout(2_000) { started.await() }
        manager.handleResponse("saved", true)
        manager.handleResponse("saved", false)
        manager.cancelPending("peer")
        assertEquals(1, manager.pending.value.size)
        verify(prefs, org.mockito.Mockito.never()).setContactAddIntentsJson(null)
        release.complete(Unit)
        withTimeout(5_000) {
            while (manager.pending.value.isNotEmpty()) kotlinx.coroutines.delay(10)
        }
        verify(executor, org.mockito.Mockito.times(1)).completeInboundAccept(request, "SEND", true, "saved")
        verify(prefs).setContactAddIntentsJson(null)
    }

    @Test fun `restored approval keeps remaining time from its original request time`() {
        assertEquals(CONTACT_ADD_TIMEOUT_MS, contactAddTimeoutDelayMs(100_000L, 100_000L))
        assertEquals(CONTACT_ADD_TIMEOUT_MS - 25_000L, contactAddTimeoutDelayMs(100_000L, 125_000L))
        assertEquals(0L, contactAddTimeoutDelayMs(100_000L, 100_000L + CONTACT_ADD_TIMEOUT_MS + 1))
    }

    @Test fun `queued add outlives the answer window the receiver accepts`() {
        // HeartbeatReceiver drops contact-add answers older than 300 s; the intent must still
        // exist when such an answer arrives, or an approval is silently lost.
        assertTrue(CONTACT_ADD_TIMEOUT_MS > 300_000L)
    }

    @Test fun `approval is written to disk before the physical add starts`() = runBlocking {
        val prefs = mock(AppPreferences::class.java)
        `when`(prefs.settings).thenReturn(flowOf(AppSettings(
            supervisedModeEnabled = true, supervisorPubkey = "supervisor"
        )))
        val keyManager = mock(KeyManager::class.java)
        `when`(keyManager.ensureKeypair()).thenReturn("00" to "11")
        val crypto = mock(CryptoUtils::class.java, org.mockito.stubbing.Answer { invocation ->
            when (invocation.method.name) {
                "hexToBytes", "sha256", "schnorrSign" -> byteArrayOf(0)
                "nip44Encrypt" -> "encrypted"
                "bytesToHex" -> "event"
                else -> org.mockito.Mockito.RETURNS_DEFAULTS.answer(invocation)
            }
        })
        val persisted = java.util.Collections.synchronizedList(mutableListOf<String?>())
        `when`(prefs.setContactAddIntentsJson(any())).thenAnswer { invocation ->
            persisted.add(invocation.arguments[0] as String?)
            Unit
        }
        val invite = InviteData("key", "Name", "relay", "key")
        var approvalOnDiskBeforeAdd = false
        val executor = mock(ContactAddExecutor::class.java)
        val manager = ContactAddApprovalManager(
            keyManager, crypto, mock(NostrRelayClient::class.java), prefs, executor
        )
        manager.requestAdd("key", "Name", ContactAddApprovalManager.AddAction.OutboundScan(invite)) { }
        val requestId = manager.pending.value.single().requestId
        `when`(executor.performOutboundAdd(invite, requestId)).thenAnswer {
            // If the process died right here, startup must find the approval on disk.
            approvalOnDiskBeforeAdd = persisted.lastOrNull()?.contains("\"approvedAt\"") == true
            true
        }
        manager.handleResponse(requestId, true)
        withTimeout(5_000) {
            while (!approvalOnDiskBeforeAdd) kotlinx.coroutines.delay(10)
        }
        assertTrue(approvalOnDiskBeforeAdd)
    }

    @Test fun `an approved add resumes after a process death`() = runBlocking {
        val prefs = mock(AppPreferences::class.java)
        val now = System.currentTimeMillis()
        val invite = InviteData("key", "Name", "relay", "key")
        `when`(prefs.getContactAddIntentsJson()).thenReturn(
            Json.encodeToString(
                listOf(
                    ContactAddApprovalManager.StoredContactAddIntent(
                        requestId = "a1",
                        contactPubkeyHex = "key",
                        contactName = "Name",
                        scan = invite,
                        requestedAt = now,
                        approvedAt = now
                    )
                )
            )
        )
        val executor = mock(ContactAddExecutor::class.java)
        `when`(executor.performOutboundAdd(invite, "a1")).thenReturn(true)
        ContactAddApprovalManager(
            mock(KeyManager::class.java), mock(CryptoUtils::class.java),
            mock(NostrRelayClient::class.java), prefs, executor
        )
        withTimeout(5_000) {
            while (true) {
                try {
                    verify(executor).performOutboundAdd(invite, "a1")
                    break
                } catch (_: AssertionError) {
                    kotlinx.coroutines.delay(10)
                }
            }
        }
        // The recovered intent is dropped once the add completes.
        verify(prefs).setContactAddIntentsJson(null)
    }

    @Test fun `an approved add older than the recovery window is not resumed`() = runBlocking {
        val prefs = mock(AppPreferences::class.java)
        val now = System.currentTimeMillis()
        val old = now - APPROVED_RECOVERY_WINDOW_MS - 1
        `when`(prefs.getContactAddIntentsJson()).thenReturn(
            Json.encodeToString(
                listOf(
                    ContactAddApprovalManager.StoredContactAddIntent(
                        requestId = "a1",
                        contactPubkeyHex = "key",
                        contactName = "Name",
                        scan = InviteData("key", "Name", "relay", "key"),
                        requestedAt = old,
                        approvedAt = old
                    )
                )
            )
        )
        val executor = mock(ContactAddExecutor::class.java)
        val manager = ContactAddApprovalManager(
            mock(KeyManager::class.java), mock(CryptoUtils::class.java),
            mock(NostrRelayClient::class.java), prefs, executor
        )
        withTimeout(5_000) {
            while (true) {
                try {
                    // Pruning the stale intent persists the empty queue.
                    verify(prefs).setContactAddIntentsJson(null)
                    break
                } catch (_: AssertionError) {
                    kotlinx.coroutines.delay(10)
                }
            }
        }
        verify(executor, never()).performOutboundAdd(InviteData("key", "Name", "relay", "key"))
        assertTrue(manager.pending.value.isEmpty())
    }
}
