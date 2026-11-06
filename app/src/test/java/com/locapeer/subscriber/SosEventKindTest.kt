package com.locapeer.subscriber

import com.locapeer.nostr.NostrEventKind
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SosEventKindTest {
    @Test fun `SOS status follows the signed event kind and not the payload flag`() {
        assertFalse(isSosEvent(NostrEventKind.HEARTBEAT))
        assertTrue(isSosEvent(NostrEventKind.SOS_ALERT))
    }
}
