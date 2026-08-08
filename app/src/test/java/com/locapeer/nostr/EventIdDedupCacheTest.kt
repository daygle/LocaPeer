package com.locapeer.nostr

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class EventIdDedupCacheTest {

    @Test
    fun `records and recognises new ids per sender`() {
        val cache = EventIdDedupCache(senderCap = 10, globalCap = 100)
        assertTrue(cache.record("peerA", "a1"))
        assertTrue(cache.record("peerA", "a2"))
        assertTrue(cache.isKnown("peerA", "a1"))
        assertFalse(cache.isKnown("peerA", "a3"))
        assertFalse("ids are scoped per sender", cache.isKnown("peerB", "a1"))
    }

    @Test
    fun `duplicates are rejected`() {
        val cache = EventIdDedupCache(senderCap = 10, globalCap = 100)
        assertTrue(cache.record("peerA", "a1"))
        assertFalse(cache.record("peerA", "a1"))
    }

    @Test
    fun `flood from one peer only evicts that peer's own oldest entries`() {
        val cache = EventIdDedupCache(senderCap = 5, globalCap = 100)
        repeat(5) { assertTrue(cache.record("peerB", "b$it")) }

        // Peer A floods 100 new ids - far past its own cap.
        repeat(100) { cache.record("peerA", "a$it") }

        // Peer B's entire window must be untouched.
        repeat(5) { assertTrue("peerB entry b$it evicted by peerA flood", cache.isKnown("peerB", "b$it")) }
        // Peer A's own oldest entries were the only ones evicted.
        assertFalse(cache.isKnown("peerA", "a0"))
        assertTrue(cache.isKnown("peerA", "a99"))
    }

    @Test
    fun `global cap trims the largest contributor first`() {
        val cache = EventIdDedupCache(senderCap = 100, globalCap = 50)
        // Three peers fill 30 slots between them.
        repeat(3) { peer ->
            repeat(10) { i -> assertTrue(cache.record("peer$peer", "$peer-$i")) }
        }
        // A flooder pushes the total (130) far over the global cap.
        repeat(100) { cache.record("flooder", "f$it") }

        // Global memory bound is enforced...
        assertTrue("snapshot size ${cache.snapshot().size} exceeds globalCap", cache.snapshot().size <= 50)
        // ...and the flooder paid for it: every other peer's window is fully intact.
        repeat(3) { peer ->
            repeat(10) { i -> assertTrue("peer$peer entry $peer-$i evicted by flood", cache.isKnown("peer$peer", "$peer-$i")) }
        }
        // The flooder lost its oldest entries first.
        assertFalse(cache.isKnown("flooder", "f0"))
        assertTrue(cache.isKnown("flooder", "f99"))
    }

    @Test
    fun `restored pre-restart ids are recognised and trimmed first`() {
        val cache = EventIdDedupCache(senderCap = 100, globalCap = 20)
        cache.addRestored(List(20) { "old$it" })
        repeat(20) { cache.record("peerA", "a$it") }

        assertTrue("total over cap", cache.snapshot().size <= 20)
        // Restored (oldest) ids were trimmed first, peerA's window survives.
        repeat(20) { assertTrue(cache.isKnown("peerA", "a$it")) }
    }

    @Test
    fun `record rejects an id already restored from persistence`() {
        val cache = EventIdDedupCache(senderCap = 10, globalCap = 100)
        cache.addRestored(listOf("old1"))
        assertTrue(cache.isKnown("peerA", "old1"))
        assertFalse("restored ids must stay suppressed", cache.record("peerA", "old1"))
    }

    @Test
    fun `snapshot contains only live ids, restored ids stay out of persistence`() {
        val cache = EventIdDedupCache(senderCap = 10, globalCap = 100)
        cache.record("peerA", "a1")
        cache.record("peerB", "b1")
        cache.addRestored(listOf("old1"))
        assertEquals(setOf("a1", "b1"), cache.snapshot())
    }
}
