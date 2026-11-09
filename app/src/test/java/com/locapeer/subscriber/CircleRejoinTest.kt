package com.locapeer.subscriber

import com.locapeer.settings.LeftCircle
import com.locapeer.settings.decodeLeftCircles
import com.locapeer.settings.encodeLeftCircles
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CircleRejoinTest {
    private val gid = "123e4567-e89b-12d3-a456-426614174000"
    private val creator = "a".repeat(64)
    private val stranger = "b".repeat(64)

    @Test fun `only a newer message from the recorded owner is a re-invite`() {
        val left = LeftCircle(gid, creator, 1_000)
        assertTrue(isCircleReinvite(left, creator, creator, eventCreatedAt = 1_001, signedVersion = 0))
        assertFalse(isCircleReinvite(left, creator, creator, eventCreatedAt = 1_000, signedVersion = 0))
        assertFalse(isCircleReinvite(left, creator, creator, eventCreatedAt = 999, signedVersion = 0))
    }

    @Test fun `another contact cannot rejoin us by claiming ownership of a left circle`() {
        val left = LeftCircle(gid, creator, 1_000)
        assertFalse(isCircleReinvite(left, stranger, stranger, eventCreatedAt = 2_000, signedVersion = 0))
        assertFalse(isCircleReinvite(left, stranger, stranger, eventCreatedAt = 2_000, signedVersion = 5))
    }

    @Test fun `a re-invite must present its sender as the creator`() {
        val left = LeftCircle(gid, creator, 1_000)
        assertFalse(isCircleReinvite(left, creator, "", eventCreatedAt = 2_000, signedVersion = 0))
        assertFalse(isCircleReinvite(left, creator, stranger, eventCreatedAt = 2_000, signedVersion = 0))
    }

    @Test fun `leave records without an owner need a signed creator state`() {
        val legacy = LeftCircle(gid, "", 0)
        assertFalse(isCircleReinvite(legacy, creator, creator, eventCreatedAt = 2_000, signedVersion = 0))
        assertTrue(isCircleReinvite(legacy, creator, creator, eventCreatedAt = 2_000, signedVersion = 1))
    }

    @Test fun `an unknown-owner leave rejects signed state replayed from before it`() {
        val recorded = LeftCircle(gid, "", 1_000)
        assertFalse(isCircleReinvite(recorded, creator, creator, eventCreatedAt = 1_000, signedVersion = 3))
        assertFalse(isCircleReinvite(recorded, creator, creator, eventCreatedAt = 999, signedVersion = 3))
        assertTrue(isCircleReinvite(recorded, creator, creator, eventCreatedAt = 1_001, signedVersion = 3))
    }

    @Test fun `leave records round trip and older bare ids still decode`() {
        val encoded = encodeLeftCircles(listOf(LeftCircle(gid, creator, 1_000), LeftCircle("g2", "", 0)))
        assertEquals(
            mapOf(gid to LeftCircle(gid, creator, 1_000), "g2" to LeftCircle("g2", "", 0)),
            decodeLeftCircles(encoded)
        )
        assertEquals(mapOf(gid to LeftCircle(gid, "", 0)), decodeLeftCircles(gid))
        assertTrue(decodeLeftCircles(null).isEmpty())
        assertTrue(decodeLeftCircles("").isEmpty())
    }
}
