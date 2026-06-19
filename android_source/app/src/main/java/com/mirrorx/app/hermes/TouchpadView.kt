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
 * v1.6.6 Hybrid Hermes — configs consolidadas (tudo na tela principal).
 *
 * Layout unificado sem modal de configurações separado:
 *
 *  ┌─────────────────────────────────────────────────────┐
 *  │ ● v1.6.6  60Hz  30Hz  15Hz    NORMAL  ⚙            │ ← status + mode chips
 *  ├─────────────────────────────────────────────────────┤
 *  │                     PC SCREEN                       │
 *  │                       ...                           │
 *  │                                                      │
 *  │  ⬤L ⬤R  ⬆  Sens: ═══○═══ 1.5x  Qual: ══○══ 75%  │ ← tudo integrado
 *  │                    Escala: [50%][75%][100%]          │
 *  └─────────────────────────────────────────────────────┘
 */
@OptIn(ExperimentalLayoutApi::class)
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
    val cursorVisible by client.cursorVisible.collectAsState()

    var serverMode by remember { mutableIntStateOf(0) }
    var mirrorQuality by remember { mutableIntStateOf(75) }
    var mirrorScale by remember { mutableFloatStateOf(0.75f) }
    var localSensitivity by remember { mutableFloatStateOf(sensitivity) }

    val sendIntervalMs = when (serverMode) { 2 -> 66L; 1 -> 33L; else -> 16L }
    val isLandscape = LocalConfiguration.current.orientation ==
        android.content.res.Configuration.ORIENTATION_LANDSCAPE
    val trackballSizeDp = if (isLandscape) 140.dp else 200.dp

    Box(
        modifier = modifier
            .fillMaxSize()
            .background(Color(0xFF0A0A0F))
    ) {
        // Video background
        val frame = currentFrame
        if (frame != null) {
            Image(
                bitmap = frame.asImageBitmap(),
                contentDescription = "PC screen",
                contentScale = ContentScale.Fit,
                modifier = Modifier.fillMaxSize()
            )
            Box(modifier = Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.08f)))
        } else {
            Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Icon(Icons.Default.Refresh, null, tint = Color(0xFF2A2A38), modifier = Modifier.size(56.dp))
                    Spacer(Modifier.height(8.dp))
                    Text(
                        text = if (connectionState is MirrorWebSocket.ConnectionState.Connected) "Aguardando vídeo..."
                        else "Conecte ao servidor",
                        color = Color(0xFF4A4A60), fontSize = 13.sp
                    )
                }
            }
        }

        // PC CURSOR overlayed on video
        if (cursorVisible && remoteCursor != null) {
            val (cx, cy) = remoteCursor!!
            Canvas(modifier = Modifier.fillMaxSize()) {
                val xPx = cx * size.width; val yPx = cy * size.height
                drawCircle(Color(0xFFEF4444).copy(alpha = 0.35f), 40f, Offset(xPx, yPx))
                drawCircle(Color.White.copy(alpha = 0.95f), 20f, Offset(xPx, yPx), style = Stroke(4f))
                drawCircle(Color(0xFFEF4444), 14f, Offset(xPx, yPx))
                drawLine(Color.White, Offset(xPx - 12f, yPx), Offset(xPx + 12f, yPx), 2.5f)
                drawLine(Color.White, Offset(xPx, yPx - 12f), Offset(xPx, yPx + 12f), 2.5f)
            }
        }

        // TOP STATUS BAR + MODE CHIPS (consolidated)
        Surface(
            color = Color(0xFF14141C).copy(alpha = 0.75f),
            modifier = Modifier.fillMaxWidth()
        ) {
            Row(
                modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 6.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    val isConnected = connectionState is MirrorWebSocket.ConnectionState.Connected
                    Box(Modifier.size(8.dp).clip(CircleShape).background(
                        if (isConnected) Color(0xFF10B981) else Color(0xFFEF4444)))
                    Spacer(Modifier.width(8.dp))
                    Text("v1.6.6", color = Color(0xFFE8E8F0), fontWeight = FontWeight.Bold, fontSize = 13.sp)
                }

                // Mode rate chips (consolidated in top bar)
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    RateChip("60Hz", 0, serverMode) { serverMode = 0; client.sendHermesQuality(0) }
                    RateChip("30Hz", 1, serverMode) { serverMode = 1; client.sendHermesQuality(1) }
                    RateChip("15Hz", 2, serverMode) { serverMode = 2; client.sendHermesQuality(2) }
                }

                // PING
                val latCol = when { latencyMs < 50 -> Color(0xFF10B981); latencyMs < 120 -> Color(0xFFF59E0B); else -> Color(0xFFEF4444) }
                Text("${latencyMs}ms", color = latCol, fontSize = 10.sp, fontWeight = FontWeight.Bold)
            }
        }

        // BOTTOM CONTROL STRIP (consolidated — sliders visible, no modal needed)
        Surface(
            color = Color(0xFF14141C).copy(alpha = 0.75f),
            modifier = Modifier.align(Alignment.BottomCenter).fillMaxWidth()
        ) {
            Column(modifier = Modifier.fillMaxWidth().padding(8.dp)) {
                // Row 1: L/R buttons + scroll + trackball
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    // L/R buttons
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        CircleBtn("L", Color(0xFF10B981), 52.dp) { client.sendHermesClick(0) }
                        CircleBtn("R", Color(0xFFF59E0B), 52.dp) { client.sendHermesClick(1) }
                        // Scroll up
                        CircleBtn("↑", Color(0xFF6366F1), 40.dp) { client.sendHermesScroll(-3) }
                        CircleBtn("↓", Color(0xFF6366F1), 40.dp) { client.sendHermesScroll(3) }
                    }

                    // Trackball (always visible)
                    TrackballCompact(
                        client = client,
                        sensitivity = localSensitivity,
                        sendIntervalMs = sendIntervalMs,
                        modifier = Modifier.size(trackballSizeDp)
                    )
                }

                Spacer(Modifier.height(4.dp))

                // Row 2: Sensitivity slider + Quality slider (tudo fixo, sem modal)
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    // Sensitivity
                    Text("Sens", color = Color(0xFFB0B0C0), fontSize = 9.sp)
                    Slider(
                        value = localSensitivity,
                        onValueChange = { localSensitivity = it; onSensitivityChange(it) },
                        valueRange = 0.3f..3.0f,
                        modifier = Modifier.weight(1f).height(28.dp),
                        colors = SliderDefaults.colors(
                            thumbColor = Color(0xFF6366F1), activeTrackColor = Color(0xFF818CF8),
                            inactiveTrackColor = Color(0xFF2A2A38)
                        )
                    )
                    Text("${"%.1f".format(localSensitivity)}x", color = Color(0xFFB0B0C0), fontSize = 9.sp)

                    // Quality
                    Text("Q", color = Color(0xFFB0B0C0), fontSize = 9.sp)
                    Slider(
                        value = mirrorQuality.toFloat(),
                        onValueChange = { mirrorQuality = it.toInt(); client.sendMirrorConfig("quality", it.toInt()) },
                        valueRange = 20f..95f,
                        modifier = Modifier.weight(1f).height(28.dp),
                        colors = SliderDefaults.colors(
                            thumbColor = Color(0xFF10B981), activeTrackColor = Color(0xFF10B981),
                            inactiveTrackColor = Color(0xFF2A2A38)
                        )
                    )
                    Text("${mirrorQuality}%", color = Color(0xFFB0B0C0), fontSize = 9.sp)
                }

                // Row 3: Scale chips (consolidated)
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text("Escala", color = Color(0xFFB0B0C0), fontSize = 9.sp)
                    ScaleChip("50%", 0.50f, mirrorScale, { mirrorScale = 0.50f; client.sendMirrorConfig("scale", 0.50f) })
                    ScaleChip("75%", 0.75f, mirrorScale, { mirrorScale = 0.75f; client.sendMirrorConfig("scale", 0.75f) })
                    ScaleChip("100%", 1.00f, mirrorScale, { mirrorScale = 1.00f; client.sendMirrorConfig("scale", 1.00f) })
                    Spacer(Modifier.weight(1f))
                    Text("FPS: 30", color = Color(0xFF6E6E80), fontSize = 9.sp)
                }
            }
        }
    }
}

// ─── Helper composables ───────────────────────────────────────────────
@Composable
private fun CircleBtn(label: String, color: Color, sizeDp: androidx.compose.ui.unit.Dp, onClick: () -> Unit) {
    var pressed by remember { mutableStateOf(false) }
    Box(
        modifier = Modifier.size(sizeDp).clip(CircleShape)
            .background(color.copy(alpha = if (pressed) 1f else 0.80f))
            .pointerInput(Unit) {
                detectTapGestures(onPress = { pressed = true; onClick(); tryAwaitRelease(); pressed = false })
            },
        contentAlignment = Alignment.Center
    ) { Text(label, color = Color.White, fontWeight = FontWeight.Bold, fontSize = 18.sp) }
}

@Composable
private fun RateChip(label: String, value: Int, current: Int, onClick: () -> Unit) {
    val sel = value == current
    Surface(
        color = if (sel) Color(0xFF6366F1) else Color(0xFF1E1E2C),
        shape = RoundedCornerShape(12.dp),
        modifier = Modifier.pointerInput(value) { detectTapGestures(onTap = { onClick() }) }
    ) { Text(label, color = if (sel) Color.White else Color(0xFFB0B0C0), fontSize = 9.sp,
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp)) }
}

@Composable
private fun ScaleChip(label: String, value: Float, current: Float, onClick: () -> Unit) {
    val sel = abs(value - current) < 0.01f
    Surface(
        color = if (sel) Color(0xFF10B981) else Color(0xFF1E1E2C),
        shape = RoundedCornerShape(12.dp),
        modifier = Modifier.pointerInput(label) { detectTapGestures(onTap = { onClick() }) }
    ) { Text(label, color = if (sel) Color.White else Color(0xFFB0B0C0), fontSize = 9.sp,
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp)) }
}

// ─── Trackball compact (no local glow, smaller) ──────────────────────
@Composable
private fun TrackballCompact(
    client: MirrorWebSocket, sensitivity: Float, sendIntervalMs: Long,
    modifier: Modifier = Modifier,
) {
    var ballX by remember { mutableFloatStateOf(0f) }; var ballY by remember { mutableFloatStateOf(0f) }
    var isPressed by remember { mutableStateOf(false) }
    var pressedAtMs by remember { mutableStateOf(0L) }
    var accDx by remember { mutableFloatStateOf(0f) }; var accDy by remember { mutableFloatStateOf(0f) }
    var lastSendMs by remember { mutableStateOf(0L) }

    val animatedX by animateFloatAsState(if (isPressed) ballX else 0f, label = "bx")
    val animatedY by animateFloatAsState(if (isPressed) ballY else 0f, label = "by")

    Box(
        modifier = modifier.clip(CircleShape).background(Color.White.copy(alpha = 0.08f))
            .pointerInput(Unit) {
                detectTapGestures(onPress = {
                    isPressed = true; pressedAtMs = SystemClock.uptimeMillis()
                    ballX = 0f; ballY = 0f; lastSendMs = pressedAtMs; accDx = 0f; accDy = 0f
                    val released = tryAwaitRelease(); isPressed = false
                    if (released) {
                        val held = SystemClock.uptimeMillis() - pressedAtMs
                        val totalMove = abs(accDx) + abs(accDy)
                        if (held < 250L && totalMove < 4f) client.sendHermesClick(0)
                        if (accDx.toInt() != 0 || accDy.toInt() != 0) client.sendHermesMove(accDx.toInt(), accDy.toInt())
                        accDx = 0f; accDy = 0f
                    }
                })
            }
            .pointerInput(Unit) {
                detectDragGestures(
                    onDragStart = { offset ->
                        val w = size.width.toFloat(); val h = size.height.toFloat()
                        ballX = ((offset.x / w) * 2f - 1f).coerceIn(-1f, 1f)
                        ballY = ((offset.y / h) * 2f - 1f).coerceIn(-1f, 1f)
                        isPressed = true; pressedAtMs = SystemClock.uptimeMillis()
                        lastSendMs = pressedAtMs; accDx = 0f; accDy = 0f
                    },
                    onDragEnd = {
                        isPressed = false
                        if (accDx.toInt() != 0 || accDy.toInt() != 0) client.sendHermesMove(accDx.toInt(), accDy.toInt())
                        accDx = 0f; accDy = 0f; ballX = 0f; ballY = 0f
                    },
                    onDragCancel = { isPressed = false; accDx = 0f; accDy = 0f; ballX = 0f; ballY = 0f },
                    onDrag = { change, _ ->
                        change.consume()
                        val w = size.width.toFloat(); val h = size.height.toFloat()
                        if (w <= 0f || h <= 0f) return@detectDragGestures
                        ballX = ((change.position.x / w) * 2f - 1f).coerceIn(-1f, 1f)
                        ballY = ((change.position.y / h) * 2f - 1f).coerceIn(-1f, 1f)
                        val dist = hypot(ballX, ballY)
                        val speed = (1f + dist * 3f) * sensitivity
                        val dx = (if (dist > 0.001f) ballX / dist else 0f) * speed * dist.coerceAtLeast(0.05f)
                        val dy = (if (dist > 0.001f) ballY / dist else 0f) * speed * dist.coerceAtLeast(0.05f)
                        accDx += dx; accDy += dy
                        val now = SystemClock.uptimeMillis()
                        if (now - lastSendMs >= sendIntervalMs) {
                            val sd = accDx.toInt(); val sy = accDy.toInt()
                            if (sd != 0 || sy != 0) { client.sendHermesMove(sd, sy); accDx -= sd; accDy -= sy }
                            lastSendMs = now
                        }
                    }
                )
            },
        contentAlignment = Alignment.Center
    ) {
        Canvas(modifier = Modifier.fillMaxSize()) {
            val cx = size.width / 2f; val cy = size.height / 2f; val r = size.minDimension / 2f
            drawCircle(Color.White.copy(alpha = 0.4f), r * 0.92f, Offset(cx, cy), style = Stroke(2f))
            val bx = cx + animatedX * r * 0.6f; val by = cy + animatedY * r * 0.6f
            val br = if (isPressed) r * 0.28f else r * 0.20f
            drawCircle(if (isPressed) Color(0xFF10B981).copy(alpha = 0.95f) else Color.White.copy(alpha = 0.80f), br, Offset(bx, by))
        }
        if (!isPressed) Text("◉", color = Color.White.copy(alpha = 0.3f), fontSize = 18.sp)
    }
}