package com.locapeer.nostr

import com.locapeer.crypto.CryptoUtils
import org.junit.Assert.assertFalse
import org.junit.Test

class NostrEventValidationTest {
    @Test fun `malformed event fields are rejected without loading native crypto`() {
        val crypto = CryptoUtils()
        val event = NostrEvent(id = "a".repeat(64), pubkey = "b".repeat(64), sig = "c".repeat(128))
        for (bad in listOf(event.copy(id = ""), event.copy(pubkey = "B".repeat(64)),
            event.copy(sig = "c".repeat(126)), event.copy(kind = -1), event.copy(kind = 65536),
            event.copy(createdAt = -1), event.copy(createdAt = Long.MAX_VALUE))) {
            assertFalse(NostrEvent.verify(bad, crypto))
        }
    }
}
