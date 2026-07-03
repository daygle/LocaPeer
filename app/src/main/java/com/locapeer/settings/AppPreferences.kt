package com.locapeer.settings

import android.content.Context
import android.util.Log
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.doublePreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.locapeer.sharing.ScheduleRule
import com.locapeer.sharing.toScheduleRules
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.io.IOException
import javax.inject.Inject
import javax.inject.Singleton

private val Context.settingsStore by preferencesDataStore(name = "locapeer_settings")
private const val TAG = "AppPreferences"

val HARDCODED_RELAYS = listOf(
    "wss://relay.daygle.net",
    "wss://nos.lol",
    "wss://relay.damus.io",
    "wss://relay.snort.social"
)

data class AppSettings(
    val displayName: String = "",
    val heartbeatEnabled: Boolean = true,
    val stationaryIntervalMinutes: Int = 15,
    val walkingIntervalMinutes: Int = 5,
    val runningIntervalMinutes: Int = 2,
    val cyclingIntervalMinutes: Int = 3,
    val drivingIntervalMinutes: Int = 2,
    val lowBatteryIntervalMinutes: Int = 30,
    val onboardingComplete: Boolean = false,
    /** Empty list = always on. Active when any rule matches the current time. */
    val globalScheduleRules: List<ScheduleRule> = emptyList(),
    /** How long to keep received location data on this device (0 = forever). */
    val localLocationRetentionDays: Int = 90,
    /** How long to keep messages on this device (0 = forever). */
    val localMessageRetentionDays: Int = 90,
    /**
     * Hide history points closer than this to the previously shown one (metres).
     * Display-time filter only — every ping is still stored. 0 = show all.
     */
    val historyMinDistanceMeters: Int = 0,
    val supervisedModeEnabled: Boolean = false,
    val supervisorPubkey: String = "",
    val customRelays: List<String> = HARDCODED_RELAYS,
    /** Ordered list of bottom-nav tab IDs the user has chosen to show. */
    val navTabIds: List<String> = listOf("map", "messages", "history-tab", "contacts", "invite", "settings"),
    /** Route shown when the app first opens. Must be one of the active navTabIds. */
    val startRoute: String = "map",
    /** Hex colour string for the user's own map pin (e.g. "#1565C0"). Empty = auto from name. */
    val pinColor: String = "",
    /** Persisted SOS state so it survives process death while HeartbeatService is sticky. */
    val sosActive: Boolean = false,
    /** Default zoom level (3–18) applied for all starting-point modes except Remember last position. */
    val mapStartZoom: Double = 16.0,
    /**
     * How the map centres on open.
     * "OWN_PIN" (default), "RESTORE_LAST", "FIT_ALL", "FIXED_LOCATION".
     */
    val mapStartingPoint: String = "OWN_PIN",
    val mapFixedLat: Double = 0.0,
    val mapFixedLng: Double = 0.0
)

@Singleton
class AppPreferences @Inject constructor(
    @ApplicationContext private val context: Context
) {
    private val KEY_DISPLAY_NAME = stringPreferencesKey("display_name")
    private val KEY_HEARTBEAT_ENABLED = booleanPreferencesKey("heartbeat_enabled")
    private val KEY_STATIONARY_INTERVAL = intPreferencesKey("stationary_interval")
    private val KEY_WALKING_INTERVAL = intPreferencesKey("walking_interval")
    private val KEY_RUNNING_INTERVAL = intPreferencesKey("running_interval")
    private val KEY_CYCLING_INTERVAL = intPreferencesKey("cycling_interval")
    private val KEY_DRIVING_INTERVAL = intPreferencesKey("driving_interval")
    private val KEY_LOW_BATTERY_INTERVAL = intPreferencesKey("low_battery_interval")
    private val KEY_ONBOARDING_COMPLETE = booleanPreferencesKey("onboarding_complete")
    private val KEY_GLOBAL_SCHEDULE_RULES = stringPreferencesKey("global_schedule_rules")
    private val KEY_SUPERVISED_MODE = booleanPreferencesKey("supervised_mode")
    private val KEY_SUPERVISOR_PUBKEY = stringPreferencesKey("supervisor_pubkey")
    private val KEY_NAV_TAB_IDS = stringPreferencesKey("nav_tab_ids")
    private val KEY_START_ROUTE = stringPreferencesKey("start_route")
    private val KEY_LOCAL_LOCATION_RETENTION = intPreferencesKey("local_location_retention_days")
    private val KEY_LOCAL_MESSAGE_RETENTION = intPreferencesKey("local_message_retention_days")
    private val KEY_HISTORY_MIN_DISTANCE = intPreferencesKey("history_min_distance_m")
    private val KEY_PIN_COLOR = stringPreferencesKey("pin_color")
    private val KEY_SOS_ACTIVE = booleanPreferencesKey("sos_active")
    private val KEY_CUSTOM_RELAYS = stringPreferencesKey("custom_relays")
    private val KEY_LAST_CONTROL_SUB_EPOCH = longPreferencesKey("last_control_sub_epoch")
    private val KEY_LAST_HEARTBEAT_SUB_EPOCH = longPreferencesKey("last_heartbeat_sub_epoch")
    private val KEY_MAP_START_ZOOM = doublePreferencesKey("map_start_zoom")
    private val KEY_MAP_STARTING_POINT = stringPreferencesKey("map_starting_point")
    private val KEY_MAP_FIXED_LAT = doublePreferencesKey("map_fixed_lat")
    private val KEY_MAP_FIXED_LNG = doublePreferencesKey("map_fixed_lng")
    private val KEY_RECENT_EVENT_IDS = stringPreferencesKey("recent_event_ids")

    val settings: Flow<AppSettings> = context.settingsStore.data
        .catch { exception ->
            if (exception is IOException) {
                Log.e(TAG, "Error reading settings DataStore", exception)
                emit(emptyPreferences())
            } else {
                throw exception
            }
        }
        .map { prefs ->
            AppSettings(
                displayName = prefs[KEY_DISPLAY_NAME] ?: "",
                heartbeatEnabled = prefs[KEY_HEARTBEAT_ENABLED] ?: true,
                stationaryIntervalMinutes = prefs[KEY_STATIONARY_INTERVAL] ?: 15,
                walkingIntervalMinutes = prefs[KEY_WALKING_INTERVAL] ?: 5,
                runningIntervalMinutes = prefs[KEY_RUNNING_INTERVAL] ?: 2,
                cyclingIntervalMinutes = prefs[KEY_CYCLING_INTERVAL] ?: 3,
                drivingIntervalMinutes = prefs[KEY_DRIVING_INTERVAL] ?: 2,
                lowBatteryIntervalMinutes = prefs[KEY_LOW_BATTERY_INTERVAL] ?: 30,
                onboardingComplete = prefs[KEY_ONBOARDING_COMPLETE] ?: false,
                globalScheduleRules = prefs[KEY_GLOBAL_SCHEDULE_RULES]?.toScheduleRules() ?: emptyList(),
                supervisedModeEnabled = prefs[KEY_SUPERVISED_MODE] ?: false,
                supervisorPubkey = prefs[KEY_SUPERVISOR_PUBKEY] ?: "",
                navTabIds = prefs[KEY_NAV_TAB_IDS]
                    ?.split(",")
                    ?.filter { it.isNotBlank() }
                    ?.takeIf { it.size >= 2 }
                    ?: listOf("map", "messages", "history-tab", "contacts", "invite", "settings"),
                startRoute = prefs[KEY_START_ROUTE] ?: "map",
                localLocationRetentionDays = prefs[KEY_LOCAL_LOCATION_RETENTION] ?: 90,
                localMessageRetentionDays = prefs[KEY_LOCAL_MESSAGE_RETENTION] ?: 90,
                historyMinDistanceMeters = prefs[KEY_HISTORY_MIN_DISTANCE] ?: 0,
                pinColor = prefs[KEY_PIN_COLOR] ?: "",
                sosActive = prefs[KEY_SOS_ACTIVE] ?: false,
                customRelays = prefs[KEY_CUSTOM_RELAYS]?.split(",")?.filter { it.isNotBlank() } ?: HARDCODED_RELAYS,
                mapStartZoom = prefs[KEY_MAP_START_ZOOM] ?: 16.0,
                mapStartingPoint = prefs[KEY_MAP_STARTING_POINT] ?: "OWN_PIN",
                mapFixedLat = prefs[KEY_MAP_FIXED_LAT] ?: 0.0,
                mapFixedLng = prefs[KEY_MAP_FIXED_LNG] ?: 0.0
            )
        }

    suspend fun updateDisplayName(name: String) {
        context.settingsStore.edit { it[KEY_DISPLAY_NAME] = name }
    }

    suspend fun setHeartbeatEnabled(enabled: Boolean) {
        context.settingsStore.edit { it[KEY_HEARTBEAT_ENABLED] = enabled }
    }

    suspend fun setOnboardingComplete(complete: Boolean) {
        context.settingsStore.edit { it[KEY_ONBOARDING_COMPLETE] = complete }
    }

    suspend fun setSupervisedMode(enabled: Boolean, supervisorPubkey: String) {
        context.settingsStore.edit {
            it[KEY_SUPERVISED_MODE] = enabled
            it[KEY_SUPERVISOR_PUBKEY] = supervisorPubkey
        }
    }

    suspend fun setNavTabIds(ids: List<String>) {
        context.settingsStore.edit { it[KEY_NAV_TAB_IDS] = ids.joinToString(",") }
    }

    suspend fun setStartRoute(route: String) {
        context.settingsStore.edit { it[KEY_START_ROUTE] = route }
    }

    suspend fun setLocalLocationRetentionDays(days: Int) {
        context.settingsStore.edit { it[KEY_LOCAL_LOCATION_RETENTION] = days }
    }

    suspend fun setLocalMessageRetentionDays(days: Int) {
        context.settingsStore.edit { it[KEY_LOCAL_MESSAGE_RETENTION] = days }
    }

    suspend fun setHistoryMinDistanceMeters(meters: Int) {
        context.settingsStore.edit { it[KEY_HISTORY_MIN_DISTANCE] = meters }
    }

    suspend fun setPinColor(hex: String) {
        context.settingsStore.edit { it[KEY_PIN_COLOR] = hex }
    }

    suspend fun setCustomRelays(relays: List<String>) {
        context.settingsStore.edit { it[KEY_CUSTOM_RELAYS] = relays.joinToString(",") }
    }

    suspend fun setSosActive(active: Boolean) {
        context.settingsStore.edit { it[KEY_SOS_ACTIVE] = active }
    }

    suspend fun setMapStartZoom(zoom: Double) {
        context.settingsStore.edit { it[KEY_MAP_START_ZOOM] = zoom }
    }

    suspend fun setMapStartingPoint(mode: String) {
        context.settingsStore.edit { it[KEY_MAP_STARTING_POINT] = mode }
    }

    suspend fun setMapFixedLocation(lat: Double, lng: Double) {
        context.settingsStore.edit {
            it[KEY_MAP_FIXED_LAT] = lat
            it[KEY_MAP_FIXED_LNG] = lng
        }
    }

    /** Returns the epoch second of the last successful control-event catch-up subscription start. */
    suspend fun getLastControlSubEpoch(): Long =
        context.settingsStore.data.first()[KEY_LAST_CONTROL_SUB_EPOCH] ?: 0L

    suspend fun setLastControlSubEpoch(epoch: Long) {
        context.settingsStore.edit { it[KEY_LAST_CONTROL_SUB_EPOCH] = epoch }
    }

    /** Returns the epoch second up to which peer heartbeats have been synced from relays. */
    suspend fun getLastHeartbeatSubEpoch(): Long =
        context.settingsStore.data.first()[KEY_LAST_HEARTBEAT_SUB_EPOCH] ?: 0L

    suspend fun setLastHeartbeatSubEpoch(epoch: Long) {
        context.settingsStore.edit { it[KEY_LAST_HEARTBEAT_SUB_EPOCH] = epoch }
    }

    suspend fun clearSupervisedMode() {
        context.settingsStore.edit {
            it[KEY_SUPERVISED_MODE] = false
            it[KEY_SUPERVISOR_PUBKEY] = ""
        }
    }

    suspend fun setGlobalScheduleRules(rules: List<ScheduleRule>) {
        context.settingsStore.edit { it[KEY_GLOBAL_SCHEDULE_RULES] = Json.encodeToString(rules) }
    }

    suspend fun getRecentEventIds(): Set<String> {
        val raw = context.settingsStore.data.first()[KEY_RECENT_EVENT_IDS] ?: return emptySet()
        return raw.split(",").filter { it.isNotBlank() }.toSet()
    }

    suspend fun saveRecentEventIds(ids: Set<String>) {
        val trimmed = ids.toList().takeLast(1000)
        context.settingsStore.edit { it[KEY_RECENT_EVENT_IDS] = trimmed.joinToString(",") }
    }

    suspend fun updateIntervals(
        stationary: Int? = null,
        walking: Int? = null,
        running: Int? = null,
        cycling: Int? = null,
        driving: Int? = null,
        lowBattery: Int? = null
    ) {
        context.settingsStore.edit { prefs ->
            stationary?.let { prefs[KEY_STATIONARY_INTERVAL] = it }
            walking?.let { prefs[KEY_WALKING_INTERVAL] = it }
            running?.let { prefs[KEY_RUNNING_INTERVAL] = it }
            cycling?.let { prefs[KEY_CYCLING_INTERVAL] = it }
            driving?.let { prefs[KEY_DRIVING_INTERVAL] = it }
            lowBattery?.let { prefs[KEY_LOW_BATTERY_INTERVAL] = it }
        }
    }
}
