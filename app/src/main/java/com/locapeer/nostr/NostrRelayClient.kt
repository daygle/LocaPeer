package com.locapeer.nostr

import android.util.Log
import com.locapeer.crypto.CryptoUtils
import com.locapeer.data.dao.PendingMessageDao
import com.locapeer.data.dao.PeerDao
import com.locapeer.data.entity.PendingMessageEntity
import com.locapeer.settings.AppPreferences
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.decodeFromJsonElement
import kotlinx.serialization.json.encodeToJsonElement
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton

private const val TAG = "NostrRelayClient"

@Singleton
class NostrRelayClient @Inject constructor(
    private val pendingMessageDao: PendingMessageDao,
    private val peerDao: PeerDao,
    private val prefs: AppPreferences,
    private val crypto: CryptoUtils,
) {
    private val json by lazy { 
        Json { 
            ignoreUnknownKeys = true
            encodeDefaults = true
            explicitNulls = false
        }
    }
    private val scope by lazy { CoroutineScope(SupervisorJob() + Dispatchers.IO) }

    @Volatile private var isGloballyConnected = false

    private val client by lazy {
        OkHttpClient.Builder()
            .pingInterval(60, TimeUnit.SECONDS)
            .connectTimeout(60, TimeUnit.SECONDS)
            .readTimeout(0, TimeUnit.SECONDS)
            .writeTimeout(60, TimeUnit.SECONDS)
            .build()
    }

    private val relays by lazy { ConcurrentHashMap<String, RelayConnection>() }

    private val _events by lazy { MutableSharedFlow<NostrEvent>(extraBufferCapacity = 256) }
    val events: SharedFlow<NostrEvent> by lazy { _events }

    private val _relayStatus by lazy { MutableStateFlow(emptyMap<String, Boolean>()) }
    /** Maps relay URL → connected. Updated on every connect/disconnect event. */
    val relayStatus: StateFlow<Map<String, Boolean>> by lazy { _relayStatus.asStateFlow() }

    private val _okEvents by lazy { MutableSharedFlow<String>(extraBufferCapacity = 64) }
    /** Emits Nostr event IDs that any relay accepted (OK true). */
    val okEvents: SharedFlow<String> by lazy { _okEvents }

    private val subsLock by lazy { Any() }
    private val activeSubscriptions by lazy { mutableMapOf<String, String>() }

    private val recentEventLock by lazy { Any() }
    private val recentEventIds by lazy { LinkedHashSet<String>(2048) }
    private val eventsSinceLastSave = java.util.concurrent.atomic.AtomicInteger(0)

    init {
        scope.launch {
            val persisted = prefs.getRecentEventIds()
            if (persisted.isNotEmpty()) {
                synchronized(recentEventLock) {
                    persisted.forEach { id ->
                        if (!recentEventIds.contains(id)) {
                            if (recentEventIds.size >= 2000) {
                                val it = recentEventIds.iterator()
                                if (it.hasNext()) {
                                    it.next()
                                    it.remove()
                                }
                            }
                            recentEventIds.add(id)
                        }
                    }
                }
            }
        }
        scope.launch {
            combine(
                prefs.settings.map { it.customRelays }.distinctUntilChanged(),
                peerDao.getAllPeers().map { peers -> peers.map { it.relayUrl } }.distinctUntilChanged()
            ) { custom, peerRelays ->
                (custom + peerRelays).filter { it.isNotBlank() }.toSet()
            }.collect { allUrls ->
                updateRelays(allUrls.toList())
            }
        }
    }

    private fun updateRelays(urls: List<String>) {
        val currentUrls = relays.keys
        val newUrls = urls.toSet()

        // Remove relays no longer in the list
        (currentUrls - newUrls).forEach { url ->
            relays.remove(url)?.disconnect()
            _relayStatus.update { it - url }
            scope.launch {
                pendingMessageDao.deleteForRelay(url)
            }
        }

        // Add new relays
        (newUrls - currentUrls).forEach { url ->
            val conn = RelayConnection(url)
            relays[url] = conn
            _relayStatus.update { it + (url to false) }
            // If we are currently in "connected" mode, connect this new relay immediately
            if (isGloballyConnected) {
                conn.connect()
            }
        }
    }

    fun connect() {
        isGloballyConnected = true
        relays.values.forEach { it.connect() }
    }

    fun connect(url: String) {
        if (url.isBlank()) return
        val conn = relays.getOrPut(url) {
            _relayStatus.update { it + (url to false) }
            RelayConnection(url)
        }
        if (isGloballyConnected) {
            conn.connect()
        }
    }

    fun disconnect() {
        isGloballyConnected = false
        relays.values.forEach { it.disconnect() }
    }

    fun publishEvent(event: NostrEvent) {
        val msg = buildJsonArray {
            add(JsonPrimitive("EVENT"))
            add(json.encodeToJsonElement(event))
        }.toString()
        sendToAll(msg, isEvent = true)
    }

    fun subscribe(subscriptionId: String, filter: NostrFilter) {
        val msg = buildJsonArray {
            add(JsonPrimitive("REQ"))
            add(JsonPrimitive(subscriptionId))
            add(json.encodeToJsonElement(filter))
        }.toString()
        synchronized(subsLock) {
            activeSubscriptions[subscriptionId] = msg
        }
        sendToAll(msg, isEvent = false)
    }

    fun unsubscribe(subscriptionId: String) {
        synchronized(subsLock) {
            activeSubscriptions.remove(subscriptionId)
        }
        val msg = buildJsonArray {
            add(JsonPrimitive("CLOSE"))
            add(JsonPrimitive(subscriptionId))
        }.toString()
        sendToAll(msg, isEvent = false)
    }

    private fun sendToAll(msg: String, isEvent: Boolean) {
        val disconnectedRelays = mutableListOf<RelayConnection>()

        relays.values.forEach { relay ->
            if (relay.isConnected) {
                val success = relay.send(msg)
                if (!success && isEvent) {
                    scope.launch {
                        pendingMessageDao.insert(PendingMessageEntity(relayUrl = relay.url, content = msg))
                    }
                    relay.scheduleReconnect()
                }
            } else {
                if (isEvent) {
                    disconnectedRelays.add(relay)
                }
                relay.ensureConnecting()
            }
        }

        if (isEvent && disconnectedRelays.isNotEmpty()) {
            scope.launch {
                disconnectedRelays.forEach { relay ->
                    pendingMessageDao.insert(PendingMessageEntity(relayUrl = relay.url, content = msg))
                }
            }
        }
    }

    private fun flushPendingTo(relay: RelayConnection) {
        scope.launch {
            val pending = pendingMessageDao.getForRelay(relay.url)
            pending.forEach { entity ->
                if (relay.send(entity.content)) {
                    pendingMessageDao.delete(entity)
                }
            }
            synchronized(subsLock) {
                activeSubscriptions.values.forEach { relay.send(it) }
            }
        }
    }

    private inner class RelayConnection(val url: String) {
        @Volatile var webSocket: WebSocket? = null
        @Volatile var isConnected = false
        private var reconnectJob: Job? = null
        private var reconnectAttempts = 0

        fun connect() {
            if (isConnected || (webSocket != null)) return
            _relayStatus.update { it + (url to false) }
            try {
                Log.d(TAG, "Connecting to $url")
                webSocket = client.newWebSocket(Request.Builder().url(url).build(), Listener())
            } catch (e: Exception) {
                Log.e(TAG, "Failed to connect to $url: ${e.message}")
                isConnected = false
                webSocket = null
                scheduleReconnect()
            }
        }

        fun disconnect() {
            reconnectJob?.cancel()
            reconnectAttempts = 0
            webSocket?.close(1000, "Disconnecting")
            webSocket = null
            isConnected = false
            _relayStatus.update { it + (url to false) }
        }

        fun ensureConnecting() {
            if (webSocket == null) connect()
        }

        fun send(msg: String): Boolean =
            if (isConnected) webSocket?.send(msg) ?: false else false

        fun scheduleReconnect() {
            if (reconnectJob?.isActive == true) return
            reconnectJob = scope.launch {
                // Exponential backoff: 5s, 10s, 20s, 40s … capped at 5 minutes, +±20% jitter
                val baseDelay = minOf(5_000L * (1L shl reconnectAttempts.coerceAtMost(6)), 300_000L)
                val jitter = ((baseDelay * 0.2) * (Math.random() * 2 - 1)).toLong()
                val waitMs = (baseDelay + jitter).coerceAtLeast(1_000L)
                Log.d(TAG, "Reconnecting to $url in ${waitMs}ms (attempt ${reconnectAttempts + 1})")
                delay(waitMs)
                if (!isGloballyConnected) return@launch
                // Don't overwrite if another connection attempt started in the meantime
                if (isConnected || webSocket != null) return@launch
                reconnectAttempts++
                connect()
            }
        }

        private inner class Listener : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) {
                Log.d(TAG, "Connected to $url")
                isConnected = true
                reconnectAttempts = 0
                _relayStatus.update { it + (url to true) }
                flushPendingTo(this@RelayConnection)
            }

            override fun onMessage(webSocket: WebSocket, text: String) {
                try {
                    val arr = json.parseToJsonElement(text) as? JsonArray ?: return
                    if (arr.isEmpty()) return
                    val type = (arr[0] as? JsonPrimitive)?.content ?: return
                    when (type) {
                        "EVENT" -> {
                            if (arr.size < 3) return
                            val event = json.decodeFromJsonElement<NostrEvent>(arr[2])
                            // Fast path: an id we've already accepted (a valid copy from another
                            // relay, or a relay retransmit) needs no re-verification. This keeps
                            // the "verify each event at most once across all relays" property.
                            if (synchronized(recentEventLock) { recentEventIds.contains(event.id) }) return
                            // Verify the signature BEFORE recording the id. The event id is a hash
                            // of the content only — it does not cover the signature — so a forged
                            // event can echo a known id with a content it doesn't hold the key for.
                            // If such an event were cached, the genuine event later arriving from an
                            // honest relay would be dropped as a duplicate. Refusing to cache
                            // unverified ids closes that cross-relay suppression vector.
                            if (!NostrEvent.verify(event, crypto)) {
                                Log.w(TAG, "Dropping event ${event.id} from $url: invalid signature")
                                return
                            }
                            val isNew = synchronized(recentEventLock) {
                                if (recentEventIds.contains(event.id)) {
                                    false
                                } else {
                                    if (recentEventIds.size >= 2000) {
                                        val it = recentEventIds.iterator()
                                        if (it.hasNext()) {
                                            it.next()
                                            it.remove()
                                        }
                                    }
                                    recentEventIds.add(event.id)
                                    true
                                }
                            }
                            if (isNew) {
                                if (eventsSinceLastSave.incrementAndGet() >= 100) {
                                    eventsSinceLastSave.set(0)
                                    val snapshot = synchronized(recentEventLock) { recentEventIds.toSet() }
                                    scope.launch { prefs.saveRecentEventIds(snapshot) }
                                }
                                scope.launch { _events.emit(event) }
                            }
                        }
                        "EOSE" -> if (arr.size >= 2) Log.d(TAG, "EOSE for sub ${arr[1]} from $url")
                        "NOTICE" -> if (arr.size >= 2) Log.d(TAG, "NOTICE from $url: ${(arr[1] as? JsonPrimitive)?.content ?: arr[1]}")
                        "OK" -> {
                            if (arr.size < 3) return
                            val eventId = (arr[1] as? JsonPrimitive)?.content ?: return
                            val accepted = (arr[2] as? JsonPrimitive)?.content?.toBooleanStrictOrNull() ?: false
                            val message = if (arr.size > 3) (arr[3] as? JsonPrimitive)?.content ?: "" else ""
                            if (accepted) {
                                scope.launch { _okEvents.emit(eventId) }
                                Log.i(TAG, "OK from $url: accepted $eventId")
                            } else {
                                Log.w(TAG, "OK from $url: rejected $eventId - $message")
                            }
                        }
                    }
                } catch (e: Exception) {
                    Log.w(TAG, "Failed to parse message from $url", e)
                }
            }

            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                val code = response?.code
                when {
                    code == 502 || t.message?.contains("502") == true -> {
                        Log.d(TAG, "Relay $url is temporarily offline (502 Bad Gateway)")
                    }
                    code == 503 || t.message?.contains("503") == true -> {
                        Log.d(TAG, "Relay $url is currently busy (503 Service Unavailable)")
                    }
                    code == 504 || t.message?.contains("504") == true -> {
                        Log.d(TAG, "Relay $url timed out (504 Gateway Timeout)")
                    }
                    t is java.net.SocketTimeoutException -> {
                        Log.d(TAG, "Relay $url connection attempt timed out")
                    }
                    else -> {
                        Log.w(TAG, "Relay $url connection issue: ${t.message ?: "Unknown error"}")
                    }
                }
                isConnected = false
                this@RelayConnection.webSocket = null
                _relayStatus.update { it + (url to false) }
                scheduleReconnect()
            }

            override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                Log.d(TAG, "Closed $url: $code $reason")
                isConnected = false
                this@RelayConnection.webSocket = null
                _relayStatus.update { it + (url to false) }
                if (code != 1000) scheduleReconnect()
            }
        }
    }
}
