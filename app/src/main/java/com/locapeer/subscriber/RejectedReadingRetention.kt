package com.locapeer.subscriber

/**
 * Retention policy for readings that were stored but not accepted.
 *
 * Accepted readings keep whatever retention policy the user configured. Rejected readings
 * have a fixed, privacy-oriented policy of their own because they exist purely to explain
 * gaps in the shared trail: they are kept for [DAYS] days, then deleted.
 */
object RejectedReadingRetention {

    /** Rejected readings are kept this long after the attempt was recorded. */
    const val DAYS = 7L

    private const val DAY_MS = 86_400_000L

    /** Rejected readings recorded before this instant are deleted. */
    fun cutoff(nowMs: Long): Long = nowMs - DAYS * DAY_MS
}
