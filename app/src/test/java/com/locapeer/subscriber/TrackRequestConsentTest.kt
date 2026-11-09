package com.locapeer.subscriber

import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.mutablePreferencesOf
import androidx.datastore.preferences.core.stringPreferencesKey
import com.locapeer.data.entity.PeerEntity
import com.locapeer.settings.applySupervisionRegistrationResponse
import org.junit.Assert.*
import org.junit.Test

class TrackRequestConsentTest {
    @Test fun `registration responses require a pending request`() {
        val now = 2_000_000L
        for (accepted in listOf(true, false)) {
            assertFalse(isCurrentSupervisionRegistrationResponse(false, now, now, now, accepted))
            assertTrue(isCurrentSupervisionRegistrationResponse(true, now, now, now, accepted))
        }
    }

    @Test fun `registration responses must match request timing allowing clock skew`() {
        val now = 2_000_000L
        for (accepted in listOf(true, false)) {
            assertFalse(isCurrentSupervisionRegistrationResponse(true, now, now - 3601, now, accepted))
            assertTrue(isCurrentSupervisionRegistrationResponse(true, now, now - 3600, now, accepted))
        }
    }

    @Test fun `registration answers retain their distinct catch up windows`() {
        val now = 4_000_000L
        val oldRequest = now - 31L * 24 * 3600
        assertTrue(isCurrentSupervisionRegistrationResponse(true, oldRequest, now - 30L * 24 * 3600, now, true))
        assertFalse(isCurrentSupervisionRegistrationResponse(true, oldRequest, now - 30L * 24 * 3600 - 1, now, true))
        assertTrue(isCurrentSupervisionRegistrationResponse(true, oldRequest, now - 86400, now, false))
        assertFalse(isCurrentSupervisionRegistrationResponse(true, oldRequest, now - 86401, now, false))
    }

    @Test fun `registration transition only applies once to the same pending request`() {
        val enabled = booleanPreferencesKey("supervised_mode")
        val pending = booleanPreferencesKey("supervision_pending")
        val supervisor = stringPreferencesKey("supervisor_pubkey")
        val requestedAt = longPreferencesKey("supervision_requested_at")
        val prefs = mutablePreferencesOf(enabled to true, pending to true, supervisor to "supervisor", requestedAt to 123L)
        assertFalse(applySupervisionRegistrationResponse(prefs, "other", 123L, false))
        assertFalse(applySupervisionRegistrationResponse(prefs, "supervisor", 122L, false))
        assertTrue(prefs[enabled] == true && prefs[pending] == true)
        assertTrue(applySupervisionRegistrationResponse(prefs, "supervisor", 123L, true))
        assertFalse(applySupervisionRegistrationResponse(prefs, "supervisor", 123L, false))
        assertTrue(prefs[enabled] == true && prefs[pending] == false)
        assertEquals("supervisor", prefs[supervisor])

        prefs[pending] = true
        assertTrue(applySupervisionRegistrationResponse(prefs, "supervisor", 123L, false))
        assertFalse(prefs[enabled]!!)
        assertFalse(prefs[pending]!!)
        assertEquals("", prefs[supervisor])
        assertFalse(applySupervisionRegistrationResponse(prefs, "supervisor", 123L, true))
    }

    @Test fun `receive only and new contacts always require review before sharing back`() {
        for (role in listOf(null, PeerEntity.ROLE_NONE, PeerEntity.ROLE_RECEIVE)) {
            assertTrue(shouldReviewTrackRequest(role, false))
        }
    }
    @Test fun `already granted sending does not need duplicate requests`() {
        assertFalse(shouldReviewTrackRequest(PeerEntity.ROLE_SEND, false))
        assertFalse(shouldReviewTrackRequest(PeerEntity.ROLE_SEND_RECEIVE, false))
    }
    @Test fun `role changes only accepted for existing contacts and always reviewed`() {
        assertFalse(shouldReviewTrackRequest(null, true))
        for (role in listOf(PeerEntity.ROLE_NONE, PeerEntity.ROLE_RECEIVE, PeerEntity.ROLE_SEND, PeerEntity.ROLE_SEND_RECEIVE)) {
            assertTrue(shouldReviewTrackRequest(role, true))
        }
    }

    @Test fun `track accept maps the acceptor's role onto ours after a scan`() {
        val scanned = PeerEntity.ROLE_SEND_RECEIVE
        assertEquals(PeerEntity.ROLE_SEND_RECEIVE, roleAfterTrackAccept(scanned, PeerEntity.ROLE_SEND_RECEIVE))
        assertEquals(PeerEntity.ROLE_SEND, roleAfterTrackAccept(scanned, PeerEntity.ROLE_RECEIVE))
        assertEquals(PeerEntity.ROLE_RECEIVE, roleAfterTrackAccept(scanned, PeerEntity.ROLE_SEND))
        assertEquals(PeerEntity.ROLE_NONE, roleAfterTrackAccept(scanned, PeerEntity.ROLE_NONE))
    }
    @Test fun `track accept never turns on sending our location`() {
        for (current in listOf(PeerEntity.ROLE_NONE, PeerEntity.ROLE_RECEIVE)) {
            for (accepted in listOf(PeerEntity.ROLE_SEND_RECEIVE, PeerEntity.ROLE_RECEIVE, "UNKNOWN", null)) {
                val result = roleAfterTrackAccept(current, accepted)
                assertTrue(result == PeerEntity.ROLE_NONE || result == PeerEntity.ROLE_RECEIVE)
            }
        }
    }
    @Test fun `track accept can grant us their location`() {
        assertEquals(PeerEntity.ROLE_RECEIVE, roleAfterTrackAccept(PeerEntity.ROLE_NONE, PeerEntity.ROLE_SEND))
        assertEquals(PeerEntity.ROLE_SEND_RECEIVE, roleAfterTrackAccept(PeerEntity.ROLE_SEND, PeerEntity.ROLE_SEND_RECEIVE))
    }
}
