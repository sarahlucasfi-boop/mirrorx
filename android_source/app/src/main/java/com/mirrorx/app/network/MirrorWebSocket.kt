package com.mirrorx.app.network

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import okhttp3.*
import org.json.JSONObject
import java.util.concurrent.TimeUnit

/**
 * WebSocket client for MirrorX v1.7.0 — C# DXGI server.
 *
 * Protocol (v1.7.0):
 *   Server → Client: raw JPEG binary frames (no header)
 *   Client → Server: JSON {"type":"down|move|up","x":0.0-1.0,"y":0.0-1.0}
 *   Port: 8080
 */
class MirrorWebSocket {

    sealed class ConnectionState {
        data object Disconnected : ConnectionState()
        data object Connecting : ConnectionState()
        data object Connected : ConnectionState()
        data class Error(val message: String) : ConnectionState()
    }

    sealed class ScreenInfo {
        data object Unknown : ScreenInfo()
        data class Known(
            val width: Int,
            val height: Int,
            val streamWidth: Int,
            val streamHeight: Int
        ) : ScreenInfo()
    }

    private var client: OkHttpClient? = null
    private var ws: WebSocket? = null
    private var scope: CoroutineScope? = null

    private val _connectionState = MutableStateFlow<ConnectionState>(ConnectionState.Disconnected)
    val connectionState: StateFlow<ConnectionState> = _connectionState

    private val _currentFrame = MutableStateFlow<Bitmap?>(null)
    val currentFrame: StateFlow<Bitmap?> = _currentFrame

    private val _remoteCursor = MutableStateFlow<Pair<Float, Float>?>(null)
    val remoteCursor: StateFlow<Pair<Float, Float>?> = _remoteCursor

    private val _screenInfo = MutableStateFlow<ScreenInfo>(ScreenInfo.Unknown)
    val screenInfo: StateFlow<ScreenInfo> = _screenInfo

    private val _fps = MutableStateFlow(0)
    val fps: StateFlow<Int> = _fps

    private val _mousePos = MutableStateFlow(androidx.compose.ui.geometry.Offset(-1f, -1f))
    val mousePos: StateFlow<androidx.compose.ui.geometry.Offset> = _mousePos

    private val _latencyMs = MutableStateFlow(0)
    val latencyMs: StateFlow<Int> = _latencyMs

    private val _cursorVisible = MutableStateFlow(true)
    val cursorVisible: StateFlow<Boolean> = _cursorVisible

    data class AdaptInfo(
        val mode: String = "auto",
        val reason: String = "",
        val quality: Int = 0,
        val scale: Int = 0,
        val fps: Float = 0f
    )
    private val _serverAdapt = MutableStateFlow(AdaptInfo())
    val serverAdapt: StateFlow<AdaptInfo> = _serverAdapt

    private var frameCount = 0
    private var lastFpsTime = System.currentTimeMillis()

    var onTouchEvent: ((Float, Float, String) -> Unit)? = null

    // Pro/Free licensing: FPS cap
    var maxFps: Int = 24  // Set by UI based on Pro status
    private var lastFrameTimeMs = 0L

    // v1.7.0: auto-reconnect state
    private var _lastHost: String? = null
    private var _lastPort: Int = 8080
    private var _reconnectAttempts = 0
    private val maxReconnectAttempts = 5
    var autoReconnect: Boolean = true

    fun connect(host: String, port: Int = 8080) {
        disconnect()

        _lastHost = host
        _lastPort = port
        _reconnectAttempts = 0

        _connectionState.value = ConnectionState.Connecting
        scope = CoroutineScope(Dispatchers.IO + SupervisorJob())

        client = OkHttpClient.Builder()
            .readTimeout(0, TimeUnit.MILLISECONDS)
            .pingInterval(15, TimeUnit.SECONDS)
            .build()

        val request = Request.Builder()
            .url("ws://$host:$port")
            .build()

        ws = client!!.newWebSocket(request, object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) {
                _connectionState.value = ConnectionState.Connected
            }

            override fun onMessage(webSocket: WebSocket, text: String) {
                // v1.7.0: server may send JSON status messages (future)
                try {
                    val json = JSONObject(text)
                    when (json.optString("type")) {
                        "screen_info" -> {
                            _screenInfo.value = ScreenInfo.Known(
                                width = json.optInt("width", 1920),
                                height = json.optInt("height", 1080),
                                streamWidth = json.optInt("stream_width", 960),
                                streamHeight = json.optInt("stream_height", 540)
                            )
                        }
                    }
                } catch (_: Exception) {}
            }

            override fun onMessage(webSocket: WebSocket, bytes: okio.ByteString) {
                // v1.7.0: raw JPEG frame — no header, just decode directly
                try {
                    val data = bytes.toByteArray()
                    val bitmap = BitmapFactory.decodeByteArray(data, 0, data.size)
                    if (bitmap != null) {
                        // FPS throttling for free users
                        val now = System.currentTimeMillis()
                        val minInterval = if (maxFps > 0) 1000L / maxFps else 0L
                        if (now - lastFrameTimeMs < minInterval) return
                        lastFrameTimeMs = now

                        _currentFrame.value = bitmap

                        // FPS counter
                        frameCount++
                        val elapsed = now - lastFpsTime
                        if (elapsed >= 1000) {
                            _fps.value = (frameCount * 1000 / elapsed).toInt()
                            frameCount = 0
                            lastFpsTime = now
                        }
                    }
                } catch (_: Exception) {}
            }

            override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
                webSocket.close(1000, null)
                _connectionState.value = ConnectionState.Disconnected
            }

            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                _connectionState.value = ConnectionState.Error(
                    t.localizedMessage ?: "Connection failed"
                )
                // Auto-reconnect with backoff
                if (autoReconnect && _reconnectAttempts < maxReconnectAttempts && _lastHost != null) {
                    _reconnectAttempts++
                    val delayMs = 1000L * _reconnectAttempts
                    scope?.launch {
                        kotlinx.coroutines.delay(delayMs)
                        if (_lastHost != null && _connectionState.value !is ConnectionState.Connected) {
                            try {
                                connect(_lastHost!!, _lastPort)
                            } catch (_: Exception) {}
                        }
                    }
                }
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
        _currentFrame.value = null
        _screenInfo.value = ScreenInfo.Unknown
    }

    // --- v1.7.0: Simplified touch protocol ---
    // Sends: {"type":"down|move|up","x":0.0-1.0,"y":0.0-1.0}
    fun sendTouchSimple(x: Float, y: Float, type: String) {
        val json = JSONObject().apply {
            put("type", type)
            put("x", x.toDouble())
            put("y", y.toDouble())
        }
        ws?.send(json.toString())
        onTouchEvent?.invoke(x, y, type)
    }

    // Legacy compatibility: map old action names to new protocol
    fun sendTouch(
        x: Float, y: Float, action: String,
        pressure: Float = 0.5f,
        tilt: Float = 0f,
        tool: String = "finger",
        buttons: Int = 0
    ) {
        val type = when (action) {
            "down" -> "down"
            "move", "drag" -> "move"
            "up" -> "up"
            "click" -> {
                // For click: send down then up
                sendTouchSimple(x, y, "down")
                sendTouchSimple(x, y, "up")
                return
            }
            else -> return
        }
        sendTouchSimple(x, y, type)
    }

    // --- Legacy stubs (no-ops in v1.7.0) ---
    fun sendTouchPath(points: List<Any>, pressure: Float = 0.5f, tilt: Float = 0f, tool: String = "finger", buttons: Int = 0) {}
    fun sendTouchPathBinary(points: List<Any>, tool: String = "finger", buttons: Int = 0) {}
    fun sendPinch(scale: Float, centerX: Float, centerY: Float) {}
    fun sendCursorVisibility(visible: Boolean) {}
    fun sendClickRequest(button: String) {}
    fun sendHermesMove(dx: Int, dy: Int) {}
    fun sendHermesClick(button: Int) {}
    fun sendHermesScroll(v: Int) {}
    fun sendHermesKey(key: String, press: Boolean) {}
    fun sendHermesQuality(mode: Int) {}
    fun sendHermesHeartbeat(ms: Int) {}
    fun sendMirrorConfig(key: String, value: Any) {}
}
