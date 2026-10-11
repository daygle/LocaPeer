package com.locapeer.subscriber

import com.locapeer.data.entity.PeerEntity
import org.junit.Assert.*
import org.junit.Test

class TrackRequestConsentTest {
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
