package com.locapeer.beacon

import java.util.concurrent.atomic.AtomicInteger

/**
 * Lightweight counters over the location pipeline's decisions, summarised as one
 * logcat line per pulse (see HeartbeatService's pulse runnable). The pipeline's
 * tunables - stationary hold, AR staleness, exit corroboration, boost windows - can
 * only be calibrated against real-world behaviour, and without a compact trail that
 * calibration means instrumenting a debug build after the fact. One line per pulse
 * (minutes apart) is negligible log volume and turns "the pin acted weird around
 * 3pm" into a grep.
 *
 * Counters are atomic because fixes land on the main looper while AR/alarm events can
 * increment from service starts; [summarizeAndReset] swaps each counter to zero so
 * every summary covers exactly the window since the previous pulse.
 */
class PipelineDiagnostics {
    private val fixesAccepted = AtomicInteger()
    private val filterRejected = AtomicInteger()
    private val selectorHeld = AtomicInteger()
    private val exitCandidatesHeld = AtomicInteger()
    private val exitsConfirmed = AtomicInteger()
    private val classificationBoosts = AtomicInteger()
    private val anchorBoosts = AtomicInteger()
    private val motionStateChanges = AtomicInteger()

    fun onFixAccepted() { fixesAccepted.incrementAndGet() }
    fun onFilterRejected() { filterRejected.incrementAndGet() }
    fun onSelectorHeld() { selectorHeld.incrementAndGet() }
    fun onExitCandidateHeld() { exitCandidatesHeld.incrementAndGet() }
    fun onExitConfirmed() { exitsConfirmed.incrementAndGet() }
    fun onClassificationBoost() { classificationBoosts.incrementAndGet() }
    fun onAnchorBoost() { anchorBoosts.incrementAndGet() }
    fun onMotionStateChange() { motionStateChanges.incrementAndGet() }

    /** One-line summary of pipeline activity since the previous call, then reset. */
    fun summarizeAndReset(): String =
        "fixes[ok=${fixesAccepted.getAndSet(0)} rejFilter=${filterRejected.getAndSet(0)} " +
            "heldSel=${selectorHeld.getAndSet(0)} exitCand=${exitCandidatesHeld.getAndSet(0)}] " +
            "exits=${exitsConfirmed.getAndSet(0)} " +
            "boosts[classify=${classificationBoosts.getAndSet(0)} anchor=${anchorBoosts.getAndSet(0)}] " +
            "stateChanges=${motionStateChanges.getAndSet(0)}"
}
