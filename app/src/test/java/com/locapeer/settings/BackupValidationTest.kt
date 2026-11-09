package com.locapeer.settings

import com.locapeer.data.entity.PeerEntity
import com.locapeer.data.entity.PrecisionMode
import com.locapeer.sharing.ScheduleRule
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

class BackupValidationTest {

    private fun area(
        id: String = "g1",
        name: String = "Home",
        lat: Double = 51.5,
        lng: Double = -0.1,
        radius: Int = 150
    ) = GeofenceBackup(id, name, lat, lng, radius)

    private fun assignment(
        id: String = "a1",
        geofenceId: String = "g1",
        trackedDeviceId: String = "b".repeat(64),
        triggerOn: String = "BOTH",
        scheduleRules: String = "[]"
    ) = GeofenceAssignmentBackup(id, geofenceId, trackedDeviceId, triggerOn, true, scheduleRules)

    @Test
    fun `valid area is kept with trimmed id and name`() {
        val out = BackupValidation.geofence(area(id = " g1 ", name = "  Home  "))
        assertNotNull(out)
        assertEquals("g1", out!!.id)
        assertEquals("Home", out.name)
    }

    @Test
    fun `area with impossible coordinates or radius is dropped`() {
        assertNull(BackupValidation.geofence(area(lat = 91.0)))
        assertNull(BackupValidation.geofence(area(lat = -90.5)))
        assertNull(BackupValidation.geofence(area(lng = 180.1)))
        assertNull(BackupValidation.geofence(area(lat = Double.NaN)))
        assertNull(BackupValidation.geofence(area(radius = 0)))
        assertNull(BackupValidation.geofence(area(radius = -500)))
        assertNull(BackupValidation.geofence(area(radius = 50_001)))
        assertNotNull(BackupValidation.geofence(area(radius = 50)))
        assertNotNull(BackupValidation.geofence(area(radius = 50_000)))
        assertNull(BackupValidation.geofence(area(id = "   ")))
    }

    @Test
    fun `assignment needs an existing area and a real pubkey`() {
        val known = setOf("g1")
        assertNotNull(BackupValidation.assignment(assignment(), known))
        assertNull(BackupValidation.assignment(assignment(geofenceId = "missing"), known))
        assertNull(BackupValidation.assignment(assignment(trackedDeviceId = "not-a-key"), known))
        assertNull(BackupValidation.assignment(assignment(trackedDeviceId = "b".repeat(65)), known))
    }

    @Test
    fun `trigger and device id are normalised, unknown trigger is dropped`() {
        val out = BackupValidation.assignment(
            assignment(triggerOn = " both ", trackedDeviceId = "B".repeat(64)),
            setOf("g1")
        )
        assertNotNull(out)
        assertEquals("BOTH", out!!.triggerOn)
        assertEquals("b".repeat(64), out.trackedDeviceId)
        assertNull(BackupValidation.assignment(assignment(triggerOn = "ALWAYS"), setOf("g1")))
    }

    @Test
    fun `malformed schedule json becomes always-active`() {
        assertEquals("[]", BackupValidation.scheduleRules("{not json"))
        assertEquals("[]", BackupValidation.scheduleRules(null))
        assertEquals("[]", BackupValidation.scheduleRules(""))
        // A readable rule list survives (re-encoded), so real schedules are not discarded.
        val encoded = BackupValidation.scheduleRules(
            """[{"id":"r1","label":"Work","days":30,"startMinute":60,"endMinute":120}]"""
        )
        val rules = Json.decodeFromString(
            kotlinx.serialization.builtins.ListSerializer(ScheduleRule.serializer()),
            encoded
        )
        assertEquals(1, rules.size)
        assertEquals(30, rules.single().days)
    }

    @Test
    fun `unknown precision falls back to the coarser suburb mode`() {
        assertEquals(PrecisionMode.EXACT.name, BackupValidation.precisionMode("exact"))
        assertEquals(PrecisionMode.SUBURB.name, BackupValidation.precisionMode("SUBURB"))
        assertEquals(PrecisionMode.SUBURB.name, BackupValidation.precisionMode("SOMETHING_NEW"))
        assertEquals(PrecisionMode.SUBURB.name, BackupValidation.precisionMode(""))
    }

    @Test
    fun `unknown location role grants no sharing`() {
        assertEquals(PeerEntity.ROLE_SEND_RECEIVE, BackupValidation.locationRole("send_receive"))
        assertEquals(PeerEntity.ROLE_NONE, BackupValidation.locationRole("ADMIN"))
        assertEquals(PeerEntity.ROLE_NONE, BackupValidation.locationRole(""))
    }
}
