package com.mirrorx.app.touch

import android.view.MotionEvent

/**
 * Touch event handler for MirrorX.
 *
 * v1.0: code was ready but DISABLED (display-only mode).
 * v1.1: ENABLED. OFF/CURSOR/CLICK_ONLY/DRAW modes.
 * v1.2: Stylus-aware — detects TOOL_TYPE_STYLUS / FINGER / ERASER,
 *       reads pressure (0..1) and tilt (0..90°), performs palm
 *       rejection (ignores wide finger contacts while stylus is active),
 *       and exposes a callback for the cursor-overlay logic to hide
 *       the cursor when stylus is in use.
 */
class TouchHandler(
    private val onSendTouch: (Float, Float, String, Float, Float, String, Int) -> Unit,
    private val onSendTouchPath: (List<TouchPathCollector.Point>, Float, Float, String, Int) -> Unit,
    // v1.4.0: WebSocket binary path (faster than JSON)
    private val onSendTouchPathBinary: (List<TouchPathCollector.Point>, String, Int) -> Unit = { _, _, _ -> },
    // v1.4.0: pinch-to-zoom (scale, centerX, centerY)
    private val onSendPinch: (Float, Float, Float) -> Unit = { _, _, _ -> }
) {
    private var isDown = false
    private var lastX = 0f
    private var lastY = 0f
    // v1.4.1: while true, the local ghost cursor should follow the finger
    // (not the server's confirmed cursor). Read by MirrorScreen to decide
    // whether to override the ghost-cursor position with the remote one.
    var isPressing: Boolean = false
        private set
    // v1.4.1: invoked on every cursor-mode ACTION_DOWN / ACTION_MOVE with
    // normalized (x, y) coordinates (0..1). Lets MirrorScreen update the
    // ghost cursor immediately, without waiting for the next frame.
    var onCursorMove: ((Float, Float) -> Unit)? = null
    // v1.2.3: track touch-start position for tap detection in CURSOR mode
    private var touchStartX = 0f
    private var touchStartY = 0f
    private var touchStartTime = 0L
    // v1.3.1: collect touch trajectory for smooth cursor movement
    private val pathCollector = TouchPathCollector()
    // v1.4.0: pinch state — track 2-finger distance
    private var pinchActive = false
    private var lastPinchDist = 0f
    var mode = TouchMode.OFF
        private set

    /**
     * If true, finger touches are rejected while the user is using a stylus
     * (palm rejection — typical for note-taking apps).
     */
    var palmRejection = true
        set(value) { field = value }

    /** Callback invoked when the active tool changes (stylus vs finger). */
    var onToolChanged: ((String) -> Unit)? = null
    private var lastReportedTool: String = "finger"

    enum class TouchMode {
        OFF,        // no events
        CURSOR,     // mouse emulation
        CLICK_ONLY, // pen/stylus mode — taps = clicks
        DRAW,       // painting mode
        HERMES      // v1.5.9: trackball overlay mode (PC screen + mouse)
    }

    fun setMode(newMode: TouchMode) {
        mode = newMode
    }

    /**
     * Called by the Compose pointerInput on every motion event.
     * @param event the synthesized MotionEvent
     * @param viewWidth  pixel width of the captured area
     * @param viewHeight pixel height of the captured area
     * @return true if the event was consumed
     */
    fun handleTouchEvent(event: MotionEvent, viewWidth: Int, viewHeight: Int): Boolean {
        if (mode == TouchMode.OFF) return false
        if (viewWidth <= 0 || viewHeight <= 0) return false

        val pointerIndex = if (event.actionIndex in 0 until event.pointerCount)
            event.actionIndex else 0

        val toolType = event.getToolType(pointerIndex)
        val tool = when (toolType) {
            MotionEvent.TOOL_TYPE_STYLUS -> "stylus"
            MotionEvent.TOOL_TYPE_ERASER -> "eraser"
            MotionEvent.TOOL_TYPE_MOUSE -> "mouse"
            else -> "finger"
        }
        // Notify if tool changed (used to hide cursor on stylus)
        if (tool != lastReportedTool) {
            lastReportedTool = tool
            onToolChanged?.invoke(tool)
        }

        // Palm rejection: if a finger contact is too wide while a stylus
        // is in use, ignore it.
        if (palmRejection && tool == "finger" && event.pressure <= 0.01f) {
            // Too soft, likely a palm
            return false
        }

        val xRatio = (event.getX(pointerIndex) / viewWidth).coerceIn(0f, 1f)
        val yRatio = (event.getY(pointerIndex) / viewHeight).coerceIn(0f, 1f)
        val pressure = event.getPressure(pointerIndex).coerceIn(0f, 1f)
        // Tilt is in degrees; API returns 0..90 (90 = perpendicular to screen)
        val tilt = event.getAxisValue(MotionEvent.AXIS_TILT, pointerIndex)
            .let { Math.toDegrees(it.toDouble()).toFloat() }
            .coerceIn(0f, 90f)
        // Buttons: 1 = primary, 2 = secondary, 4 = tertiary
        val buttons = event.buttonState

        // v1.4.0: pinch-to-zoom detection (2 fingers).
        // When ACTION_POINTER_DOWN happens, store the distance.
        // When fingers move, compute new distance and send scale = newDist/oldDist.
        if (event.actionMasked == MotionEvent.ACTION_POINTER_DOWN && event.pointerCount >= 2) {
            val dx = event.getX(0) - event.getX(1)
            val dy = event.getY(0) - event.getY(1)
            lastPinchDist = kotlin.math.hypot(dx, dy)
            pinchActive = lastPinchDist > 0f
            // Cancel any in-flight single-touch path
            pathCollector.flushPath()
            return true
        }
        if (event.actionMasked == MotionEvent.ACTION_POINTER_UP || event.actionMasked == MotionEvent.ACTION_CANCEL) {
            pinchActive = false
            lastPinchDist = 0f
        }
        if (pinchActive && event.pointerCount >= 2) {
            val dx = event.getX(0) - event.getX(1)
            val dy = event.getY(0) - event.getY(1)
            val newDist = kotlin.math.hypot(dx, dy)
            if (lastPinchDist > 0f && newDist > 0f) {
                val scale = newDist / lastPinchDist
                val cx = ((event.getX(0) + event.getX(1)) / 2f / viewWidth).coerceIn(0f, 1f)
                val cy = ((event.getY(0) + event.getY(1)) / 2f / viewHeight).coerceIn(0f, 1f)
                onSendPinch(scale, cx, cy)
            }
            lastPinchDist = newDist
            return true
        }

        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                isDown = true
                isPressing = true
                lastX = xRatio
                lastY = yRatio
                // v1.2.3: record touch start for tap-vs-drag detection
                touchStartX = xRatio
                touchStartY = yRatio
                touchStartTime = System.currentTimeMillis()
                when (mode) {
                    // v1.3.1: CURSOR = touchpad mode: collect the whole trajectory
                    // and send it as a compressed path. Start collecting now.
                    TouchMode.CURSOR -> {
                        pathCollector.add(xRatio, yRatio)
                        onSendTouch(xRatio, yRatio, "move", pressure, tilt, tool, buttons)
                        onCursorMove?.invoke(xRatio, yRatio)
                    }
                    TouchMode.DRAW -> onSendTouch(xRatio, yRatio, "down", pressure, tilt, tool, buttons)
                    TouchMode.HERMES -> { /* Hermes uses its own trackball UI */ }
                    TouchMode.CLICK_ONLY, TouchMode.OFF -> { /* wait for up */ }
                }
            }

            MotionEvent.ACTION_MOVE -> {
                if (!isDown) return false
                lastX = xRatio
                lastY = yRatio
                when (mode) {
                    TouchMode.CURSOR -> {
                        // v1.3.1: add point to trajectory. Send a batch as soon as
                        // enough points are collected or the oldest is older than 50ms,
                        // so the cursor on the PC stays responsive.
                        pathCollector.add(xRatio, yRatio)
                        if (pathCollector.shouldFlush()) {
                            val path = pathCollector.flushPath()
                            if (path.size >= 2) {
                                onSendTouchPath(path, pressure, tilt, tool, buttons)
                            }
                        }
                        // Also send a single move so the PC cursor is never stuck
                        // if the batch is delayed.
                        onSendTouch(xRatio, yRatio, "move", pressure, tilt, tool, buttons)
                        // v1.4.1: update the ghost cursor immediately so the user
                        // sees their finger moving the cursor on the tablet with
                        // zero perceived latency.
                        onCursorMove?.invoke(xRatio, yRatio)
                    }
                    TouchMode.DRAW -> onSendTouch(xRatio, yRatio, "drag", pressure, tilt, tool, buttons)
                    TouchMode.HERMES -> { /* Hermes uses its own trackball UI */ }
                    TouchMode.CLICK_ONLY, TouchMode.OFF -> { /* ignore movement */ }
                }
            }

            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                isDown = false
                isPressing = false
                when (mode) {
                    TouchMode.CURSOR -> {
                        // v1.3.1: send any remaining trajectory points
                        val path = pathCollector.flushPath()
                        if (path.size >= 2) {
                            onSendTouchPath(path, pressure, tilt, tool, buttons)
                        }
                        // v1.2.3: tap detection — if the finger didn't move much
                        // and the touch was short, treat it as a click.
                        val dx = xRatio - touchStartX
                        val dy = yRatio - touchStartY
                        val dist = Math.sqrt((dx * dx + dy * dy).toDouble()).toFloat()
                        val elapsed = System.currentTimeMillis() - touchStartTime
                        // Tap threshold: < 2% of screen AND < 400ms
                        if (dist < 0.02f && elapsed < 400) {
                            onSendTouch(xRatio, yRatio, "click", pressure, tilt, tool, buttons)
                        }
                        // If it was a long press or drag, do nothing — the cursor
                        // was already positioned by the move events, and no mouse
                        // button was ever pressed, so there's nothing to release.
                    }
                    TouchMode.DRAW -> onSendTouch(xRatio, yRatio, "up", pressure, tilt, tool, buttons)
                    TouchMode.CLICK_ONLY -> onSendTouch(xRatio, yRatio, "click", pressure, tilt, tool, buttons)
                    TouchMode.HERMES -> { /* Hermes uses its own trackball UI */ }
                    TouchMode.OFF -> { /* never reached */ }
                }
            }
        }
        return true
    }
}
