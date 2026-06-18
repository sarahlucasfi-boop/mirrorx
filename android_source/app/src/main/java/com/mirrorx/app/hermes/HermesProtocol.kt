package com.mirrorx.app.hermes

import org.json.JSONObject

/**
 * MirrorX v1.5.0 "Hermes" — JSON packet builders.
 *
 * Each function returns a JSON string ready to be sent over the WebSocket.
 * We pre-build strings (not JSONObject) to avoid per-packet object
 * allocation, which matters at 60 Hz (15,000 packets/minute).
 *
 * The protocol is symmetric to the Python side (see server_hermes.py /
 * protocols.py). Keep these in sync if you change either.
 */
object HermesProtocol {

    const val TYPE_MOVE      = "m"
    const val TYPE_CLICK     = "c"
    const val TYPE_SCROLL    = "s"
    const val TYPE_KEY       = "k"
    const val TYPE_QUALITY   = "q"
    const val TYPE_HEARTBEAT = "h"

    /** Build a "m" packet: relative mouse move.
     *  Pre-built: {"t":"m","x":12,"y":-4} = 22 chars typical.
     */
    fun move(dx: Int, dy: Int): String {
        // Hot path: use StringBuilder to avoid JSONObject overhead.
        val sb = StringBuilder(24)
        sb.append("{\"t\":\"m\",\"x\":").append(dx)
          .append(",\"y\":").append(dy).append('}')
        return sb.toString()
    }

    /** Build a "c" packet: click.
     *  b: 0=left, 1=right, 2=middle, 3=double-left.
     *  Pre-built: {"t":"c","b":0} = 16 chars.
     */
    fun click(button: Int): String {
        val sb = StringBuilder(16)
        sb.append("{\"t\":\"c\",\"b\":").append(button).append('}')
        return sb.toString()
    }

    /** Build a "s" packet: scroll. positive=down, negative=up. */
    fun scroll(value: Int): String {
        val sb = StringBuilder(20)
        sb.append("{\"t\":\"s\",\"v\":").append(value).append('}')
        return sb.toString()
    }

    /** Build a "k" packet: key press/release.
     *  pressed=true → keyDown, false → keyUp.
     */
    fun key(key: String, pressed: Boolean): String {
        val sb = StringBuilder(40)
        sb.append("{\"t\":\"k\",\"k\":\"").append(escape(key))
          .append("\",\"p\":").append(if (pressed) "true" else "false")
          .append('}')
        return sb.toString()
    }

    /** Build a "q" packet: change connection quality mode.
     *  mode: 0=normal, 1=ruim (bad), 2=ultra ruim.
     */
    fun quality(mode: Int): String {
        val sb = StringBuilder(16)
        sb.append("{\"t\":\"q\",\"m\":").append(mode).append('}')
        return sb.toString()
    }

    /** Build a "h" packet: heartbeat. ms is the latency Android is feeling. */
    fun heartbeat(ms: Int): String {
        val sb = StringBuilder(20)
        sb.append("{\"t\":\"h\",\"ms\":").append(ms).append('}')
        return sb.toString()
    }

    /** Parse an incoming server message. Returns a typed HermesMessage or null
     *  if the message is not recognized (or malformed). */
    fun parse(text: String): HermesMessage? {
        return try {
            val obj = JSONObject(text)
            when (obj.optString("type")) {
                "hello" -> HermesMessage.Hello(
                    version = obj.optString("version", "?"),
                    mode = obj.optString("mode", "?"),
                )
                "ack" -> HermesMessage.Ack(
                    t = obj.optString("t", "?"),
                    ok = obj.optBoolean("ok", false),
                )
                "mode" -> HermesMessage.Mode(
                    m = obj.optInt("m", 0),
                    reason = obj.optString("reason", ""),
                )
                "error" -> HermesMessage.Error(
                    msg = obj.optString("msg", "?"),
                )
                "cursor_pos" -> HermesMessage.CursorPos(
                    x = obj.optDouble("x", 0.0).toFloat(),
                    y = obj.optDouble("y", 0.0).toFloat(),
                )
                else -> null
            }
        } catch (_: Exception) {
            null
        }
    }

    private fun escape(s: String): String {
        // Minimal escape for control chars in a key name. Real key names
        // are short ASCII strings (e.g. "a", "ctrl", "F5") so this is
        // overkill but safe.
        val sb = StringBuilder(s.length + 2)
        for (c in s) {
            when (c) {
                '\\' -> sb.append("\\\\")
                '"' -> sb.append("\\\"")
                '\n' -> sb.append("\\n")
                '\r' -> sb.append("\\r")
                '\t' -> sb.append("\\t")
                else -> if (c < ' ') sb.append('?') else sb.append(c)
            }
        }
        return sb.toString()
    }
}

/** Sealed class for messages received from the server. */
sealed class HermesMessage {
    data class Hello(val version: String, val mode: String) : HermesMessage()
    data class Ack(val t: String, val ok: Boolean) : HermesMessage()
    data class Mode(val m: Int, val reason: String) : HermesMessage()
    data class Error(val msg: String) : HermesMessage()
    data class CursorPos(val x: Float, val y: Float) : HermesMessage()
}
