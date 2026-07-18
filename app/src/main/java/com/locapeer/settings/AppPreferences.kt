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
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.shareIn
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

/**
 * The app's own relay, always used regardless of the public-relay toggle - it is the one
 * relay we control and the safe default rendezvous. The remaining [HARDCODED_RELAYS] are
 * large public Nostr relays: encrypted payloads are safe on them, but the event envelope
 * (recipient `p` tags, kinds, timing) is not, so a privacy-conscious user can switch them
 * off via [AppSettings.usePublicRelays] and rely on the primary plus their own custom
 * relays. Peers' invite supplied relays are always honoured so contacts can still connect.
 */
val PRIMARY_RELAY: String = HARDCODED_RELAYS.first()
val PUBLIC_RELAYS: List<String> = HARDCODED_RELAYS.drop(1)

/**
 * Regions that display road speed in mph. Used to pick a sensible default speed unit before
 * the user has explicitly chosen one. Android exposes no system-wide unit preference, so this
 * is a best-effort guess from the device locale that the user can override in Settings.
 */
private val IMPERIAL_SPEED_REGIONS = setOf(
    "US", "GB", "MM", "LR", "AG", "BS", "BZ", "DM", "GD", "GU",
    "KN", "LC", "MH", "FM", "MP", "PW", "PR", "VC", "VG", "VI", "WS"
)

private fun localeDefaultImperial(): Boolean =
    java.util.Locale.getDefault().country.uppercase(java.util.Locale.ROOT) in IMPERIAL_SPEED_REGIONS

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
     * Display-time filter only - every ping is still stored. 0 = show all.
     */
    val historyMinDistanceMeters: Int = 0,
    /**
     * Hide history points whose accuracy radius is larger than this (metres).
     * Display-time filter only - every ping is still stored, so lowering the
     * threshold re-reveals them. SOS pings are always shown. 0 = show all.
     */
    val historyMaxAccuracyMeters: Int = 0,
    /**
     * Don't broadcast or record own fixes whose accuracy radius is larger than
     * this (metres). A sender-side quality gate applied before storage and
     * transmission. SOS is never gated. 0 = broadcast every fix.
     */
    val sendMaxAccuracyMeters: Int = 0,
    val supervisedModeEnabled: Boolean = false,
    val supervisorPubkey: String = "",
    /** Ordered list of bottom-nav tab IDs the user has chosen to show. */
    val navTabIds: List<String> = listOf("map", "messages", "history-tab", "contacts", "invite", "settings"),
    /** Route shown when the app first opens. Must be one of the active navTabIds. */
    val startRoute: String = "map",
    /** Hex colour string for the user's own map pin (e.g. "#1565C0"). Empty = auto from name. */
    val pinColor: String = "",
    /** Persisted SOS state so it survives process death while HeartbeatService is sticky. */
    val sosActive: Boolean = false,
    /** Default zoom level (3-18) applied for all starting-point modes except Remember last position. */
    val mapStartZoom: Double = 16.0,
    /**
     * How the map centres on open.
     * "OWN_PIN" (default), "RESTORE_LAST", "FIT_ALL", "FIXED_LOCATION".
     */
    val mapStartingPoint: String = "OWN_PIN",
    val mapFixedLat: Double = 0.0,
    val mapFixedLng: Double = 0.0,
    /** Show speeds in mph instead of km/h. Defaults from the device locale. */
    val useImperialSpeed: Boolean = false,
    /** Use a 24-hour clock for displayed times. Defaults from the device's clock setting. */
    val use24HourTime: Boolean = true,
    /** Show elevation in feet instead of metres. Defaults from the device locale. */
    val useImperialElevation: Boolean = false,
    /** Show distances (accuracy, radii) in feet/miles instead of metres/km. Defaults from locale. */
    val useImperialDistance: Boolean = false,
    /** Notify this user when someone else gets an alert (proximity/geofence) about them. */
    val notifyOnTrackingAlerts: Boolean = false,
    /** Draw geofence circles on the map. Off by default so the map stays uncluttered. */
    val showGeofencesOnMap: Boolean = false,
    /**
     * Look up street addresses for history points, and search by address in the geofence
     * editor, via the device geocoder. Off by default: the platform Geocoder sends the
     * queried coordinates or typed address to the OS geocoding backend (Google on most
     * devices), which is at odds with the app's relay-only design, so it must be an
     * explicit, informed opt-in.
     */
    val reverseGeocodingEnabled: Boolean = false,
    /**
     * Required at next app-start (and after [appLockTimeoutSeconds] of background time)
     * biometric / device-credential prompt before the app contents are revealed.
     * [appLockTimeoutSeconds] == 0 means relock immediately on background; the value
     * choice is exposed as Immediate / 30s / 1m / 5m in Settings.
     */
    val appLockEnabled: Boolean = false,
    /** 0 = immediate on backgrounding. Positive seconds = grace period before relock. */
    val appLockTimeoutSeconds: Int = 0,
    /** App appearance: "SYSTEM" (follow OS), "LIGHT", "DARK". */
    val themeMode: String = "SYSTEM",
    /** Use Material You / dynamic color on Android 12+. Independent of [themeMode]. */
    val useDynamicColor: Boolean = true,
    /**
     * Set of relay URLs (built-in or custom) that the user has explicitly disabled.
     * If a URL is not in this set, it is considered enabled.
     */
    val disabledRelayUrls: Set<String> = emptySet(),
    /**
     * Extra relay URLs (wss://) the user added. Combined with the hardcoded
     * [HARDCODED_RELAYS] and peers' invite relays to form the live connection set.
     */
    val customRelays: List<String> = emptyList(),
    /**
     * If true, speed up own broadcasts when a contact is watching the map.
     */
    val allowLiveBoost: Boolean = true,
    /**
     * If true, ask contacts for faster updates when viewing them on the map.
     */
    val requestLiveBoost: Boolean = true
)

enum class ThemeMode { SYSTEM, LIGHT, DARK }

@Singleton
class AppPreferences @Inject constructor(
    @ApplicationContext private val context: Context
) {
    // Deserializing AppSettings (30+ fields, plus CSV/JSON parsing) is done once here on a
    // background dispatcher and shared, instead of re-running for every collector on its own
    // (often main) thread. At app start a dozen+ collectors - Compose, several ViewModels,
    // the Application - subscribe to `settings`; sharing keeps that off the main thread and
    // collapses the redundant work into a single upstream, cutting cold-start jank.
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

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
    private val KEY_HISTORY_MAX_ACCURACY = intPreferencesKey("history_max_accuracy_m")
    private val KEY_SEND_MAX_ACCURACY = intPreferencesKey("send_max_accuracy_m")
    private val KEY_PIN_COLOR = stringPreferencesKey("pin_color")
    private val KEY_SOS_ACTIVE = booleanPreferencesKey("sos_active")
    private val KEY_LAST_CONTROL_SUB_EPOCH = longPreferencesKey("last_control_sub_epoch")
    private val KEY_LAST_HEARTBEAT_SUB_EPOCH = longPreferencesKey("last_heartbeat_sub_epoch")
    private val KEY_MAP_START_ZOOM = doublePreferencesKey("map_start_zoom")
    private val KEY_MAP_STARTING_POINT = stringPreferencesKey("map_starting_point")
    private val KEY_MAP_FIXED_LAT = doublePreferencesKey("map_fixed_lat")
    private val KEY_MAP_FIXED_LNG = doublePreferencesKey("map_fixed_lng")
    private val KEY_RECENT_EVENT_IDS = stringPreferencesKey("recent_event_ids")
    private val KEY_LEFT_CIRCLE_IDS = stringPreferencesKey("left_circle_ids")
    private val KEY_USE_IMPERIAL_SPEED = booleanPreferencesKey("use_imperial_speed")
    private val KEY_USE_24_HOUR_TIME = booleanPreferencesKey("use_24_hour_time")
    private val KEY_USE_IMPERIAL_ELEVATION = booleanPreferencesKey("use_imperial_elevation")
    private val KEY_USE_IMPERIAL_DISTANCE = booleanPreferencesKey("use_imperial_distance")
    private val KEY_NOTIFY_ON_TRACKING_ALERTS = booleanPreferencesKey("notify_on_tracking_alerts")
    private val KEY_SHOW_GEOFENCES_ON_MAP = booleanPreferencesKey("show_geofences_on_map")
    private val KEY_REVERSE_GEOCODING = booleanPreferencesKey("reverse_geocoding_enabled")
    private val KEY_APP_LOCK_ENABLED = booleanPreferencesKey("app_lock_enabled")
    private val KEY_APP_LOCK_TIMEOUT_SECONDS = intPreferencesKey("app_lock_timeout_seconds")
    private val KEY_THEME_MODE = stringPreferencesKey("theme_mode")
    private val KEY_USE_DYNAMIC_COLOR = booleanPreferencesKey("use_dynamic_color")
    private val KEY_DISABLED_RELAY_URLS = stringPreferencesKey("disabled_relay_urls")
    private val KEY_CUSTOM_RELAYS = stringPreferencesKey("custom_relays")
    private val KEY_ALLOW_LIVE_BOOST = booleanPreferencesKey("allow_live_boost")
    private val KEY_REQUEST_LIVE_BOOST = booleanPreferencesKey("request_live_boost")

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
                historyMaxAccuracyMeters = prefs[KEY_HISTORY_MAX_ACCURACY] ?: 0,
                sendMaxAccuracyMeters = prefs[KEY_SEND_MAX_ACCURACY] ?: 0,
                pinColor = prefs[KEY_PIN_COLOR] ?: "",
                sosActive = prefs[KEY_SOS_ACTIVE] ?: false,
                mapStartZoom = prefs[KEY_MAP_START_ZOOM] ?: 16.0,
                mapStartingPoint = prefs[KEY_MAP_STARTING_POINT] ?: "OWN_PIN",
                mapFixedLat = prefs[KEY_MAP_FIXED_LAT] ?: 0.0,
                mapFixedLng = prefs[KEY_MAP_FIXED_LNG] ?: 0.0,
                useImperialSpeed = prefs[KEY_USE_IMPERIAL_SPEED] ?: localeDefaultImperial(),
                use24HourTime = prefs[KEY_USE_24_HOUR_TIME]
                    ?: android.text.format.DateFormat.is24HourFormat(context),
                useImperialElevation = prefs[KEY_USE_IMPERIAL_ELEVATION] ?: localeDefaultImperial(),
                useImperialDistance = prefs[KEY_USE_IMPERIAL_DISTANCE] ?: localeDefaultImperial(),
                notifyOnTrackingAlerts = prefs[KEY_NOTIFY_ON_TRACKING_ALERTS] ?: false,
                showGeofencesOnMap = prefs[KEY_SHOW_GEOFENCES_ON_MAP] ?: false,
                reverseGeocodingEnabled = prefs[KEY_REVERSE_GEOCODING] ?: false,
                appLockEnabled = prefs[KEY_APP_LOCK_ENABLED] ?: false,
                appLockTimeoutSeconds = prefs[KEY_APP_LOCK_TIMEOUT_SECONDS] ?: 0,
                themeMode = prefs[KEY_THEME_MODE] ?: "SYSTEM",
                useDynamicColor = prefs[KEY_USE_DYNAMIC_COLOR] ?: true,
                disabledRelayUrls = prefs[KEY_DISABLED_RELAY_URLS]
                    ?.split(",")
                    ?.filter { it.isNotBlank() }
                    ?.toSet() ?: emptySet(),
                customRelays = prefs[KEY_CUSTOM_RELAYS]
                    ?.split(",")
                    ?.map { it.trim() }
                    ?.filter { it.isNotBlank() }
                    ?: emptyList(),
                allowLiveBoost = prefs[KEY_ALLOW_LIVE_BOOST] ?: true,
                requestLiveBoost = prefs[KEY_REQUEST_LIVE_BOOST] ?: true
            )
        }
        // replay = 1 caches the latest settings so newly-mounted screens get it immediately
        // (no spinner flash) without re-reading disk; WhileSubscribed lets the upstream stop
        // when nothing is observing. No synthetic initial value is emitted, so the existing
        // `null`-until-loaded semantics in MainActivity (onboarding spinner) are preserved.
        .shareIn(scope, SharingStarted.WhileSubscribed(5_000), replay = 1)

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

    suspend fun setRelayEnabled(url: String, enabled: Boolean) {
        context.settingsStore.edit { prefs ->
            val current = prefs[KEY_DISABLED_RELAY_URLS]?.split(",")?.filter { it.isNotBlank() }?.toMutableSet()
                ?: mutableSetOf()
            if (enabled) current.remove(url) else current.add(url)
            prefs[KEY_DISABLED_RELAY_URLS] = current.joinToString(",")
        }
    }

    /** Persist the user's custom relay list (already validated/deduped by the caller). */
    suspend fun setCustomRelays(urls: List<String>) {
        context.settingsStore.edit { it[KEY_CUSTOM_RELAYS] = urls.joinToString(",") }
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

    suspend fun setHistoryMaxAccuracyMeters(meters: Int) {
        context.settingsStore.edit { it[KEY_HISTORY_MAX_ACCURACY] = meters }
    }

    suspend fun setSendMaxAccuracyMeters(meters: Int) {
        context.settingsStore.edit { it[KEY_SEND_MAX_ACCURACY] = meters }
    }

    suspend fun setPinColor(hex: String) {
        context.settingsStore.edit { it[KEY_PIN_COLOR] = hex }
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

    suspend fun setUseImperialSpeed(imperial: Boolean) {
        context.settingsStore.edit { it[KEY_USE_IMPERIAL_SPEED] = imperial }
    }

    suspend fun setUse24HourTime(use24Hour: Boolean) {
        context.settingsStore.edit { it[KEY_USE_24_HOUR_TIME] = use24Hour }
    }

    suspend fun setUseImperialElevation(imperial: Boolean) {
        context.settingsStore.edit { it[KEY_USE_IMPERIAL_ELEVATION] = imperial }
    }

    suspend fun setUseImperialDistance(imperial: Boolean) {
        context.settingsStore.edit { it[KEY_USE_IMPERIAL_DISTANCE] = imperial }
    }

    suspend fun setNotifyOnTrackingAlerts(notify: Boolean) {
        context.settingsStore.edit { it[KEY_NOTIFY_ON_TRACKING_ALERTS] = notify }
    }

    suspend fun setShowGeofencesOnMap(show: Boolean) {
        context.settingsStore.edit { it[KEY_SHOW_GEOFENCES_ON_MAP] = show }
    }

    suspend fun setReverseGeocodingEnabled(enabled: Boolean) {
        context.settingsStore.edit { it[KEY_REVERSE_GEOCODING] = enabled }
    }

    suspend fun setAppLockEnabled(enabled: Boolean) {
        context.settingsStore.edit { it[KEY_APP_LOCK_ENABLED] = enabled }
    }

    suspend fun setAppLockTimeoutSeconds(seconds: Int) {
        context.settingsStore.edit { it[KEY_APP_LOCK_TIMEOUT_SECONDS] = seconds.coerceAtLeast(0) }
    }

    suspend fun setThemeMode(mode: ThemeMode) {
        context.settingsStore.edit { it[KEY_THEME_MODE] = mode.name }
    }

    suspend fun setUseDynamicColor(use: Boolean) {
        context.settingsStore.edit { it[KEY_USE_DYNAMIC_COLOR] = use }
    }

    suspend fun setAllowLiveBoost(enabled: Boolean) {
        context.settingsStore.edit { it[KEY_ALLOW_LIVE_BOOST] = enabled }
    }

    suspend fun setRequestLiveBoost(enabled: Boolean) {
        context.settingsStore.edit { it[KEY_REQUEST_LIVE_BOOST] = enabled }
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

    /**
     * Circle ids the local user has explicitly left, as a live flow. An incoming circle message for
     * one of these is ignored so a straggler from another member can't silently re-create a circle
     * the user left (see [com.locapeer.subscriber.HeartbeatReceiver], which caches the latest value
     * so it never reads DataStore on the per-message hot path). Cleared for a circle when its owner
     * re-invites the user. Stored as a comma-separated string; circle ids are UUIDs so they never
     * contain a comma.
     */
    val leftCircleIds: Flow<Set<String>> = context.settingsStore.data
        .catch { e -> if (e is IOException) emit(emptyPreferences()) else throw e }
        .map { prefs ->
            prefs[KEY_LEFT_CIRCLE_IDS]?.split(",")?.filter { it.isNotBlank() }?.toSet() ?: emptySet()
        }

    suspend fun addLeftCircleId(gid: String) {
        context.settingsStore.edit {
            val current = it[KEY_LEFT_CIRCLE_IDS]?.split(",")?.filter { s -> s.isNotBlank() }?.toMutableSet()
                ?: mutableSetOf()
            current.add(gid)
            it[KEY_LEFT_CIRCLE_IDS] = current.joinToString(",")
        }
    }

    suspend fun removeLeftCircleId(gid: String) {
        context.settingsStore.edit {
            val current = it[KEY_LEFT_CIRCLE_IDS]?.split(",")?.filter { s -> s.isNotBlank() }?.toMutableSet()
                ?: return@edit
            if (current.remove(gid)) it[KEY_LEFT_CIRCLE_IDS] = current.joinToString(",")
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

    suspend fun resetIntervals() {
        context.settingsStore.edit { prefs ->
            prefs.remove(KEY_STATIONARY_INTERVAL)
            prefs.remove(KEY_WALKING_INTERVAL)
            prefs.remove(KEY_RUNNING_INTERVAL)
            prefs.remove(KEY_CYCLING_INTERVAL)
            prefs.remove(KEY_DRIVING_INTERVAL)
            prefs.remove(KEY_LOW_BATTERY_INTERVAL)
        }
    }
}
