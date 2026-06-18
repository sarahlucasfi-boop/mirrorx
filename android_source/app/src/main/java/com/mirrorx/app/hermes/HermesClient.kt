package com.mirrorx.app.hermes

import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import okhttp3.*
import org.json.JSONObject
import java.util.concurrent.TimeUnit

/**
 * v1.5.0 "Hermes" — WebSocket client for mouse-only mode.
 *
 * This is a SEPARATE class from MirrorWebSocket because the message
 * shapes are completely different (no JPEG frames, just tiny JSON
 * commands). Sharing one class with optional JSON-only mode would
 * have made the existing mirror code messier for no real gain.
 *
 * The connection state is exposed as a StateFlow so the UI can react
 * (show/hide the touchpad, status dot, etc).
 */
class HermesClient {

    sealed class ConnectionState {
        data object Disconnected : ConnectionState()
        data object Connecting : ConnectionState()
        data object Connected : ConnectionState()
        data class Error(val message: String) : ConnectionState()
    }

    private var client: OkHttpClient? = null
    private var ws: WebSocket? = null
    private var scope: CoroutineScope? = null

    // Public reactive state
    private val _connectionState = MutableStateFlow<ConnectionState>(ConnectionState.Disconnected)
    val connectionState: StateFlow<ConnectionState> = _connectionState

    // Last time we successfully sent a packet (for ping/heartbeat)
    private var lastSendTime = 0L

    // Server-reported mode
    private val _serverMode = MutableStateFlow(0)
    val serverMode: StateFlow<Int> = _serverMode

    // Server hello (version)
    private val _serverVersion = MutableStateFlow<String?>(null)
    val serverVersion: StateFlow<String?> = _serverVersion

    // PC cursor position (for ghost cursor on tablet)
    private val _remoteCursor = MutableStateFlow<Pair<Float, Float>?>(null)
    val remoteCursor: StateFlow<Pair<Float, Float>?> = _remoteCursor

    // Throughput counters (for the status bar)
    private val _packetsSent = MutableStateFlow(0)
    val packetsSent: StateFlow<Int> = _packetsSent

    fun connect(host: String, port: Int = 9900) {
        disconnect()
        _connectionState.value = ConnectionState.Connecting
        scope = CoroutineScope(Dispatchers.IO + SupervisorJob())

        client = OkHttpClient.Builder()
            .readTimeout(0, TimeUnit.MILLISECONDS)
            .pingInterval(20, TimeUnit.SECONDS)
            .build()

        val request = Request.Builder()
            .url("ws://$host:$port/")
            .build()

        ws = client!!.newWebSocket(request, object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) {
                _connectionState.value = ConnectionState.Connected
            }

            override fun onMessage(webSocket: WebSocket, text: String) {
                val msg = HermesProtocol.parse(text) ?: return
                when (msg) {
                    is HermesMessage.Hello -> {
                        _serverVersion.value = msg.version
                    }
                    is HermesMessage.Ack -> {
                        // no-op; we don't really care
                    }
                    is HermesMessage.Mode -> {
                        _serverMode.value = msg.m
                    }
                    is HermesMessage.CursorPos -> {
                        _remoteCursor.value = Pair(msg.x, msg.y)
                    }
                    is HermesMessage.Error -> {
                        // Surface as a generic error; UI can show toast
                    }
                }
            }

            override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
                webSocket.close(1000, null)
                _connectionState.value = ConnectionState.Disconnected
            }

            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                _connectionState.value = ConnectionState.Error(
                    t.localizedMessage ?: "Connection failed"
                )
            }
        })
    }

    fun disconnect() {
        ws?.close(1000, "User disconnect")
        ws = null
        client?.dispatcher?.executorService?.shutdown()
        client = null
        scope?.cancel()
        scope = null
        _connectionState.value = ConnectionState.Disconnected
        _remoteCursor.value = null
        _serverVersion.value = null
    }

    // ------------------------------------------------------------------
    // Send helpers — all return Boolean so the UI can throttle/retry.
    // ------------------------------------------------------------------
    fun sendMove(dx: Int, dy: Int): Boolean =
        send(HermesProtocol.move(dx, dy))

    fun sendClick(button: Int): Boolean =
        send(HermesProtocol.click(button))

    fun sendScroll(value: Int): Boolean =
        send(HermesProtocol.scroll(value))

    fun sendKey(key: String, pressed: Boolean): Boolean =
        send(HermesProtocol.key(key, pressed))

    fun sendQuality(mode: Int): Boolean =
        send(HermesProtocol.quality(mode))

    fun sendHeartbeat(ms: Int): Boolean =
        send(HermesProtocol.heartbeat(ms))

    private fun send(json: String): Boolean {
        val socket = ws ?: return false
        if (_connectionState.value !is ConnectionState.Connected) return false
        return try {
            val ok = socket.send(json)
            if (ok) {
                lastSendTime = System.currentTimeMillis()
                _packetsSent.value = _packetsSent.value + 1
            }
            ok
        } catch (_: Exception) {
            false
        }
    }

    /** How long since the last successful send. */
    fun idleMs(): Long = System.currentTimeMillis() - lastSendTime
}
