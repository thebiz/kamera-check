package de.john.kameracheck

import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

/** Ausrichten von Bildserien – reine Rechenlogik ohne Android-Abhängigkeiten. */
object Align {

    fun sharpness(y: ByteArray, w: Int, h: Int): Float {
        val x0 = max(1, w / 2 - 400)
        val x1 = min(w - 2, w / 2 + 400)
        val y0 = max(1, h / 2 - 300)
        val y1 = min(h - 2, h / 2 + 300)
        var s = 0L
        var yy = y0
        while (yy < y1) {
            var xx = x0
            val row = yy * w
            while (xx < x1) {
                val c = y[row + xx].toInt() and 255
                s += abs((y[row + xx + 1].toInt() and 255) - c) + abs((y[row + w + xx].toInt() and 255) - c)
                xx += 2
            }
            yy += 2
        }
        return s.toFloat()
    }

    fun downscale4(y: ByteArray, w: Int, h: Int): IntArray {
        val dw = w / 4
        val dh = h / 4
        val out = IntArray(dw * dh)
        for (dy in 0 until dh) {
            for (dx in 0 until dw) {
                var s = 0
                for (a in 0 until 4) {
                    val row = (dy * 4 + a) * w + dx * 4
                    for (b in 0 until 4) s += y[row + b].toInt() and 255
                }
                out[dy * dw + dx] = s
            }
        }
        return out
    }

    /** Liefert dx, dy, Fehler – so dass Bild(p) ≈ Referenz(p + d). */
    fun align(refY: ByteArray, refS: IntArray, fy: ByteArray, w: Int, h: Int): FloatArray {
        val dw = w / 4
        val dh = h / 4
        val fs = downscale4(fy, w, h)
        val R = 10

        // Grob: auf Viertel-Auflösung
        var bdx = 0
        var bdy = 0
        val wx0 = max(R + 1, dw / 2 - 160)
        val wx1 = min(dw - R - 1, dw / 2 + 160)
        val wy0 = max(R + 1, dh / 2 - 120)
        val wy1 = min(dh - R - 1, dh / 2 + 120)
        if (wx1 > wx0 && wy1 > wy0) {
            var best = Long.MAX_VALUE
            for (ddy in -R..R) for (ddx in -R..R) {
                var s = 0L
                var y = wy0
                while (y < wy1) {
                    val ro = (y + ddy) * dw + ddx
                    val fo = y * dw
                    var x = wx0
                    while (x < wx1) {
                        s += abs(refS[ro + x] - fs[fo + x])
                        x += 2
                    }
                    y += 2
                }
                if (s < best) {
                    best = s
                    bdx = ddx
                    bdy = ddy
                }
            }
        }

        // Fein: volle Auflösung um die Grobschätzung herum
        val cdx = bdx * 4
        val cdy = bdy * 4
        val F = 4
        val m = R * 4 + F + 2
        val fx0 = max(m, w / 2 - 256)
        val fx1 = min(w - m, w / 2 + 256)
        val fy0 = max(m, h / 2 - 192)
        val fy1 = min(h - m, h / 2 + 192)
        if (fx1 <= fx0 || fy1 <= fy0) return floatArrayOf(cdx.toFloat(), cdy.toFloat(), 0f)

        val size = 2 * F + 1
        val sad = Array(size) { LongArray(size) }
        var count = 0
        for (a in -F..F) for (b in -F..F) {
            val dx = cdx + b
            val dy = cdy + a
            var s = 0L
            var c = 0
            var y = fy0
            while (y < fy1) {
                val ro = (y + dy) * w + dx
                val fo = y * w
                var x = fx0
                while (x < fx1) {
                    s += abs((refY[ro + x].toInt() and 255) - (fy[fo + x].toInt() and 255))
                    c++
                    x += 2
                }
                y += 2
            }
            sad[a + F][b + F] = s
            count = c
        }

        var ai = F
        var bi = F
        for (a in 0 until size) for (b in 0 until size) {
            if (sad[a][b] < sad[ai][bi]) {
                ai = a
                bi = b
            }
        }
        val subX = if (bi in 1 until size - 1) parabola(sad[ai][bi - 1], sad[ai][bi], sad[ai][bi + 1]) else 0f
        val subY = if (ai in 1 until size - 1) parabola(sad[ai - 1][bi], sad[ai][bi], sad[ai + 1][bi]) else 0f
        val cost = if (count > 0) sad[ai][bi].toFloat() / count else 0f
        return floatArrayOf(cdx + (bi - F) + subX, cdy + (ai - F) + subY, cost)
    }

    private fun parabola(l: Long, c: Long, r: Long): Float {
        val den = (l - 2 * c + r).toFloat()
        if (den <= 0f) return 0f
        return ((l - r).toFloat() / (2f * den)).coerceIn(-0.5f, 0.5f)
    }
}
