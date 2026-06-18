package com.mirrorx.app

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.mirrorx.app.hermes.TouchpadView
import com.mirrorx.app.network.MirrorWebSocket

/**
 * v1.5.5 — Top-level wrapper that combines a connection bar (IP +
 * Connect button) with the HybridTouchpadView. Uses MirrorWebSocket
 * so the same socket carries both JPG frames AND JSON mouse commands.
 */
@Composable
fun TouchpadWithConnection(
    client: MirrorWebSocket,
    ip: String,
    onIpChange: (String) -> Unit,
    sensitivity: Float,
    onSensitivityChange: (Float) -> Unit,
    modifier: Modifier = Modifier
) {
    val state by client.connectionState.collectAsState()
    val isConnected = state is MirrorWebSocket.ConnectionState.Connected
    val isConnecting = state is MirrorWebSocket.ConnectionState.Connecting

    Column(modifier = modifier) {
        // Connection strip
        Surface(
            color = androidx.compose.ui.graphics.Color(0xFF0F1A30),
            modifier = Modifier.fillMaxWidth()
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 12.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                OutlinedTextField(
                    value = ip,
                    onValueChange = onIpChange,
                    label = { Text("IP do PC", fontSize = 11.sp) },
                    singleLine = true,
                    modifier = Modifier.weight(1f),
                    textStyle = LocalTextStyle.current.copy(
                        fontFamily = FontFamily.Monospace,
                        fontSize = 14.sp
                    ),
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedTextColor = androidx.compose.ui.graphics.Color(0xFFE0E0E0),
                        unfocusedTextColor = androidx.compose.ui.graphics.Color(0xFFE0E0E0),
                        focusedContainerColor = androidx.compose.ui.graphics.Color(0xFF1A1A2E),
                        unfocusedContainerColor = androidx.compose.ui.graphics.Color(0xFF1A1A2E),
                        focusedBorderColor = androidx.compose.ui.graphics.Color(0xFFE94560),
                        unfocusedBorderColor = androidx.compose.ui.graphics.Color(0xFF16213E),
                    )
                )
                Button(
                    onClick = {
                        if (isConnected || isConnecting) {
                            client.disconnect()
                        } else {
                            val (host, port) = parseHostPort(ip)
                            client.connect(host, port)
                        }
                    },
                    colors = ButtonDefaults.buttonColors(
                        containerColor = if (isConnected)
                            androidx.compose.ui.graphics.Color(0xFFE94560)
                        else
                            androidx.compose.ui.graphics.Color(0xFF00D4AA)
                    ),
                    shape = RoundedCornerShape(10.dp),
                    modifier = Modifier.height(50.dp)
                ) {
                    Text(
                        when {
                            isConnecting -> "..."
                            isConnected -> "Sair"
                            else -> "Conectar"
                        },
                        fontWeight = FontWeight.Bold,
                        fontSize = 13.sp
                    )
                }
            }
        }

        // The actual touchpad (hybrid — video + trackpad overlay)
        TouchpadView(
            client = client,
            sensitivity = sensitivity,
            onSensitivityChange = onSensitivityChange,
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f)
        )
    }
}

/** Parse "192.168.0.1" or "192.168.0.1:9900" into (host, port). */
private fun parseHostPort(s: String): Pair<String, Int> {
    val trimmed = s.trim()
    val colon = trimmed.lastIndexOf(':')
    return if (colon > 0 && colon < trimmed.length - 1) {
        val host = trimmed.substring(0, colon)
        val port = trimmed.substring(colon + 1).toIntOrNull() ?: 9900
        Pair(host, port)
    } else {
        Pair(trimmed, 9900)
    }
}