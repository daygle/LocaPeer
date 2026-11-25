package com.locapeer.beacon

import com.locapeer.data.entity.RejectReason
import com.locapeer.data.entity.ReadingStatus
import com.locapeer.settings.AppSettings

/**
 * One consumer's idea of a usable reading.
 *
 * [maxAccuracyMeters] and [maxFixAgeSeconds] are both disabled at 0, matching the existing
 * settings where 0 means "no filter". Thresholds are evaluated at egress, never used to
 * discard a reading at capture: storage keeps every attempt and this policy only decides
 * what one consumer is willing to use.
 */
data class QualityThresholds(
    val maxAccuracyMeters: Int = 0,
    val maxFixAgeSeconds: Int = 0,
) {
    val accuracyGateEnabled: Boolean get() = maxAccuracyMeters > 0
    val fixAgeGateEnabled: Boolean get() = maxFixAgeSeconds > 0
}

/**
 * The single quality-policy object, resolved once per reading and shared by every consumer.
 *
 * Previously the app had one capture-time discard (the send-accuracy setting), which also
 * stopped the reading from reaching local history and left no record behind. Each consumer
 * now names its own bar:
 *
 * - [sharing] - contacts and live sharing. A reading that fails this is stored as rejected
 *   and never enters a sharing payload.
 * - [history] - the user's own timeline. It shows everything by default; the history view
 *   applies this threshold only as an explicit "hide low-accuracy" display toggle.
 * - [analytics] / [tripDetection] - deliberately unrestricted: quality is stored as data,
 *   so these consumers carry the flags and choose for themselves. Trip detection keeps
 *   its existing multi-sample hysteresis and never flips on a single reading.
 */
data class LocationQualityConfig(
    val sharing: QualityThresholds,
    val history: QualityThresholds,
    val analytics: QualityThresholds,
    val tripDetection: QualityThresholds,
) {
    companion object {
        /**
         * A fix older than this is treated as stale for sharing even if it is accurate.
         *
         * Deliberately generous: the longest normal cadence is the 30-minute low-battery
         * interval, and the stationary profile can hold a sharp fix across several coarse
         * polls, so anything near the cadence would newly silence a healthy device. An hour
         * only rejects a position that is genuinely from a dead provider.
         */
        const val DEFAULT_MAX_FIX_AGE_SECONDS = 3600

        /**
         * Resolves the per-consumer policy from settings. The accuracy values are the
         * existing `sendMaxAccuracyMeters` / `historyMaxAccuracyMeters` settings unchanged.
         */
        fun fromSettings(settings: AppSettings): LocationQualityConfig = LocationQualityConfig(
            sharing = QualityThresholds(
                maxAccuracyMeters = settings.sendMaxAccuracyMeters,
                maxFixAgeSeconds = DEFAULT_MAX_FIX_AGE_SECONDS,
            ),
            history = QualityThresholds(
                maxAccuracyMeters = settings.historyMaxAccuracyMeters,
                maxFixAgeSeconds = 0,
            ),
            analytics = QualityThresholds(),
            tripDetection = QualityThresholds(),
        )
    }
}

/** The quality facts recorded for one reading attempt, independent of any consumer. */
data class ReadingQuality(
    /** Horizontal accuracy radius reported by the provider, in metres. */
    val accuracyMeters: Float,
    /** How old the fix was when the attempt was ingested. */
    val fixAgeSeconds: Long,
    /** False when the provider produced no position at all for this attempt. */
    val hasFix: Boolean,
    /** False when the app no longer holds location permission for this attempt. */
    val permissionGranted: Boolean = true,
)

/** The stored verdict for one attempt. */
data class QualityVerdict(
    val status: ReadingStatus,
    val rejectReason: RejectReason?,
) {
    val accepted: Boolean get() = status == ReadingStatus.ACCEPTED
}

/**
 * Applies [QualityThresholds] to a reading's stored quality. Pure Kotlin so the per-consumer
 * selection is unit-testable without Android or a database.
 *
 * A caller may [bypass] the quality thresholds (SOS does): the reading is then accepted
 * whatever its accuracy or age, preserving the promise that an emergency position is not
 * withheld. A call that produced no position at all is still rejected - there is nothing
 * to share, and SOS already falls back to the last known position in that case.
 */
object LocationQualityGate {

    fun evaluate(
        quality: ReadingQuality,
        thresholds: QualityThresholds,
        bypass: Boolean = false,
    ): QualityVerdict {
        if (!quality.permissionGranted) return rejected(RejectReason.PERMISSION_DENIED)
        if (!quality.hasFix) return rejected(RejectReason.NO_FIX)
        if (bypass) return ACCEPTED
        // Age before accuracy: a stale reading is no longer evidence about where the device
        // is, however sharp it claims to be.
        if (thresholds.fixAgeGateEnabled && quality.fixAgeSeconds > thresholds.maxFixAgeSeconds) {
            return rejected(RejectReason.STALE)
        }
        if (thresholds.accuracyGateEnabled && quality.accuracyMeters > thresholds.maxAccuracyMeters) {
            return rejected(RejectReason.TOO_IMPRECISE)
        }
        return ACCEPTED
    }

    private val ACCEPTED = QualityVerdict(ReadingStatus.ACCEPTED, null)

    private fun rejected(reason: RejectReason) = QualityVerdict(ReadingStatus.REJECTED, reason)
}
