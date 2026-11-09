package com.locapeer.supervised

import com.locapeer.crypto.CryptoUtils
import com.locapeer.crypto.KeyManager
import com.locapeer.data.dao.PeerSharingConfigDao
import com.locapeer.nostr.NostrRelayClient
import com.locapeer.invite.ContactAddExecutor
import com.locapeer.invite.InviteData
import com.locapeer.settings.AppPreferences
import com.locapeer.settings.AppSettings
import kotlinx.coroutines.flow.flowOf
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
        var decisions = 0
        manager.requestAdd("key", "Name", ContactAddApprovalManager.AddAction.OutboundScan(invite)) {
            assertTrue(it)
            decisions++
        }
        verify(executor).performOutboundAdd(invite)
        manager.requestAdd("peer", "Peer", ContactAddApprovalManager.AddAction.InboundAccept("peer", "RECEIVE", true)) {
            assertTrue(it)
            decisions++
        }
        verify(executor).completeInboundAccept("peer", "RECEIVE", true)
        assertEquals(2, decisions)
    }

    @Test fun `restored approval keeps remaining time from its original request time`() {
        assertEquals(60_000L, contactAddTimeoutDelayMs(100_000L, 100_000L))
        assertEquals(35_000L, contactAddTimeoutDelayMs(100_000L, 125_000L))
        assertEquals(0L, contactAddTimeoutDelayMs(100_000L, 160_000L))
    }
}
