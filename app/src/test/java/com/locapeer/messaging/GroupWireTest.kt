package com.locapeer.messaging

import org.junit.Assert.*
import org.junit.Test

class GroupWireTest {
    private val member = "a".repeat(64)
    private val group = GroupMessage("12345678-1234-1234-1234-123456789abc", "Friends", listOf(member), "hello", member)

    @Test fun `legitimate groups including legacy ownerless groups round trip`() {
        assertEquals(group, GroupWire.decode(GroupWire.encode(group)))
        assertEquals(group.copy(creator = ""), GroupWire.decode(GroupWire.encode(group.copy(creator = ""))))
    }

    @Test fun `remote group cannot collide with a private peer thread`() {
        assertNull(GroupWire.decode(GroupWire.encode(group.copy(gid = member))))
    }

    @Test fun `rejects malformed keys and unbounded memberships`() {
        for (bad in listOf(group.copy(gid = ""), group.copy(members = listOf("bad")),
            group.copy(creator = "bad"), group.copy(members = List(257) { member }))) {
            assertNull(GroupWire.decode(GroupWire.encode(bad)))
        }
    }
}
