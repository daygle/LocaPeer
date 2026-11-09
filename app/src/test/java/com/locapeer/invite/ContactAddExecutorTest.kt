package com.locapeer.invite

import android.app.NotificationManager
import com.locapeer.crypto.CryptoUtils
import com.locapeer.crypto.KeyManager
import com.locapeer.data.AppDatabase
import com.locapeer.data.dao.ContactAddReceiptDao
import com.locapeer.data.dao.PeerDao
import com.locapeer.data.entity.ContactAddReceiptEntity
import com.locapeer.data.entity.PendingRequestEntity
import com.locapeer.nostr.NostrRelayClient
import com.locapeer.settings.AppPreferences
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.mockito.Mockito
import org.mockito.Mockito.mock
import org.mockito.Mockito.never
import org.mockito.Mockito.verify
import org.mockito.Mockito.`when`
import org.mockito.stubbing.Answer

class ContactAddExecutorTest {
    private val request = PendingRequestEntity("peer", "Peer", "wss://relay.example")

    private fun executor(
        receiptDao: ContactAddReceiptDao,
        sender: TrackResponseSender,
        keyManager: KeyManager = mock(KeyManager::class.java),
        peerDao: PeerDao = mock(PeerDao::class.java),
    ): ContactAddExecutor {
        val database = mock(AppDatabase::class.java)
        `when`(database.contactAddReceiptDao()).thenReturn(receiptDao)
        return ContactAddExecutor(
            database,
            peerDao,
            sender,
            mock(NotificationManager::class.java),
            keyManager,
            mock(CryptoUtils::class.java),
            mock(NostrRelayClient::class.java),
            mock(AppPreferences::class.java),
        )
    }

    /** The accept already committed in a previous attempt; only the reply is outstanding. */
    private fun receiptDao(): ContactAddReceiptDao =
        mock(ContactAddReceiptDao::class.java, Answer { invocation ->
            if (invocation.method.name == "get") ContactAddReceiptEntity("r1", 1L)
            else Mockito.RETURNS_DEFAULTS.answer(invocation)
        })

    @Test fun `a failed accept reply keeps the receipt so it can be resent`() = runBlocking {
        val receiptDao = receiptDao()
        val sender = mock(TrackResponseSender::class.java, Answer { invocation ->
            if (invocation.method.name == "sendAccept") throw IllegalStateException("relay down")
            else Mockito.RETURNS_DEFAULTS.answer(invocation)
        })

        val completed = executor(receiptDao, sender)
            .completeInboundAccept(request, "SEND", true, receiptId = "r1")

        assertFalse(completed)
        // The receipt is the only record that the accept committed; dropping it here would
        // lose the reply that the requester is still waiting for.
        verify(receiptDao, never()).delete("r1")
    }

    @Test fun `a sent accept reply clears the receipt`() = runBlocking {
        val receiptDao = receiptDao()

        val completed = executor(receiptDao, mock(TrackResponseSender::class.java))
            .completeInboundAccept(request, "SEND", true, receiptId = "r1")

        assertTrue(completed)
        verify(receiptDao).delete("r1")
    }

    @Test fun `an outbound add whose track request cannot be sent reports failure`() = runBlocking {
        val receiptDao = mock(ContactAddReceiptDao::class.java)
        val keyManager = mock(KeyManager::class.java, Answer { invocation ->
            if (invocation.method.name == "ensureKeypair") "priv" to "mine"
            else Mockito.RETURNS_DEFAULTS.answer(invocation)
        })
        // The mocked preferences have no settings flow, so building the request throws.
        val invite = InviteData("theirs", "Them", "wss://relay.example", "theirs")

        val peerDao = mock(PeerDao::class.java)
        val completed = executor(receiptDao, mock(TrackResponseSender::class.java), keyManager, peerDao)
            .performOutboundAdd(invite, receiptId = "r1")

        assertFalse(completed)
        // The new contact row is removed again: left in place it would share this device's
        // location with someone who was never asked, while the user is told the add failed.
        verify(peerDao).deletePeerById("theirs")
        // No receipt: the request never went out, so nothing may claim it was sent.
        verify(receiptDao, never()).upsert(Mockito.any(ContactAddReceiptEntity::class.java) ?: ContactAddReceiptEntity("r1"))
    }
}
