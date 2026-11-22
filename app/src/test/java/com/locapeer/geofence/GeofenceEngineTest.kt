package com.locapeer.geofence

import android.app.NotificationManager
import android.content.Context
import com.locapeer.crypto.CryptoUtils
import com.locapeer.crypto.KeyManager
import com.locapeer.data.dao.ActiveGeofence
import com.locapeer.data.dao.GeofenceAssignmentDao
import com.locapeer.data.dao.GeofenceDao
import com.locapeer.data.entity.GeofenceAssignmentEntity
import com.locapeer.data.entity.GeofenceEntity
import com.locapeer.data.entity.HeartbeatEntity
import com.locapeer.nostr.NostrRelayClient
import com.locapeer.settings.AppPreferences
import com.locapeer.settings.AppSettings
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.mockito.ArgumentMatchers.any
import org.mockito.ArgumentMatchers.anyInt
import org.mockito.Mockito.mock
import org.mockito.Mockito.`when`

/**
 * Behavioural tests for the polygon-specific paths in [GeofenceEngine]: the accuracy gate
 * before a polygon arrival counts, seeding the initial inside/outside state from the stored
 * previous heartbeat, and the per-area decoded-ring cache.
 *
 * The engine's observable effects are its notification bookkeeping and decoded ring cache,
 * both of which are read back by reflection rather than by asserting on Android
 * notifications: the transition decision (which is what these tests cover) writes the
 * cooldown entry before any Notification is built, so alert settings can stay disabled and
 * no Android notification stack is needed.
 */
class GeofenceEngineTest {

    private lateinit var context: Context
    private lateinit var assignmentDao: GeofenceAssignmentDao
    private lateinit var geofenceDao: GeofenceDao
    private lateinit var notificationManager: NotificationManager
    private lateinit var relayClient: NostrRelayClient
    private lateinit var keyManager: KeyManager
    private lateinit var crypto: CryptoUtils
    private lateinit var prefs: AppPreferences

    private val deviceId = "peer-1"
    private val assignmentFlow = MutableStateFlow<List<GeofenceAssignmentEntity>>(emptyList())
    private val geofenceFlow = MutableStateFlow<List<GeofenceEntity>>(emptyList())
    private var activeFences: List<ActiveGeofence> = emptyList()

    /** ~1.1 km square around the origin; its area-equivalent radius is ~628 m. */
    private val squareRing = "0.0,0.0;0.0,0.01;0.01,0.01;0.01,0.0"

    private fun fence(
        triggerOn: String = "BOTH",
        polygon: String? = squareRing
    ) = ActiveGeofence(
        id = "fence-1",
        name = "Square",
        lat = 0.005,
        lng = 0.005,
        radiusMetres = 628,
        triggerOn = triggerOn,
        scheduleRules = "",
        polygon = polygon
    )

    /**
     * Replays a stored row for fence-1 before the engine starts. The engine's startup
     * collector prunes inside/notification state for areas it does not know about, and the
     * geofence flow is empty by default here, so that first emission could otherwise wipe
     * the very bookkeeping these tests assert on mid-test - including the inside state that
     * turns a sharp fix into an arrival instead of a first-ever (silent) observation.
     * Seeding the row makes the prune harmless whenever the emission lands.
     */
    private fun seedGeofenceRow() {
        geofenceFlow.value = listOf(
            GeofenceEntity(
                id = "fence-1",
                name = "Square",
                lat = 0.005,
                lng = 0.005,
                radiusMetres = 628,
                polygon = squareRing
            )
        )
    }

    private fun heartbeat(
        lat: Double,
        lng: Double,
        accuracy: Float,
        timestamp: Long = System.currentTimeMillis()
    ) = HeartbeatEntity(
        deviceId = deviceId,
        displayName = "Alex",
        timestamp = timestamp,
        lat = lat,
        lng = lng,
        accuracy = accuracy,
        battery = 80,
        motionState = "STILL",
        expectedIntervalSeconds = 60
    )

    @Before
    fun setUp() {
        context = mock(Context::class.java)
        assignmentDao = mock(GeofenceAssignmentDao::class.java)
        geofenceDao = mock(GeofenceDao::class.java)
        notificationManager = mock(NotificationManager::class.java)
        relayClient = mock(NostrRelayClient::class.java)
        keyManager = mock(KeyManager::class.java)
        crypto = mock(CryptoUtils::class.java)
        prefs = mock(AppPreferences::class.java)

        `when`(assignmentDao.observeAll()).thenReturn(assignmentFlow)
        `when`(geofenceDao.getAllGeofences()).thenReturn(geofenceFlow)
        `when`(context.getString(anyInt(), any(), any())).thenReturn("notification")
        `when`(context.getString(anyInt(), any())).thenReturn("notification")
        `when`(context.getString(anyInt())).thenReturn("notification")
        // Alerts are suppressed so no Android Notification is built; the transition's
        // cooldown bookkeeping still records that an alert was due.
        `when`(prefs.settings).thenReturn(flowOf(AppSettings(alertNotificationsEnabled = false)))

        runBlocking {
            `when`(assignmentDao.getActiveGeofencesForDevice(deviceId))
                .thenAnswer { activeFences }
        }
    }

    private fun engine(): GeofenceEngine = GeofenceEngine(
        context = context,
        geofenceAssignmentDao = assignmentDao,
        geofenceDao = geofenceDao,
        notificationManager = notificationManager,
        relayClient = relayClient,
        keyManager = keyManager,
        crypto = crypto,
        prefs = prefs
    )

    private fun insideState(engine: GeofenceEngine): Map<*, *> = readField(engine, "insideState") as Map<*, *>

    private fun notifiedKeys(engine: GeofenceEngine): Set<*> =
        (readField(engine, "lastNotifiedAt") as Map<*, *>).keys

    private fun ringCache(engine: GeofenceEngine): Map<*, *> = readField(engine, "ringCache") as Map<*, *>

    private fun readField(engine: GeofenceEngine, name: String): Any? =
        GeofenceEngine::class.java.getDeclaredField(name).apply { isAccessible = true }.get(engine)

    @Test
    fun `a coarse fix inside a polygon is gated until a sharper arrival fix`() {
        activeFences = listOf(fence())
        seedGeofenceRow()
        val engine = engine()

        runBlocking {
            // Seed the person outside the fence with a sharp fix.
            engine.evaluate(heartbeat(0.05, 0.05, accuracy = 10f), previous = null)
            // Inside the polygon, but 2000 m accuracy against a ~628 m fence: gated.
            engine.evaluate(
                heartbeat(0.005, 0.005, accuracy = 2000f),
                previous = heartbeat(0.05, 0.05, accuracy = 10f)
            )
        }

        assertEquals(false, insideState(engine)["fence-1:$deviceId"])
        assertNull(notifiedKeys(engine).firstOrNull { it == "fence-1:$deviceId:enter" })

        runBlocking {
            engine.evaluate(
                heartbeat(0.005, 0.005, accuracy = 10f),
                previous = heartbeat(0.005, 0.005, accuracy = 2000f)
            )
        }

        assertEquals(true, insideState(engine)["fence-1:$deviceId"])
        assertTrue(notifiedKeys(engine).contains("fence-1:$deviceId:enter"))
    }

    @Test
    fun `a coarse fix just outside a polygon still fires an exit`() {
        activeFences = listOf(fence())
        seedGeofenceRow()
        val engine = engine()

        runBlocking {
            engine.evaluate(heartbeat(0.005, 0.005, accuracy = 10f), previous = null)
            // 0.05° away is far outside even with a 500 m accuracy buffer; exits are never gated.
            engine.evaluate(
                heartbeat(0.05, 0.05, accuracy = 500f),
                previous = heartbeat(0.005, 0.005, accuracy = 10f)
            )
        }

        assertTrue(notifiedKeys(engine).contains("fence-1:$deviceId:exit"))
    }

    @Test
    fun `a stored recent heartbeat inside the polygon seeds the inside state`() {
        activeFences = listOf(fence())
        seedGeofenceRow()
        val engine = engine()
        val now = System.currentTimeMillis()

        runBlocking {
            // First evaluation after a restart: no in-memory state. The previous stored
            // heartbeat places the person inside, so the move outside is a real exit.
            engine.evaluate(
                heartbeat(0.05, 0.05, accuracy = 10f, timestamp = now),
                previous = heartbeat(0.005, 0.005, accuracy = 20f, timestamp = now - 60_000)
            )
        }

        assertTrue(notifiedKeys(engine).contains("fence-1:$deviceId:exit"))
    }

    @Test
    fun `a stored heartbeat older than the seeding window does not seed`() {
        activeFences = listOf(fence())
        seedGeofenceRow()
        val engine = engine()
        val now = System.currentTimeMillis()

        runBlocking {
            engine.evaluate(
                heartbeat(0.05, 0.05, accuracy = 10f, timestamp = now),
                previous = heartbeat(
                    0.005, 0.005,
                    accuracy = 20f,
                    timestamp = now - 11 * 60 * 1000L
                )
            )
        }

        // First-ever observation seeds silently: the person was not observed leaving.
        assertTrue(notifiedKeys(engine).isEmpty())
        assertEquals(false, insideState(engine)["fence-1:$deviceId"])
    }

    @Test
    fun `the decoded polygon ring is cached per area and cleared when areas change`() {
        activeFences = listOf(fence())
        // Both startup collectors clear the ring cache unconditionally, so unlike the state
        // prune (which a seeded row survives) no fixture value makes a late first emission
        // harmless here. Give the engine flows that do not replay anything instead: then the
        // only emission is the one below, and no sleep is needed to get out of its way.
        val assignmentEmissions = MutableSharedFlow<List<GeofenceAssignmentEntity>>()
        val geofenceEmissions = MutableSharedFlow<List<GeofenceEntity>>()
        `when`(assignmentDao.observeAll()).thenReturn(assignmentEmissions)
        `when`(geofenceDao.getAllGeofences()).thenReturn(geofenceEmissions)
        val engine = engine()

        runBlocking {
            engine.evaluate(heartbeat(0.005, 0.005, accuracy = 10f), previous = null)
            engine.evaluate(heartbeat(0.005, 0.005, accuracy = 10f), previous = null)
        }
        assertEquals(1, ringCache(engine).size)

        // A new geofence emission invalidates the cache, so an edited ring cannot keep
        // being evaluated from a stale decode. Wait for the collector to attach first, so
        // the emission cannot be dropped by a replay-less flow with no subscriber yet.
        runBlocking {
            withTimeout(5_000) { geofenceEmissions.subscriptionCount.first { it > 0 } }
            geofenceEmissions.emit(
                listOf(
                    GeofenceEntity(
                        id = "fence-1",
                        name = "Square",
                        lat = 0.005,
                        lng = 0.005,
                        radiusMetres = 628,
                        polygon = squareRing
                    )
                )
            )
        }

        val deadline = System.currentTimeMillis() + 2_000
        while (ringCache(engine).isNotEmpty() && System.currentTimeMillis() < deadline) {
            Thread.sleep(20)
        }
        assertTrue("ring cache should be cleared by the geofence emission", ringCache(engine).isEmpty())
        assertFalse(ringCache(engine).containsKey("fence-1"))
    }
}
