package com.locapeer.settings

import android.content.Context
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import javax.inject.Inject
import javax.inject.Singleton

private val Context.settingsStore by preferencesDataStore(name = "locapeer_settings")

data class AppSettings(
    val displayName: String = "",
    val relayUrl: String = "wss://relay.damus.io",
    val heartbeatEnabled: Boolean = false,
    val stationaryIntervalMinutes: Int = 15,
    val walkingIntervalMinutes: Int = 5,
    val drivingIntervalMinutes: Int = 2,
    val lowBatteryIntervalMinutes: Int = 30,
    val onboardingComplete: Boolean = false,
    val globalScheduleEnabled: Boolean = false,
    /** Bitmask: bit 0 = Monday … bit 6 = Sunday. Default = all days (127). */
    val globalScheduleDays: Int = 0b1111111,
    val globalScheduleStartMinute: Int = 0,
    val globalScheduleEndMinute: Int = 1439,
    /**
     * How many days subscribers should keep this device's location history.
     * 0 = no automatic deletion (keep forever).
     */
    val retentionDays: Int = 30,
    /**
     * How many days peers should keep messages sent by this device.
     * 0 = no automatic deletion (keep forever).
     */
    val messageRetentionDays: Int = 0
)

@Singleton
class AppPreferences @Inject constructor(
    @ApplicationContext private val context: Context
) {
    private val KEY_DISPLAY_NAME = stringPreferencesKey("display_name")
    private val KEY_RELAY_URL = stringPreferencesKey("relay_url")
    private val KEY_HEARTBEAT_ENABLED = booleanPreferencesKey("heartbeat_enabled")
    private val KEY_STATIONARY_INTERVAL = intPreferencesKey("stationary_interval")
    private val KEY_WALKING_INTERVAL = intPreferencesKey("walking_interval")
    private val KEY_DRIVING_INTERVAL = intPreferencesKey("driving_interval")
    private val KEY_LOW_BATTERY_INTERVAL = intPreferencesKey("low_battery_interval")
    private val KEY_ONBOARDING_COMPLETE = booleanPreferencesKey("onboarding_complete")
    private val KEY_GLOBAL_SCHEDULE_ENABLED = booleanPreferencesKey("global_schedule_enabled")
    private val KEY_GLOBAL_SCHEDULE_DAYS = intPreferencesKey("global_schedule_days")
    private val KEY_GLOBAL_SCHEDULE_START = intPreferencesKey("global_schedule_start")
    private val KEY_GLOBAL_SCHEDULE_END = intPreferencesKey("global_schedule_end")
    private val KEY_RETENTION_DAYS = intPreferencesKey("retention_days")
    private val KEY_MSG_RETENTION_DAYS = intPreferencesKey("msg_retention_days")

    val settings: Flow<AppSettings> = context.settingsStore.data.map { prefs ->
        AppSettings(
            displayName = prefs[KEY_DISPLAY_NAME] ?: "",
            relayUrl = prefs[KEY_RELAY_URL] ?: "wss://relay.damus.io",
            heartbeatEnabled = prefs[KEY_HEARTBEAT_ENABLED] ?: false,
            stationaryIntervalMinutes = prefs[KEY_STATIONARY_INTERVAL] ?: 15,
            walkingIntervalMinutes = prefs[KEY_WALKING_INTERVAL] ?: 5,
            drivingIntervalMinutes = prefs[KEY_DRIVING_INTERVAL] ?: 2,
            lowBatteryIntervalMinutes = prefs[KEY_LOW_BATTERY_INTERVAL] ?: 30,
            onboardingComplete = prefs[KEY_ONBOARDING_COMPLETE] ?: false,
            globalScheduleEnabled = prefs[KEY_GLOBAL_SCHEDULE_ENABLED] ?: false,
            globalScheduleDays = prefs[KEY_GLOBAL_SCHEDULE_DAYS] ?: 0b1111111,
            globalScheduleStartMinute = prefs[KEY_GLOBAL_SCHEDULE_START] ?: 0,
            globalScheduleEndMinute = prefs[KEY_GLOBAL_SCHEDULE_END] ?: 1439,
            retentionDays = prefs[KEY_RETENTION_DAYS] ?: 30,
            messageRetentionDays = prefs[KEY_MSG_RETENTION_DAYS] ?: 0
        )
    }

    suspend fun updateDisplayName(name: String) {
        context.settingsStore.edit { it[KEY_DISPLAY_NAME] = name }
    }

    suspend fun updateRelayUrl(url: String) {
        context.settingsStore.edit { it[KEY_RELAY_URL] = url }
    }

    suspend fun setHeartbeatEnabled(enabled: Boolean) {
        context.settingsStore.edit { it[KEY_HEARTBEAT_ENABLED] = enabled }
    }

    suspend fun setOnboardingComplete(complete: Boolean) {
        context.settingsStore.edit { it[KEY_ONBOARDING_COMPLETE] = complete }
    }

    suspend fun setRetentionDays(days: Int) {
        context.settingsStore.edit { it[KEY_RETENTION_DAYS] = days }
    }

    suspend fun setMessageRetentionDays(days: Int) {
        context.settingsStore.edit { it[KEY_MSG_RETENTION_DAYS] = days }
    }

    suspend fun setGlobalScheduleEnabled(enabled: Boolean) {
        context.settingsStore.edit { it[KEY_GLOBAL_SCHEDULE_ENABLED] = enabled }
    }

    suspend fun updateGlobalSchedule(
        days: Int? = null,
        startMinute: Int? = null,
        endMinute: Int? = null
    ) {
        context.settingsStore.edit { prefs ->
            days?.let { prefs[KEY_GLOBAL_SCHEDULE_DAYS] = it }
            startMinute?.let { prefs[KEY_GLOBAL_SCHEDULE_START] = it }
            endMinute?.let { prefs[KEY_GLOBAL_SCHEDULE_END] = it }
        }
    }

    suspend fun updateIntervals(
        stationary: Int? = null,
        walking: Int? = null,
        driving: Int? = null,
        lowBattery: Int? = null
    ) {
        context.settingsStore.edit { prefs ->
            stationary?.let { prefs[KEY_STATIONARY_INTERVAL] = it }
            walking?.let { prefs[KEY_WALKING_INTERVAL] = it }
            driving?.let { prefs[KEY_DRIVING_INTERVAL] = it }
            lowBattery?.let { prefs[KEY_LOW_BATTERY_INTERVAL] = it }
        }
    }
}
