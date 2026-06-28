package com.locapeer.settings

import android.content.Context
import android.util.Log
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.locapeer.sharing.ScheduleRule
import com.locapeer.sharing.toScheduleRules
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.map
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.io.IOException
import javax.inject.Inject
import javax.inject.Singleton

private val Context.settingsStore by preferencesDataStore(name = "locapeer_settings")
private const val TAG = "AppPreferences"

val HARDCODED_RELAYS = listOf("wss://relay.daygle.net", "wss://relay.damus.io")

data class AppSettings(
    val displayName: String = "",
    val heartbeatEnabled: Boolean = false,
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
    val supervisedModeEnabled: Boolean = false,
    val supervisorPubkey: String = "",
    val customRelays: List<String> = HARDCODED_RELAYS,
    /** Ordered list of bottom-nav tab IDs the user has chosen to show. */
    val navTabIds: List<String> = listOf("map", "messages", "contacts", "invite", "settings"),
    /** Route shown when the app first opens. Must be one of the active navTabIds. */
    val startRoute: String = "map"
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
                heartbeatEnabled = prefs[KEY_HEARTBEAT_ENABLED] ?: false,
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
                    ?: listOf("map", "messages", "contacts", "invite", "settings"),
                startRoute = prefs[KEY_START_ROUTE] ?: "map",
                localLocationRetentionDays = prefs[KEY_LOCAL_LOCATION_RETENTION] ?: 90,
                localMessageRetentionDays = prefs[KEY_LOCAL_MESSAGE_RETENTION] ?: 90
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

    suspend fun clearSupervisedMode() {
        context.settingsStore.edit {
            it[KEY_SUPERVISED_MODE] = false
            it[KEY_SUPERVISOR_PUBKEY] = ""
        }
    }

    suspend fun setGlobalScheduleRules(rules: List<ScheduleRule>) {
        context.settingsStore.edit { it[KEY_GLOBAL_SCHEDULE_RULES] = Json.encodeToString(rules) }
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
