package com.locapeer.sharing

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SupervisorSharingTest {

    private val supervisor = "a".repeat(64)
    private val other = "b".repeat(64)

    @Test
    fun `the active supervisor overrides pause and schedule`() {
        assertTrue(isActiveSupervisor(supervisionActive = true, supervisorPubkey = supervisor, peerPubkey = supervisor))
    }

    @Test
    fun `supervisor match ignores hex case`() {
        assertTrue(isActiveSupervisor(true, supervisor.uppercase(), supervisor))
    }

    @Test
    fun `other contacts keep their limits`() {
        assertFalse(isActiveSupervisor(true, supervisor, other))
    }

    @Test
    fun `no override while supervision is pending or off`() {
        assertFalse(isActiveSupervisor(supervisionActive = false, supervisorPubkey = supervisor, peerPubkey = supervisor))
    }

    @Test
    fun `an empty supervisor key never matches`() {
        assertFalse(isActiveSupervisor(true, "", ""))
    }
}
