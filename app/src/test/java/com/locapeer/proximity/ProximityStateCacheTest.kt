package com.locapeer.proximity

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.yield
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ProximityStateCacheTest {

    private class FakePersistence : ProximityStatePersistence {
        val stored = mutableMapOf<String, ProximityPeerState>()
        var reads = 0
        var saves = 0
        var clears = 0
        /** When set, clear suspends until it completes, so a test can observe the await. */
        var clearGate: CompletableDeferred<Unit>? = null

        override suspend fun read(peerId: String): ProximityPeerState? {
            reads++
            return stored[peerId]
        }

        override suspend fun save(peerId: String, state: ProximityPeerState) {
            saves++
            stored[peerId] = state
        }

        override suspend fun clear(peerId: String) {
            clears++
            clearGate?.await()
            stored.remove(peerId)
        }
    }

    @Test
    fun `snapshot loads persisted state once per peer`() = runBlocking {
        val persistence = FakePersistence()
        persistence.stored["peer"] = ProximityPeerState(nearby = true, lastNotifiedAtMs = 42L)
        val cache = ProximityStateCache(persistence)

        val first = cache.snapshot("peer")
        val second = cache.snapshot("peer")

        assertEquals(true, first.nearby)
        assertEquals(42L, first.lastNotifiedAtMs)
        assertEquals(first, second)
        assertEquals(1, persistence.reads)
    }

    @Test
    fun `save persists the state under the snapshot generation`() = runBlocking {
        val persistence = FakePersistence()
        val cache = ProximityStateCache(persistence)

        val snapshot = cache.snapshot("peer")
        cache.save("peer", snapshot, ProximityPeerState(nearby = true, lastNotifiedAtMs = 99L))

        assertEquals(ProximityPeerState(true, 99L), persistence.stored["peer"])
        assertEquals(true, cache.snapshot("peer").nearby)
    }

    @Test
    fun `removal awaits the persisted clear`() = runBlocking {
        val persistence = FakePersistence()
        persistence.stored["peer"] = ProximityPeerState(nearby = true, lastNotifiedAtMs = 1L)
        val gate = CompletableDeferred<Unit>()
        persistence.clearGate = gate
        val cache = ProximityStateCache(persistence)

        val removal = launch { cache.remove("peer") }
        yield()
        assertFalse("removal must not finish before its clear has", removal.isCompleted)
        assertEquals(1, persistence.clears)

        gate.complete(Unit)
        removal.join()
        assertTrue(removal.isCompleted)
        assertTrue(persistence.stored.isEmpty())
    }

    @Test
    fun `state saved by an evaluation that started before removal is refused`() = runBlocking {
        val persistence = FakePersistence()
        persistence.stored["peer"] = ProximityPeerState(nearby = false, lastNotifiedAtMs = 0L)
        val cache = ProximityStateCache(persistence)

        // An evaluation reads the old state, then the contact is removed before it saves.
        val inFlight = cache.snapshot("peer")
        cache.remove("peer")
        cache.save("peer", inFlight, ProximityPeerState(nearby = true, lastNotifiedAtMs = 99L))

        assertNull("a stale save must not resurrect removed state", persistence.stored["peer"])
        assertNull(cache.snapshot("peer").nearby)
    }

    @Test
    fun `re-added contact seeds fresh after removal`() = runBlocking {
        val persistence = FakePersistence()
        persistence.stored["peer"] = ProximityPeerState(nearby = true, lastNotifiedAtMs = 42L)
        val cache = ProximityStateCache(persistence)

        cache.remove("peer")

        // Re-add: nothing persisted remains, so the first observation seeds silently.
        val reseed = cache.snapshot("peer")
        assertNull(reseed.nearby)
        assertEquals(0L, reseed.lastNotifiedAtMs)

        // A save on the new generation is accepted.
        cache.save("peer", reseed, ProximityPeerState(nearby = true, lastNotifiedAtMs = 7L))
        assertEquals(ProximityPeerState(true, 7L), persistence.stored["peer"])
    }
}
