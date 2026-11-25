package com.locapeer.data.entity

/**
 * Whether a location reading passed the sharing quality gate at ingest.
 *
 * Every attempt is stored, accepted or rejected. The status records the decision made
 * when the reading was captured, so a later change to the configured thresholds does
 * not rewrite history; consumers that want a different policy apply their own
 * thresholds to the stored quality fields instead.
 */
enum class ReadingStatus(val dbValue: String) {
    ACCEPTED("accepted"),
    REJECTED("rejected");

    companion object {
        fun fromDbValue(value: String?): ReadingStatus =
            if (value == REJECTED.dbValue) REJECTED else ACCEPTED
    }
}

/**
 * Why a reading was not shared. [code] is the stable value persisted in
 * [HeartbeatEntity.rejectReason]; the UI maps it to a localized label, and logs and
 * exports carry the code so a gap can be explained from stored data alone.
 */
enum class RejectReason(val code: String) {
    TOO_IMPRECISE("too_imprecise"),
    STALE("stale"),
    NO_FIX("no_fix"),
    PERMISSION_DENIED("permission_denied"),
    OTHER("other");

    companion object {
        /** The reason for [code], or null for a missing/unknown value (e.g. an older row). */
        fun fromCode(code: String?): RejectReason? = entries.firstOrNull { it.code == code }
    }
}
