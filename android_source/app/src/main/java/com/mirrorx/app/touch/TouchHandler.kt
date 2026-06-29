package com.mirrorx.app.touch

import android.os.SystemClock
import android.view.MotionEvent
import kotlin.math.abs
import kotlin.math.hypot

/**
 * MirrorX v2.0.1 — TouchHandler reescrito do zero.
 *
 * Modos:
 *  - CURSOR   : emula mouse. Toque move o cursor, tap = click,
 *               long-press = click direito, drag = clique-segurar.
 *  - PEN      : caneta. Toque = down, move = move, up = up.
 *               Passa pressure/tilt para o PC renderizar com espessura.
 *  - DRAW     : modo pintura. Igual ao PEN mas força pressure=constante
 *               se finger touch (fingertip painting).
 *  - OFF      : nada é enviado.
 *  - HERMES   : preservado da v1.x (compatibilidade HermesActivity).
 *
 * Design:
 *  - Toda coord é normalizada (0..1) no espaço do servidor.
 *  - Stylus físico (S Pen etc.) tem prioridade em PEN/DRAW.
 *  - Tap: deslocamento < 2% da tela em < 400ms.
 *  - Long-press: 600ms sem movimento > 1.5% da tela.
 *  - Pinch 2 dedos: zoom (escala + centro).
 *  - Palm rejection ativo quando stylus está na tela.
 */
class TouchHandler(
    private val onSendTouch: (x: Float, y: Float, action: String, pressure: Float, tilt: Float, tool: String, buttons: Int) -> Unit = { _,_,_,_,_,_,_ -> },
    private val onSendTouchPath: (points: List<TouchPathCollector.Point>, pressure: Float, tilt: Float, tool: String, buttons: Int) -> Unit = { _,_,_,_,_ -> },
    private val onSendTouchPathBinary: (points: List<TouchPathCollector.Point>, tool: String, buttons: Int) -> Unit = { _,_,_ -> },
    private val onSendPinch: (scale: Float, cx: Float, cy: Float) -> Unit = { _,_,_ -> },
) {

    /** Estado atual visível pelo caller. */
    var mode: TouchMode = TouchMode.OFF
        private set

    /** true enquanto o usuário está com o dedo/caneta pressionando. */
    var isPressing: Boolean = false
        private set

    /** Callback para o ghost-cursor (CURSOR mode): move o cursor local sem esperar RTT. */
    var onCursorMove: ((Float, Float) -> Unit)? = null

    /** Callback quando o tool muda (stylus/eraser/finger) — usado pra esconder cursor. */
    var onToolChanged: ((String) -> Unit)? = null

    /** Palm rejection ativo (rejeita dedos quando stylus está presente). */
    var palmRejection: Boolean = true
        set(v) { field = v }

    enum class TouchMode {
        OFF, CURSOR, CLICK_ONLY, DRAW, PEN, HERMES
    }

    fun setMode(m: TouchMode) {
        if (m != mode) {
            mode = m
            resetState()
        }
    }

    // ----- estado interno -----
    private var downTimeMs = 0L
    private var downX = 0f
    private var downY = 0f
    private var lastX = 0f
    private var lastY = 0f
    private var lastTool = "finger"
    private var stylusActive = false
    private var longPressFired = false
    private val handler = android.os.Handler(android.os.Looper.getMainLooper())
    private val longPressRunnable = Runnable {
        if (isPressing && !longPressFired) {
            val dx = abs(lastX - downX)
            val dy = abs(lastY - downY)
            if (hypot(dx, dy) < 0.015f) {   // 1.5% tolerância
                longPressFired = true
                onSendTouch(lastX, lastY, "click", 1f, 0f, lastTool, 2)  // botão direito
            }
        }
    }

    private val pathCollector = TouchPathCollector()

    // Pinch
    private var pinchBaseDist = 0f
    private var pinchActive = false

    private fun resetState() {
        isPressing = false
        downTimeMs = 0L
        downX = 0f; downY = 0f
        lastX = 0f; lastY = 0f
        longPressFired = false
        handler.removeCallbacks(longPressRunnable)
        pathCollector.flushPath()
        pinchActive = false
    }

    // ----- protocolo de entrada -----
    fun handleTouchEvent(event: MotionEvent, viewWidth: Int, viewHeight: Int): Boolean {
        if (mode == TouchMode.OFF) return false
        if (mode == TouchMode.HERMES) return false   // HermesActivity tem seu próprio Touchpad
        if (viewWidth <= 0 || viewHeight <= 0) return false

        // --- Pinch 2 dedos ---
        when (event.actionMasked) {
            MotionEvent.ACTION_POINTER_DOWN -> {
                if (event.pointerCount >= 2) {
                    pinchBaseDist = distBetween(event, 0, 1)
                    pinchActive = pinchBaseDist > 5f
                    pathCollector.flushPath()
                    cancelLongPress()
                    return true
                }
            }
            MotionEvent.ACTION_MOVE -> {
                if (pinchActive && event.pointerCount >= 2) {
                    val d = distBetween(event, 0, 1)
                    if (pinchBaseDist > 5f) {
                        val scale = (d / pinchBaseDist).coerceIn(0.1f, 10f)
                        val cx = ((event.getX(0) + event.getX(1)) / 2f / viewWidth).coerceIn(0f, 1f)
                        val cy = ((event.getY(0) + event.getY(1)) / 2f / viewHeight).coerceIn(0f, 1f)
                        onSendPinch(scale, cx, cy)
                        pinchBaseDist = d
                    }
                    return true
                }
            }
            MotionEvent.ACTION_POINTER_UP -> {
                if (event.pointerCount <= 2) {
                    pinchActive = false
                    pinchBaseDist = 0f
                }
            }
        }
        if (pinchActive) return true

        // --- Single touch ---
        val idx = event.actionIndex.coerceIn(0, event.pointerCount - 1)
        val toolType = event.getToolType(idx)
        val tool = when (toolType) {
            MotionEvent.TOOL_TYPE_STYLUS -> "stylus"
            MotionEvent.TOOL_TYPE_ERASER -> "eraser"
            MotionEvent.TOOL_TYPE_MOUSE  -> "mouse"
            else -> "finger"
        }

        if (tool != lastTool) {
            lastTool = tool
            onToolChanged?.invoke(tool)
        }
        stylusActive = event.isStylusInProximity()

        // Palm rejection
        if (palmRejection && tool == "finger" && stylusActive) {
            return true   // rejeita, mas consome o evento
        }

        val x = (event.getX(idx) / viewWidth).coerceIn(0f, 1f)
        val y = (event.getY(idx) / viewHeight).coerceIn(0f, 1f)
        val pressure = event.getPressure(idx).coerceIn(0f, 1f)
        val tilt = event.getAxisValue(MotionEvent.AXIS_TILT, idx).coerceIn(0f, 1.57f) * 57.2958f
        val buttons = event.buttonState

        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> handleDown(x, y, pressure, tilt, tool, buttons)
            MotionEvent.ACTION_MOVE -> handleMove(x, y, pressure, tilt, tool, buttons)
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> handleUp(x, y, pressure, tilt, tool, buttons, cancel = event.actionMasked == MotionEvent.ACTION_CANCEL)
            else -> {}
        }

        return true
    }

    private fun MotionEvent.isStylusInProximity(): Boolean {
        for (i in 0 until pointerCount) {
            val t = getToolType(i)
            if (t == MotionEvent.TOOL_TYPE_STYLUS || t == MotionEvent.TOOL_TYPE_ERASER) {
                return true
            }
        }
        return false
    }

    // ---- modo-specific handlers ----

    private fun handleDown(x: Float, y: Float, pressure: Float, tilt: Float, tool: String, buttons: Int) {
        downX = x; downY = y; lastX = x; lastY = y
        downTimeMs = SystemClock.elapsedRealtime()
        longPressFired = false
        isPressing = true

        when (mode) {
            TouchMode.CURSOR -> {
                pathCollector.flushPath()
                pathCollector.add(x, y)
                onSendTouch(x, y, "move", pressure, tilt, tool, buttons)
                onCursorMove?.invoke(x, y)
                scheduleLongPress()
            }
            TouchMode.CLICK_ONLY -> {
                scheduleLongPress()
            }
            TouchMode.PEN -> {
                onSendTouch(x, y, "down", pressure, tilt, tool, buttons)
            }
            TouchMode.DRAW -> {
                val p = if (tool == "finger") 0.7f else pressure   // fingertip = pressão fixa
                onSendTouch(x, y, "down", p, tilt, tool, buttons)
            }
            else -> {}
        }
    }

    private fun handleMove(x: Float, y: Float, pressure: Float, tilt: Float, tool: String, buttons: Int) {
        if (!isPressing) return
        lastX = x; lastY = y

        // Cancela long-press se movimento é grande
        if (abs(x - downX) > 0.015f || abs(y - downY) > 0.015f) {
            cancelLongPress()
        }

        when (mode) {
            TouchMode.CURSOR -> {
                pathCollector.add(x, y)
                if (pathCollector.shouldFlush()) {
                    val path = pathCollector.flushPath()
                    if (path.size >= 2) onSendTouchPathBinary(path, tool, buttons)
                }
                onSendTouch(x, y, "move", pressure, tilt, tool, buttons)
                onCursorMove?.invoke(x, y)
            }
            TouchMode.PEN -> {
                onSendTouch(x, y, "move", pressure, tilt, tool, buttons)
            }
            TouchMode.DRAW -> {
                val p = if (tool == "finger") 0.7f else pressure
                onSendTouch(x, y, "drag", p, tilt, tool, buttons)
            }
            TouchMode.CLICK_ONLY -> { /* espera UP */ }
            else -> {}
        }
    }

    private fun handleUp(x: Float, y: Float, pressure: Float, tilt: Float, tool: String, buttons: Int, cancel: Boolean) {
        val wasPressing = isPressing
        isPressing = false
        cancelLongPress()
        if (!wasPressing) return

        val dx = abs(x - downX)
        val dy = abs(y - downY)
        val dist = hypot(dx, dy)
        val elapsed = SystemClock.elapsedRealtime() - downTimeMs
        val wasTap = dist < 0.02f && elapsed < 400
        val wasLongPress = longPressFired

        when (mode) {
            TouchMode.CURSOR -> {
                val path = pathCollector.flushPath()
                if (path.size >= 2) onSendTouchPathBinary(path, tool, buttons)
                // Se foi um longo-arraste, já está tudo enviado pelos moves; só não gera click.
                if (!cancel && wasTap) {
                    onSendTouch(x, y, "click", pressure, tilt, tool, buttons)
                } else if (!cancel && !wasLongPress && elapsed >= 600) {
                    // segurar sem movimento → up do botão direito já disparado no long-press
                }
                // Sempre um up final pra garantir (server ignora se não houver down)
                if (!cancel) onSendTouch(x, y, "up", pressure, tilt, tool, buttons)
            }
            TouchMode.CLICK_ONLY -> {
                if (!cancel) {
                    if (wasLongPress || wasTap) {
                        val btn = if (wasLongPress) 2 else buttons   // long-press = botão direito
                        onSendTouch(x, y, "click", pressure, tilt, tool, btn)
                    }
                }
            }
            TouchMode.PEN -> {
                if (!cancel) onSendTouch(x, y, "up", pressure, tilt, tool, buttons)
            }
            TouchMode.DRAW -> {
                val p = if (tool == "finger") 0.7f else pressure
                if (!cancel) onSendTouch(x, y, "up", p, tilt, tool, buttons)
            }
            else -> {}
        }
    }

    private fun scheduleLongPress() {
        handler.removeCallbacks(longPressRunnable)
        handler.postDelayed(longPressRunnable, 600)
    }

    private fun cancelLongPress() {
        handler.removeCallbacks(longPressRunnable)
    }

    private fun distBetween(event: MotionEvent, a: Int, b: Int): Float {
        val dx = event.getX(a) - event.getX(b)
        val dy = event.getY(a) - event.getY(b)
        return hypot(dx, dy)
    }
}
