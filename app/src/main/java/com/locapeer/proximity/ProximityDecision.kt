package com.locapeer.proximity

import com.locapeer.util.GeoMath

// A passive (lastLocation) fix older than this is refreshed with one bounded active
// fetch before proximity is judged on it; see ProximityEngine.getFreshOwnLocation.
internal const val OWN_FIX_STALE_MS = 60_000L
// Reuse a freshly fetched own position for this long before fetching again (cost
// guard when several peers' heartbeats land in the same moment).
internal const val OWN_FIX_CACHE_MS = 30_000L
// The oldest own fix still usable for a proximity judgement: the passive window plus
// one cache reuse. Anything older measures where we *used to be* - a drive to the
// meeting could read as "outside", re-arm the alert, and then ring the same visit a
// second time when a fresh fix landed - so such a beat is skipped rather than judged.
internal const val MAX_OWN_FIX_AGE_MS = OWN_FIX_STALE_MS + OWN_FIX_CACHE_MS

/** Whether an own fix of this age (negative on clock skew) can still judge proximity. */
internal fun isOwnFixFresh(fixAgeMs: Long): Boolean = fixAgeMs <= MAX_OWN_FIX_AGE_MS

/**
 * Per-peer proximity state the engine keeps between evaluations. Persisted so a
 * process restart can't re-arm an alert that already fired.
 */
internal data class ProximityPeerState(
    val nearby: Boolean,
    val lastNotifiedAtMs: Long
)

internal fun encodeProximityPeerState(state: ProximityPeerState): String =
    "${if (state.nearby) 1 else 0}:${state.lastNotifiedAtMs}"

/** Null for absent or unrecognised values, so an old/foreign format only re-seeds. */
internal fun decodeProximityPeerState(raw: String?): ProximityPeerState? {
    if (raw.isNullOrBlank()) return null
    val parts = raw.split(':')
    if (parts.size != 2) return null
    val nearby = when (parts[0]) {
        "1" -> true
        "0" -> false
        else -> return null
    }
    val lastNotifiedAtMs = parts[1].toLongOrNull() ?: return null
    return ProximityPeerState(nearby, lastNotifiedAtMs)
}

/**
 * One proximity evaluation's outcome.
 *
 * [nearby] is the peer's new inside state, or null when this reading can't decide
 * (the fix uncertainty spans the radius) and the previous state must be kept.
 */
internal data class ProximityDecision(
    val nearby: Boolean?,
    val notify: Boolean,
    val notifiedAtMs: Long
)

/**
 * Pure proximity state machine, split out of [ProximityEngine] so the entry, exit and
 * seed paths can be exercised without Android location or notification services.
 *
 * Alerts fire on the outside→inside transition only. Leaving re-arms the alert (the
 * new state is outside), so a peer who stays nearby rings once, not once per cooldown.
 */
internal object ProximityDecisionPolicy {

    /** Minimum hysteresis buffer past the alert radius before the peer counts as gone. */
    internal const val MIN_EXIT_BUFFER_M = 100.0

    fun decide(
        distanceMetres: Double,
        radiusMetres: Double,
        uncertaintyMetres: Double,
        wasNearby: Boolean?,
        announce: Boolean,
        lastNotifiedAtMs: Long,
        nowMs: Long,
        cooldownMs: Long,
        scheduleActive: Boolean
    ): ProximityDecision {
        // Hysteresis: a peer who is already inside stays inside through the buffer,
        // so a fix jittering around the boundary neither leaves nor re-alerts.
        val buffer = maxOf(MIN_EXIT_BUFFER_M, uncertaintyMetres)
        val inside = GeoMath.isInsideWithHysteresis(
            distanceMetres, radiusMetres, buffer, wasNearby == true
        )

        // Uncertainty wider than the radius can't confirm a NEW entry - but it can
        // still retire a stale inside state, and a reading that is provably inside
        // even at full uncertainty must alert instead of being dropped.
        if (inside && wasNearby != true &&
            uncertaintyMetres > radiusMetres &&
            distanceMetres + uncertaintyMetres > radiusMetres
        ) return ProximityDecision(nearby = null, notify = false, notifiedAtMs = lastNotifiedAtMs)

        // First observation for a peer with no stored state: seed only. Someone who
        // was already nearby shouldn't re-alert every time the process starts.
        if (wasNearby == null && !announce) {
            return ProximityDecision(nearby = inside, notify = false, notifiedAtMs = lastNotifiedAtMs)
        }

        // Only an arrival can notify; staying inside or leaving is silent. Re-enabling
        // an alert (announce) reports the visit that is already in progress.
        val arrival = inside && (wasNearby != true || announce)
        if (!arrival || !scheduleActive || nowMs - lastNotifiedAtMs < cooldownMs) {
            return ProximityDecision(nearby = inside, notify = false, notifiedAtMs = lastNotifiedAtMs)
        }

        return ProximityDecision(nearby = inside, notify = true, notifiedAtMs = nowMs)
    }
}
