package com.locapeer.nostr

import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.launch
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton

private const val TAG = "NostrRelayClient"

@Singleton
class NostrRelayClient @Inject constructor() {

    private val json = Json { ignoreUnknownKeys = true }
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private var webSocket: WebSocket? = null
    private var currentRelayUrl: String = "wss://relay.damus.io"
    private var isConnected = false

    private val _events = MutableSharedFlow<NostrEvent>(extraBufferCapacity = 256)
    val events: SharedFlow<NostrEvent> = _events

    private val pendingMessages = ArrayDeque<String>()
    private val activeSubscriptions = mutableMapOf<String, String>()

    private val client = OkHttpClient.Builder()
        .pingInterval(30, TimeUnit.SECONDS)
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(0, TimeUnit.SECONDS)
        .build()

    fun connect(relayUrl: String = currentRelayUrl) {
        if (isConnected && relayUrl == currentRelayUrl) return
        currentRelayUrl = relayUrl
        disconnect()
        val request = Request.Builder().url(relayUrl).build()
        webSocket = client.newWebSocket(request, NostrWebSocketListener())
        Log.d(TAG, "Connecting to $relayUrl")
    }

    fun disconnect() {
        webSocket?.close(1000, "Disconnecting")
        webSocket = null
        isConnected = false
    }

    fun publishEvent(event: NostrEvent) {
        val msg = buildJsonArray {
            add(JsonPrimitive("EVENT"))
            add(json.parseToJsonElement(json.encodeToString(event)))
        }.toString()
        sendOrQueue(msg)
    }

    fun subscribe(subscriptionId: String, filter: NostrFilter) {
        val filterJson = json.encodeToString(filter)
        val msg = buildJsonArray {
            add(JsonPrimitive("REQ"))
            add(JsonPrimitive(subscriptionId))
            add(json.parseToJsonElement(filterJson))
        }.toString()
        activeSubscriptions[subscriptionId] = msg
        sendOrQueue(msg)
    }

    fun unsubscribe(subscriptionId: String) {
        activeSubscriptions.remove(subscriptionId)
        val msg = buildJsonArray {
            add(JsonPrimitive("CLOSE"))
            add(JsonPrimitive(subscriptionId))
        }.toString()
        sendOrQueue(msg)
    }

    private fun sendOrQueue(msg: String) {
        if (isConnected) {
            webSocket?.send(msg)
        } else {
            pendingMessages.addLast(msg)
            if (webSocket == null) connect()
        }
    }

    private fun flushPending() {
        while (pendingMessages.isNotEmpty()) {
            val msg = pendingMessages.removeFirst()
            webSocket?.send(msg)
        }
        activeSubscriptions.values.forEach { webSocket?.send(it) }
    }

    private fun scheduleReconnect() {
        scope.launch {
            delay(5_000)
            connect(currentRelayUrl)
        }
    }

    private inner class NostrWebSocketListener : WebSocketListener() {
        override fun onOpen(webSocket: WebSocket, response: Response) {
            Log.d(TAG, "Connected to relay")
            isConnected = true
            flushPending()
        }

        override fun onMessage(webSocket: WebSocket, text: String) {
            try {
                val arr = json.parseToJsonElement(text).jsonArray
                when (arr[0].jsonPrimitive.content) {
                    "EVENT" -> {
                        val eventJson = arr[2].toString()
                        val event = json.decodeFromString<NostrEvent>(eventJson)
                        scope.launch { _events.emit(event) }
                    }
                    "EOSE" -> Log.d(TAG, "End of stored events for sub: ${arr[1]}")
                    "NOTICE" -> Log.d(TAG, "Relay notice: ${arr[1]}")
                    "OK" -> Log.d(TAG, "Event accepted: ${arr[1]}")
                }
            } catch (e: Exception) {
                Log.w(TAG, "Failed to parse relay message", e)
            }
        }

        override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
            Log.w(TAG, "WebSocket failure", t)
            isConnected = false
            scheduleReconnect()
        }

        override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
            Log.d(TAG, "WebSocket closed: $code $reason")
            isConnected = false
            if (code != 1000) scheduleReconnect()
        }
    }
}
