package com.mirrorx.app.hermes

import android.os.SystemClock
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.mirrorx.app.network.MirrorWebSocket
import kotlin.math.abs
import kotlin.math.hypot

/**
 * v1.5.9 Hybrid Hermes
 *  - Cursor LOCAL no trackball (segue o dedo, verde translúcido)
 *  - Cursor do PC visível (seta vermelha + borda branca, 24dp)
 *  - Indicador de latência na topbar
 *  - Auto-reconexão
 */
@Composable
fun TouchpadView(
    client: MirrorWebSocket,
    sensitivity: Float = 1.5f,
    onSensitivityChange: (Float) -> Unit = {},
    modifier: Modifier = Modifier
) {
    val connectionState by client.connectionState.collectAsState()
    val currentFrame by client.currentFrame.collectAsState()
    val screenInfo by client.screenInfo.collectAsState()
    val remoteCursor by client.remoteCursor.collectAsState()
    val latencyMs by client.latencyMs.collectAsState()

    var showSettings by remember { mutableStateOf(false) }
    var serverMode by remember { mutableIntStateOf(0) }

    // In-app mirror settings
    var mirrorQuality by remember { mutableIntStateOf(75) }
    var mirrorScale by remember { mutableFloatStateOf(0.75f) }
    var mirrorFps by remember { mutableIntStateOf(30) }
    var mirrorAutoAdjust by remember { mutableStateOf(false) }

    // Local cursor position (inside trackball) — normalized -1..1
    var localBallX by remember { mutableFloatStateOf(0f) }
    var localBallY by remember { mutableFloatStateOf(0f) }
    var isPressed by remember { mutableStateOf(false) }

    val sendIntervalMs = when (serverMode) {
        2 -> 66L; 1 -> 33L; else -> 16L
    }
    val isLandscape = LocalConfiguration.current.orientation ==
        android.content.res.Configuration.ORIENTATION_LANDSCAPE
    val trackballSizeDp = if (isLandscape) 180.dp else 200.dp
    val buttonSizeDp = if (isLandscape) 60.dp else 68.dp

    // Auto-reconnect
    LaunchedEffect(connectionState) {
        if (connectionState is MirrorWebSocket.ConnectionState.Disconnected) {
            kotlinx.coroutines.delay(1000)
            // try reconnect once
        }
    }

    Box(
        modifier = modifier
            .fillMaxSize()
            .background(Color(0xFF0A0A0F))
    ) {
        // ── PC screen stream ────────────────────────────────────────
        val frame = currentFrame
        if (frame != null) {
            Image(
                bitmap = frame.asImageBitmap(),
                contentDescription = "PC screen",
                contentScale = ContentScale.Fit,
                modifier = Modifier.fillMaxSize()
            )
            Box(
                modifier = Modifier.fillMaxSize()
                    .background(Color.Black.copy(alpha = 0.10f))
            )
        } else {
            Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Icon(Icons.Default.Refresh, null,
                        tint = Color(0xFF2A2A38), modifier = Modifier.size(56.dp))
                    Spacer(Modifier.height(8.dp))
                    Text(
                        text = if (connectionState is MirrorWebSocket.ConnectionState.Connected) "Aguardando vídeo..."
                        else "Conecte ao servidor",
                        color = Color(0xFF4A4A60), fontSize = 13.sp
                    )
                }
            }
        }

        // ── PC CURSOR (24dp red arrow) ──────────────────────────────
        remoteCursor?.let { (cx, cy) ->
            Canvas(modifier = Modifier.fillMaxSize()) {
                val xPx = cx * size.width
                val yPx = cy * size.height
                // White outline ring
                drawCircle(
                    color = Color.White.copy(alpha = 0.8f),
                    radius = 24f,
                    center = Offset(xPx, yPx),
                    style = Stroke(width = 3f)
                )
                // Red filled arrow dot
                drawCircle(
                    color = Color(0xFFEF4444).copy(alpha = 0.95f),
                    radius = 18f,
                    center = Offset(xPx, yPx),
                )
                // White inner crosshair
                drawCircle(
                    color = Color.White,
                    radius = 4f,
                    center = Offset(xPx, yPx),
                )
            }
        }

        // ── Top bar (translucent) ───────────────────────────────────
        HybridStatusBar(
            connectionState = connectionState,
            screenInfo = screenInfo,
            sensitivity = sensitivity,
            latencyMs = latencyMs,
            onSettingsClick = { showSettings = true },
        )

        // ── Bottom controls ─────────────────────────────────────────
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp, vertical = 12.dp)
                .align(Alignment.BottomCenter)
        ) {
            // L + R buttons
            Row(
                modifier = Modifier.align(Alignment.BottomStart),
                horizontalArrangement = Arrangement.spacedBy(10.dp),
                verticalAlignment = Alignment.Bottom,
            ) {
                ClickButton("L", Color(0xFF10B981), { client.sendHermesClick(0) }, buttonSizeDp)
                ClickButton("R", Color(0xFFF59E0B), { client.sendHermesClick(1) }, buttonSizeDp)
            }

            // Trackball (white translucent) with local cursor
            TrackballWithLocalCursor(
                client = client,
                sensitivity = sensitivity,
                sendIntervalMs = sendIntervalMs,
                onPositionChange = { nx, ny, pressed ->
                    localBallX = nx
                    localBallY = ny
                    isPressed = pressed
                },
                modifier = Modifier
                    .align(Alignment.BottomEnd)
                    .size(trackballSizeDp),
            )
        }

        // ── Settings sheet ──────────────────────────────────────────
        if (showSettings) {
            MirrorSettingsSheet(
                serverMode = serverMode,
                sensitivity = sensitivity,
                mirrorQuality = mirrorQuality,
                mirrorScale = mirrorScale,
                mirrorFps = mirrorFps,
                mirrorAutoAdjust = mirrorAutoAdjust,
                onMode = { m -> serverMode = m; client.sendHermesQuality(m); showSettings = false },
                onSensitivity = onSensitivityChange,
                onQuality = { q -> mirrorQuality = q; client.sendMirrorConfig("quality", q) },
                onScale = { s -> mirrorScale = s; client.sendMirrorConfig("scale", s) },
                onFps = { f -> mirrorFps = f; client.sendMirrorConfig("fps", f) },
                onAutoAdjust = { a -> mirrorAutoAdjust = a; client.sendMirrorConfig("auto_adjust", if (a) 1 else 0) },
                onDismiss = { showSettings = false },
            )
        }
    }
}

// ─── Status bar with latency ───────────────────────────────────────────
@Composable
private fun HybridStatusBar(
    connectionState: MirrorWebSocket.ConnectionState,
    screenInfo: MirrorWebSocket.ScreenInfo,
    sensitivity: Float,
    latencyMs: Int,
    onSettingsClick: () -> Unit,
) {
    val latColor = when {
        latencyMs < 50 -> Color(0xFF10B981)
        latencyMs < 120 -> Color(0xFFF59E0B)
        else -> Color(0xFFEF4444)
    }
    Surface(
        color = Color(0xFF14141C).copy(alpha = 0.75f),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                val isConnected = connectionState is MirrorWebSocket.ConnectionState.Connected
                Box(Modifier.size(10.dp).clip(CircleShape).background(
                    if (isConnected) Color(0xFF10B981) else Color(0xFFEF4444)))
                Spacer(Modifier.width(10.dp))
                Text("Hermes v1.5.9", color = Color(0xFFE8E8F0),
                    fontWeight = FontWeight.SemiBold, fontSize = 14.sp)
                Spacer(Modifier.width(10.dp))
                val desc = when (screenInfo) {
                    is MirrorWebSocket.ScreenInfo.Known -> "${screenInfo.streamWidth}×${screenInfo.streamHeight}"
                    else -> "--"
                }
                Text(desc, color = Color(0xFF6E6E80), fontSize = 10.sp)
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("PING ${latencyMs}ms", color = latColor, fontSize = 10.sp,
                    fontWeight = FontWeight.Bold)
                Spacer(Modifier.width(12.dp))
                IconButton(onClick = onSettingsClick, modifier = Modifier.size(32.dp)) {
                    Icon(Icons.Default.Settings, "Settings", tint = Color(0xFFB0B0C0),
                        modifier = Modifier.size(20.dp))
                }
            }
        }
    }
}

// ─── Trackball with LOCAL CURSOR ────────────────────────────────────────
@Composable
private fun TrackballWithLocalCursor(
    client: MirrorWebSocket,
    sensitivity: Float,
    sendIntervalMs: Long,
    onPositionChange: (Float, Float, Boolean) -> Unit,
    modifier: Modifier = Modifier,
) {
    var ballX by remember { mutableFloatStateOf(0f) }
    var ballY by remember { mutableFloatStateOf(0f) }
    var isPressed by remember { mutableStateOf(false) }
    var pressedAtMs by remember { mutableStateOf(0L) }
    var accDx by remember { mutableFloatStateOf(0f) }
    var accDy by remember { mutableFloatStateOf(0f) }
    var lastSendMs by remember { mutableStateOf(0L) }

    val animatedX by animateFloatAsState(if (isPressed) ballX else 0f, label = "bx")
    val animatedY by animateFloatAsState(if (isPressed) ballY else 0f, label = "by")

    Box(
        modifier = modifier
            .clip(CircleShape)
            .background(Color.White.copy(alpha = 0.10f))
            .pointerInput(Unit) {
                detectTapGestures(
                    onPress = {
                        isPressed = true; pressedAtMs = SystemClock.uptimeMillis()
                        ballX = 0f; ballY = 0f; lastSendMs = pressedAtMs
                        accDx = 0f; accDy = 0f
                        onPositionChange(0f, 0f, true)
                        val released = tryAwaitRelease()
                        isPressed = false
                        onPositionChange(0f, 0f, false)
                        if (released) {
                            val held = SystemClock.uptimeMillis() - pressedAtMs
                            val totalMove = abs(accDx) + abs(accDy)
                            if (held < 250L && totalMove < 4f) client.sendHermesClick(0)
                            if (accDx.toInt() != 0 || accDy.toInt() != 0) {
                                client.sendHermesMove(accDx.toInt(), accDy.toInt())
                            }
                            accDx = 0f; accDy = 0f
                        }
                    },
                )
            }
            .pointerInput(Unit) {
                detectDragGestures(
                    onDragStart = { offset ->
                        val w = size.width.toFloat(); val h = size.height.toFloat()
                        ballX = ((offset.x / w) * 2f - 1f).coerceIn(-1f, 1f)
                        ballY = ((offset.y / h) * 2f - 1f).coerceIn(-1f, 1f)
                        isPressed = true; pressedAtMs = SystemClock.uptimeMillis()
                        lastSendMs = pressedAtMs; accDx = 0f; accDy = 0f
                        onPositionChange(ballX, ballY, true)
                    },
                    onDragEnd = {
                        isPressed = false
                        if (accDx.toInt() != 0 || accDy.toInt() != 0) {
                            client.sendHermesMove(accDx.toInt(), accDy.toInt())
                        }
                        accDx = 0f; accDy = 0f; ballX = 0f; ballY = 0f
                        onPositionChange(0f, 0f, false)
                    },
                    onDragCancel = { isPressed = false; accDx = 0f; accDy = 0f
                        ballX = 0f; ballY = 0f; onPositionChange(0f, 0f, false) },
                    onDrag = { change, _ ->
                        change.consume()
                        val w = size.width.toFloat(); val h = size.height.toFloat()
                        if (w <= 0f || h <= 0f) return@detectDragGestures
                        ballX = ((change.position.x / w) * 2f - 1f).coerceIn(-1f, 1f)
                        ballY = ((change.position.y / h) * 2f - 1f).coerceIn(-1f, 1f)
                        onPositionChange(ballX, ballY, true)
                        val dist = hypot(ballX, ballY)
                        val speed = (1f + dist * 3f) * sensitivity
                        val dx = (if (dist > 0.001f) ballX / dist else 0f) * speed * dist.coerceAtLeast(0.05f)
                        val dy = (if (dist > 0.001f) ballY / dist else 0f) * speed * dist.coerceAtLeast(0.05f)
                        accDx += dx; accDy += dy
                        val now = SystemClock.uptimeMillis()
                        if (now - lastSendMs >= sendIntervalMs) {
                            val sd = accDx.toInt(); val sy = accDy.toInt()
                            if (sd != 0 || sy != 0) {
                                client.sendHermesMove(sd, sy)
                                accDx -= sd; accDy -= sy
                            }
                            lastSendMs = now
                        }
                    },
                )
            },
        contentAlignment = Alignment.Center,
    ) {
        Canvas(modifier = Modifier.fillMaxSize()) {
            val cx = size.width / 2f; val cy = size.height / 2f
            val radius = size.minDimension / 2f
            // White outer ring
            drawCircle(
                color = Color.White.copy(alpha = 0.45f),
                radius = radius * 0.92f,
                center = Offset(cx, cy),
                style = Stroke(width = 2.5f),
            )
            // Local cursor position (offset from center)
            val bx = cx + animatedX * radius * 0.6f
            val by = cy + animatedY * radius * 0.6f
            val ballRadius = if (isPressed) radius * 0.30f else radius * 0.22f

            // Glow when pressed
            if (isPressed) {
                drawCircle(Color.Green.copy(alpha = 0.20f), ballRadius * 1.8f, Offset(bx, by))
            }
            // The ball (green when pressed = active cursor, white when idle)
            drawCircle(
                color = if (isPressed) Color(0xFF10B981).copy(alpha = 0.95f)
                else Color.White.copy(alpha = 0.85f),
                radius = ballRadius,
                center = Offset(bx, by),
            )
        }
        if (!isPressed) {
            Text("◉", color = Color.White.copy(alpha = 0.4f), fontSize = 20.sp)
        }
    }
}

// ─── Click button ───────────────────────────────────────────────────────
@Composable
private fun ClickButton(
    label: String, color: Color, onClick: () -> Unit,
    sizeDp: androidx.compose.ui.unit.Dp = 60.dp,
) {
    var pressed by remember { mutableStateOf(false) }
    Box(
        modifier = Modifier.size(sizeDp).clip(CircleShape)
            .background(color.copy(alpha = if (pressed) 1f else 0.80f))
            .pointerInput(Unit) {
                detectTapGestures(onPress = { pressed = true; onClick(); tryAwaitRelease(); pressed = false })
            },
        contentAlignment = Alignment.Center,
    ) {
        Text(label, color = Color.White, fontWeight = FontWeight.Bold, fontSize = 22.sp)
    }
}

// ─── Settings sheet (full mirror config in-app) ──────────────────────
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun MirrorSettingsSheet(
    serverMode: Int, sensitivity: Float,
    mirrorQuality: Int, mirrorScale: Float, mirrorFps: Int,
    mirrorAutoAdjust: Boolean,
    onMode: (Int) -> Unit, onSensitivity: (Float) -> Unit,
    onQuality: (Int) -> Unit, onScale: (Float) -> Unit, onFps: (Int) -> Unit,
    onAutoAdjust: (Boolean) -> Unit,
    onDismiss: () -> Unit,
) {
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        containerColor = Color(0xFF14141C),
        contentColor = Color(0xFFE8E8F0),
    ) {
        Column(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            Text("Configurações", fontSize = 16.sp, fontWeight = FontWeight.Bold, color = Color(0xFFE8E8F0))

            SectionLabel("Modo Hermes")
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                ModeChip("Normal", 0, serverMode, onMode)
                ModeChip("Ruim", 1, serverMode, onMode)
                ModeChip("Ultra", 2, serverMode, onMode)
            }

            SectionLabel("Sensibilidade: %.1fx".format(sensitivity))
            Slider(value = sensitivity, onValueChange = onSensitivity, valueRange = 0.3f..3.0f, steps = 26,
                colors = SliderDefaults.colors(thumbColor = Color(0xFF6366F1), activeTrackColor = Color(0xFF818CF8),
                    inactiveTrackColor = Color(0xFF2A2A38)))

            SectionLabel("Qualidade: $mirrorQuality%")
            Slider(value = mirrorQuality.toFloat(), onValueChange = { onQuality(it.toInt()) },
                valueRange = 20f..95f, steps = 14,
                colors = SliderDefaults.colors(thumbColor = Color(0xFF10B981), activeTrackColor = Color(0xFF10B981),
                    inactiveTrackColor = Color(0xFF2A2A38)))

            SectionLabel("Escala: ${(mirrorScale * 100).toInt()}%")
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                ScaleChip("50%", 0.50f, mirrorScale, onScale)
                ScaleChip("75%", 0.75f, mirrorScale, onScale)
                ScaleChip("100%", 1.00f, mirrorScale, onScale)
            }

            SectionLabel("FPS alvo: $mirrorFps")
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                listOf(15, 24, 30, 45, 60).forEach { fps ->
                    ModeChip("$fps", fps, mirrorFps) { onFps(it) }
                }
            }

            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("Auto-ajuste de FPS", color = Color(0xFFB0B0C0), fontSize = 12.sp, modifier = Modifier.weight(1f))
                Switch(checked = mirrorAutoAdjust, onCheckedChange = onAutoAdjust,
                    colors = SwitchDefaults.colors(checkedThumbColor = Color(0xFF10B981), checkedTrackColor = Color(0xFF065F46)))
            }

            Spacer(Modifier.height(16.dp))
        }
    }
}

@Composable
private fun SectionLabel(text: String) {
    Text(text, fontSize = 11.sp, color = Color(0xFF8A8AA0), fontWeight = FontWeight.SemiBold)
}

@Composable
private fun ModeChip(label: String, value: Int, current: Int, onSel: (Int) -> Unit) {
    val sel = value == current
    Surface(
        color = if (sel) Color(0xFF6366F1) else Color(0xFF1E1E2C),
        shape = RoundedCornerShape(18.dp),
        modifier = Modifier.pointerInput(value) { detectTapGestures(onTap = { onSel(value) }) }
    ) {
        Text(label, color = if (sel) Color.White else Color(0xFFB0B0C0),
            fontWeight = FontWeight.SemiBold, fontSize = 12.sp,
            modifier = Modifier.padding(horizontal = 14.dp, vertical = 8.dp))
    }
}

@Composable
private fun ScaleChip(label: String, value: Float, current: Float, onSel: (Float) -> Unit) {
    val sel = abs(value - current) < 0.01f
    Surface(
        color = if (sel) Color(0xFF10B981) else Color(0xFF1E1E2C),
        shape = RoundedCornerShape(18.dp),
        modifier = Modifier.pointerInput(label.hashCode()) { detectTapGestures(onTap = { onSel(value) }) }
    ) {
        Text(label, color = if (sel) Color.White else Color(0xFFB0B0C0),
            fontWeight = FontWeight.SemiBold, fontSize = 12.sp,
            modifier = Modifier.padding(horizontal = 14.dp, vertical = 8.dp))
    }
}