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
}
