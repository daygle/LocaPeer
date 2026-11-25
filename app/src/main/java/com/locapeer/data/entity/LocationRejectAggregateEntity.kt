package com.locapeer.data.entity

import androidx.room.Entity
import androidx.room.Index

/**
 * One hour of rejected readings, after their full-detail rows aged out.
 *
 * Rejected readings keep coordinates, accuracy, provider and reason for
 * [com.locapeer.subscriber.RejectedReadingRetention.FULL_DETAIL_DAYS] days. Beyond that the
 * rows are replaced by this per-hour summary (how many attempts were rejected, and the
 * median accuracy radius among them), which is kept for
 * [com.locapeer.subscriber.RejectedReadingRetention.AGGREGATE_DAYS] days. A gap is still
 * explainable after the detail is gone, without keeping a fine-grained trail of where the
 * user was when the provider was struggling.
 */
@Entity(
    tableName = "location_reject_aggregates",
    primaryKeys = ["deviceId", "hourStartUtcMs"],
    indices = [Index("hourStartUtcMs")]
)
data class LocationRejectAggregateEntity(
    val deviceId: String,
    /** UTC epoch millis at the start of the hour this bucket covers. */
    val hourStartUtcMs: Long,
    val rejectedCount: Int,
    val medianAccuracyMeters: Float,
)
