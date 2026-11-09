package com.locapeer.settings

import android.content.Context
import android.util.Log
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.doublePreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.MutablePreferences
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.locapeer.sharing.ScheduleRule
import com.locapeer.sharing.toScheduleRules
import com.locapeer.ui.DEFAULT_NAV_TAB_IDS
import com.locapeer.util.normalizeHexColor
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.shareIn
import kotlinx.serialization.json.Json
import java.io.IOException
import javax.inject.Inject
import javax.inject.Singleton

private val Context.settingsStore by preferencesDataStore(name = "locapeer_settings")
private const val TAG = "AppPreferences"
private val KEY_SUPERVISED_MODE = booleanPreferencesKey("supervised_mode")
private val KEY_SUPERVISOR_PUBKEY = stringPreferencesKey("supervisor_pubkey")
private val KEY_SUPERVISION_PENDING = booleanPreferencesKey("supervision_pending")
private val KEY_SUPERVISION_REQUESTED_AT = longPreferencesKey("supervision_requested_at")
private val KEY_SUPERVISION_RELEASE_APPROVED = booleanPreferencesKey("supervision_release_approved")
private val KEY_SUPERVISION_REQUEST_SENT = booleanPreferencesKey("supervision_request_sent")

/** Must be called inside a DataStore edit so competing answers cannot both succeed. */
internal fun applySupervisionRegistrationResponse(
    prefs: MutablePreferences, supervisorPubkey: String, requestedAt: Long, accepted: Boolean
): Boolean {
    if (prefs[KEY_SUPERVISED_MODE] != true || prefs[KEY_SUPERVISION_PENDING] != true ||
        prefs[KEY_SUPERVISOR_PUBKEY] != supervisorPubkey ||
        (prefs[KEY_SUPERVISION_REQUESTED_AT] ?: 0L) != requestedAt
    ) return false
    prefs[KEY_SUPERVISION_PENDING] = false
    prefs[KEY_SUPERVISION_REQUEST_SENT] = false
    if (!accepted) {
        prefs[KEY_SUPERVISED_MODE] = false
        prefs[KEY_SUPERVISOR_PUBKEY] = ""
    }
    return true
}

/** A circle the user explicitly left: its owner at that moment, and when we left (epoch seconds). */
data class LeftCircle(val circleId: String, val creatorPubkey: String, val leftAt: Long)

/**
 * Decode the stored leave records: comma-separated `id:creator:leftAt` entries. A bare id written
 * by an older version decodes with no known owner and time 0.
 */
/**
 * Upper bound on stored leave records. The record's only job is to stop a straggling
 * message re-creating a circle the user left; that matters for recent leaves, not for
 * hundreds of them, and the encoded list is read on every settings emission. 200 is far
 * more than a person accumulates and still bounds the string to a few tens of kilobytes.
 */
internal const val MAX_LEFT_CIRCLE_RECORDS = 200

internal fun decodeLeftCircles(raw: String?): Map<String, LeftCircle> {
    if (raw.isNullOrBlank()) return emptyMap()
    val result = LinkedHashMap<String, LeftCircle>()
    for (entry in raw.split(',')) {
        if (result.size >= MAX_LEFT_CIRCLE_RECORDS) break
        if (entry.isBlank()) continue
        val parts = entry.split(':')
        val leftAt = parts.getOrNull(2)?.toLongOrNull()
        if (parts.size == 3 && leftAt != null && parts[0].isNotBlank()) {
            result[parts[0]] = LeftCircle(parts[0], parts[1], leftAt)
        } else if (entry.isNotBlank()) {
            result[entry] = LeftCircle(entry, "", 0L)
        }
    }
    return result
}

/** Oldest leaves fall off first; a duplicate id keeps its most recent leave. */
internal fun encodeLeftCircles(circles: Collection<LeftCircle>): String =
    circles.asSequence()
        .sortedByDescending { it.leftAt }
        .distinctBy { it.circleId }
        .take(MAX_LEFT_CIRCLE_RECORDS)
        .joinToString(",") { "${it.circleId}:${it.creatorPubkey}:${it.leftAt}" }

val HARDCODED_RELAYS = listOf(
    "wss://relay.daygle.net",
    "wss://nos.lol",
    "wss://relay.damus.io",
    "wss://relay.snort.social",
)

/**
 * The app's own relay, always used regardless of the public-relay toggle - it is the one
 * relay we control and the safe default rendezvous. The remaining [HARDCODED_RELAYS] are
 * large public Nostr relays: encrypted payloads are safe on them, but the event envelope
 * (recipient `p` tags, kinds, timing) is not, so a privacy-conscious user can switch them
 * off and rely on the primary plus their own custom
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
    /** Supervised mode was requested but the supervisor hasn't accepted yet. */
    val supervisionPending: Boolean = false,
    /** When the current supervision request was sent (epoch seconds), 0 if none. */
    val supervisionRequestedAt: Long = 0,
    /** The request for [supervisionRequestedAt] actually reached the relay (retry guard). */
    val supervisionRequestSent: Boolean = false,
    /** Ordered list of bottom-nav tab IDs the user has chosen to show. */
    val navTabIds: List<String> = DEFAULT_NAV_TAB_IDS,
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
    /** Alert when a contact starts watching this device live (selects our pin). */
    val notifyWhenWatched: Boolean = false,
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
     * If true, speed up own broadcasts while a contact has our pin selected (live view).
     */
    val allowLiveBoost: Boolean = true,
    /** Seconds between broadcasts while a contact is watching live (one of
     *  [com.locapeer.beacon.LIVE_UPDATE_INTERVAL_OPTIONS_S]). */
    val liveUpdateIntervalSeconds: Int = 5,
    /**
     * If true, ask contacts for faster updates when viewing them on the map.
     */
    val requestLiveBoost: Boolean = true,
    /** Show a system notification for incoming chat / circle messages. */
    val messageNotificationsEnabled: Boolean = true,
    /** Show geofence / proximity / missed-location alerts. SOS alerts are always shown. */
    val alertNotificationsEnabled: Boolean = true,
    /** Suppress message and tracking-alert notifications during [quietStartMinute]..[quietEndMinute]. */
    val quietHoursEnabled: Boolean = false,
    /** Minutes of day the quiet window starts (inclusive), e.g. 1320 = 22:00. */
    val quietStartMinute: Int = 22 * 60,
    /** Minutes of day the quiet window ends (exclusive), e.g. 480 = 08:00. */
    val quietEndMinute: Int = 8 * 60
) {
    /**
     * True once the supervisor has accepted this device. Until then supervision is only
     * requested: the supervisor's app ignores unlock requests from a device it hasn't
     * accepted, so gating settings while pending could lock them for good.
     */
    val supervisionActive: Boolean get() = supervisedModeEnabled && !supervisionPending
}

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
    private val KEY_NOTIFY_WHEN_WATCHED = booleanPreferencesKey("notify_when_watched")
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
    private val KEY_LIVE_UPDATE_INTERVAL_SECONDS = intPreferencesKey("live_update_interval_seconds")
    private val KEY_MESSAGE_NOTIFICATIONS = booleanPreferencesKey("message_notifications_enabled")
    private val KEY_ALERT_NOTIFICATIONS = booleanPreferencesKey("alert_notifications_enabled")
    private val KEY_QUIET_HOURS_ENABLED = booleanPreferencesKey("quiet_hours_enabled")
    private val KEY_QUIET_HOURS_START_MINUTE = intPreferencesKey("quiet_hours_start_minute")
    private val KEY_QUIET_HOURS_END_MINUTE = intPreferencesKey("quiet_hours_end_minute")
    private val KEY_MUTED_CONVERSATIONS = stringPreferencesKey("muted_conversations")

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
                supervisionPending = prefs[KEY_SUPERVISION_PENDING] ?: false,
                supervisionRequestedAt = prefs[KEY_SUPERVISION_REQUESTED_AT] ?: 0L,
                supervisionRequestSent = prefs[KEY_SUPERVISION_REQUEST_SENT] ?: false,
                navTabIds = prefs[KEY_NAV_TAB_IDS]
                    ?.split(",")
                    ?.filter { it.isNotBlank() }
                    ?.takeIf { it.size >= 2 }
                    ?: DEFAULT_NAV_TAB_IDS,
                startRoute = prefs[KEY_START_ROUTE] ?: "map",
                localLocationRetentionDays = prefs[KEY_LOCAL_LOCATION_RETENTION] ?: 90,
                localMessageRetentionDays = prefs[KEY_LOCAL_MESSAGE_RETENTION] ?: 90,
                historyMinDistanceMeters = prefs[KEY_HISTORY_MIN_DISTANCE] ?: 0,
                historyMaxAccuracyMeters = prefs[KEY_HISTORY_MAX_ACCURACY] ?: 0,
                sendMaxAccuracyMeters = prefs[KEY_SEND_MAX_ACCURACY] ?: 0,
                // A restored or hand-edited colour must never reach toColorInt() unvalidated.
                pinColor = normalizeHexColor(prefs[KEY_PIN_COLOR] ?: ""),
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
                notifyWhenWatched = prefs[KEY_NOTIFY_WHEN_WATCHED] ?: false,
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
                requestLiveBoost = prefs[KEY_REQUEST_LIVE_BOOST] ?: true,
                liveUpdateIntervalSeconds = com.locapeer.beacon.normalizeLiveUpdateIntervalSeconds(
                    prefs[KEY_LIVE_UPDATE_INTERVAL_SECONDS]
                ),
                messageNotificationsEnabled = prefs[KEY_MESSAGE_NOTIFICATIONS] ?: true,
                alertNotificationsEnabled = prefs[KEY_ALERT_NOTIFICATIONS] ?: true,
                quietHoursEnabled = prefs[KEY_QUIET_HOURS_ENABLED] ?: false,
                quietStartMinute = prefs[KEY_QUIET_HOURS_START_MINUTE] ?: 22 * 60,
                quietEndMinute = prefs[KEY_QUIET_HOURS_END_MINUTE] ?: 8 * 60
            )
        }
        // The store also holds sync bookkeeping (dedup event ids, catch-up epochs) that is
        // rewritten constantly in the background; without this every such write re-emitted an
        // identical AppSettings to every collector (service reschedules, UI recompositions).
        .distinctUntilChanged()
        // replay = 1 caches the latest settings so newly-mounted screens get it immediately
        // (no spinner flash) without re-reading disk; WhileSubscribed lets the upstream stop
        // when nothing is observing. No synthetic initial value is emitted, so the existing
        // `null`-until-loaded semantics in MainActivity (onboarding spinner) are preserved.
        .shareIn(scope, SharingStarted.WhileSubscribed(5_000), replay = 1)

    suspend fun updateDisplayName(name: String) {
        context.settingsStore.edit {
            if (it[KEY_DISPLAY_NAME] != name) it[KEY_DISPLAY_NAME] = name
        }
    }

    suspend fun setHeartbeatEnabled(enabled: Boolean) {
        context.settingsStore.edit {
            if (it[KEY_HEARTBEAT_ENABLED] != enabled) it[KEY_HEARTBEAT_ENABLED] = enabled
        }
    }

    suspend fun setOnboardingComplete(complete: Boolean) {
        context.settingsStore.edit {
            if (it[KEY_ONBOARDING_COMPLETE] != complete) it[KEY_ONBOARDING_COMPLETE] = complete
        }
    }

    suspend fun setSupervisedMode(enabled: Boolean, supervisorPubkey: String) {
        context.settingsStore.edit {
            it[KEY_SUPERVISED_MODE] = enabled
            it[KEY_SUPERVISOR_PUBKEY] = supervisorPubkey
            // A new request waits for the supervisor's acceptance before it gates anything.
            it[KEY_SUPERVISION_PENDING] = enabled
            it[KEY_SUPERVISION_REQUESTED_AT] = if (enabled) System.currentTimeMillis() / 1000 else 0L
            // Nothing has been published yet for this request; the send marks it.
            it[KEY_SUPERVISION_REQUEST_SENT] = false
        }
    }

    /** Apply a registration answer only if the same request is still pending. */
    suspend fun applySupervisionRegistrationResponse(
        supervisorPubkey: String, requestedAt: Long, accepted: Boolean
    ): Boolean {
        var applied = false
        context.settingsStore.edit {
            applied = applySupervisionRegistrationResponse(it, supervisorPubkey, requestedAt, accepted)
        }
        return applied
    }

    suspend fun setNavTabIds(ids: List<String>) {
        context.settingsStore.edit { it[KEY_NAV_TAB_IDS] = ids.joinToString(",") }
    }

    suspend fun setStartRoute(route: String) {
        context.settingsStore.edit {
            if (it[KEY_START_ROUTE] != route) it[KEY_START_ROUTE] = route
        }
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

    /**
     * Restore helper: set the disabled set wholesale from a backup. Entries that no longer
     * match any known URL are simply ignored by the connection manager, so no validation
     * needed here beyond writing the values.
     */
    suspend fun setDisabledRelayUrls(urls: Set<String>) {
        context.settingsStore.edit { it[KEY_DISABLED_RELAY_URLS] = urls.joinToString(",") }
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
        val normalized = normalizeHexColor(hex)
        context.settingsStore.edit {
            if (it[KEY_PIN_COLOR] != normalized) it[KEY_PIN_COLOR] = normalized
        }
    }

    suspend fun setSosActive(active: Boolean) {
        context.settingsStore.edit {
            if (it[KEY_SOS_ACTIVE] != active) it[KEY_SOS_ACTIVE] = active
        }
    }

    suspend fun setMapStartZoom(zoom: Double) {
        context.settingsStore.edit {
            if (it[KEY_MAP_START_ZOOM] != zoom) it[KEY_MAP_START_ZOOM] = zoom
        }
    }

    suspend fun setMapStartingPoint(mode: String) {
        context.settingsStore.edit {
            if (it[KEY_MAP_STARTING_POINT] != mode) it[KEY_MAP_STARTING_POINT] = mode
        }
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

    suspend fun setNotifyWhenWatched(notify: Boolean) {
        context.settingsStore.edit { it[KEY_NOTIFY_WHEN_WATCHED] = notify }
    }

    suspend fun setNotifyOnTrackingAlerts(notify: Boolean) {
        context.settingsStore.edit { it[KEY_NOTIFY_ON_TRACKING_ALERTS] = notify }
    }

    suspend fun setShowGeofencesOnMap(show: Boolean) {
        context.settingsStore.edit { it[KEY_SHOW_GEOFENCES_ON_MAP] = show }
    }

    suspend fun setReverseGeocodingEnabled(enabled: Boolean) {
        context.settingsStore.edit {
            if (it[KEY_REVERSE_GEOCODING] != enabled) it[KEY_REVERSE_GEOCODING] = enabled
        }
    }

    suspend fun setAppLockEnabled(enabled: Boolean) {
        context.settingsStore.edit {
            if (it[KEY_APP_LOCK_ENABLED] != enabled) it[KEY_APP_LOCK_ENABLED] = enabled
        }
    }

    suspend fun setAppLockTimeoutSeconds(seconds: Int) {
        context.settingsStore.edit {
            val s = seconds.coerceAtLeast(0)
            if (it[KEY_APP_LOCK_TIMEOUT_SECONDS] != s) it[KEY_APP_LOCK_TIMEOUT_SECONDS] = s
        }
    }

    suspend fun setThemeMode(mode: ThemeMode) {
        context.settingsStore.edit {
            if (it[KEY_THEME_MODE] != mode.name) it[KEY_THEME_MODE] = mode.name
        }
    }

    suspend fun setUseDynamicColor(use: Boolean) {
        context.settingsStore.edit {
            if (it[KEY_USE_DYNAMIC_COLOR] != use) it[KEY_USE_DYNAMIC_COLOR] = use
        }
    }

    suspend fun setAllowLiveBoost(enabled: Boolean) {
        context.settingsStore.edit { it[KEY_ALLOW_LIVE_BOOST] = enabled }
    }

    suspend fun setLiveUpdateIntervalSeconds(seconds: Int) {
        val value = com.locapeer.beacon.normalizeLiveUpdateIntervalSeconds(seconds)
        context.settingsStore.edit { it[KEY_LIVE_UPDATE_INTERVAL_SECONDS] = value }
    }

    suspend fun setRequestLiveBoost(enabled: Boolean) {
        context.settingsStore.edit { it[KEY_REQUEST_LIVE_BOOST] = enabled }
    }

    suspend fun setMessageNotificationsEnabled(enabled: Boolean) {
        context.settingsStore.edit {
            if (it[KEY_MESSAGE_NOTIFICATIONS] != enabled) it[KEY_MESSAGE_NOTIFICATIONS] = enabled
        }
    }

    suspend fun setAlertNotificationsEnabled(enabled: Boolean) {
        context.settingsStore.edit {
            if (it[KEY_ALERT_NOTIFICATIONS] != enabled) it[KEY_ALERT_NOTIFICATIONS] = enabled
        }
    }

    suspend fun setQuietHours(enabled: Boolean, startMinute: Int = -1, endMinute: Int = -1) {
        context.settingsStore.edit { prefs ->
            prefs[KEY_QUIET_HOURS_ENABLED] = enabled
            if (startMinute >= 0) prefs[KEY_QUIET_HOURS_START_MINUTE] = startMinute
            if (endMinute >= 0) prefs[KEY_QUIET_HOURS_END_MINUTE] = endMinute
        }
    }

    suspend fun setQuietStartMinute(minute: Int) {
        context.settingsStore.edit {
            if (it[KEY_QUIET_HOURS_START_MINUTE] != minute) it[KEY_QUIET_HOURS_START_MINUTE] = minute
        }
    }

    suspend fun setQuietEndMinute(minute: Int) {
        context.settingsStore.edit {
            if (it[KEY_QUIET_HOURS_END_MINUTE] != minute) it[KEY_QUIET_HOURS_END_MINUTE] = minute
        }
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

    /** Persisted contact-add intents for the supervised approval queue (JSON string; see
     *  ContactAddApprovalManager). Survives process death so a decision that lands right
     *  after a restart can still complete the queued add. */
    private val KEY_CONTACT_ADD_INTENTS = stringPreferencesKey("contact_add_intents")

    suspend fun getContactAddIntentsJson(): String? {
        val prefs = context.settingsStore.data.first()
        return prefs[KEY_CONTACT_ADD_INTENTS]
    }

    suspend fun setContactAddIntentsJson(json: String?) {
        context.settingsStore.edit { prefs ->
            if (json == null) prefs.remove(KEY_CONTACT_ADD_INTENTS) else prefs[KEY_CONTACT_ADD_INTENTS] = json
        }
    }

    /** Register answers waiting for the relay (JSON string; see SupervisionResponseSender).
     *  Written before publishing so a failed or interrupted send is retried on next start. */
    private val KEY_PENDING_REGISTER_RESPONSES = stringPreferencesKey("pending_register_responses")

    suspend fun getPendingRegisterResponsesJson(): String? =
        context.settingsStore.data.first()[KEY_PENDING_REGISTER_RESPONSES]

    suspend fun setPendingRegisterResponsesJson(json: String?) {
        context.settingsStore.edit { prefs ->
            if (json == null) prefs.remove(KEY_PENDING_REGISTER_RESPONSES)
            else prefs[KEY_PENDING_REGISTER_RESPONSES] = json
        }
    }

    /**
     * Settings-change entries a supervised device still owes its supervisor (JSON string;
     * see SupervisionChangeReporter). Written the moment a change is recorded so a process
     * death inside the report's debounce window cannot drop it.
     */
    private val KEY_SUPERVISION_CHANGE_BUFFER = stringPreferencesKey("supervision_change_buffer")

    suspend fun getSupervisionChangeBufferJson(): String? =
        context.settingsStore.data.first()[KEY_SUPERVISION_CHANGE_BUFFER]

    suspend fun setSupervisionChangeBufferJson(json: String?) {
        context.settingsStore.edit { prefs ->
            if (json == null) prefs.remove(KEY_SUPERVISION_CHANGE_BUFFER)
            else prefs[KEY_SUPERVISION_CHANGE_BUFFER] = json
        }
    }

    /**
     * Full settings wipe for "Delete identity & erase all data": every key in the settings
     * store (name, intervals, schedules, relays, mute lists, supervision, SOS flag, sync
     * bookmarks). Returns the store to pristine DataStore state; next launch generates a new
     * identity and starts onboarding.
     */
    suspend fun wipeAllSettings() {
        context.settingsStore.edit { it.clear() }
    }

    suspend fun clearSupervisedMode() {
        context.settingsStore.edit {
            it[KEY_SUPERVISED_MODE] = false
            it[KEY_SUPERVISOR_PUBKEY] = ""
            it[KEY_SUPERVISION_PENDING] = false
            it[KEY_SUPERVISION_REQUEST_SENT] = false
            // The release has been applied; never let a stale marker end a later supervision.
            it[KEY_SUPERVISION_RELEASE_APPROVED] = false
            // Change reports describe a supervision that no longer exists; a summary that
            // can never be sent would otherwise be retried at every start.
            it.remove(KEY_SUPERVISION_CHANGE_BUFFER)
        }
    }

    /**
     * Persist a supervisor-approved release BEFORE the flags are cleared. A process death in
     * between then leaves this marker, and [applyPendingSupervisionRelease] finishes the
     * release on the next start instead of the device staying gated while the supervisor
     * already considers it released.
     */
    suspend fun markSupervisionReleaseApproved() {
        context.settingsStore.edit { it[KEY_SUPERVISION_RELEASE_APPROVED] = true }
    }

    /** The registration request was published; a restart must not send it again. */
    suspend fun markSupervisionRequestSent() {
        context.settingsStore.edit { it[KEY_SUPERVISION_REQUEST_SENT] = true }
    }

    /**
     * Applies an approved release whose clearing was interrupted (or never started), in one
     * atomic edit. Returns true when a release was applied. Idempotent: with no marker this
     * writes nothing and reports false.
     */
    suspend fun applyPendingSupervisionRelease(): Boolean {
        var applied = false
        context.settingsStore.edit { prefs ->
            if (prefs[KEY_SUPERVISION_RELEASE_APPROVED] != true) return@edit
            prefs[KEY_SUPERVISED_MODE] = false
            prefs[KEY_SUPERVISOR_PUBKEY] = ""
            prefs[KEY_SUPERVISION_PENDING] = false
            prefs[KEY_SUPERVISION_RELEASE_APPROVED] = false
            applied = true
        }
        return applied
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
     * Circles the local user has explicitly left, keyed by circle id, as a live flow. Each record
     * keeps the owner we knew and when we left, so an incoming message for one of these is ignored
     * unless it is a newer message from that same owner (see
     * [com.locapeer.subscriber.HeartbeatReceiver], which caches the latest value so it never reads
     * DataStore on the per-message hot path). Stored as a comma-separated string of
     * `id:creator:leftAt` entries; circle ids are UUIDs so they contain neither a comma nor a colon.
     */
    val leftCircles: Flow<Map<String, LeftCircle>> = context.settingsStore.data
        .catch { e -> if (e is IOException) emit(emptyPreferences()) else throw e }
        .map { prefs -> decodeLeftCircles(prefs[KEY_LEFT_CIRCLE_IDS]) }
        .distinctUntilChanged()

    suspend fun addLeftCircle(circleId: String, creatorPubkey: String) {
        context.settingsStore.edit {
            val current = decodeLeftCircles(it[KEY_LEFT_CIRCLE_IDS]).toMutableMap()
            current[circleId] = LeftCircle(
                circleId = circleId,
                creatorPubkey = creatorPubkey.lowercase(),
                leftAt = System.currentTimeMillis() / 1000
            )
            it[KEY_LEFT_CIRCLE_IDS] = encodeLeftCircles(current.values)
        }
    }

    suspend fun removeLeftCircle(circleId: String) {
        context.settingsStore.edit {
            val current = decodeLeftCircles(it[KEY_LEFT_CIRCLE_IDS]).toMutableMap()
            if (current.remove(circleId) != null) {
                it[KEY_LEFT_CIRCLE_IDS] = encodeLeftCircles(current.values)
            }
        }
    }

    /** Conversation mute keys: contact ids and circle ids could conceptually collide, so each
     *  gets its own prefix before the id. Both are opaque unique strings from other tables. */
    companion object {
        private const val MUTE_KEY_PREFIX_PEER = "peer:"
        private const val MUTE_KEY_PREFIX_CIRCLE = "circle:"
        fun peerMuteKey(deviceId: String): String = MUTE_KEY_PREFIX_PEER + deviceId
        fun circleMuteKey(circleId: String): String = MUTE_KEY_PREFIX_CIRCLE + circleId
    }

    /**
     * Conversation ids the user muted, as a live flow. Muted chats / circles still receive and
     * store messages; only the system notification for a new message in them is suppressed.
     * Stored as a comma-separated list of prefixed keys (see [peerMuteKey] / [circleMuteKey]).
     */
    val mutedConversationIds: Flow<Set<String>> = context.settingsStore.data
        .catch { e -> if (e is IOException) emit(emptyPreferences()) else throw e }
        .map { prefs ->
            prefs[KEY_MUTED_CONVERSATIONS]
                ?.split(",")
                ?.filter { it.isNotBlank() }
                ?.toSet()
                ?: emptySet()
        }
        .distinctUntilChanged()

    suspend fun setConversationMuted(muteKey: String, muted: Boolean) {
        context.settingsStore.edit { prefs ->
            val current = prefs[KEY_MUTED_CONVERSATIONS]
                ?.split(",")
                ?.filter { it.isNotBlank() }
                ?.toMutableSet()
                ?: mutableSetOf()
            if (muted) current.add(muteKey) else current.remove(muteKey)
            prefs[KEY_MUTED_CONVERSATIONS] = current.joinToString(",")
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
