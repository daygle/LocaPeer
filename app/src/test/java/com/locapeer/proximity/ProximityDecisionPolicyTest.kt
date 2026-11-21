package com.locapeer.proximity

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ProximityDecisionPolicyTest {

    private val radius = 500.0
    private val now = 1_700_000_000_000L
    private val cooldown = 10 * 60_000L

    private fun decide(
        distance: Double,
        wasNearby: Boolean?,
        uncertainty: Double = 0.0,
        announce: Boolean = false,
        lastNotifiedAtMs: Long = 0L,
        scheduleActive: Boolean = true
    ) = ProximityDecisionPolicy.decide(
        distanceMetres = distance,
        radiusMetres = radius,
        uncertaintyMetres = uncertainty,
        wasNearby = wasNearby,
        announce = announce,
        lastNotifiedAtMs = lastNotifiedAtMs,
        nowMs = now,
        cooldownMs = cooldown,
        scheduleActive = scheduleActive
    )

    @Test
    fun `first observation seeds silently`() {
        // No stored state (startup): record inside, but never ring for a visit that
        // was already in progress before the engine observed anything.
        val decision = decide(distance = 100.0, wasNearby = null)

        assertEquals(true, decision.nearby)
        assertFalse(decision.notify)
    }

    @Test
    fun `first observation outside seeds outside`() {
        val decision = decide(distance = 5_000.0, wasNearby = null)

        assertEquals(false, decision.nearby)
        assertFalse(decision.notify)
    }

    @Test
    fun `entry from outside notifies once`() {
        val decision = decide(distance = 100.0, wasNearby = false)

        assertEquals(true, decision.nearby)
        assertTrue(decision.notify)
        assertEquals(now, decision.notifiedAtMs)
    }

    @Test
    fun `staying inside does not notify again`() {
        val decision = decide(
            distance = 200.0,
            wasNearby = true,
            lastNotifiedAtMs = now - cooldown - 1
        )

        assertEquals(true, decision.nearby)
        assertFalse(decision.notify)
    }

    @Test
    fun `exit past radius plus buffer re-arms without notifying`() {
        // radius 500 + the 100 m minimum buffer = 600 m; 700 m is a real exit.
        val decision = decide(distance = 700.0, wasNearby = true)

        assertEquals(false, decision.nearby)
        assertFalse(decision.notify)
    }

    @Test
    fun `inside the hysteresis band still counts as inside`() {
        val decision = decide(distance = 550.0, wasNearby = true)

        assertEquals(true, decision.nearby)
        assertFalse(decision.notify)
    }

    @Test
    fun `re-entry after an exit notifies again`() {
        val exit = decide(distance = 700.0, wasNearby = true)
        assertEquals(false, exit.nearby)

        val reEntry = decide(
            distance = 100.0,
            wasNearby = exit.nearby,
            lastNotifiedAtMs = now - cooldown - 1
        )

        assertEquals(true, reEntry.nearby)
        assertTrue(reEntry.notify)
    }

    @Test
    fun `cooldown suppresses an arrival but still records inside`() {
        val decision = decide(
            distance = 100.0,
            wasNearby = false,
            lastNotifiedAtMs = now - cooldown + 1
        )

        assertEquals(true, decision.nearby)
        assertFalse(decision.notify)
        assertEquals(now - cooldown + 1, decision.notifiedAtMs)
    }

    @Test
    fun `inactive schedule records the visit without notifying`() {
        val decision = decide(distance = 100.0, wasNearby = false, scheduleActive = false)

        assertEquals(true, decision.nearby)
        assertFalse(decision.notify)
    }

    @Test
    fun `re-enabled alert announces the visit already in progress`() {
        val decision = decide(
            distance = 100.0,
            wasNearby = true,
            announce = true,
            lastNotifiedAtMs = now - cooldown - 1
        )

        assertEquals(true, decision.nearby)
        assertTrue(decision.notify)
    }

    @Test
    fun `uncertainty wider than the radius cannot confirm a new entry`() {
        // 100 m away, but ±2 km of uncertainty: this reading can't decide, so the
        // prior outside state is kept and no alert fires.
        val decision = decide(distance = 100.0, wasNearby = false, uncertainty = 2_000.0)

        assertNull(decision.nearby)
        assertFalse(decision.notify)
    }

    @Test
    fun `provably nearby survives a wide uncertainty`() {
        // Even at full uncertainty (+100 m) the peer is inside the 500 m radius.
        val decision = decide(distance = 50.0, wasNearby = false, uncertainty = 100.0)

        assertEquals(true, decision.nearby)
        assertTrue(decision.notify)
    }

    @Test
    fun `own fix older than the passive window plus cache reuse is stale`() {
        assertTrue(isOwnFixFresh(MAX_OWN_FIX_AGE_MS))
        assertFalse(isOwnFixFresh(MAX_OWN_FIX_AGE_MS + 1))
        // A fix timestamped in the future (clock skew) is never treated as stale.
        assertTrue(isOwnFixFresh(-1_000))
    }

    @Test
    fun `persisted peer state round-trips`() {
        for (state in listOf(
            ProximityPeerState(nearby = true, lastNotifiedAtMs = 1_700_000_000_123L),
            ProximityPeerState(nearby = false, lastNotifiedAtMs = 0L)
        )) {
            assertEquals(state, decodeProximityPeerState(encodeProximityPeerState(state)))
        }
    }

    @Test
    fun `unrecognised persisted state decodes as absent`() {
        assertNull(decodeProximityPeerState(null))
        assertNull(decodeProximityPeerState(""))
        assertNull(decodeProximityPeerState("maybe:123"))
        assertNull(decodeProximityPeerState("1:not-a-number"))
        assertNull(decodeProximityPeerState("1:2:3"))
    }
}
