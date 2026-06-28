package com.mirrorx.app.hermes

import android.os.SystemClock
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.changedToUp
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.mirrorx.app.BuildConfig
import com.mirrorx.app.network.MirrorWebSocket
import kotlin.math.abs

/**
 * v1.9.1 — Full-Screen Touchpad
 *
 * A tela inteira é o sensor de entrada, igual a um touchpad de notebook.
 * Qualquer arrasto em qualquer ponto envia o delta relativo ao servidor.
 *
 *  ┌─────────────────────────────────────────────────────┐
 *  │ ● v1.9.1  60Hz  30Hz  15Hz              ping        │ ← status bar
 *  │                                                     │
 *  │         (área de tela cheia = touchpad)             │
 *  │                                                     │
 *  │  ⬤L ⬤R  ↑ ↓   Sens: ═══○═══   Q: ═══○═══        │ ← barra inferior
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
    val remoteCursor by client.remoteCursor.collectAsState()
    val latencyMs by client.latencyMs.collectAsState()
    val cursorVisible by client.cursorVisible.collectAsState()
    val actualFps by client.fps.collectAsState()

    var serverMode by remember { mutableIntStateOf(0) }
    var mirrorQuality by remember { mutableIntStateOf(45) }
    var localSensitivity by remember { mutableFloatStateOf(sensitivity) }

    val sendIntervalMs = when (serverMode) { 2 -> 66L; 1 -> 33L; else -> 16L }

    // v1.9.1: Full-screen touchpad gesture state
    var accDx by remember { mutableFloatStateOf(0f) }
    var accDy by remember { mutableFloatStateOf(0f) }
    var lastSendMs by remember { mutableLongStateOf(0L) }
    var dragStartMs by remember { mutableLongStateOf(0L) }
    var dragTotalPx by remember { mutableFloatStateOf(0f) }
    var isDragging by remember { mutableStateOf(false) }

    Box(
        modifier = modifier
            .fillMaxSize()
            .background(Color(0xFF0A0A0F))
    ) {
        // ── Video background ──────────────────────────────────────────
        val frame = currentFrame
        if (frame != null) {
            Image(
                bitmap = frame.asImageBitmap(),
                contentDescription = "PC screen",
                contentScale = ContentScale.Fit,
                modifier = Modifier.fillMaxSize()
            )
            Box(modifier = Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.06f)))
        } else {
            Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Icon(Icons.Default.Refresh, null, tint = Color(0xFF2A2A38), modifier = Modifier.size(48.dp))
                    Spacer(Modifier.height(12.dp))
                    Text(
                        text = if (connectionState is MirrorWebSocket.ConnectionState.Connected) "Aguardando vídeo..."
                        else "Conecte ao servidor",
                        color = Color(0xFF4A4A60), fontSize = 13.sp
                    )
                    Spacer(Modifier.height(8.dp))
                    // v1.9.1: hint text instead of joystick
                    Text(
                        text = "TOUCHPAD ATIVO",
                        color = Color(0xFF6366F1).copy(alpha = 0.5f),
                        fontSize = 11.sp,
                        fontWeight = FontWeight.Bold,
                        letterSpacing = 2.sp
                    )
                    Text(
                        text = "Deslize em qualquer ponto para mover o cursor",
                        color = Color(0xFF3A3A50),
                        fontSize = 10.sp
                    )
                }
            }
        }

        // ── PC cursor overlay ─────────────────────────────────────────
        if (cursorVisible && remoteCursor != null) {
            val (cx, cy) = remoteCursor!!
            Canvas(modifier = Modifier.fillMaxSize()) {
                val xPx = cx * size.width; val yPx = cy * size.height
                drawCircle(Color(0xFFEF4444).copy(alpha = 0.30f), 38f, Offset(xPx, yPx))
                drawCircle(Color.White.copy(alpha = 0.90f), 18f, Offset(xPx, yPx), style = Stroke(3.5f))
                drawCircle(Color(0xFFEF4444), 12f, Offset(xPx, yPx))
                drawLine(Color.White, Offset(xPx - 10f, yPx), Offset(xPx + 10f, yPx), 2f)
                drawLine(Color.White, Offset(xPx, yPx - 10f), Offset(xPx, yPx + 10f), 2f)
            }
        }

        // ── v1.9.1: Full-screen touchpad input layer ──────────────────
        // Placed over video, under status bars so gestures don't hit UI controls.
        Box(
            modifier = Modifier
                .fillMaxSize()
                // Leave top status bar and bottom control strip untouched by padding
                .padding(top = 40.dp, bottom = 120.dp)
                .pointerInput(localSensitivity, sendIntervalMs) {
                    awaitEachGesture {
                        // Wait for first finger down
                        val firstDown = awaitFirstDown(requireUnconsumed = false)
                        dragStartMs = SystemClock.uptimeMillis()
                        dragTotalPx = 0f
                        accDx = 0f; accDy = 0f
                        lastSendMs = dragStartMs
                        isDragging = false

                        var scrollMode = false
                        var prevScrollY = firstDown.position.y

                        // Track all subsequent pointer events until all fingers lift
                        do {
                            val event = awaitPointerEvent()
                            val fingerCount = event.changes.count { it.pressed }

                            if (event.type == PointerEventType.Move) {
                                val changes = event.changes
                                if (fingerCount >= 2) {
                                    // 2-finger drag = vertical scroll
                                    scrollMode = true
                                    val avg2Y = changes.filter { it.pressed }.map { it.position.y }.average().toFloat()
                                    val prevAvg2Y = changes.filter { it.pressed }.map { it.previousPosition.y }.average().toFloat()
                                    val scrollDelta = (avg2Y - prevAvg2Y) * localSensitivity * 0.08f
                                    val notches = scrollDelta.toInt()
                                    if (notches != 0) client.sendHermesScroll(notches)
                                    changes.forEach { it.consume() }
                                } else if (fingerCount == 1 && !scrollMode) {
                                    // 1-finger drag = mouse move (relative delta)
                                    val change = changes.firstOrNull { it.pressed } ?: continue
                                    val rawDx = change.position.x - change.previousPosition.x
                                    val rawDy = change.position.y - change.previousPosition.y
                                    dragTotalPx += abs(rawDx) + abs(rawDy)
                                    isDragging = dragTotalPx > 5f

                                    val scaledDx = rawDx * localSensitivity
                                    val scaledDy = rawDy * localSensitivity
                                    accDx += scaledDx; accDy += scaledDy

                                    val now = SystemClock.uptimeMillis()
                                    if (now - lastSendMs >= sendIntervalMs) {
                                        val sdx = accDx.toInt(); val sdy = accDy.toInt()
                                        if (sdx != 0 || sdy != 0) {
                                            client.sendHermesMove(sdx, sdy)
                                            accDx -= sdx; accDy -= sdy
                                        }
                                        lastSendMs = now
                                    }
                                    change.consume()
                                }
                            }
                        } while (event.changes.any { it.pressed })

                        // On release: flush remaining accumulator
                        val sdx = accDx.toInt(); val sdy = accDy.toInt()
                        if (sdx != 0 || sdy != 0) client.sendHermesMove(sdx, sdy)
                        accDx = 0f; accDy = 0f

                        // Tap detection: short press, minimal movement → left click
                        val held = SystemClock.uptimeMillis() - dragStartMs
                        if (!scrollMode && !isDragging && held < 280L) {
                            client.sendHermesClick(0)
                        }
                        isDragging = false; scrollMode = false
                    }
                }
        )

        // ── TOP STATUS BAR ────────────────────────────────────────────
        Surface(
            color = Color(0xFF14141C).copy(alpha = 0.80f),
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
                    Text("v${BuildConfig.VERSION_NAME}", color = Color(0xFFE8E8F0), fontWeight = FontWeight.Bold, fontSize = 12.sp)
                }

                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    RateChip("60Hz", 0, serverMode) { serverMode = 0; client.sendHermesQuality(0) }
                    RateChip("30Hz", 1, serverMode) { serverMode = 1; client.sendHermesQuality(1) }
                    RateChip("15Hz", 2, serverMode) { serverMode = 2; client.sendHermesQuality(2) }
                }

                val latCol = when {
                    latencyMs < 50 -> Color(0xFF10B981)
                    latencyMs < 120 -> Color(0xFFF59E0B)
                    else -> Color(0xFFEF4444)
                }
                Text("${latencyMs}ms", color = latCol, fontSize = 10.sp, fontWeight = FontWeight.Bold)
            }
        }

        // ── BOTTOM CONTROL STRIP ──────────────────────────────────────
        Surface(
            color = Color(0xFF14141C).copy(alpha = 0.82f),
            modifier = Modifier.align(Alignment.BottomCenter).fillMaxWidth()
        ) {
            Column(modifier = Modifier.fillMaxWidth().padding(horizontal = 10.dp, vertical = 8.dp)) {
                // Row 1: Click buttons + scroll buttons
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    CircleBtn("L", Color(0xFF10B981), 48.dp) { client.sendHermesClick(0) }
                    CircleBtn("R", Color(0xFFF59E0B), 48.dp) { client.sendHermesClick(1) }
                    CircleBtn("↑", Color(0xFF6366F1), 38.dp) { client.sendHermesScroll(-3) }
                    CircleBtn("↓", Color(0xFF6366F1), 38.dp) { client.sendHermesScroll(3) }
                    Spacer(Modifier.weight(1f))
                    Text("FPS: $actualFps", color = Color(0xFF6E6E80), fontSize = 9.sp)
                }

                Spacer(Modifier.height(6.dp))

                // Row 2: Sensitivity + Quality sliders
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Text("Sens", color = Color(0xFFB0B0C0), fontSize = 9.sp)
                    Slider(
                        value = localSensitivity,
                        onValueChange = { localSensitivity = it; onSensitivityChange(it) },
                        valueRange = 0.3f..4.0f,
                        modifier = Modifier.weight(1f).height(28.dp),
                        colors = SliderDefaults.colors(
                            thumbColor = Color(0xFF6366F1),
                            activeTrackColor = Color(0xFF818CF8),
                            inactiveTrackColor = Color(0xFF2A2A38)
                        )
                    )
                    Text("${"%.1f".format(localSensitivity)}x", color = Color(0xFFB0B0C0), fontSize = 9.sp)

                    Text("Q", color = Color(0xFFB0B0C0), fontSize = 9.sp)
                    Slider(
                        value = mirrorQuality.toFloat(),
                        onValueChange = {
                            mirrorQuality = it.toInt()
                            client.sendMirrorConfig("quality", it.toInt())
                        },
                        valueRange = 20f..95f,
                        modifier = Modifier.weight(1f).height(28.dp),
                        colors = SliderDefaults.colors(
                            thumbColor = Color(0xFF10B981),
                            activeTrackColor = Color(0xFF10B981),
                            inactiveTrackColor = Color(0xFF2A2A38)
                        )
                    )
                    Text("${mirrorQuality}", color = Color(0xFFB0B0C0), fontSize = 9.sp)
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
            .background(color.copy(alpha = if (pressed) 1f else 0.82f))
            .pointerInput(Unit) {
                detectTapGestures(onPress = { pressed = true; onClick(); tryAwaitRelease(); pressed = false })
            },
        contentAlignment = Alignment.Center
    ) { Text(label, color = Color.White, fontWeight = FontWeight.Bold, fontSize = 16.sp) }
}

@Composable
private fun RateChip(label: String, value: Int, current: Int, onClick: () -> Unit) {
    val sel = value == current
    Surface(
        color = if (sel) Color(0xFF6366F1) else Color(0xFF1E1E2C),
        shape = RoundedCornerShape(12.dp),
        modifier = Modifier.pointerInput(value) { detectTapGestures(onTap = { onClick() }) }
    ) {
        Text(
            label,
            color = if (sel) Color.White else Color(0xFFB0B0C0),
            fontSize = 9.sp,
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp)
        )
    }
}