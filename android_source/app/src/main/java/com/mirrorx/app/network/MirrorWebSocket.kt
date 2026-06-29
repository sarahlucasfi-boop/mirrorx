package com.mirrorx.app.network

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import okhttp3.*
import okio.ByteString.Companion.toByteString
import org.json.JSONObject
import java.nio.ByteBuffer
import java.util.concurrent.TimeUnit

/**
 * WebSocket client for MirrorX screen streaming.
 *
 * v1.2 changes:
 *  - Frame header is now 11 bytes (was 9 in v1.1.0):
 *      type(1) + jpeg_len(4) + mouse_x(2) + mouse_y(2) +
 *      cursor_visible(1) + reserved(1) + jpeg
 *  - sendTouch accepts pressure, tilt, tool, buttons
 *  - sendCursorVisibility(visible) — server can hide cursor when
 *    stylus is active (saves rendering on tablet)
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

    // v2.0.0: Dirty region renderer — frame buffer permanente para partial updates
    private var frameBuffer: Bitmap? = null
    private var frameBufferWidth: Int = 0
    private var frameBufferHeight: Int = 0

    // v2.0.0: Bandwidth savings tracking para partial updates
    private val _bandwidthSavings = MutableStateFlow(0)
    val bandwidthSavings: StateFlow<Int> = _bandwidthSavings
    
    private var totalFrameBytes: Long = 0
    private var partialBytesReceived: Long = 0

    // v1.4.1: cursor position confirmed by the server. Used by the ghost
    // cursor overlay to know where the PC cursor actually is. The local
    // predicted cursor (from touch) interpolates toward this value.
    private val _remoteCursor = MutableStateFlow<Pair<Float, Float>?>(null)
    val remoteCursor: StateFlow<Pair<Float, Float>?> = _remoteCursor

    private val _screenInfo = MutableStateFlow<ScreenInfo>(ScreenInfo.Unknown)
    val screenInfo: StateFlow<ScreenInfo> = _screenInfo

    private val _fps = MutableStateFlow(0)
    val fps: StateFlow<Int> = _fps

    // v1.0.5+: mouse position overlay (server sends with each frame)
    private val _mousePos = MutableStateFlow(androidx.compose.ui.geometry.Offset(-1f, -1f))
    val mousePos: StateFlow<androidx.compose.ui.geometry.Offset> = _mousePos

    // v1.5.9: latency in milliseconds (from server hello with ms field)
    private val _latencyMs = MutableStateFlow(0)
    val latencyMs: StateFlow<Int> = _latencyMs

    // v1.2: server tells us whether to draw the cursor (driven by
    // server-side mouse-tracking; APK can also request hide via
    // sendCursorVisibility(false))
    private val _cursorVisible = MutableStateFlow(true)
    val cursorVisible: StateFlow<Boolean> = _cursorVisible

    // v1.8: cursor is within the current monitor bounds
    private val _cursorInBounds = MutableStateFlow(false)
    val cursorInBounds: StateFlow<Boolean> = _cursorInBounds

    // v1.2.2: server sends adaptation updates so the APK can show
    // an "Adapt." badge when the server is reducing quality/scale
    // to maintain 30+ FPS.
    data class AdaptInfo(
        val mode: String = "auto",
        val reason: String = "",
        val quality: Int = 0,
        val scale: Int = 0,
        val fps: Float = 0f
    )
    private val _serverAdapt = MutableStateFlow(AdaptInfo())
    val serverAdapt: StateFlow<AdaptInfo> = _serverAdapt

    // v2.0.0: HUD enviado pelo servidor via JSON "hud_v2" a cada 2s.
    private val _hud = MutableStateFlow<ServerHud?>(null)
    val hud: StateFlow<ServerHud?> = _hud

    // v1.7.2: Multi-monitor support
    data class MonitorInfo(
        val idx: Int = 0,
        val name: String = "Monitor 1",
        val w: Int = 1920,
        val h: Int = 1080,
        val left: Int = 0,
        val top: Int = 0
    )
    private val _monitors = MutableStateFlow<List<MonitorInfo>>(emptyList())
    val monitors: StateFlow<List<MonitorInfo>> = _monitors

    private val _currentMonitorIdx = MutableStateFlow(0)
    val currentMonitorIdx: StateFlow<Int> = _currentMonitorIdx

    private var frameCount = 0
    private var lastFpsTime = System.currentTimeMillis()

    // --- v1.1: Touch callback (for legacy callers) ---
    var onTouchEvent: ((Float, Float, String) -> Unit)? = null

    // v1.8.1: default port changed from 9900 to 8080 to match the new
    // .NET server (Program.cs). The previous default (9900) was for the
    // legacy Python server. The .NET server now listens on BOTH 8080
    // AND 9900, so old APKs that still call connect() without args
    // and use the new server still work. But new APKs should use 8080
    // for cleaner code.
    fun connect(host: String, port: Int = 8080) {
        disconnect()

        _lastHost = host  // v1.5.9: remember for auto-reconnect
        _lastPort = port
        _reconnectAttempts = 0

        _connectionState.value = ConnectionState.Connecting
        scope = CoroutineScope(Dispatchers.IO + SupervisorJob())

        client = OkHttpClient.Builder()
            .readTimeout(0, TimeUnit.MILLISECONDS)
            .pingInterval(30, TimeUnit.SECONDS)
            .build()

        val request = Request.Builder()
            .url("ws://$host:$port")
            .build()

        ws = client!!.newWebSocket(request, object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) {
                _connectionState.value = ConnectionState.Connected
                // v1.2.2: explicitly request cursor visibility on connect.
                // Prevents the no-cursor gap that used to happen until the PC
                // mouse moved for the first time.
                try {
                    webSocket.send(JSONObject().apply {
                        put("type", "cursor")
                        put("visible", true)
                    }.toString())
                } catch (_: Exception) {}
            }

            override fun onMessage(webSocket: WebSocket, text: String) {
                // JSON messages: screen_info, adapt, pong
                try {
                    val json = JSONObject(text)
                    when (json.optString("type")) {
                        "hello" -> {
                            // v1.7.2: parse monitor list
                            val arr = json.optJSONArray("monitors")
                            if (arr != null) {
                                val list = mutableListOf<MonitorInfo>()
                                for (i in 0 until arr.length()) {
                                    val m = arr.getJSONObject(i)
                                    list.add(MonitorInfo(
                                        idx = m.optInt("idx", i),
                                        name = m.optString("name", "Monitor ${i+1}"),
                                        w = m.optInt("w", 1920),
                                        h = m.optInt("h", 1080),
                                        left = m.optInt("left", 0),
                                        top = m.optInt("top", 0)
                                    ))
                                }
                                _monitors.value = list
                            }
                            _currentMonitorIdx.value = json.optInt("monitor_idx", 0)
                            // Also parse screen info from hello
                            val screen = json.optJSONObject("screen")
                            if (screen != null) {
                                _screenInfo.value = ScreenInfo.Known(
                                    width = screen.optInt("width", 1920),
                                    height = screen.optInt("height", 1080),
                                    streamWidth = 960,
                                    streamHeight = 540
                                )
                            }
                            if (json.has("ms")) {
                                _latencyMs.value = json.optInt("ms", 0)
                            }
                        }
                        "screen_info" -> {
                            _screenInfo.value = ScreenInfo.Known(
                                width = json.optInt("width", 1920),
                                height = json.optInt("height", 1080),
                                streamWidth = json.optInt("stream_width", 960),
                                streamHeight = json.optInt("stream_height", 540)
                            )
                        }
                        "adapt" -> {
                            _serverAdapt.value = AdaptInfo()
                            // The APK just records this for the badge — it
                            // doesn't drive any UI action itself.
                            _serverAdapt.value = AdaptInfo(
                                mode = json.optString("mode", "auto"),
                                reason = json.optString("reason", ""),
                                quality = json.optInt("q", 0),
                                scale = json.optInt("s", 0),
                                fps = json.optDouble("fps", 0.0).toFloat()
                            )
                        }
                        "hud_v2" -> {
                            // v2.0.0: telemetria do servidor (codec, bitrate, fps).
                            ServerHud.parse(text)?.let { _hud.value = it }
                        }
                        "cursor_pos" -> {
                            // v1.4.1: server reports the actual PC cursor
                            // position. Coordinates are in PC screen pixels.
                            _remoteCursor.value = Pair(
                                json.optDouble("x", 0.0).toFloat(),
                                json.optDouble("y", 0.0).toFloat()
                            )
                        }
                        "ping" -> {
                            try {
                                webSocket.send(JSONObject().apply {
                                    put("type", "pong")
                                }.toString())
                            } catch (_: Exception) {}
                        }
                    }
                } catch (_: Exception) {}
            }

            override fun onMessage(webSocket: WebSocket, bytes: okio.ByteString) {
                // v1.2 binary frame format (11-byte header):
                //   type(1) + jpeg_len(4) + mouse_x(2) + mouse_y(2) +
                //   cursor_visible(1) + reserved(1) + jpeg_bytes
                try {
                    val data = bytes.toByteArray()
                    // v1.8.1: raw JPEG fallback — if server sends headerless JPEG
                    // (starts with FF D8 FF), decode directly without header parsing
                    if (data.size > 2 && (data[0].toInt() and 0xFF) == 0xFF && (data[1].toInt() and 0xFF) == 0xD8) {
                        val bitmap = BitmapFactory.decodeByteArray(data, 0, data.size)
                        if (bitmap != null) {
                            _currentFrame.value = bitmap
                            frameCount++
                            val now = System.currentTimeMillis()
                            val elapsed = now - lastFpsTime
                            if (elapsed >= 1000) {
                                _fps.value = (frameCount * 1000 / elapsed).toInt()
                                frameCount = 0
                                lastFpsTime = now
                            }
                        }
                        return
                    }
                    if (data.size < 11) {
                        // Fallback: try v1.1 format (9-byte header) for back-compat
                        return decodeFrameLegacy(data)
                    }

                    // v2.0.0: Check for type=3 (partial frame update / dirty regions)
                    val type = data[0].toInt() and 0xFF
                    if (type == 0x03) {
                        return handlePartialUpdate(data)
                    }

                    val length = ByteBuffer.wrap(data, 1, 4).int
                    val mouseXRaw = ByteBuffer.wrap(data, 5, 2).short.toInt() and 0xFFFF
                    val mouseYRaw = ByteBuffer.wrap(data, 7, 2).short.toInt() and 0xFFFF
                    val cursorVis = data[9].toInt() != 0
                    val cursorInBounds = data[10].toInt() != 0  // v1.8: reserved byte = in_bounds
                    val bitmap = BitmapFactory.decodeByteArray(data, 11, length.coerceAtMost(data.size - 11))
                    if (bitmap != null) {
                        _currentFrame.value = bitmap
                        _cursorVisible.value = cursorVis
                        _cursorInBounds.value = cursorInBounds  // v1.8

                        val info = _screenInfo.value
                        val normX: Float
                        val normY: Float
                        if (info is ScreenInfo.Known && info.width > 0 && info.height > 0) {
                            normX = (mouseXRaw.toFloat() / info.width).coerceIn(0f, 1f)
                            normY = (mouseYRaw.toFloat() / info.height).coerceIn(0f, 1f)
                        } else {
                            normX = (mouseXRaw.toFloat() / 1920f).coerceIn(0f, 1f)
                            normY = (mouseYRaw.toFloat() / 1080f).coerceIn(0f, 1f)
                        }
                        _mousePos.value = androidx.compose.ui.geometry.Offset(normX, normY)

                        // FPS counter
                        frameCount++
                        val now = System.currentTimeMillis()
                        val elapsed = now - lastFpsTime
                        if (elapsed >= 1000) {
                            _fps.value = (frameCount * 1000 / elapsed).toInt()
                            frameCount = 0
                            lastFpsTime = now
                        }
                    }
                } catch (_: Exception) {}
            }

            private fun decodeFrameLegacy(data: ByteArray) {
                // v1.1.0 fallback (9-byte header)
                if (data.size < 9) return
                val length = ByteBuffer.wrap(data, 1, 4).int
                val mouseXRaw = ByteBuffer.wrap(data, 5, 2).short.toInt() and 0xFFFF
                val mouseYRaw = ByteBuffer.wrap(data, 7, 2).short.toInt() and 0xFFFF
                val bitmap = BitmapFactory.decodeByteArray(data, 9, length.coerceAtMost(data.size - 9))
                if (bitmap != null) {
                    _currentFrame.value = bitmap
                    val info = _screenInfo.value
                    val normX: Float
                    val normY: Float
                    if (info is ScreenInfo.Known && info.width > 0 && info.height > 0) {
                        normX = (mouseXRaw.toFloat() / info.width).coerceIn(0f, 1f)
                        normY = (mouseYRaw.toFloat() / info.height).coerceIn(0f, 1f)
                    } else {
                        normX = (mouseXRaw.toFloat() / 1920f).coerceIn(0f, 1f)
                        normY = (mouseYRaw.toFloat() / 1080f).coerceIn(0f, 1f)
                    }
                    _mousePos.value = androidx.compose.ui.geometry.Offset(normX, normY)
                }
            }

            // v2.0.0: Dirty region renderer — handles type=0x03 partial frame updates
            private fun handlePartialUpdate(data: ByteArray) {
                // Header format (16 bytes big-endian):
                //   type(1) + frameId(4) + totalTiles(2) + dirtyTiles(2) +
                //   screenWidth(2) + screenHeight(2) + tileWidth(2) + tileHeight(2)
                if (data.size < 17) return  // Need at least type + 16 bytes header
                
                val buf = ByteBuffer.wrap(data).order(java.nio.ByteOrder.BIG_ENDIAN)
                
                // Skip type byte
                buf.position(1)
                
                val frameId = buf.getInt()
                val totalTiles = buf.getShort().toInt() and 0xFFFF
                val dirtyTiles = buf.getShort().toInt() and 0xFFFF
                val screenWidth = buf.getShort().toInt() and 0xFFFF
                val screenHeight = buf.getShort().toInt() and 0xFFFF
                val tileWidth = buf.getShort().toInt() and 0xFFFF
                val tileHeight = buf.getShort().toInt() and 0xFFFF
                
                // Validate dimensions
                if (screenWidth <= 0 || screenHeight <= 0 || tileWidth <= 0 || tileHeight <= 0) {
                    return
                }
                
                // Initialize or resize frameBuffer if needed
                if (frameBuffer == null || frameBufferWidth != screenWidth || frameBufferHeight != screenHeight) {
                    frameBuffer?.recycle()
                    frameBuffer = Bitmap.createBitmap(screenWidth, screenHeight, Bitmap.Config.ARGB_8888)
                    frameBufferWidth = screenWidth
                    frameBufferHeight = screenHeight
                }
                
                val fb = frameBuffer ?: return
                
                // Track bandwidth: compare full frame size vs partial data received
                val fullFrameBytes = screenWidth * screenHeight * 4  // ARGB_8888
                totalFrameBytes += fullFrameBytes
                
                // Parse and apply each dirty tile
                // Each tile: tileX(2) + tileY(2) + tileSize(4) + tileData(tileSize bytes)
                var offset = 17  // Start after header
                val canvas = android.graphics.Canvas(fb)
                var bytesReceived = 0
                
                for (i in 0 until dirtyTiles) {
                    if (offset + 8 > data.size) break  // Not enough data for tile header
                    
                    val tileX = ByteBuffer.wrap(data, offset, 2).order(java.nio.ByteOrder.BIG_ENDIAN).getShort().toInt() and 0xFFFF
                    val tileY = ByteBuffer.wrap(data, offset + 2, 2).order(java.nio.ByteOrder.BIG_ENDIAN).getShort().toInt() and 0xFFFF
                    val tileSize = ByteBuffer.wrap(data, offset + 4, 4).order(java.nio.ByteOrder.BIG_ENDIAN).getInt()
                    
                    offset += 8
                    
                    if (tileSize <= 0 || offset + tileSize > data.size) break
                    
                    bytesReceived += 8 + tileSize
                    partialBytesReceived += 8 + tileSize
                    
                    // Decode tile data (JPEG or raw RGB)
                    val tileData = ByteArray(tileSize)
                    System.arraycopy(data, offset, tileData, 0, tileSize)
                    
                    // Check if it's JPEG (starts with FF D8)
                    val tileBitmap = if (tileSize >= 2 && (tileData[0].toInt() and 0xFF) == 0xFF && (tileData[1].toInt() and 0xFF) == 0xD8) {
                        // JPEG encoded tile
                        BitmapFactory.decodeByteArray(tileData, 0, tileSize)
                    } else {
                        // Raw RGB (24-bit) or RGBA (32-bit) data
                        val pixelsPerRow = tileWidth
                        if (tileSize == tileWidth * tileHeight * 3) {
                            // 24-bit RGB
                            val tempBitmap = Bitmap.createBitmap(tileWidth, tileHeight, Bitmap.Config.ARGB_8888)
                            for (y in 0 until tileHeight) {
                                for (x in 0 until tileWidth) {
                                    val idx = (y * tileWidth + x) * 3
                                    if (idx + 2 < tileSize) {
                                        val r = tileData[idx].toInt() and 0xFF
                                        val g = tileData[idx + 1].toInt() and 0xFF
                                        val b = tileData[idx + 2].toInt() and 0xFF
                                        tempBitmap.setPixel(x, y, android.graphics.Color.rgb(r, g, b))
                                    }
                                }
                            }
                            tempBitmap
                        } else if (tileSize == tileWidth * tileHeight * 4) {
                            // 32-bit RGBA
                            val tempBitmap = Bitmap.createBitmap(tileWidth, tileHeight, Bitmap.Config.ARGB_8888)
                            for (y in 0 until tileHeight) {
                                for (x in 0 until tileWidth) {
                                    val idx = (y * tileWidth + x) * 4
                                    if (idx + 3 < tileSize) {
                                        val r = tileData[idx].toInt() and 0xFF
                                        val g = tileData[idx + 1].toInt() and 0xFF
                                        val b = tileData[idx + 2].toInt() and 0xFF
                                        val a = tileData[idx + 3].toInt() and 0xFF
                                        tempBitmap.setPixel(x, y, android.graphics.Color.argb(a, r, g, b))
                                    }
                                }
                            }
                            tempBitmap
                        } else {
                            null
                        }
                    }
                    
                    // Composite tile onto frameBuffer
                    if (tileBitmap != null) {
                        val destRect = android.graphics.Rect(tileX, tileY, tileX + tileBitmap.width, tileY + tileBitmap.height)
                        canvas.drawBitmap(tileBitmap, null, destRect, null)
                        tileBitmap.recycle()
                    }
                    
                    offset += tileSize
                }
                
                // Calculate bandwidth savings percentage
                if (partialBytesReceived > 0 && totalFrameBytes > 0) {
                    val savings = ((1.0 - partialBytesReceived.toDouble() / totalFrameBytes.toDouble()) * 100).toInt().coerceIn(0, 100)
                    _bandwidthSavings.value = savings
                }
                
                // Emit the updated frameBuffer as currentFrame
                _currentFrame.value = fb
                
                // Update FPS counter
                frameCount++
                val now = System.currentTimeMillis()
                val elapsed = now - lastFpsTime
                if (elapsed >= 1000) {
                    _fps.value = (frameCount * 1000 / elapsed).toInt()
                    frameCount = 0
                    lastFpsTime = now
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
                // v1.5.9: auto-reconnect with backoff (3 attempts max)
                if (autoReconnect && _reconnectAttempts < maxReconnectAttempts && _lastHost != null) {
                    _reconnectAttempts++
                    val delayMs = 1000L * _reconnectAttempts  // 1s, 2s, 3s
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
        _reconnectAttempts = 0  // v1.8.1: reset so next connect() cycle works cleanly
        _connectionState.value = ConnectionState.Disconnected
        _currentFrame.value = null
        _screenInfo.value = ScreenInfo.Unknown
        
        // v2.0.0: limpiar frameBuffer al desconectar
        frameBuffer?.recycle()
        frameBuffer = null
        frameBufferWidth = 0
        frameBufferHeight = 0
        totalFrameBytes = 0
        partialBytesReceived = 0
        _bandwidthSavings.value = 0
    }

    // v1.5.9: auto-reconnect state
    private var _lastHost: String? = null
    private var _lastPort: Int = 9900
    private var _reconnectAttempts = 0
    private val maxReconnectAttempts = 5  // v1.5.10: was 3
    var autoReconnect: Boolean = true

    // --- v1.2: Touch with full stylus data ---
    fun sendTouch(
        x: Float, y: Float, action: String,
        pressure: Float = 0.5f,
        tilt: Float = 0f,
        tool: String = "finger",
        buttons: Int = 0
    ) {
        val json = JSONObject().apply {
            put("type", "touch")
            put("x", x)
            put("y", y)
            put("action", action)
            put("pressure", pressure.toDouble())
            put("tilt", tilt.toDouble())
            put("tool", tool)
            put("buttons", buttons)
            if (buttons == 2 || (buttons and 2) != 0) {
                put("button", "right")
            }
        }
        ws?.send(json.toString())
        onTouchEvent?.invoke(x, y, action)
    }

    // v1.3.1: Send a whole touch trajectory as a compressed batch.
    // The server will interpolate the cursor along these points.
    data class PathPoint(val x: Float, val y: Float, val t: Long)

    fun sendTouchPath(
        points: List<com.mirrorx.app.touch.TouchPathCollector.Point>,
        pressure: Float = 0.5f,
        tilt: Float = 0f,
        tool: String = "finger",
        buttons: Int = 0
    ) {
        if (points.size < 2) return
        val arr = org.json.JSONArray()
        points.forEach { p ->
            arr.put(JSONObject().apply {
                put("x", p.x.toDouble())
                put("y", p.y.toDouble())
                put("t", p.t)
            })
        }
        val json = JSONObject().apply {
            put("type", "touch_path")
            put("points", arr)
            put("pressure", pressure.toDouble())
            put("tilt", tilt.toDouble())
            put("tool", tool)
            put("buttons", buttons)
        }
        ws?.send(json.toString())
    }

    // v1.4.0: WebSocket binary frame for touch path — much faster than JSON.
    // Layout: [0x10 = touch_path_bin][count:u8][(x:u16 LE)(y:u16 LE)...]*
    // Max 255 points per frame. ~6 bytes per point vs ~60+ for JSON.
    fun sendTouchPathBinary(
        points: List<com.mirrorx.app.touch.TouchPathCollector.Point>,
        tool: String = "finger",
        buttons: Int = 0
    ) {
        if (points.isEmpty()) return
        val n = points.size.coerceAtMost(255)
        val buf = java.nio.ByteBuffer.allocate(2 + n * 4).order(java.nio.ByteOrder.LITTLE_ENDIAN)
        buf.put(0x10)  // message type: touch_path_binary
        buf.put(n.toByte())
        for (i in 0 until n) {
            val p = points[i]
            // u16: 0..65535 mapping to 0..1
            val xi = (p.x.coerceIn(0f, 1f) * 65535f).toInt().coerceIn(0, 65535)
            val yi = (p.y.coerceIn(0f, 1f) * 65535f).toInt().coerceIn(0, 65535)
            buf.putShort(xi.toShort())
            buf.putShort(yi.toShort())
        }
        ws?.send(buf.array().toByteString(0, buf.array().size))
    }

    // v1.4.0: Pinch-to-zoom event from multitouch.
    // Layout: [0x11 = pinch][scale:float32 LE][centerX:float32 LE][centerY:float32 LE]
    fun sendPinch(scale: Float, centerX: Float, centerY: Float) {
        val buf = java.nio.ByteBuffer.allocate(13).order(java.nio.ByteOrder.LITTLE_ENDIAN)
        buf.put(0x11)
        buf.putFloat(scale)
        buf.putFloat(centerX.coerceIn(0f, 1f))
        buf.putFloat(centerY.coerceIn(0f, 1f))
        val arr = buf.array()
        ws?.send(arr.toByteString(0, arr.size))
    }

    /** v1.2: ask the server to show/hide the PC mouse cursor (stylus mode). */
    fun sendCursorVisibility(visible: Boolean) {
        val json = JSONObject().apply {
            put("type", "cursor")
            put("visible", visible)
        }
        ws?.send(json.toString())
    }

    // v1.7.2: switch the server to a different monitor
    fun sendMonitorSwitch(monitorIdx: Int) {
        val json = JSONObject().apply {
            put("type", "mirror_config")
            put("key", "monitor")
            put("value", monitorIdx)
        }
        ws?.send(json.toString())
        _currentMonitorIdx.value = monitorIdx
    }

    // v1.4.2: explicit click button on tablet UI. The cursor is already
    // where the user dragged it (last touch event), so the server reads
    // pyautogui.position() and clicks there. Buttons: "left"|"right"|
    // "middle"|"double".
    fun sendClickRequest(button: String) {
        val json = JSONObject().apply {
            put("type", "click_request")
            put("button", button)
        }
        ws?.send(json.toString())
    }

    // ------------------------------------------------------------------
    // v1.5.5 Hybrid Hermes — JSON mouse/scroll/key/quality/heartbeat
    // Used by HermesActivity together with the JPG stream so the same
    // WebSocket carries both video and mouse control.
    // ------------------------------------------------------------------

    /** Relative mouse move: {"t":"m","x":<int>,"y":<int>} */
    fun sendHermesMove(dx: Int, dy: Int) {
        if (dx == 0 && dy == 0) return
        val json = JSONObject().apply {
            put("t", "m")
            put("x", dx)
            put("y", dy)
        }
        ws?.send(json.toString())
    }

    /** Click by Hermes button id. 0=L, 1=R, 2=M, 3=double. */
    fun sendHermesClick(button: Int) {
        val json = JSONObject().apply {
            put("t", "c")
            put("b", button)
        }
        ws?.send(json.toString())
    }

    /** Mouse down: {"t":"d","b":<int>} */
    fun sendHermesMouseDown(button: Int) {
        val json = JSONObject().apply {
            put("t", "d")
            put("b", button)
        }
        ws?.send(json.toString())
    }

    /** Mouse up: {"t":"u","b":<int>} */
    fun sendHermesMouseUp(button: Int) {
        val json = JSONObject().apply {
            put("t", "u")
            put("b", button)
        }
        ws?.send(json.toString())
    }

    /** Vertical scroll (notches). */
    fun sendHermesScroll(v: Int) {
        if (v == 0) return
        val json = JSONObject().apply {
            put("t", "s")
            put("v", v)
        }
        ws?.send(json.toString())
    }

    /** Key press/release. */
    fun sendHermesKey(key: String, press: Boolean) {
        if (key.isEmpty()) return
        val json = JSONObject().apply {
            put("t", "k")
            put("k", key)
            put("p", press)
        }
        ws?.send(json.toString())
    }

    /** Quality/connection mode: 0=Normal, 1=Bad, 2=Ultra. */
    fun sendHermesQuality(mode: Int) {
        val json = JSONObject().apply {
            put("t", "q")
            put("m", mode)
        }
        ws?.send(json.toString())
    }

    /** Heartbeat with measured RTT (ms). */
    fun sendHermesHeartbeat(ms: Int) {
        val json = JSONObject().apply {
            put("t", "h")
            put("ms", ms)
        }
        ws?.send(json.toString())
    }

    /** v1.5.8: send mirror config command to server. */
    fun sendMirrorConfig(key: String, value: Any) {
        val json = JSONObject().apply {
            put("type", "mirror_config")
            put("key", key)
            put("value", value)
        }
        ws?.send(json.toString())
    }
}
