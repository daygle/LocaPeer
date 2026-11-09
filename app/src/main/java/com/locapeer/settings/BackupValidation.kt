package com.locapeer.settings

import com.locapeer.data.entity.PeerEntity
import com.locapeer.data.entity.PrecisionMode
import com.locapeer.sharing.ScheduleRule
import kotlinx.serialization.json.Json

/**
 * Validation and normalisation for values read out of a backup file.
 *
 * A backup is untrusted input: it can be hand-edited, produced by another app version, or
 * corrupted in transit. Restore writes those values straight into Room, so impossible ones
 * (a fence with a negative radius, a contact with a made-up role, schedule JSON that would
 * throw inside the alert engine) have to be rejected or normalised here rather than left to
 * fail somewhere without a user-visible error.
 */
internal object BackupValidation {

    /** Same boundaries the geofence editor enforces, so a restored area behaves like one drawn in the app. */
    const val MIN_GEOFENCE_RADIUS_M = 50
    const val MAX_GEOFENCE_RADIUS_M = 50_000

    private const val MAX_ID_CHARS = 128
    private const val MAX_NAME_CHARS = 100
    /** Schedule rules are a handful of entries; anything larger is not a real schedule. */
    private const val MAX_SCHEDULE_JSON_CHARS = 8_192

    private const val TRIGGER_ENTER = "ENTER"
    private const val TRIGGER_EXIT = "EXIT"
    private const val TRIGGER_BOTH = "BOTH"
    private val TRIGGERS = setOf(TRIGGER_ENTER, TRIGGER_EXIT, TRIGGER_BOTH)
    private val PUBKEY_REGEX = Regex("^[0-9a-f]{64}$")
    private val json = Json { ignoreUnknownKeys = true }

    /**
     * An area that can actually be evaluated, with trimmed id/name. Returns null for anything
     * the geofence engine could not use: non-finite or out-of-range coordinates (which would
     * make every distance meaningless) and a radius outside the editor's range (a zero or
     * negative radius can never contain anyone, an enormous one contains everyone).
     */
    fun geofence(backup: GeofenceBackup): GeofenceBackup? {
        val id = backup.id.trim()
        if (id.isEmpty() || id.length > MAX_ID_CHARS) return null
        if (!backup.lat.isFinite() || backup.lat !in -90.0..90.0) return null
        if (!backup.lng.isFinite() || backup.lng !in -180.0..180.0) return null
        if (backup.radiusMetres !in MIN_GEOFENCE_RADIUS_M..MAX_GEOFENCE_RADIUS_M) return null
        return backup.copy(id = id, name = backup.name.trim().take(MAX_NAME_CHARS))
    }

    /**
     * An assignment whose area exists and whose contact is a real pubkey. [knownGeofenceIds]
     * must include both restored and already-present areas, since restore merges rather than
     * replaces. Returns null for orphans, malformed device ids, and unknown triggers - the
     * engine treats an unknown trigger as "never notify", so keeping it would silently
     * disable the fence.
     */
    fun assignment(
        backup: GeofenceAssignmentBackup,
        knownGeofenceIds: Set<String>
    ): GeofenceAssignmentBackup? {
        val id = backup.id.trim()
        val geofenceId = backup.geofenceId.trim()
        val trackedDeviceId = backup.trackedDeviceId.trim().lowercase()
        if (id.isEmpty() || id.length > MAX_ID_CHARS) return null
        if (geofenceId !in knownGeofenceIds) return null
        if (!PUBKEY_REGEX.matches(trackedDeviceId)) return null
        val triggerOn = backup.triggerOn.trim().uppercase()
        if (triggerOn !in TRIGGERS) return null
        return backup.copy(
            id = id,
            geofenceId = geofenceId,
            trackedDeviceId = trackedDeviceId,
            triggerOn = triggerOn,
            scheduleRules = scheduleRules(backup.scheduleRules)
        )
    }

    /**
     * Sharing precision for a restored contact. An unrecognised value falls back to SUBURB
     * (the coarser mode) rather than EXACT: a corrupted or forward-versioned backup must not
     * upgrade a contact from suburb-level to exact coordinates.
     */
    fun precisionMode(raw: String): String = when (raw.trim().uppercase()) {
        PrecisionMode.EXACT.name -> PrecisionMode.EXACT.name
        else -> PrecisionMode.SUBURB.name
    }

    /**
     * Location role for a restored contact. An unrecognised value becomes NONE (messaging
     * only), so a corrupt role can never grant location sharing the user did not configure.
     */
    fun locationRole(raw: String): String = when (val role = raw.trim().uppercase()) {
        PeerEntity.ROLE_NONE,
        PeerEntity.ROLE_RECEIVE,
        PeerEntity.ROLE_SEND,
        PeerEntity.ROLE_SEND_RECEIVE -> role
        else -> PeerEntity.ROLE_NONE
    }

    /**
     * Schedule rules re-encoded from [raw], or "[]" (always active) when the field is not a
     * readable rule list. Schedule JSON is parsed inside the geofence and proximity engines,
     * so a malformed restored value must not reach them.
     */
    fun scheduleRules(raw: String?): String {
        val text = raw?.trim().orEmpty()
        if (text.isEmpty()) return "[]"
        if (text.length > MAX_SCHEDULE_JSON_CHARS) return "[]"
        return try {
            val rules = json.decodeFromString(kotlinx.serialization.builtins.ListSerializer(ScheduleRule.serializer()), text)
            json.encodeToString(kotlinx.serialization.builtins.ListSerializer(ScheduleRule.serializer()), rules)
        } catch (_: Exception) {
            "[]"
        }
    }
}
