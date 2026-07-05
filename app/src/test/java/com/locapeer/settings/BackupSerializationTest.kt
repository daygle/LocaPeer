package com.locapeer.settings

import com.locapeer.sharing.ScheduleRule
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Guards the backup export/import (import/export) round-trip: every field that is written must be
 * read back unchanged, and older backups that predate newly-added fields must still decode.
 */
class BackupSerializationTest {

    // Mirrors SettingsViewModel.jsonExport / jsonImport.
    private val jsonExport = Json { encodeDefaults = true }
    private val jsonImport = Json { ignoreUnknownKeys = true }

    private fun fullBackup() = LocaPeerBackup(
        privateKeyHex = "a".repeat(64),
        contacts = listOf(
            ContactBackup(
                deviceId = "dev1",
                displayName = "Alice",
                publicKeyHex = "b".repeat(64),
                relayUrl = "wss://relay.example",
                locationRole = "RECEIVE",
                messagingEnabled = false,
                addedAt = 1234L,
                isArchived = true,
                archivedAt = 5678L,
                sharingConfig = SharingConfigBackup(
                    sharingEnabled = false,
                    precisionMode = "SUBURB",
                    scheduleRulesJson = "[]",
                    isSosContact = true,
                    retentionDaysLocation = 7,
                    retentionDaysMessages = 14,
                    isMySupervised = true,
                    notifyOnMissedHeartbeat = true
                )
            )
        ),
        geofences = listOf(
            GeofenceBackup("g1", "Home", 51.5, -0.1, 150)
        ),
        geofenceAssignments = listOf(
            GeofenceAssignmentBackup("a1", "g1", "dev1", "BOTH", false)
        ),
        settings = SettingsBackup(
            displayName = "Me",
            stationaryIntervalMinutes = 20,
            walkingIntervalMinutes = 6,
            runningIntervalMinutes = 3,
            cyclingIntervalMinutes = 4,
            drivingIntervalMinutes = 2,
            lowBatteryIntervalMinutes = 45,
            navTabIds = listOf("map", "settings"),
            startRoute = "settings",
            pinColor = "#1565C0",
            localLocationRetentionDays = 30,
            localMessageRetentionDays = 60,
            historyMinDistanceMeters = 25,
            heartbeatEnabled = false,
            onboardingComplete = true,
            globalScheduleRules = listOf(ScheduleRule(id = "r1", label = "Work", days = 0b0011111)),
            customRelays = listOf("wss://relay.example"),
            // Non-default unit/format preferences so the round-trip actually exercises them.
            useImperialSpeed = true,
            use24HourTime = false,
            useImperialElevation = true,
            useImperialDistance = true
        )
    )

    @Test
    fun `full backup round-trips unchanged`() {
        val original = fullBackup()
        val json = jsonExport.encodeToString(original)
        val restored = jsonImport.decodeFromString<LocaPeerBackup>(json)
        assertEquals(original, restored)
    }

    @Test
    fun `archive state and per-contact prefs survive round-trip`() {
        val restored = jsonImport.decodeFromString<LocaPeerBackup>(
            jsonExport.encodeToString(fullBackup())
        )
        val contact = restored.contacts!!.single()
        assertEquals(true, contact.isArchived)
        assertEquals(5678L, contact.archivedAt)
        val cfg = contact.sharingConfig!!
        assertEquals(true, cfg.isMySupervised)
        assertEquals(true, cfg.notifyOnMissedHeartbeat)
    }

    @Test
    fun `unselected sections stay null so they are not restored`() {
        val json = jsonExport.encodeToString(
            LocaPeerBackup(privateKeyHex = "c".repeat(64))
        )
        val restored = jsonImport.decodeFromString<LocaPeerBackup>(json)
        assertNull(restored.contacts)
        assertNull(restored.geofences)
        assertNull(restored.settings)
    }

    @Test
    fun `older backup without newly-added fields still decodes with defaults`() {
        // A v2 backup produced before isArchived / isMySupervised / notifyOnMissedHeartbeat existed.
        val legacyJson = """
            {
              "version": 2,
              "contacts": [
                {
                  "deviceId": "dev1",
                  "displayName": "Alice",
                  "publicKeyHex": "${"b".repeat(64)}",
                  "relayUrl": "wss://relay.example",
                  "sharingConfig": {
                    "sharingEnabled": true,
                    "precisionMode": "EXACT",
                    "scheduleRulesJson": "[]",
                    "isSosContact": false,
                    "retentionDaysLocation": 30,
                    "retentionDaysMessages": 0
                  }
                }
              ]
            }
        """.trimIndent()

        val restored = jsonImport.decodeFromString<LocaPeerBackup>(legacyJson)
        val contact = restored.contacts!!.single()
        assertEquals(false, contact.isArchived)
        assertEquals(0L, contact.archivedAt)
        val cfg = contact.sharingConfig!!
        assertEquals(false, cfg.isMySupervised)
        assertEquals(false, cfg.notifyOnMissedHeartbeat)
    }
}
