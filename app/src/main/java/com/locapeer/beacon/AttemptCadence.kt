package com.locapeer.beacon

/**
 * Spacing for the next location attempt after a run of rejected readings.
 *
 * The cadence must be measured from the last *attempt*, not the last successful broadcast:
 * deriving it from the last success let a run of rejections stall the loop (or, after a
 * reschedule, fire it back-to-back). Rejected attempts still count, and a long run backs
 * off gently so a provider that is stuck coarse is not polled at full rate forever.
 *
 * The backoff is bounded at 2x the configured cadence. That is deliberate: this is a
 * hiccup, not a reason to stop collecting, and a fixed ceiling means a device whose
 * provider recovers is never more than one doubled interval away from a normal reading.
 * Pure Kotlin so the schedule is unit-testable.
 */
object AttemptCadence {

    /** No extra spacing for the first rejection: one bad fix is normal. */
    private const val REJECTIONS_PER_BACKOFF_STEP = 2

    /** 25% per step, so at most 2x the base interval (never a stall). */
    private const val BACKOFF_STEP_PERCENT = 25
    private const val MAX_BACKOFF_PERCENT = 100

    /**
     * The delay before the next attempt: [baseIntervalMs] plus a bounded backoff that grows
     * with [consecutiveRejections] and resets to the base interval once a reading is accepted.
     */
    fun nextDelayMs(baseIntervalMs: Long, consecutiveRejections: Int): Long {
        if (baseIntervalMs <= 0L) return 0L
        val steps = consecutiveRejections.coerceAtLeast(0) / REJECTIONS_PER_BACKOFF_STEP
        val extraPercent = (steps * BACKOFF_STEP_PERCENT).coerceAtMost(MAX_BACKOFF_PERCENT)
        return baseIntervalMs + baseIntervalMs * extraPercent / 100
    }
}
