package com.mirrorx.app.network

import org.json.JSONObject

/**
 * MirrorX v2.0.0 — telemetria HUD enviada pelo servidor a cada 2s.
 *
 * Formato JSON esperado (enviado pelo .NET server em HudBroadcaster):
 *   {
 *     "type": "hud_v2",
 *     "codec": "JPEG CPU (compat v1.x)" | "H.264 NVENC (RTX)" | ...,
 *     "resolution": "1920x1080",
 *     "target_fps": 60,
 *     "captured_fps": 58.3,
 *     "bitrate_kbps": 4500,
 *     "quality": 45,
 *     "rtt_ms": 35.7,
 *     "clients": 2,
 *     "frames": 12345,
 *     "ts": 1719598234567
 *   }
 *
 * O servidor v1.x NÃO envia esse payload (só envia "mode", "cursor_pos",
 * "error"). A função parse() devolve null nesses casos para que o caller
 * saiba que não há dados novos — o HUD simplesmente não atualiza.
 */
data class ServerHud(
    val codecName: String = "—",
    val resolution: String = "—",
    val targetFps: Int = 0,
    val capturedFps: Double = 0.0,
    val bitrateKbps: Int = 0,
    val quality: Int = 0,
    val rttMs: Double = 0.0,
    val clients: Int = 0,
    val frames: Long = 0,
    val ts: Long = 0
) {
    companion object {
        fun parse(text: String): ServerHud? {
            return try {
                val o = JSONObject(text)
                if (o.optString("type") != "hud_v2") return null
                ServerHud(
                    codecName = o.optString("codec", "—"),
                    resolution = o.optString("resolution", "—"),
                    targetFps = o.optInt("target_fps", 0),
                    capturedFps = o.optDouble("captured_fps", 0.0),
                    bitrateKbps = o.optInt("bitrate_kbps", 0),
                    quality = o.optInt("quality", 0),
                    rttMs = o.optDouble("rtt_ms", 0.0),
                    clients = o.optInt("clients", 0),
                    frames = o.optLong("frames", 0),
                    ts = o.optLong("ts", 0)
                )
            } catch (_: Exception) {
                null
            }
        }
    }
}