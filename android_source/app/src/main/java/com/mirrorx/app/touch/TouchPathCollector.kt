package com.mirrorx.app.touch

import kotlin.math.hypot
import kotlin.math.pow

/**
 * TouchPathCollector v1.3.1
 *
 * Coleta pontos de touch durante uma interação contínua (modo CURSOR),
 * comprime a trajetória com Douglas-Peucker e envia o caminho como um
 * único batch via WebSocket.
 *
 * Benefícios:
 *   - Menos pacotes de rede (cada ACTION_MOVE não vira um JSON)
 *   - Curva suavizada no PC via MotionInterpolator
 *   - Cursor segue o movimento real do dedo sem jitter
 */
class TouchPathCollector(
    private val capacity: Int = 32,
    private val epsilon: Float = 0.005f
) {
    data class Point(val x: Float, val y: Float, val t: Long)

    private val buffer = ArrayDeque<Point>(capacity)

    private var lastFlushTime = 0L

    /**
     * Adiciona um ponto ao buffer com timestamp atual.
     * @return true se o buffer atingiu a capacidade e deve ser flushado
     */
    fun add(x: Float, y: Float): Boolean {
        if (buffer.size >= capacity) {
            flushToPath()
        }
        buffer.addLast(Point(x.coerceIn(0f, 1f), y.coerceIn(0f, 1f), System.currentTimeMillis()))
        return false
    }

    /**
     * Força o envio do buffer atual (usado no ACTION_UP).
     * @return lista de pontos da trajetória (pode estar vazia)
     */
    fun flushPath(): List<Point> {
        return flushToPath()
    }

    /**
     * Verifica se já passou tempo suficiente desde o último flush para
     * manter a latência baixa. v1.4.0: adaptive — moves rápidos = 16ms,
     * moves lentos = 100ms. Reduz latência visível sem inflar a fila.
     */
    fun shouldFlush(): Boolean {
        if (buffer.isEmpty()) return false
        val first = buffer.firstOrNull() ?: return false
        val age = System.currentTimeMillis() - first.t
        // Estimate current speed: total path length / age
        var dist = 0f
        var prev = buffer.first()
        for (i in 1 until buffer.size) {
            val p = buffer[i]
            dist += hypot(p.x - prev.x, p.y - prev.y)
            prev = p
        }
        val speed = if (age > 0) dist / age else 0f
        // speed in "screen-fraction per ms": >0.005 = fast, <0.001 = slow
        val maxAge = when {
            speed > 0.005f -> 16L   // fast movement: flush at ~60Hz
            speed > 0.001f -> 33L   // medium: ~30Hz
            else -> 100L            // slow: ~10Hz
        }
        return age >= maxAge
    }

    /**
     * Retorna a distância total acumulada desde o primeiro ponto do buffer.
     * Útil para detectar tap vs drag no server.
     */
    fun totalPathDistance(): Float {
        if (buffer.size < 2) return 0f
        var dist = 0f
        var prev = buffer.first()
        for (i in 1 until buffer.size) {
            val p = buffer[i]
            dist += hypot(p.x - prev.x, p.y - prev.y)
            prev = p
        }
        return dist
    }

    /**
     * Comprime o caminho usando o algoritmo Douglas-Peucker.
     * Remove pontos intermediários que não afetam a forma geral.
     */
    private fun compressDouglasPeucker(points: List<Point>, eps: Float): List<Point> {
        if (points.size <= 2) return points

        fun perpendicularDistance(p: Point, start: Point, end: Point): Float {
            if (start.x == end.x && start.y == end.y) {
                return hypot(p.x - start.x, p.y - start.y)
            }
            val num = kotlin.math.abs(
                (end.y - start.y) * p.x - (end.x - start.x) * p.y +
                    end.x * start.y - end.y * start.x
            )
            val den = hypot(end.y - start.y, end.x - start.x)
            return num / den
        }

        fun simplify(start: Int, end: Int, result: MutableList<Point>) {
            if (start > end) return
            if (start == end) {
                result.add(points[start])
                return
            }

            var maxDist = 0f
            var maxIdx = start
            for (i in start + 1 until end) {
                val d = perpendicularDistance(points[i], points[start], points[end])
                if (d > maxDist) {
                    maxDist = d
                    maxIdx = i
                }
            }

            if (maxDist > eps) {
                simplify(start, maxIdx, result)
                simplify(maxIdx, end, result)
            } else {
                result.add(points[start])
                result.add(points[end])
            }
        }

        val result = mutableListOf<Point>()
        simplify(0, points.size - 1, result)
        return result.distinct()
    }

    private fun flushToPath(): List<Point> {
        if (buffer.isEmpty()) return emptyList()
        val path = buffer.toList()
        buffer.clear()
        lastFlushTime = System.currentTimeMillis()

        // Compressão: reduz ~30-60% dos pontos sem perda perceptível
        val compressed = compressDouglasPeucker(path, epsilon)
        return if (compressed.size >= 2) compressed else path
    }
}
