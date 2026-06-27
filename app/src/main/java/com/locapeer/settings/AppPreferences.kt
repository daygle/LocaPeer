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
    val heartbeatEnabled: Boolean = false,
    val stationaryIntervalMinutes: Int = 15,
    val walkingIntervalMinutes: Int = 5,
    val runningIntervalMinutes: Int = 2,
    val cyclingIntervalMinutes: Int = 3,
    val drivingIntervalMinutes: Int = 2,
    val lowBatteryIntervalMinutes: Int = 30,
    val onboardingComplete: Boolean = false,
    val globalScheduleEnabled: Boolean = false,
    /** Bitmask: bit 0 = Monday … bit 6 = Sunday. Default = all days (127). */
    val globalScheduleDays: Int = 0b1111111,
    val globalScheduleStartMinute: Int = 0,
    val globalScheduleEndMinute: Int = 1439,
    val retentionDays: Int = 30,
    val messageRetentionDays: Int = 0,
    val supervisedModeEnabled: Boolean = false,
    val supervisorPubkey: String = "",
    val customRelays: List<String> = listOf("wss://relay.daygle.net", "wss://relay.damus.io")
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
    private val KEY_GLOBAL_SCHEDULE_ENABLED = booleanPreferencesKey("global_schedule_enabled")
    private val KEY_GLOBAL_SCHEDULE_DAYS = intPreferencesKey("global_schedule_days")
    private val KEY_GLOBAL_SCHEDULE_START = intPreferencesKey("global_schedule_start")
    private val KEY_GLOBAL_SCHEDULE_END = intPreferencesKey("global_schedule_end")
    private val KEY_RETENTION_DAYS = intPreferencesKey("retention_days")
    private val KEY_MSG_RETENTION_DAYS = intPreferencesKey("msg_retention_days")
    private val KEY_SUPERVISED_MODE = booleanPreferencesKey("supervised_mode")
    private val KEY_SUPERVISOR_PUBKEY = stringPreferencesKey("supervisor_pubkey")
    private val KEY_CUSTOM_RELAYS = stringPreferencesKey("custom_relays")

    val settings: Flow<AppSettings> = context.settingsStore.data.map { prefs ->
        val relayString = prefs[KEY_CUSTOM_RELAYS] ?: "wss://relay.daygle.net,wss://relay.damus.io"
        val relays = relayString.split(",").filter { it.isNotBlank() }

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
            globalScheduleEnabled = prefs[KEY_GLOBAL_SCHEDULE_ENABLED] ?: false,
            globalScheduleDays = prefs[KEY_GLOBAL_SCHEDULE_DAYS] ?: 0b1111111,
            globalScheduleStartMinute = prefs[KEY_GLOBAL_SCHEDULE_START] ?: 0,
            globalScheduleEndMinute = prefs[KEY_GLOBAL_SCHEDULE_END] ?: 1439,
            retentionDays = prefs[KEY_RETENTION_DAYS] ?: 30,
            messageRetentionDays = prefs[KEY_MSG_RETENTION_DAYS] ?: 0,
            supervisedModeEnabled = prefs[KEY_SUPERVISED_MODE] ?: false,
            supervisorPubkey = prefs[KEY_SUPERVISOR_PUBKEY] ?: "",
            customRelays = relays
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

    suspend fun setRetentionDays(days: Int) {
        context.settingsStore.edit { it[KEY_RETENTION_DAYS] = days }
    }

    suspend fun setMessageRetentionDays(days: Int) {
        context.settingsStore.edit { it[KEY_MSG_RETENTION_DAYS] = days }
    }

    suspend fun setSupervisedMode(enabled: Boolean, supervisorPubkey: String) {
        context.settingsStore.edit {
            it[KEY_SUPERVISED_MODE] = enabled
            it[KEY_SUPERVISOR_PUBKEY] = supervisorPubkey
        }
    }

    suspend fun clearSupervisedMode() {
        context.settingsStore.edit {
            it[KEY_SUPERVISED_MODE] = false
            it[KEY_SUPERVISOR_PUBKEY] = ""
        }
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

    suspend fun addRelay(url: String) {
        context.settingsStore.edit { prefs ->
            val current = prefs[KEY_CUSTOM_RELAYS] ?: "wss://relay.daygle.net,wss://relay.damus.io"
            val list = current.split(",").toMutableList()
            if (!list.contains(url)) {
                list.add(url)
                prefs[KEY_CUSTOM_RELAYS] = list.filter { it.isNotBlank() }.joinToString(",")
            }
        }
    }

    suspend fun removeRelay(url: String) {
        context.settingsStore.edit { prefs ->
            val current = prefs[KEY_CUSTOM_RELAYS] ?: "wss://relay.daygle.net,wss://relay.damus.io"
            val list = current.split(",").toMutableList()
            list.remove(url)
            prefs[KEY_CUSTOM_RELAYS] = list.filter { it.isNotBlank() }.joinToString(",")
        }
    }
}
