package com.locapeer.nostr

import com.locapeer.crypto.CryptoUtils
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class NostrEventValidationTest {
    @Test fun `queue size accounts for content and nested tags`() {
        val base = NostrEvent(content = "a".repeat(100))
        val withTags = base.copy(tags = listOf(listOf("p", "b".repeat(1_000))))
        assertTrue(incomingEventBytes(base) >= 200L)
        assertTrue(incomingEventBytes(withTags) >= incomingEventBytes(base) + 2_000L)
        val withEmptyTags = base.copy(tags = List(100) { emptyList() })
        assertTrue(incomingEventBytes(withEmptyTags) >= incomingEventBytes(base) + 3_200L)
    }

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
