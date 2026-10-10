package com.locapeer.messaging

import com.locapeer.messaging.GroupControl.Update
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

class GroupControlTest {
    private val gid = "123e4567-e89b-12d3-a456-426614174000"
    private val creator = "a".repeat(64)
    private val member = "b".repeat(64)
    private val other = "c".repeat(64)

    @Test fun `signed payload ignores member order but covers every field`() {
        val base = GroupControl.signedPayload(gid, creator, 5, listOf(creator, member), "Family")
        assertArrayEquals(base, GroupControl.signedPayload(gid, creator, 5, listOf(member, creator, member), "Family"))
        for (changed in listOf(
            GroupControl.signedPayload(gid, creator, 6, listOf(creator, member), "Family"),
            GroupControl.signedPayload(gid, member, 5, listOf(creator, member), "Family"),
            GroupControl.signedPayload(gid, creator, 5, listOf(creator), "Family"),
            GroupControl.signedPayload(gid, creator, 5, listOf(creator, member), "Family2"),
        )) assertFalse(base.contentEquals(changed))
    }

    @Test fun `a newer signed version from the recorded creator applies, whoever relays it`() {
        assertEquals(Update.APPLY_SIGNED, decide(knownVersion = 10, sender = member, signedVersion = 11))
        assertEquals(Update.APPLY_SIGNED, decide(knownVersion = 0, sender = creator, signedVersion = 1))
    }

    @Test fun `older or equal signed versions can't roll the circle back`() {
        assertEquals(Update.IGNORE, decide(knownVersion = 10, sender = creator, signedVersion = 10))
        assertEquals(Update.IGNORE, decide(knownVersion = 10, sender = creator, signedVersion = 9))
    }

    @Test fun `a signature from a different creator never takes over a circle`() {
        assertEquals(Update.IGNORE, decide(claimed = other, sender = other, signedVersion = 99))
    }

    @Test fun `a legacy circle without a recorded creator adopts the signed one`() {
        assertEquals(Update.APPLY_SIGNED, decide(knownCreator = "", claimed = creator, sender = member, signedVersion = 1))
    }

    @Test fun `once signed, unsigned updates are ignored even from the creator`() {
        assertEquals(Update.IGNORE, decide(knownVersion = 3, sender = creator, signedVersion = 0))
    }

    @Test fun `unsigned circles keep the creator-only, newest-first rules`() {
        assertEquals(Update.APPLY_UNSIGNED, decide(sender = creator, createdAt = 100, updatedAt = 100))
        assertEquals(Update.IGNORE, decide(sender = creator, createdAt = 99, updatedAt = 100))
        assertEquals(Update.IGNORE, decide(sender = member, createdAt = 200, updatedAt = 100))
    }

    @Test fun `wire format accepts only well-formed signature fields`() {
        val msg = GroupMessage(gid, "Family", listOf(creator, member), "hi", creator, cver = 7, csig = "d".repeat(128))
        assertNotNull(GroupWire.decode(GroupWire.encode(msg)))
        assertEquals(7L, GroupWire.decode(GroupWire.encode(msg))!!.cver)
        assertNull(GroupWire.decode(GroupWire.encode(msg.copy(csig = "xyz"))))
        assertNull(GroupWire.decode(GroupWire.encode(msg.copy(cver = -1))))
        // Messages from app versions without the fields still decode, unsigned.
        assertEquals(0L, GroupWire.decode(GroupWire.encode(msg.copy(cver = 0, csig = "")))!!.cver)
    }

    private fun decide(
        knownCreator: String = creator,
        knownVersion: Long = 0,
        updatedAt: Long = 0,
        sender: String,
        claimed: String = creator,
        signedVersion: Long = 0,
        createdAt: Long = 0,
    ) = GroupControl.decide(knownCreator, knownVersion, updatedAt, sender, claimed, signedVersion, createdAt)
}
