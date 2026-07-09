package com.locapeer.data.entity

import org.junit.Assert.assertEquals
import org.junit.Test

class PeerEntityTest {

    // --- roleWithReceive ---

    @Test
    fun `promotes messaging-only contact to receive`() {
        assertEquals(PeerEntity.ROLE_RECEIVE, PeerEntity.roleWithReceive(PeerEntity.ROLE_NONE))
    }

    @Test
    fun `promotes send-only contact to send-receive`() {
        assertEquals(PeerEntity.ROLE_SEND_RECEIVE, PeerEntity.roleWithReceive(PeerEntity.ROLE_SEND))
    }

    @Test
    fun `leaves roles that already receive unchanged`() {
        assertEquals(PeerEntity.ROLE_RECEIVE, PeerEntity.roleWithReceive(PeerEntity.ROLE_RECEIVE))
        assertEquals(
            PeerEntity.ROLE_SEND_RECEIVE,
            PeerEntity.roleWithReceive(PeerEntity.ROLE_SEND_RECEIVE)
        )
    }
}
