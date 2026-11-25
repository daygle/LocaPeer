package com.locapeer.beacon

import com.locapeer.data.entity.RejectReason
import com.locapeer.settings.AppSettings
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class LocationQualityTest {

    private val goodReading = ReadingQuality(accuracyMeters = 12f, fixAgeSeconds = 5, hasFix = true)

    @Test
    fun `sharing thresholds come from the send-accuracy setting`() {
        val sharing = SharingQuality.thresholds(AppSettings(sendMaxAccuracyMeters = 75))

        assertEquals(75, sharing.maxAccuracyMeters)
        assertEquals(SharingQuality.DEFAULT_MAX_FIX_AGE_SECONDS, sharing.maxFixAgeSeconds)
    }

    @Test
    fun `the same reading passes a loose consumer and fails a strict one`() {
        val reading = ReadingQuality(accuracyMeters = 120f, fixAgeSeconds = 5, hasFix = true)

        assertTrue(LocationQualityGate.evaluate(reading, QualityThresholds(maxAccuracyMeters = 150)).accepted)
        val strict = LocationQualityGate.evaluate(reading, QualityThresholds(maxAccuracyMeters = 75))
        assertFalse(strict.accepted)
        assertEquals(RejectReason.TOO_IMPRECISE, strict.rejectReason)
    }

    @Test
    fun `zero thresholds accept every reading that has a fix`() {
        val coarse = ReadingQuality(accuracyMeters = 5_000f, fixAgeSeconds = 9_999, hasFix = true)
        assertTrue(LocationQualityGate.evaluate(coarse, QualityThresholds()).accepted)
    }

    @Test
    fun `a missing fix is rejected with its own reason`() {
        val verdict = LocationQualityGate.evaluate(
            ReadingQuality(accuracyMeters = 0f, fixAgeSeconds = 0, hasFix = false),
            QualityThresholds(maxAccuracyMeters = 10),
        )
        assertFalse(verdict.accepted)
        assertEquals(RejectReason.NO_FIX, verdict.rejectReason)
    }

    @Test
    fun `lost permission is reported before the fix is judged`() {
        val verdict = LocationQualityGate.evaluate(
            goodReading.copy(permissionGranted = false),
            QualityThresholds(maxAccuracyMeters = 10),
        )
        assertFalse(verdict.accepted)
        assertEquals(RejectReason.PERMISSION_DENIED, verdict.rejectReason)
    }

    @Test
    fun `a stale fix is reported as stale even when it is also coarse`() {
        val verdict = LocationQualityGate.evaluate(
            ReadingQuality(accuracyMeters = 900f, fixAgeSeconds = 3_601, hasFix = true),
            QualityThresholds(maxAccuracyMeters = 50, maxFixAgeSeconds = 3_600),
        )
        assertFalse(verdict.accepted)
        assertEquals(RejectReason.STALE, verdict.rejectReason)
    }

    @Test
    fun `a fix exactly at the thresholds is still accepted`() {
        val verdict = LocationQualityGate.evaluate(
            ReadingQuality(accuracyMeters = 50f, fixAgeSeconds = 3_600, hasFix = true),
            QualityThresholds(maxAccuracyMeters = 50, maxFixAgeSeconds = 3_600),
        )
        assertTrue(verdict.accepted)
    }

    @Test
    fun `sos bypasses the quality thresholds`() {
        val verdict = LocationQualityGate.evaluate(
            ReadingQuality(accuracyMeters = 9_000f, fixAgeSeconds = 9_999, hasFix = true),
            QualityThresholds(maxAccuracyMeters = 10, maxFixAgeSeconds = 10),
            bypass = true,
        )
        assertTrue(verdict.accepted)
    }

    @Test
    fun `sos cannot turn a call with no position into a reading`() {
        val verdict = LocationQualityGate.evaluate(
            ReadingQuality(accuracyMeters = 0f, fixAgeSeconds = 0, hasFix = false),
            QualityThresholds(maxAccuracyMeters = 10, maxFixAgeSeconds = 10),
            bypass = true,
        )
        assertFalse(verdict.accepted)
        assertEquals(RejectReason.NO_FIX, verdict.rejectReason)
    }
}
