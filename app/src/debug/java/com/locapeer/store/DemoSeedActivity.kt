package com.locapeer.store

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import com.locapeer.EXTRA_ALLOW_SCREENSHOTS
import com.locapeer.MainActivity
import com.locapeer.crypto.KeyManager
import com.locapeer.data.AppDatabase
import com.locapeer.data.entity.HeartbeatEntity
import com.locapeer.data.entity.PeerEntity
import com.locapeer.data.entity.PeerSharingConfig
import com.locapeer.map.clearMapPrefs
import com.locapeer.settings.AppPreferences
import dagger.hilt.android.AndroidEntryPoint
import java.security.MessageDigest
import java.util.Calendar
import javax.inject.Inject
import kotlin.math.max
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

/**
 * Debug-only entry point for the emulator screenshot route (MapLibre's native renderer cannot
 * run in the JVM Roborazzi harness, so the Map and Location History screens are captured from
 * a real emulator instead).
 *
 * It writes an entirely fabricated cast of contacts, current locations and one day of walking
 * history through the app's real Hilt graph - the same encrypted Room database and DataStore
 * preferences the UI reads - then opens [MainActivity] with screenshot protection lifted for
 * that launch. Nothing here ships in a release build: the whole file lives in `src/debug`.
 *
 *     adb shell am start -n com.locapeer/.store.DemoSeedActivity \
 *         --ez seed true --es start map
 *
 * Seeding is idempotent: every demo row (and the demo user's own heartbeats) is deleted before
 * it is re-inserted, so the activity can be re-run freely.
 */
@AndroidEntryPoint
class DemoSeedActivity : ComponentActivity() {

    @Inject lateinit var db: AppDatabase
    @Inject lateinit var prefs: AppPreferences
    @Inject lateinit var keyManager: KeyManager

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val seed = intent.getBooleanExtra(EXTRA_SEED, false)
        val startRoute = intent.getStringExtra(EXTRA_START_ROUTE)
        scope.launch {
            if (seed) seedDemoData()
            prefs.setStartRoute(startRoute ?: "map")
            openApp()
        }
    }

    private fun openApp() {
        runOnUiThread {
            startActivity(
                Intent(this, MainActivity::class.java)
                    .putExtra(EXTRA_ALLOW_SCREENSHOTS, true)
            )
            finish()
        }
    }

    private suspend fun seedDemoData() {
        val (_, me) = keyManager.ensureKeypair()
        prefs.updateDisplayName(ME_NAME)
        prefs.setOnboardingComplete(true)
        // No real heartbeat service: the emulator's own GPS fixes must not overwrite the
        // fabricated positions (and there is no relay to send anything to).
        prefs.setHeartbeatEnabled(false)
        prefs.setMapStartingPoint("OWN_PIN")
        // A camera saved by an earlier session would otherwise win over the own-pin centring.
        clearMapPrefs(this)

        // Idempotent: drop the previous run's rows before inserting the same cast again.
        demoPeers.forEach { peer ->
            db.heartbeatDao().deleteAllForDevice(peer.id)
            db.peerDao().deletePeerById(peer.id)
            db.peerSharingConfigDao().deleteForPeer(peer.id)
        }
        db.heartbeatDao().deleteAllForDevice(me)

        val now = System.currentTimeMillis()
        demoPeers.forEach { demo ->
            db.peerDao().upsertPeer(
                PeerEntity(
                    deviceId = demo.id,
                    displayName = demo.name,
                    publicKeyHex = demo.id,
                    relayUrl = RELAY_URL,
                    locationRole = demo.role,
                    addedAt = now - 30L * 24 * 60 * 60 * 1000,
                )
            )
            db.peerSharingConfigDao().upsert(
                PeerSharingConfig(
                    peerDeviceId = demo.id,
                    isSosContact = demo.sos,
                    precisionMode = demo.precision,
                )
            )
            demo.heartbeat(now)?.let { db.heartbeatDao().insert(it) }
        }

        // The demo user's own trail doubles as the Location History screenshot and gives the
        // map its blue "me" dot (the map centres on the latest own heartbeat).
        db.heartbeatDao().insertAll(ownTrail(me, now))
    }

    /** A fabricated current fix, or null for the contact who has not shared a location yet. */
    private fun DemoPeer.heartbeat(now: Long): HeartbeatEntity? {
        val lat = lat ?: return null
        val lng = lng ?: return null
        val timestamp = now - minutesAgo * 60_000
        return HeartbeatEntity(
            deviceId = id,
            displayName = name,
            timestamp = timestamp,
            lat = lat,
            lng = lng,
            accuracy = accuracy,
            battery = battery,
            motionState = motion,
            receivedAt = timestamp,
            speed = speed,
            bearing = bearing,
            altitude = 18.0,
            expectedIntervalSeconds = 300,
            capturedAt = timestamp,
            source = "gps",
        )
    }

    /**
     * A walk across central London, spread between this morning (never before today's start, so
     * it all lands on the history screen's default day) and two minutes ago. The last point is
     * where the map's own-location dot and camera end up.
     */
    private fun ownTrail(pubkey: String, now: Long): List<HeartbeatEntity> {
        val end = now - 120_000
        val start = max(dayStart(now) + 20 * 60_000, end - 5 * 60 * 60_000)
        val points = 24
        return (0 until points).map { index ->
            val fraction = index / (points - 1).toDouble()
            val (lat, lng) = pointAlongRoute(fraction)
            val timestamp = start + ((end - start) * fraction).toLong()
            val resting = index % 6 == 5
            HeartbeatEntity(
                deviceId = pubkey,
                displayName = ME_NAME,
                timestamp = timestamp,
                lat = lat,
                lng = lng,
                accuracy = 6f + (index % 5),
                battery = 88 - index / 3,
                motionState = if (resting) "stationary" else "walking",
                receivedAt = timestamp,
                speed = if (resting) 0f else 1.2f,
                bearing = 35f,
                altitude = 16.0,
                expectedIntervalSeconds = 300,
                capturedAt = timestamp,
                source = "gps",
            )
        }
    }

    private fun dayStart(now: Long): Long = Calendar.getInstance().apply {
        timeInMillis = now
        set(Calendar.HOUR_OF_DAY, 0)
        set(Calendar.MINUTE, 0)
        set(Calendar.SECOND, 0)
        set(Calendar.MILLISECOND, 0)
    }.timeInMillis

    private fun pointAlongRoute(fraction: Double): Pair<Double, Double> {
        val scaled = fraction.coerceIn(0.0, 1.0) * (ROUTE.size - 1)
        val index = scaled.toInt().coerceAtMost(ROUTE.size - 2)
        val t = scaled - index
        val (lat1, lng1) = ROUTE[index]
        val (lat2, lng2) = ROUTE[index + 1]
        return (lat1 + (lat2 - lat1) * t) to (lng1 + (lng2 - lng1) * t)
    }

    private data class DemoPeer(
        val id: String,
        val name: String,
        val role: String,
        val lat: Double? = null,
        val lng: Double? = null,
        val minutesAgo: Long = 0,
        val motion: String = "",
        val battery: Int = 0,
        val accuracy: Float = 0f,
        val speed: Float = 0f,
        val bearing: Float = 0f,
        val precision: String = "EXACT",
        val sos: Boolean = false,
    )

    private companion object {
        const val EXTRA_SEED = "seed"
        const val EXTRA_START_ROUTE = "start"
        const val ME_NAME = "Alex Rivera"
        const val RELAY_URL = "wss://relay.daygle.net"

        fun pubkey(label: String): String =
            MessageDigest.getInstance("SHA-256")
                .digest("locapeer-demo-$label".toByteArray())
                .joinToString("") { "%02x".format(it) }

        val demoPeers = listOf(
            // Positions are composed around the end of ROUTE (the user's own dot) so all four
            // pins stay clear of the SOS pill, the FAB column and the screen edges at 411dp.
            DemoPeer(
                id = pubkey("maya"), name = "Maya Chen", role = PeerEntity.ROLE_SEND_RECEIVE,
                lat = 51.5110, lng = -0.1271, minutesAgo = 2, motion = "walking",
                battery = 76, accuracy = 6f, speed = 1.3f, bearing = 42f, sos = true,
            ),
            DemoPeer(
                id = pubkey("sam"), name = "Sam Okafor", role = PeerEntity.ROLE_SEND_RECEIVE,
                lat = 51.5093, lng = -0.1299, minutesAgo = 6, motion = "cycling",
                battery = 61, accuracy = 9f, speed = 4.8f, bearing = 210f,
            ),
            DemoPeer(
                id = pubkey("priya"), name = "Priya Raman", role = PeerEntity.ROLE_RECEIVE,
                lat = 51.5107, lng = -0.1308, minutesAgo = 21, motion = "stationary",
                battery = 88, accuracy = 12f, speed = 0f, bearing = 0f, precision = "SUBURB",
            ),
            DemoPeer(
                id = pubkey("jonas"), name = "Jonas Weber", role = PeerEntity.ROLE_SEND_RECEIVE,
                lat = 51.5085, lng = -0.1264, minutesAgo = 43, motion = "walking",
                battery = 34, accuracy = 18f, speed = 1.1f, bearing = 95f,
            ),
            // Messaging-only contact: appears in Contacts, never as a map pin.
            DemoPeer(id = pubkey("dana"), name = "Dana Whitfield", role = PeerEntity.ROLE_NONE),
        )

        /** Trafalgar Square down to the Embankment, then a loop back north. */
        val ROUTE = listOf(
            51.5080 to -0.1281,
            51.5096 to -0.1296,
            51.5112 to -0.1310,
            51.5127 to -0.1324,
            51.5140 to -0.1338,
            51.5152 to -0.1320,
            51.5144 to -0.1296,
            51.5129 to -0.1278,
            51.5113 to -0.1268,
            51.5101 to -0.1281,
        )
    }
}
