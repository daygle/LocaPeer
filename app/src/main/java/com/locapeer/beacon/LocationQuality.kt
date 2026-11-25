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

/** The sharing quality policy: what contacts and live sharing are willing to receive. */
object SharingQuality {
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
     * Thresholds for sharing. A reading that fails them is stored as rejected and never
     * enters a sharing payload. The accuracy value is the existing `sendMaxAccuracyMeters`
     * setting unchanged.
     */
    fun thresholds(settings: AppSettings): QualityThresholds = QualityThresholds(
        maxAccuracyMeters = settings.sendMaxAccuracyMeters,
        maxFixAgeSeconds = DEFAULT_MAX_FIX_AGE_SECONDS,
    )
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
