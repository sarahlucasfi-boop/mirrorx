package com.mirrorx.app.ui

import android.app.Activity
import android.graphics.Bitmap
import android.os.Build
import android.view.MotionEvent
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.activity.compose.BackHandler
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.mirrorx.app.BuildConfig
import com.mirrorx.app.hermes.TouchpadView
import com.mirrorx.app.network.MirrorWebSocket
import com.mirrorx.app.network.NetworkScanner
import com.mirrorx.app.touch.TouchHandler
import com.mirrorx.app.touch.TouchHandler.TouchMode
import com.mirrorx.app.ui.theme.MirrorAccent
import com.mirrorx.app.ui.theme.MirrorBorder
import com.mirrorx.app.ui.theme.MirrorGreen
import com.mirrorx.app.ui.theme.MirrorRed
import com.mirrorx.app.ui.theme.MirrorSurface
import com.mirrorx.app.ui.theme.MirrorSurfaceVariant
import com.mirrorx.app.ui.theme.MirrorText
import com.mirrorx.app.ui.theme.MirrorTextDim
import com.mirrorx.app.ui.theme.MirrorYellow
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

private enum class CursorSize(val scale: Float, val label: String) {
    MICRO(0.007f, "Micro"),    // v1.2.1: ultra-tiny for monitor + stylus
    TINY(0.011f, "Mínimo"),    // v1.2: extra-small for "monitor mode"
    SMALL(0.018f, "Pequeno"),
    MEDIUM(0.025f, "Médio"),
    LARGE(0.035f, "Grande");
}

@Composable
fun MirrorScreen() {
    val view = LocalView.current

    // v1.2: immersive mode — hide system bars (status + navigation)
    // We ask the window to use BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE so the
    // user can still pull down the system bar if needed.
    @Suppress("unused")
    val immersive = remember { mutableStateOf(false) }

    DisposableEffect(Unit) {
        val window = (view.context as? Activity)?.window
        if (window != null && Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            window.setDecorFitsSystemWindows(false)
            window.insetsController?.let { ctl ->
                ctl.hide(android.view.WindowInsets.Type.statusBars() or android.view.WindowInsets.Type.navigationBars())
                ctl.systemBarsBehavior = android.view.WindowInsetsController.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
            }
        }
        onDispose {
            if (window != null && Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                window.insetsController?.show(
                    android.view.WindowInsets.Type.statusBars() or android.view.WindowInsets.Type.navigationBars()
                )
            }
        }
    }

    val ws = remember { MirrorWebSocket() }

    // v1.8.1: disconnect WebSocket when composable leaves composition
    // to prevent TCP socket leaks when Activity is destroyed
    DisposableEffect(ws) {
        onDispose { ws.disconnect() }
    }

    val connectionState by ws.connectionState.collectAsState()
    val frame by ws.currentFrame.collectAsState()
    val screenInfo by ws.screenInfo.collectAsState()
    val fps by ws.fps.collectAsState()
    val mousePos by ws.mousePos.collectAsState()
    val serverCursorVisible by ws.cursorVisible.collectAsState()
    // v1.2.2: server-driven adaptation info for the "Adapt." badge
    val serverAdapt by ws.serverAdapt.collectAsState()
    // v1.7.0: multi-monitor support
    val monitors by ws.monitors.collectAsState()
    val currentMonitorIdx by ws.currentMonitorIdx.collectAsState()
    // v1.8: cursor in monitor bounds
    val cursorInBounds by ws.cursorInBounds.collectAsState()

    // v1.8.1: persist last-used IP via SharedPreferences instead of hardcoded default
        val context = LocalContext.current
        val prefs = remember { context.getSharedPreferences("mirrorx", android.content.Context.MODE_PRIVATE) }
        var ipAddress by rememberSaveable {
            mutableStateOf(prefs.getString("last_ip", "192.168.100.12") ?: "192.168.100.12")
        }
        // v1.8.1: porta agora e' configuravel (default 8080 para casar com o
        // .NET server novo). Persistida igual ao IP.
        var portText by rememberSaveable {
                    mutableStateOf(prefs.getString("last_port", "8080") ?: "8080")
                }
                val port: Int = portText.toIntOrNull()?.coerceIn(1, 65535) ?: 8080
                var showSettings by rememberSaveable { mutableStateOf(false) }

    // v1.2 monitor mode (hide ALL chrome for a clean display)
    var monitorMode by rememberSaveable { mutableStateOf(false) }

    // v1.8.7: touchpad-only mode (Hermes) — embedded in MirrorScreen instead of separate Activity
    var touchpadMode by rememberSaveable { mutableStateOf(false) }

    // --- Touch state ---
    var touchEnabled by rememberSaveable { mutableStateOf(false) }
    var touchModeName by rememberSaveable { mutableStateOf(TouchMode.CURSOR.name) }
    // v1.2.3: default cursor = MICRO (user requested nearly invisible)
    var cursorSizeName by rememberSaveable { mutableStateOf(CursorSize.MICRO.name) }

    val touchMode = remember(touchModeName) {
        runCatching { TouchMode.valueOf(touchModeName) }.getOrDefault(TouchMode.OFF)
    }
    val cursorSize = remember(cursorSizeName) {
        runCatching { CursorSize.valueOf(cursorSizeName) }.getOrDefault(CursorSize.SMALL)
    }

    // v1.2: cursor auto-hide on stylus
    var cursorHiddenByStylus by remember { mutableStateOf(false) }
    // v1.8: show cursor only when server says visible AND cursor is within monitor bounds
    val cursorVisible = serverCursorVisible && !cursorHiddenByStylus && cursorInBounds

    // v1.4.1: ghost cursor. While the user is touching the screen (CURSOR
    // mode) the ghost follows the finger instantly — no waiting for the
    // server round-trip. When the user lifts, the ghost snaps to the last
    // confirmed cursor_pos from the server so any drift is corrected.
    var predictedCursor by remember { mutableStateOf(Offset(-1f, -1f)) }
    val remoteCursor by ws.remoteCursor.collectAsState()

    // v1.2: top bar auto-hide (3s without touch)
    var topBarVisible by remember { mutableStateOf(true) }
    var lastInteractionMs by remember { mutableStateOf(System.currentTimeMillis()) }

    // v1.2.2: Back button always exits Monitor Mode (was missing — user got stuck).
    // Placed AFTER all the state it touches so the compiler can resolve them.
    BackHandler(enabled = monitorMode) {
        monitorMode = false
        topBarVisible = true
        lastInteractionMs = System.currentTimeMillis()
    }

    // Local IP
    val localIp = remember { mutableStateOf("detectando...") }
    LaunchedEffect(Unit) {
        try {
            localIp.value = java.net.NetworkInterface.getNetworkInterfaces()
                ?.asSequence()
                ?.flatMap { it.inetAddresses.asSequence() }
                ?.firstOrNull { !it.isLoopbackAddress && it.hostAddress?.contains('.') == true }
                ?.hostAddress ?: "indisponível"
        } catch (_: Exception) { localIp.value = "indisponível" }
    }

    // v1.9: UDP broadcast network scanner state
    val scope = rememberCoroutineScope()
    var isScanning by remember { mutableStateOf(false) }
    var scanMessage by remember { mutableStateOf("") }

    // Tap ripple
    val ripples = remember { mutableStateListOf<Ripple>() }

    // Touch handler
    val touchHandler = remember {
        TouchHandler(
            onSendTouch = { x, y, action, pressure, tilt, tool, buttons ->
                if (connectionState is MirrorWebSocket.ConnectionState.Connected) {
                    ws.sendTouch(x, y, action, pressure, tilt, tool, buttons)
                }
                if (action == "down" || action == "click") {
                    ripples.add(
                        Ripple(
                            position = Offset(x, y),
                            color = when {
                                tool == "stylus" -> MirrorGreen
                                tool == "eraser" -> MirrorRed
                                touchMode == TouchMode.DRAW -> MirrorGreen
                                touchMode == TouchMode.CURSOR -> MirrorAccent
                                else -> MirrorYellow
                            },
                            bornAt = System.currentTimeMillis(),
                            pressure = pressure
                        )
                    )
                }
            },
            onSendTouchPath = { points, pressure, tilt, tool, buttons ->
                if (connectionState is MirrorWebSocket.ConnectionState.Connected) {
                    // v1.4.0: WebSocket binary frame is faster than JSON.
                    // Falls back to JSON if the APK ever needs the rich fields.
                    ws.sendTouchPathBinary(points, tool, buttons)
                }
            },
            onSendPinch = { scale, cx, cy ->
                if (connectionState is MirrorWebSocket.ConnectionState.Connected) {
                    ws.sendPinch(scale, cx, cy)
                }
            }
        )
    }
    // React to stylus tool changes — hide cursor when stylus is active
    LaunchedEffect(Unit) {
        touchHandler.onToolChanged = { tool ->
            cursorHiddenByStylus = (tool == "stylus" || tool == "eraser") &&
                (touchMode == TouchMode.CLICK_ONLY || touchMode == TouchMode.DRAW)
            // Notify the server so the frame header can stop sending mouse position
            ws.sendCursorVisibility(!cursorHiddenByStylus)
        }
    }
    LaunchedEffect(touchMode) {
        touchHandler.setMode(touchMode)
    }
    // v1.4.1: ghost cursor wiring.
    LaunchedEffect(Unit) {
        // Every cursor-mode ACTION_DOWN / ACTION_MOVE updates the ghost
        // immediately so the user sees instant feedback on the tablet.
        touchHandler.onCursorMove = { x, y ->
            predictedCursor = Offset(x, y)
        }
    }
    // When the server confirms the actual PC cursor position, snap the
    // ghost to it — but only when the user is NOT currently touching the
    // screen (otherwise we'd fight the finger-driven prediction).
    LaunchedEffect(remoteCursor, screenInfo) {
        val rc = remoteCursor ?: return@LaunchedEffect
        if (touchHandler.isPressing) return@LaunchedEffect
        val pcW = (screenInfo as? MirrorWebSocket.ScreenInfo.Known)?.width ?: 0
        val pcH = (screenInfo as? MirrorWebSocket.ScreenInfo.Known)?.height ?: 0
        if (pcW <= 0 || pcH <= 0) return@LaunchedEffect
        predictedCursor = Offset(rc.first / pcW, rc.second / pcH)
    }

    // Top bar auto-hide timer — ONLY in monitor mode (NOT when touch is enabled)
    LaunchedEffect(monitorMode) {
        if (monitorMode) {
            while (true) {
                delay(200)
                val now = System.currentTimeMillis()
                if (now - lastInteractionMs > 3000 && topBarVisible) {
                    topBarVisible = false
                }
            }
        } else {
            topBarVisible = true
        }
    }

    // Ripple cleanup
    LaunchedEffect(Unit) {
        while (true) {
            val now = System.currentTimeMillis()
            ripples.removeAll { now - it.bornAt > 600 }
            delay(60)
        }
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color(0xFF050507))
    ) {
        MirrorContent(
            frame = frame,
            screenInfo = screenInfo,
            // v1.4.1: predictedCursor is the ghost-cursor position — moves
            // with the finger instantly (CURSOR mode) or snaps to the last
            // server-confirmed cursor position (when idle).
            mousePos = predictedCursor,
            cursorScale = cursorSize.scale,
            cursorVisible = cursorVisible,
            ripples = ripples,
            touchEnabled = touchEnabled,
            touchHandler = touchHandler,
            onAnyInteraction = { lastInteractionMs = System.currentTimeMillis() },
            modifier = Modifier.fillMaxSize()
        )

        // v1.2.2: Top bar shows whenever topBarVisible=true (regardless of monitorMode).
        // Previously the `!monitorMode` gate hid the bar in monitor mode, leaving the
        // user with no way to leave (the icon button lived in the bar they couldn't see).
        // Now: edge-swipe in monitor mode reveals the bar, the bar's monitor-mode toggle
        // turns it off again. BackHandler is a second safety net.
        if (topBarVisible) {
            ConnectionBar(
                ipAddress = ipAddress,
                connectionState = connectionState,
                fps = fps,
                touchEnabled = touchEnabled,
                touchMode = touchMode,
                monitorMode = monitorMode,
                serverAdapt = serverAdapt,
                // v1.7.2: multi-monitor
                monitors = monitors,
                currentMonitorIdx = currentMonitorIdx,
                onToggleTouch = {
                    touchEnabled = !touchEnabled
                    lastInteractionMs = System.currentTimeMillis()
                    // v1.2.3: default to CURSOR (touchpad) when turning touch on
                    if (touchEnabled && touchMode == TouchMode.OFF) {
                        touchModeName = TouchMode.CURSOR.name
                    }
                },
                onCycleTouchMode = {
                    val next = when (touchMode) {
                        TouchMode.OFF -> TouchMode.CURSOR
                        TouchMode.CURSOR -> TouchMode.CLICK_ONLY
                        TouchMode.CLICK_ONLY -> TouchMode.DRAW
                        TouchMode.DRAW -> TouchMode.OFF
                        TouchMode.HERMES -> TouchMode.OFF
                    }
                    touchModeName = next.name
                    lastInteractionMs = System.currentTimeMillis()
                },
                onToggleMonitorMode = {
                    monitorMode = !monitorMode
                    // v1.2.2: always show the bar when toggling — ensures the user can
                    // see the toggle button they just pressed and switch back if needed.
                    topBarVisible = true
                    lastInteractionMs = System.currentTimeMillis()
                },
                onConnect = { ws.connect(ipAddress, port) },
                                onDisconnect = { ws.disconnect() },
                onSettingsClick = { showSettings = true },
                // v1.4.2: explicit click buttons (L/R/2x) — calls pyautogui.click()
                // at the current PC cursor position. Useful when the user
                // already positioned the cursor and just needs to click without
                // re-tapping the screen.
                onClickRequest = { button -> ws.sendClickRequest(button) },
                modifier = Modifier
                    .align(Alignment.TopCenter)
                    .fillMaxWidth()
            )
        }

        // v1.2: edge-swipe area at top — tap to show top bar in monitor mode
        if (monitorMode && !topBarVisible) {
            Box(
                modifier = Modifier
                    .align(Alignment.TopCenter)
                    .fillMaxWidth()
                    .height(60.dp)
                    .pointerInput(Unit) {
                        detectTapGestures(onTap = {
                            topBarVisible = true
                            lastInteractionMs = System.currentTimeMillis()
                        })
                    }
            )
        }
    }

    if (showSettings) {
        SettingsDialog(
                    ipAddress = ipAddress,
                    onIpChange = {
                        ipAddress = it
                        prefs.edit().putString("last_ip", it).apply()  // v1.8.1: persist
                    },
                    portText = portText,                                    // v1.8.1
                    onPortChange = {
                        portText = it
                        prefs.edit().putString("last_port", it).apply()      // v1.8.1: persist
                    },
                    connectionState = connectionState,
            localIp = localIp.value,
            isScanning = isScanning,
            scanMessage = scanMessage,
            onScanNetwork = {
                if (!isScanning) {
                    isScanning = true
                    scanMessage = "Procurando servidor..."
                    scope.launch {
                        val result = NetworkScanner.scanForServer()
                        if (result != null) {
                            ipAddress = result.ip
                            prefs.edit().putString("last_ip", result.ip).apply()
                            portText = result.port.toString()
                            prefs.edit().putString("last_port", result.port.toString()).apply()
                            scanMessage = "✓ ${result.ip}:${result.port} encontrado!"
                        } else {
                            scanMessage = "Nenhum servidor encontrado na rede."
                        }
                        isScanning = false
                        // Clear message after 5 seconds
                        delay(5000)
                        scanMessage = ""
                    }
                }
            },
            touchEnabled = touchEnabled,
            onTouchEnabledChange = { touchEnabled = it },
            touchMode = touchMode,
            onTouchModeChange = { touchModeName = it.name },
            cursorSize = cursorSize,
            onCursorSizeChange = { cursorSizeName = it.name },
            monitorMode = monitorMode,
            onMonitorModeChange = {
                monitorMode = it
                // v1.2.2: reveal top bar when changed via Settings so the user sees it
                topBarVisible = true
                lastInteractionMs = System.currentTimeMillis()
            },
            touchpadMode = touchpadMode,  // v1.8.7: pass touchpad mode
            onTouchpadModeChange = {  // v1.8.7: toggle embedded touchpad
                touchpadMode = it
                if (it) {
                    // Close settings when entering touchpad mode
                    showSettings = false
                }
            },
            // v1.7.2: multi-monitor
            monitors = monitors,
            currentMonitorIdx = currentMonitorIdx,
            onMonitorSwitch = { idx -> ws.sendMonitorSwitch(idx) },
            onConnect = { ws.connect(ipAddress, port) },
                            onDisconnect = { ws.disconnect() },
            onDismiss = { showSettings = false }
        )
    }

    // v1.8.7: Embedded TouchpadView (Hermes) — replaces separate HermesActivity
    LaunchedEffect(touchpadMode) {
        if (touchpadMode && connectionState !is MirrorWebSocket.ConnectionState.Connected && connectionState !is MirrorWebSocket.ConnectionState.Connecting) {
            ws.connect(ipAddress, port)
        }
    }

    if (touchpadMode) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(Color(0xFF050507))
        ) {
            TouchpadView(
                client = ws,
                modifier = Modifier.fillMaxSize()
            )
            // Floating back button
            Box(
                modifier = Modifier
                    .align(Alignment.TopStart)
                    .padding(12.dp)
                    .size(48.dp)
                    .clip(CircleShape)
                    .background(Color(0xCC000000))
                    .clickable { touchpadMode = false },
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    imageVector = Icons.Default.Close,
                    contentDescription = "Voltar",
                    tint = MirrorRed,
                    modifier = Modifier.size(28.dp)
                )
            }
        }
    }
}

private data class Ripple(
    val position: Offset,
    val color: Color,
    val bornAt: Long,
    val pressure: Float = 0.5f
)

@Composable
private fun MirrorContent(
    frame: Bitmap?,
    screenInfo: MirrorWebSocket.ScreenInfo,
    mousePos: Offset,
    cursorScale: Float,
    cursorVisible: Boolean,
    ripples: List<Ripple>,
    touchEnabled: Boolean,
    touchHandler: TouchHandler,
    onAnyInteraction: () -> Unit,
    modifier: Modifier = Modifier
) {
    Box(
        modifier = modifier.background(Color.Black),
        contentAlignment = Alignment.Center
    ) {
        if (frame != null) {
            val aspect = when (val info = screenInfo) {
                is MirrorWebSocket.ScreenInfo.Known ->
                    info.streamWidth.toFloat() / info.streamHeight.coerceAtLeast(1)
                else -> 16f / 9f
            }
            val img = remember(frame.generationId) { frame.asImageBitmap() }

            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .aspectRatio(aspect)
            ) {
                Image(
                    bitmap = img,
                    contentDescription = "PC Screen",
                    modifier = Modifier.fillMaxSize()
                )
                // v1.2 — cursor is its own composable so mousePos updates
                // do NOT recompose the whole MirrorContent tree.
                // The Canvas redraws on a sub-frame basis via the snapshot
                // of mousePos that this composable receives.
                if (cursorVisible && mousePos.x >= 0f && mousePos.y >= 0f) {
                    key(cursorScale) {
                        CursorOverlay(
                            mousePos = mousePos,
                            scale = cursorScale,
                            modifier = Modifier.fillMaxSize()
                        )
                    }
                }
                // Ripples only update when the list reference changes
                key(ripples.size) {
                    RippleOverlay(
                        ripples = ripples,
                        modifier = Modifier.fillMaxSize()
                    )
                }
                if (touchEnabled) {
                    Box(
                        modifier = Modifier
                            .fillMaxSize()
                            .pointerInput(touchHandler.mode) {
                                awaitPointerEventScope {
                                    while (true) {
                                        val event = awaitPointerEvent()
                                        val change = event.changes.firstOrNull() ?: continue
                                        val action = when {
                                            !change.previousPressed && change.pressed ->
                                                MotionEvent.ACTION_DOWN
                                            change.previousPressed && !change.pressed ->
                                                MotionEvent.ACTION_UP
                                            else -> MotionEvent.ACTION_MOVE
                                        }
                                        val motion = MotionEvent.obtain(
                                            0L, 0L, action,
                                            change.position.x, change.position.y, 0
                                        )
                                        if (touchHandler.handleTouchEvent(
                                                motion, size.width.toInt(), size.height.toInt()
                                            )) {
                                            change.consume()
                                            onAnyInteraction()
                                        }
                                        motion.recycle()
                                    }
                                }
                            }
                    )
                }
            }
        } else {
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(12.dp),
                modifier = Modifier.padding(24.dp)
            ) {
                Text("MirrorX", style = MaterialTheme.typography.headlineLarge,
                     color = MirrorAccent)
                Text("Aguardando conexão...", style = MaterialTheme.typography.bodyLarge,
                     color = MirrorTextDim)
                Text("Toque em ⚙ para configurar o IP do PC",
                     style = MaterialTheme.typography.bodyMedium, color = MirrorTextDim)
            }
        }
    }
}

@Composable
private fun CursorOverlay(mousePos: Offset, scale: Float, modifier: Modifier = Modifier) {
    Canvas(modifier = modifier) {
        val cursorSize = size.minDimension * scale
        val x = mousePos.x * size.width
        val y = mousePos.y * size.height
        drawCursor(Color.Black, x, y, cursorSize, strokeWidth = cursorSize * 0.12f)
        drawCursor(Color.White, x, y, cursorSize * 0.85f, strokeWidth = 0f)
    }
}

private fun androidx.compose.ui.graphics.drawscope.DrawScope.drawCursor(
    color: Color, tipX: Float, tipY: Float, size: Float, strokeWidth: Float
) {
    val path = androidx.compose.ui.graphics.Path().apply {
        moveTo(tipX, tipY)
        lineTo(tipX, tipY + size * 3.2f)
        lineTo(tipX + size * 0.85f, tipY + size * 2.6f)
        lineTo(tipX + size * 1.4f, tipY + size * 3.6f)
        lineTo(tipX + size * 1.85f, tipY + size * 3.3f)
        lineTo(tipX + size * 1.3f, tipY + size * 2.3f)
        lineTo(tipX + size * 2.2f, tipY + size * 2.0f)
        close()
    }
    if (strokeWidth > 0f) {
        drawPath(path, color, style = Stroke(width = strokeWidth))
    } else {
        drawPath(path, color)
    }
}

@Composable
private fun RippleOverlay(ripples: List<Ripple>, modifier: Modifier = Modifier) {
    val now = System.currentTimeMillis()
    Canvas(modifier = modifier) {
        for (r in ripples) {
            val age = (now - r.bornAt).toFloat()
            val t = (age / 600f).coerceIn(0f, 1f)
            val alpha = (1f - t).coerceIn(0f, 1f)
            // Pressure affects ripple radius (stylus = larger ripple)
            val baseRadius = size.minDimension * 0.012f * (1f + t * 1.2f)
            val radius = baseRadius * (0.6f + r.pressure * 0.8f)
            drawCircle(
                color = r.color.copy(alpha = alpha * 0.45f),
                radius = radius,
                center = r.position
            )
            drawCircle(
                color = r.color.copy(alpha = alpha),
                radius = radius * 0.45f,
                center = r.position,
                style = Stroke(width = radius * 0.18f)
            )
        }
    }
}

@Composable
private fun ConnectionBar(
    ipAddress: String,
    connectionState: MirrorWebSocket.ConnectionState,
    fps: Int,
    touchEnabled: Boolean,
    touchMode: TouchMode,
    monitorMode: Boolean,
    serverAdapt: MirrorWebSocket.AdaptInfo = MirrorWebSocket.AdaptInfo(),
    onToggleTouch: () -> Unit,
    onCycleTouchMode: () -> Unit,
    onToggleMonitorMode: () -> Unit,
    // v1.7.2: multi-monitor badge
    monitors: List<MirrorWebSocket.MonitorInfo> = emptyList(),
    currentMonitorIdx: Int = 0,
    onConnect: () -> Unit,
    onDisconnect: () -> Unit,
    onSettingsClick: () -> Unit,
    onClickRequest: (String) -> Unit = {},
    modifier: Modifier = Modifier
) {
    // v1.4.1: responsive UI — phones get bigger touch targets + labels,
    // tablets keep the compact icon-only layout.
    val config = LocalConfiguration.current
    val isCompact = config.screenWidthDp < 600
    val btnSize = if (isCompact) 44.dp else 32.dp
    val clickBtnSize = btnSize + 8.dp  // v1.4.3: bigger L/R/2x buttons
    val iconSize = if (isCompact) 22.dp else 18.dp
    val clickIconSize = iconSize + 2.dp  // v1.4.3: bigger icons inside click buttons
    val padH = if (isCompact) 12.dp else 8.dp
    val padV = if (isCompact) 8.dp else 6.dp
    val spacing = if (isCompact) 10.dp else 6.dp
    Surface(
        modifier = modifier,
        color = MirrorSurface,
        shadowElevation = 4.dp
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = padH, vertical = padV),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(spacing)
        ) {
            // v1.2.1: brand + version (always visible at startup)
            Column(verticalArrangement = Arrangement.spacedBy(0.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    Box(
                        modifier = Modifier
                            .size(if (isCompact) 10.dp else 8.dp)
                            .clip(CircleShape)
                            .background(statusColor(connectionState))
                    )
                    Text(
                        text = "MirrorX",
                        style = MaterialTheme.typography.labelLarge.copy(
                            fontWeight = FontWeight.Bold,
                            fontSize = if (isCompact) 16.sp else 13.sp
                        ),
                        color = MirrorText,
                        maxLines = 1
                    )
                    Text(
                        text = "v${BuildConfig.VERSION_NAME}",
                        style = MaterialTheme.typography.labelSmall.copy(
                            fontFamily = FontFamily.Monospace,
                            fontSize = if (isCompact) 12.sp else 10.sp
                        ),
                        color = MirrorAccent,
                        maxLines = 1
                    )
                }
                Text(
                    text = ipAddress,
                    style = MaterialTheme.typography.labelSmall.copy(
                        fontFamily = FontFamily.Monospace,
                        fontSize = if (isCompact) 13.sp else 11.sp
                    ),
                    color = MirrorTextDim,
                    maxLines = 1
                )
            }

            Spacer(modifier = Modifier.weight(1f))

            // Compact: touch badge
            TouchBadge(
                enabled = touchEnabled,
                mode = touchMode,
                onClick = onCycleTouchMode,
                onLongClick = onToggleTouch
            )

            // v1.2.2: "Adapt." badge — only shown when the server is in a
            // non-default mode (reduced / manual / boosted). Gives the user
            // a one-glance signal that auto-adaptation is working.
            if (serverAdapt.mode != "auto") {
                val adaptColor = when (serverAdapt.mode) {
                    "reduced" -> MirrorYellow
                    "boosted" -> MirrorGreen
                    "manual" -> MirrorTextDim
                    else -> MirrorAccent
                }
                Surface(
                    color = MirrorSurfaceVariant,
                    shape = RoundedCornerShape(6.dp),
                    border = androidx.compose.foundation.BorderStroke(
                        1.dp, adaptColor.copy(alpha = 0.6f)
                    )
                ) {
                    Text(
                        text = "Adapt. ${serverAdapt.quality}%",
                        style = MaterialTheme.typography.labelSmall,
                        color = adaptColor,
                        modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                    )
                }
            }

            // FPS badge
            Surface(
                color = MirrorSurfaceVariant,
                shape = RoundedCornerShape(6.dp)
            ) {
                Text(
                    text = "$fps",
                    style = MaterialTheme.typography.labelLarge,
                    color = fpsColor(fps),
                    modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                )
            }

            // v1.7.2: Monitor badge — shows which monitor is active (only if multiple)
            if (monitors.size > 1) {
                Surface(
                    color = MirrorSurfaceVariant,
                    shape = RoundedCornerShape(6.dp),
                    border = androidx.compose.foundation.BorderStroke(
                        1.dp, MirrorAccent.copy(alpha = 0.4f)
                    )
                ) {
                    Text(
                        text = "Tela ${currentMonitorIdx + 1}",
                        style = MaterialTheme.typography.labelSmall,
                        color = MirrorAccent,
                        modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                    )
                }
            }

            // Connection button (v1.4.1: bigger touch target + label on phones)
            if (isCompact) {
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    modifier = Modifier
                        .size(btnSize + 8.dp, btnSize)
                        .clickable {
                            when (connectionState) {
                                is MirrorWebSocket.ConnectionState.Connected -> onDisconnect()
                                is MirrorWebSocket.ConnectionState.Connecting -> {}
                                else -> onConnect()
                            }
                        }
                ) {
                    when (connectionState) {
                        is MirrorWebSocket.ConnectionState.Connected -> {
                            Icon(
                                imageVector = Icons.Default.Close,
                                contentDescription = "Desconectar",
                                tint = MirrorRed,
                                modifier = Modifier.size(iconSize)
                            )
                            Text("Sair", style = MaterialTheme.typography.labelSmall, color = MirrorText)
                        }
                        is MirrorWebSocket.ConnectionState.Connecting -> {
                            CircularProgressIndicator(
                                modifier = Modifier.size(iconSize),
                                strokeWidth = 2.dp,
                                color = MirrorAccent
                            )
                        }
                        else -> {
                            Icon(
                                imageVector = Icons.Default.Phone,
                                contentDescription = "Conectar",
                                tint = MirrorAccent,
                                modifier = Modifier.size(iconSize)
                            )
                            Text("Conectar", style = MaterialTheme.typography.labelSmall, color = MirrorAccent)
                        }
                    }
                }
            } else {
                IconButton(
                    onClick = {
                        when (connectionState) {
                            is MirrorWebSocket.ConnectionState.Connected -> onDisconnect()
                            is MirrorWebSocket.ConnectionState.Connecting -> { /* no-op */ }
                            else -> onConnect()
                        }
                    },
                    modifier = Modifier.size(btnSize)
                ) {
                    when (connectionState) {
                        is MirrorWebSocket.ConnectionState.Connected -> {
                            Icon(
                                imageVector = Icons.Default.Close,
                                contentDescription = "Desconectar",
                                tint = MirrorRed,
                                modifier = Modifier.size(iconSize)
                            )
                        }
                        is MirrorWebSocket.ConnectionState.Connecting -> {
                            CircularProgressIndicator(
                                modifier = Modifier.size(iconSize),
                                strokeWidth = 2.dp,
                                color = MirrorAccent
                            )
                        }
                        else -> {
                            Icon(
                                imageVector = Icons.Default.Phone,
                                contentDescription = "Conectar",
                                tint = MirrorAccent,
                                modifier = Modifier.size(iconSize)
                            )
                        }
                    }
                }
            }

            // v1.2: monitor mode toggle — v1.2.2 uses Close when active so the user
            // can always see the affordance to LEAVE the mode (was Check, confusing).
            IconButton(
                onClick = onToggleMonitorMode,
                modifier = Modifier.size(btnSize)
            ) {
                Icon(
                    imageVector = if (monitorMode) Icons.Default.Close
                                  else Icons.Default.Add,
                    contentDescription = if (monitorMode) "Sair do Modo Monitor" else "Modo Monitor",
                    tint = if (monitorMode) MirrorRed else MirrorTextDim,
                    modifier = Modifier.size(iconSize)
                )
            }

            // v1.4.2: Explicit click buttons — only useful in CURSOR mode but
            // always shown when touch is enabled. Three buttons:
            //   L  = mouse left click   (pyautogui.click(button='left'))
            //   R  = mouse right click  (pyautogui.click(button='right'))
            //   2x = double click       (pyautogui.doubleClick())
            // The click happens at the current PC cursor position, so the user
            // drags with finger to position, then taps L/R/2x to act.
            if (touchEnabled) {
                IconButton(
                    onClick = { onClickRequest("left") },
                    modifier = Modifier.size(clickBtnSize)
                ) {
                    Icon(
                        imageVector = Icons.Default.Check,
                        contentDescription = "Clique esquerdo",
                        tint = MirrorGreen,
                        modifier = Modifier.size(clickIconSize)
                    )
                }
                IconButton(
                    onClick = { onClickRequest("right") },
                    modifier = Modifier.size(clickBtnSize)
                ) {
                    Icon(
                        imageVector = Icons.Default.Add,
                        contentDescription = "Clique direito",
                        tint = MirrorYellow,
                        modifier = Modifier.size(clickIconSize)
                    )
                }
                IconButton(
                    onClick = { onClickRequest("double") },
                    modifier = Modifier.size(clickBtnSize)
                ) {
                    Icon(
                        imageVector = Icons.Default.Edit,
                        contentDescription = "Clique duplo",
                        tint = MirrorAccent,
                        modifier = Modifier.size(clickIconSize)
                    )
                }
            }

            // Settings
            IconButton(
                onClick = onSettingsClick,
                modifier = Modifier.size(btnSize)
            ) {
                Icon(
                    imageVector = Icons.Default.Settings,
                    contentDescription = "Configurações",
                    tint = MirrorAccent,
                    modifier = Modifier.size(iconSize)
                )
            }
        }
    }
}

@Composable
private fun TouchBadge(
    enabled: Boolean,
    mode: TouchMode,
    onClick: () -> Unit,
    onLongClick: () -> Unit
) {
    val color = if (enabled) MirrorGreen else MirrorTextDim
    val text = when {
        !enabled -> "Touch OFF"
        else -> when (mode) {
            TouchMode.OFF -> "Touch OFF"
            TouchMode.CURSOR -> "Cursor"
            TouchMode.CLICK_ONLY -> "Caneta"
            TouchMode.DRAW -> "Desenhar"
            TouchMode.HERMES -> "Hermes"
        }
    }
    Surface(
        color = if (enabled) MirrorSurfaceVariant else Color.Transparent,
        shape = RoundedCornerShape(6.dp),
        border = androidx.compose.foundation.BorderStroke(1.dp, color.copy(alpha = 0.4f)),
        modifier = Modifier
            .pointerInput(Unit) {
                detectTapGestures(
                    onTap = { onClick() },
                    onLongPress = { onLongClick() }
                )
            }
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            Icon(
                imageVector = if (mode == TouchMode.DRAW) Icons.Default.Edit
                              else if (mode == TouchMode.CLICK_ONLY) Icons.Default.Phone
                              else Icons.Default.Build,
                contentDescription = null,
                tint = color,
                modifier = Modifier.size(14.dp)
            )
            Text(
                text = text,
                style = MaterialTheme.typography.labelSmall,
                color = color,
                fontSize = 11.sp
            )
        }
    }
}

@Composable
private fun SettingsDialog(
    ipAddress: String,
    onIpChange: (String) -> Unit,
    portText: String,                              // v1.8.1: porta configuravel
    onPortChange: (String) -> Unit,
    connectionState: MirrorWebSocket.ConnectionState,
    localIp: String,
    isScanning: Boolean = false,                   // v1.9: network scan state
    scanMessage: String = "",                      // v1.9: scan feedback message
    onScanNetwork: () -> Unit = {},                // v1.9: trigger network scan
    touchEnabled: Boolean,
    onTouchEnabledChange: (Boolean) -> Unit,
    touchMode: TouchMode,
    onTouchModeChange: (TouchMode) -> Unit,
    cursorSize: CursorSize,
    onCursorSizeChange: (CursorSize) -> Unit,
    monitorMode: Boolean,
    onMonitorModeChange: (Boolean) -> Unit,
    touchpadMode: Boolean,                          // v1.8.7: embedded touchpad (Hermes)
    onTouchpadModeChange: (Boolean) -> Unit,        // v1.8.7
    // v1.7.2: multi-monitor support
    monitors: List<MirrorWebSocket.MonitorInfo> = emptyList(),
    currentMonitorIdx: Int = 0,
    onMonitorSwitch: (Int) -> Unit = {},
    onConnect: () -> Unit,
    onDisconnect: () -> Unit,
    onDismiss: () -> Unit
) {
    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false)
    ) {
        Box(modifier = Modifier.fillMaxSize()) {
            Surface(
                modifier = Modifier
                    .align(Alignment.Center)  // v1.4.3: center dialog — doesn't cover edges
                    .fillMaxWidth(0.90f)
                    .heightIn(min = 300.dp, max = 600.dp)  // v1.4.3: cap height so edges remain visible
                    .padding(16.dp),
                color = MirrorSurface,
                shape = RoundedCornerShape(16.dp),
                tonalElevation = 8.dp
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(20.dp)
                        .verticalScroll(rememberScrollState()),  // v1.4.3: scrollable if content overflows
                    verticalArrangement = Arrangement.spacedBy(14.dp)
                ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    // v1.9: accent icon badge next to title
                    Surface(
                        color = MirrorAccent.copy(alpha = 0.15f),
                        shape = RoundedCornerShape(10.dp),
                        modifier = Modifier.size(36.dp)
                    ) {
                        Box(contentAlignment = Alignment.Center) {
                            Icon(
                                imageVector = Icons.Default.Settings,
                                contentDescription = null,
                                tint = MirrorAccent,
                                modifier = Modifier.size(20.dp)
                            )
                        }
                    }
                    Spacer(Modifier.width(12.dp))
                    Text(
                        text = "Configurações",
                        style = MaterialTheme.typography.headlineSmall.copy(
                            fontWeight = FontWeight.Bold
                        ),
                        color = MirrorText,
                        modifier = Modifier.weight(1f)
                    )
                    IconButton(onClick = onDismiss) {
                        Icon(
                            imageVector = Icons.Default.Close,
                            contentDescription = "Fechar",
                            tint = MirrorTextDim
                        )
                    }
                }
                // v1.9: subtle accent line instead of full divider
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(2.dp)
                        .background(
                            androidx.compose.ui.graphics.Brush.horizontalGradient(
                                colors = listOf(MirrorAccent, MirrorAccent.copy(alpha = 0.1f), Color.Transparent)
                            )
                        )
                )

                SettingsSection(title = "Conexão", icon = Icons.Default.Phone) {
                                    // v1.8.1: IP + Porta lado a lado (Row com weight)
                                    Row(
                                        modifier = Modifier.fillMaxWidth(),
                                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                                    ) {
                                        OutlinedTextField(
                                            value = ipAddress,
                                            onValueChange = onIpChange,
                                            modifier = Modifier.weight(1f),
                                            singleLine = true,
                                            label = { Text("IP do PC") },
                                            textStyle = MaterialTheme.typography.bodyLarge.copy(
                                                fontFamily = FontFamily.Monospace
                                            ),
                                            colors = OutlinedTextFieldDefaults.colors(
                                                focusedBorderColor = MirrorAccent,
                                                unfocusedBorderColor = MirrorBorder,
                                                focusedTextColor = MirrorText,
                                                unfocusedTextColor = MirrorText,
                                                cursorColor = MirrorAccent,
                                                focusedLabelColor = MirrorAccent,
                                                unfocusedLabelColor = MirrorTextDim,
                                            )
                                        )
                                        OutlinedTextField(
                                            value = portText,
                                            onValueChange = { v ->
                                                // aceita apenas digitos
                                                val filtered = v.filter { it.isDigit() }.take(5)
                                                onPortChange(filtered)
                                            },
                                            modifier = Modifier.width(110.dp),
                                            singleLine = true,
                                            label = { Text("Porta") },
                                            textStyle = MaterialTheme.typography.bodyLarge.copy(
                                                fontFamily = FontFamily.Monospace
                                            ),
                                            colors = OutlinedTextFieldDefaults.colors(
                                                focusedBorderColor = MirrorAccent,
                                                unfocusedBorderColor = MirrorBorder,
                                                focusedTextColor = MirrorText,
                                                unfocusedTextColor = MirrorText,
                                                cursorColor = MirrorAccent,
                                                focusedLabelColor = MirrorAccent,
                                                unfocusedLabelColor = MirrorTextDim,
                                            )
                                        )
                                    }
                                    Spacer(Modifier.height(6.dp))
                                    Text(
                                        text = "IP local do tablet: $localIp",
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MirrorTextDim
                                    )
                                    // v1.8.1: dica para emulador Android (10.0.2.2 = host PC)
                                    Text(
                                        text = "Dica: no emulador Android use IP 10.0.2.2 (e' o proprio PC).",
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MirrorTextDim
                                    )
                                    Spacer(Modifier.height(8.dp))
                                    // v1.9: Scan Network button — UDP broadcast discovery
                                    OutlinedButton(
                                        onClick = onScanNetwork,
                                        modifier = Modifier.fillMaxWidth(),
                                        enabled = !isScanning &&
                                            connectionState !is MirrorWebSocket.ConnectionState.Connecting,
                                        shape = RoundedCornerShape(10.dp),
                                        colors = ButtonDefaults.outlinedButtonColors(
                                            contentColor = MirrorAccent
                                        ),
                                        border = BorderStroke(
                                            1.dp,
                                            if (isScanning) MirrorAccent.copy(alpha = 0.5f) else MirrorBorder
                                        )
                                    ) {
                                        if (isScanning) {
                                            CircularProgressIndicator(
                                                modifier = Modifier.size(16.dp),
                                                color = MirrorAccent,
                                                strokeWidth = 2.dp
                                            )
                                            Spacer(Modifier.width(8.dp))
                                            Text("Procurando...", style = MaterialTheme.typography.labelLarge)
                                        } else {
                                            Icon(
                                                imageVector = Icons.Default.Search,
                                                contentDescription = null,
                                                modifier = Modifier.size(18.dp)
                                            )
                                            Spacer(Modifier.width(8.dp))
                                            Text("Procurar na Rede", style = MaterialTheme.typography.labelLarge)
                                        }
                                    }
                                    // v1.9: Scan feedback message
                                    if (scanMessage.isNotEmpty()) {
                                        Text(
                                            text = scanMessage,
                                            style = MaterialTheme.typography.bodySmall,
                                            color = if (scanMessage.startsWith("✓")) MirrorGreen
                                                    else if (scanMessage.startsWith("Nenhum")) MirrorRed
                                                    else MirrorYellow,
                                            fontWeight = FontWeight.SemiBold
                                        )
                                    }
                                    Spacer(Modifier.height(8.dp))
                    if (connectionState is MirrorWebSocket.ConnectionState.Connected) {
                        OutlinedButton(
                            onClick = onDisconnect,
                            modifier = Modifier.fillMaxWidth(),
                            colors = ButtonDefaults.outlinedButtonColors(
                                contentColor = MirrorRed
                            )
                        ) { Text("Desconectar") }
                    } else {
                        Button(
                            onClick = onConnect,
                            modifier = Modifier.fillMaxWidth(),
                            colors = ButtonDefaults.buttonColors(
                                containerColor = MirrorAccent
                            ),
                            enabled = ipAddress.isNotBlank() &&
                                connectionState !is MirrorWebSocket.ConnectionState.Connecting
                        ) {
                            Text(
                                if (connectionState is MirrorWebSocket.ConnectionState.Connecting)
                                    "Conectando..." else "Conectar"
                            )
                        }
                    }
                }

                // v1.7.0: Monitor selector — only when connected with multiple monitors
                if (monitors.size > 1 && connectionState is MirrorWebSocket.ConnectionState.Connected) {
                    SettingsSection(title = "Tela", icon = Icons.Default.Phone) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(6.dp)
                        ) {
                            monitors.forEach { mon ->
                                val selected = mon.idx == currentMonitorIdx
                                Surface(
                                    color = if (selected) MirrorAccent else MirrorSurface,
                                    shape = RoundedCornerShape(8.dp),
                                    modifier = Modifier
                                        .weight(1f)
                                        .clickable { onMonitorSwitch(mon.idx) },
                                    border = androidx.compose.foundation.BorderStroke(
                                        1.dp,
                                        if (selected) MirrorAccent else MirrorBorder
                                    )
                                ) {
                                    Column(
                                        modifier = Modifier.padding(vertical = 6.dp, horizontal = 4.dp),
                                        horizontalAlignment = Alignment.CenterHorizontally
                                    ) {
                                        Text(
                                            text = "Tela ${mon.idx + 1}",
                                            style = MaterialTheme.typography.labelMedium,
                                            color = if (selected) MirrorText else MirrorTextDim,
                                            fontWeight = if (selected) FontWeight.Bold else FontWeight.Normal
                                        )
                                        Text(
                                            text = "${mon.w}x${mon.h}",
                                            style = MaterialTheme.typography.labelSmall,
                                            color = MirrorTextDim
                                        )
                                    }
                                }
                            }
                        }
                    }
                }

                // v1.2: Monitor mode + Touch combined
                SettingsSection(title = "Modo de Exibição", icon = Icons.Default.Add) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = "Modo Monitor",
                                style = MaterialTheme.typography.bodyLarge,
                                color = MirrorText
                            )
                            Text(
                                text = "Tela cheia sem distrações",
                                style = MaterialTheme.typography.bodySmall,
                                color = MirrorTextDim
                            )
                        }
                        Switch(
                            checked = monitorMode,
                            onCheckedChange = onMonitorModeChange,
                            colors = SwitchDefaults.colors(
                                checkedThumbColor = MirrorText,
                                checkedTrackColor = MirrorAccent,
                                uncheckedThumbColor = MirrorTextDim,
                                uncheckedTrackColor = MirrorBorder,
                            )
                        )
                    }
                    Divider(color = MirrorBorder, modifier = Modifier.padding(vertical = 4.dp))
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = "Touch Input",
                                style = MaterialTheme.typography.bodyLarge,
                                color = MirrorText
                            )
                            Text(
                                text = "Cursor, caneta ou desenhar",
                                style = MaterialTheme.typography.bodySmall,
                                color = MirrorTextDim
                            )
                        }
                        Switch(
                            checked = touchEnabled,
                            onCheckedChange = onTouchEnabledChange,
                            colors = SwitchDefaults.colors(
                                checkedThumbColor = MirrorText,
                                checkedTrackColor = MirrorGreen,
                                uncheckedThumbColor = MirrorTextDim,
                                uncheckedTrackColor = MirrorBorder,
                            )
                        )
                    }
                    if (touchEnabled) {
                        Spacer(Modifier.height(6.dp))
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(6.dp)
                        ) {
                            TouchModeChip(
                                label = "Cursor",
                                icon = Icons.Default.Build,
                                selected = touchMode == TouchMode.CURSOR,
                                enabled = true,
                                onClick = { onTouchModeChange(TouchMode.CURSOR) },
                                modifier = Modifier.weight(1f)
                            )
                            TouchModeChip(
                                label = "Caneta",
                                icon = Icons.Default.Phone,
                                selected = touchMode == TouchMode.CLICK_ONLY,
                                enabled = true,
                                onClick = { onTouchModeChange(TouchMode.CLICK_ONLY) },
                                modifier = Modifier.weight(1f)
                            )
                            TouchModeChip(
                                label = "Desenhar",
                                icon = Icons.Default.Edit,
                                selected = touchMode == TouchMode.DRAW,
                                enabled = true,
                                onClick = { onTouchModeChange(TouchMode.DRAW) },
                                modifier = Modifier.weight(1f)
                            )
                        }
                    }
                }

                // Visual: cursor size
                SettingsSection(title = "Cursor", icon = Icons.Default.Build) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(4.dp)
                    ) {
                        CursorSize.values().forEach { size ->
                            val selected = cursorSize == size
                            Surface(
                                color = if (selected) MirrorAccent else MirrorSurface,
                                shape = RoundedCornerShape(8.dp),
                                border = androidx.compose.foundation.BorderStroke(
                                    1.dp,
                                    if (selected) MirrorAccent else MirrorBorder
                                ),
                                modifier = Modifier
                                    .weight(1f)
                                    .clickable { onCursorSizeChange(size) }
                            ) {
                                Text(
                                    text = size.label,
                                    style = MaterialTheme.typography.labelSmall,
                                    color = if (selected) MirrorText else MirrorTextDim,
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .padding(vertical = 6.dp),
                                    textAlign = androidx.compose.ui.text.style.TextAlign.Center
                                )
                            }
                        }
                    }
                }

                // v1.8.7: Modo de Uso — Video Remoto ou Touchpad Puro (Hermes embutido)
                SettingsSection(title = "Modo de Uso", icon = Icons.Default.Build) {
                    // Modo 1: Vídeo Remoto (padrão)
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = "Vídeo Remoto",
                                style = MaterialTheme.typography.bodyLarge,
                                color = MirrorText,
                                fontWeight = if (!touchpadMode) FontWeight.Bold else FontWeight.Normal
                            )
                            Text(
                                text = "Espelha a tela do PC com touch",
                                style = MaterialTheme.typography.bodySmall,
                                color = MirrorTextDim
                            )
                        }
                        RadioButton(
                            selected = !touchpadMode,
                            onClick = { if (touchpadMode) onTouchpadModeChange(false) },
                            colors = RadioButtonDefaults.colors(
                                selectedColor = MirrorAccent,
                                unselectedColor = MirrorTextDim
                            )
                        )
                    }
                    Divider(color = MirrorBorder, modifier = Modifier.padding(vertical = 4.dp))
                    // Modo 2: Touchpad Puro (Hermes)
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = "Touchpad Puro",
                                style = MaterialTheme.typography.bodyLarge,
                                color = MirrorText,
                                fontWeight = if (touchpadMode) FontWeight.Bold else FontWeight.Normal
                            )
                            Text(
                                text = "Controle puro de mouse, sem vídeo. Latência ~20ms.",
                                style = MaterialTheme.typography.bodySmall,
                                color = MirrorTextDim
                            )
                        }
                        RadioButton(
                            selected = touchpadMode,
                            onClick = { if (!touchpadMode) onTouchpadModeChange(true) },
                            colors = RadioButtonDefaults.colors(
                                selectedColor = MirrorGreen,
                                unselectedColor = MirrorTextDim
                            )
                        )
                    }
                }

                // v1.8.7: Aceleração por Hardware — controla decode do JPEG no cliente Android
                SettingsSection(title = "Aceleração por Hardware", icon = Icons.Default.Build) {
                    var hwAccel by rememberSaveable { mutableStateOf("Auto") }
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(4.dp)
                    ) {
                        listOf(
                            "Auto" to "Detecta automatico",
                            "Hardware" to "Usa GPU (mais rapido)",
                            "Software" to "CPU (mais compativel)"
                        ).forEach { (label, desc) ->
                            val selected = hwAccel == label
                            Surface(
                                color = if (selected) MirrorAccent else MirrorSurface,
                                shape = RoundedCornerShape(8.dp),
                                border = androidx.compose.foundation.BorderStroke(
                                    1.dp,
                                    if (selected) MirrorAccent else MirrorBorder
                                ),
                                modifier = Modifier
                                    .weight(1f)
                                    .clickable { hwAccel = label }
                            ) {
                                Column(
                                    modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp, horizontal = 6.dp),
                                    horizontalAlignment = Alignment.CenterHorizontally
                                ) {
                                    Text(
                                        text = label,
                                        style = MaterialTheme.typography.labelMedium,
                                        color = if (selected) MirrorText else MirrorTextDim,
                                        fontWeight = if (selected) FontWeight.Bold else FontWeight.Normal
                                    )
                                    Text(
                                        text = desc,
                                        style = MaterialTheme.typography.labelSmall,
                                        color = if (selected) MirrorText.copy(alpha = 0.8f) else MirrorTextDim.copy(alpha = 0.7f),
                                        textAlign = androidx.compose.ui.text.style.TextAlign.Center
                                    )
                                }
                            }
                        }
                    }
                }

                // v1.8.7: Gestos — instruções do modo trackpad e tátil
                SettingsSection(title = "Gestos", icon = Icons.Default.Info) {
                    Text(
                        text = "Modo Trackpad (Touchpad Puro):",
                        style = MaterialTheme.typography.labelLarge,
                        color = MirrorAccent,
                        fontWeight = FontWeight.Bold
                    )
                    Spacer(Modifier.height(4.dp))
                    GestureRow("Arrastar 1 dedo", "Mover cursor")
                    GestureRow("Toque rápido", "Clique esquerdo")
                    GestureRow("Toque 2 dedos", "Clique direito")
                    GestureRow("Arrastar 2 dedos", "Rolar tela")
                    GestureRow("Pinça (2 dedos)", "Zoom")
                    Spacer(Modifier.height(10.dp))
                    Text(
                        text = "Modo Tátil (Vídeo Remoto):",
                        style = MaterialTheme.typography.labelLarge,
                        color = MirrorAccent,
                        fontWeight = FontWeight.Bold
                    )
                    Spacer(Modifier.height(4.dp))
                    GestureRow("Toque", "Clique no PC")
                    GestureRow("Arrastar", "Arrastar no PC")
                    GestureRow("Dois dedos", "Alternar modo touch")
                }

                // Footer: version + close
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = "v${BuildConfig.VERSION_NAME}",
                        style = MaterialTheme.typography.labelSmall,
                        color = MirrorTextDim
                    )
                    Button(
                        onClick = onDismiss,
                        colors = ButtonDefaults.buttonColors(
                            containerColor = MirrorAccent
                        ),
                        shape = RoundedCornerShape(8.dp)
                    ) {
                        Text(
                            "Fechar",
                            style = MaterialTheme.typography.labelLarge.copy(
                                fontWeight = FontWeight.SemiBold
                            )
                        )
                    }
                }
            }
        }
    }
}
}

@Composable
private fun TouchModeChip(
    label: String,
    icon: ImageVector,
    selected: Boolean,
    enabled: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    val bg = if (selected) MirrorAccent else MirrorSurface
    val fg = if (!enabled) MirrorTextDim else MirrorText
    Surface(
        color = bg,
        shape = RoundedCornerShape(8.dp),
        border = androidx.compose.foundation.BorderStroke(
            1.dp,
            if (selected) MirrorAccent else MirrorBorder
        ),
        modifier = modifier.clickable(enabled = enabled) { onClick() }
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(vertical = 8.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(2.dp)
        ) {
            Icon(imageVector = icon, contentDescription = null, tint = fg,
                 modifier = Modifier.size(18.dp))
            Text(text = label, style = MaterialTheme.typography.labelSmall, color = fg)
        }
    }
}

@Composable
private fun SettingsSection(
    title: String,
    icon: ImageVector,
    content: @Composable ColumnScope.() -> Unit
) {
    Column(
        modifier = Modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        // v1.9: Section header with accent-tinted icon and subtle underline
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Surface(
                color = MirrorAccent.copy(alpha = 0.12f),
                shape = RoundedCornerShape(6.dp),
                modifier = Modifier.size(28.dp)
            ) {
                Box(contentAlignment = Alignment.Center) {
                    Icon(
                        imageVector = icon,
                        contentDescription = null,
                        tint = MirrorAccent,
                        modifier = Modifier.size(15.dp)
                    )
                }
            }
            Text(
                text = title,
                style = MaterialTheme.typography.titleSmall,
                color = MirrorText,
                fontWeight = FontWeight.SemiBold,
                letterSpacing = 0.3.sp
            )
        }
        // v1.9: Card with left accent stripe using IntrinsicSize for proper height
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .height(IntrinsicSize.Min)
        ) {
            // Left accent stripe
            Box(
                modifier = Modifier
                    .width(3.dp)
                    .fillMaxHeight()
                    .clip(RoundedCornerShape(topStart = 3.dp, bottomStart = 3.dp))
                    .background(MirrorAccent.copy(alpha = 0.5f))
            )
            Surface(
                color = MirrorSurfaceVariant,
                shape = RoundedCornerShape(topEnd = 12.dp, bottomEnd = 12.dp),
                modifier = Modifier.weight(1f),
                tonalElevation = 1.dp,
                border = BorderStroke(0.5.dp, MirrorBorder.copy(alpha = 0.5f))
            ) {
                Column(
                    modifier = Modifier.fillMaxWidth().padding(14.dp),
                    content = content
                )
            }
        }
    }
}

@Composable
private fun InfoRow(label: String, value: String) {
    Row(modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
        Text(text = label, style = MaterialTheme.typography.bodyMedium, color = MirrorTextDim,
             modifier = Modifier.weight(1f))
        Text(
            text = value,
            style = MaterialTheme.typography.bodyMedium.copy(fontFamily = FontFamily.Monospace),
            color = MirrorText
        )
    }
}

// v1.8.7: linha de gesto (ação → resultado)
@Composable
private fun GestureRow(action: String, result: String) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(vertical = 3.dp),
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            text = "• $action",
            style = MaterialTheme.typography.bodySmall,
            color = MirrorText,
            fontWeight = FontWeight.SemiBold,
            modifier = Modifier.weight(1f)
        )
        Text(
            text = "→ $result",
            style = MaterialTheme.typography.bodySmall,
            color = MirrorTextDim
        )
    }
}

private fun statusColor(state: MirrorWebSocket.ConnectionState): Color = when (state) {
    is MirrorWebSocket.ConnectionState.Connected -> MirrorGreen
    is MirrorWebSocket.ConnectionState.Connecting -> MirrorYellow
    is MirrorWebSocket.ConnectionState.Error -> MirrorRed
    is MirrorWebSocket.ConnectionState.Disconnected -> MirrorTextDim
}

private fun statusLabel(state: MirrorWebSocket.ConnectionState): String = when (state) {
    is MirrorWebSocket.ConnectionState.Connected -> "Conectado"
    is MirrorWebSocket.ConnectionState.Connecting -> "Conectando..."
    is MirrorWebSocket.ConnectionState.Error -> "Erro"
    is MirrorWebSocket.ConnectionState.Disconnected -> "Desconectado"
}

private fun fpsColor(fps: Int): Color = when {
    fps >= 25 -> MirrorGreen
    fps >= 15 -> MirrorYellow
    else -> MirrorRed
}
