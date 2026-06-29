package com.mirrorx.app.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.mirrorx.app.network.ServerHud

/**
 * MirrorX v2.0.0 — HUD overlay component.
 *
 * Recebe um [ServerHud] (parseado do JSON `hud_v2` enviado pelo servidor
 * a cada 2s) e renderiza um painel flutuante no canto da tela com:
 *  - resolução
 *  - codec (HW/SW)
 *  - FPS capturado + target
 *  - bitrate / qualidade
 *  - latência RTT agregada
 *  - v2.0.0: economia de banda (dirty region renderer)
 *
 * O overlay usa `pointerEvents = NoPointerInput` por padrão para não
 * interferir com o touch handler — é puramente visual.
 */
@Composable
fun HudOverlay(
    hud: ServerHud?,
    fps: Int,
    bandwidthSavings: Int = 0,
    modifier: Modifier = Modifier
) {
    if (hud == null && bandwidthSavings == 0) return
    Column(
        modifier = modifier
            .padding(12.dp)
            .clip(RoundedCornerShape(10.dp))
            .background(Color(0xCC0A1C50))
            .padding(horizontal = 12.dp, vertical = 8.dp)
            .width(IntrinsicSize.Min),
        verticalArrangement = Arrangement.spacedBy(2.dp)
    ) {
        if (hud != null) {
            // Resolução + FPS
            Row(verticalAlignment = Alignment.CenterVertically) {
                HudTag("RES", Color(0xFF7DD3FC))
                HudValue(hud.resolution)
                Spacer(Modifier.width(8.dp))
                HudTag("FPS", Color(0xFF34D399))
                HudValue("${fps} → ${hud.targetFps}")
            }
            // Codec + bitrate
            Row(verticalAlignment = Alignment.CenterVertically) {
                HudTag("CODEC", Color(0xFFA78BFA))
                HudValue(hud.codecName)
                Spacer(Modifier.width(8.dp))
                HudTag("BITRATE", Color(0xFFFBBF24))
                HudValue("${hud.bitrateKbps} kbps")
            }
            // Qualidade + RTT + Clients
            Row(verticalAlignment = Alignment.CenterVertically) {
                HudTag("Q", Color(0xFFFB923C))
                HudValue("${hud.quality}")
                Spacer(Modifier.width(8.dp))
                HudTag("RTT", Color(0xFFF87171))
                HudValue("${"%.1f".format(hud.rttMs)} ms")
                Spacer(Modifier.width(8.dp))
                HudTag("CLIENTS", Color(0xFF60A5FA))
                HudValue("${hud.clients}")
            }
        }
        
        // v2.0.0: Bandwidth savings indicator (dirty region renderer)
        // Shows "Banda: X% (economia de Y%)" style info when partial mode active
        if (bandwidthSavings > 0) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                HudTag("BANDA", Color(0xFF10B981))
                HudValue("${100 - bandwidthSavings}% (economia ${bandwidthSavings}%)")
                Spacer(Modifier.width(6.dp))
                HudTag("DIRTY", Color(0xFF06B6D4))
                HudValue("✓")
            }
        }
    }
}

@Composable
private fun HudTag(label: String, color: Color) {
    Text(
        text = label,
        color = color,
        fontWeight = FontWeight.Bold,
        fontSize = 10.sp,
        fontFamily = FontFamily.Monospace,
        modifier = Modifier.padding(end = 4.dp)
    )
}

@Composable
private fun HudValue(text: String) {
    Text(
        text = text,
        color = Color(0xFFE6EBFF),
        fontSize = 11.sp,
        fontFamily = FontFamily.Monospace
    )
}
