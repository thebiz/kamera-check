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

    /**
     * Verschiebungsfeld: je Kachel eine eigene Verschiebung (Handdrehung, Verzerrung, leichte
     * Bewegung im Bild), zwischen den Kachelmitten weich (bilinear) übergeblendet.
     * Gleiche Bedeutung wie [align]: Bild(p) ≈ Referenz(p + d(p)).
     */
    class ShiftField(val nx: Int, val ny: Int, val w: Int, val h: Int, val dx: FloatArray, val dy: FloatArray) {
        fun at(x: Float, y: Float, out: FloatArray) {
            val fx = (x / w * nx - 0.5f).coerceIn(0f, (nx - 1).toFloat())
            val fy = (y / h * ny - 0.5f).coerceIn(0f, (ny - 1).toFloat())
            val ix = min(nx - 2, fx.toInt()).coerceAtLeast(0)
            val iy = min(ny - 2, fy.toInt()).coerceAtLeast(0)
            if (nx < 2 || ny < 2) {
                val k = iy * nx + ix
                out[0] = dx[k]; out[1] = dy[k]; return
            }
            val ax = fx - ix
            val ay = fy - iy
            val k = iy * nx + ix
            out[0] = (dx[k] * (1 - ax) + dx[k + 1] * ax) * (1 - ay) + (dx[k + nx] * (1 - ax) + dx[k + nx + 1] * ax) * ay
            out[1] = (dy[k] * (1 - ax) + dy[k + 1] * ax) * (1 - ay) + (dy[k + nx] * (1 - ax) + dy[k + nx + 1] * ax) * ay
        }

        companion object {
            fun constant(w: Int, h: Int, dx: Float, dy: Float) = ShiftField(1, 1, w, h, floatArrayOf(dx), floatArrayOf(dy))
        }
    }

    /**
     * Kachelweises Ausrichten, ausgehend von der globalen Verschiebung (gx, gy): erst grob auf
     * Viertel-Auflösung (fängt Handdrehung bis ≈ 1° ab), dann fein in voller Auflösung.
     * Kacheln ohne Struktur oder mit Ausreißern bekommen den Wert ihrer Nachbarn.
     */
    fun localField(ref: ByteArray, f: ByteArray, w: Int, h: Int, gx: Float, gy: Float, tile: Int = 96): ShiftField {
        val nx = max(1, w / tile)
        val ny = max(1, h / tile)
        val refI = IntArray(w * h) { ref[it].toInt() and 255 }
        val fI = IntArray(w * h) { f[it].toInt() and 255 }
        // grob (Viertel-Auflösung, Suche ±3 = ±12 Pixel)
        val dw = w / 4
        val dh = h / 4
        val rs = downscale4(ref, w, h)
        val fs = downscale4(f, w, h)
        val cx = FloatArray(nx * ny) { gx / 4f }
        val cy = FloatArray(nx * ny) { gy / 4f }
        val okC = tileSearch(rs, fs, dw, dh, nx, ny, cx, cy, 3, 1, 12f)
        clean(nx, ny, cx, cy, okC, gx / 4f, gy / 4f, 0.5f)
        // fein (volle Auflösung, Suche ±3 um die Grobschätzung)
        val fx = FloatArray(nx * ny) { cx[it] * 4f }
        val fy = FloatArray(nx * ny) { cy[it] * 4f }
        val ok = tileSearch(refI, fI, w, h, nx, ny, fx, fy, 3, 2, 3f)
        clean(nx, ny, fx, fy, ok, gx, gy, 0.75f)
        return ShiftField(nx, ny, w, h, fx, fy)
    }

    /** Sucht je Kachel die beste Verschiebung um (dx[k], dy[k]) herum; schreibt das Ergebnis zurück. */
    private fun tileSearch(
        ref: IntArray, f: IntArray, w: Int, h: Int, nx: Int, ny: Int,
        dx: FloatArray, dy: FloatArray, R: Int, step: Int, minTex: Float,
    ): BooleanArray {
        val ok = BooleanArray(nx * ny)
        val size = 2 * R + 1
        val sad = Array(size) { LongArray(size) }
        for (ty in 0 until ny) for (tx in 0 until nx) {
            val k = ty * nx + tx
            val cgx = Math.round(dx[k])
            val cgy = Math.round(dy[k])
            val m = R + max(abs(cgx), abs(cgy)) + 2
            val xa = max(tx * w / nx, m)
            val xb = min((tx + 1) * w / nx, w - m)
            val ya = max(ty * h / ny, m)
            val yb = min((ty + 1) * h / ny, h - m)
            if (xb - xa < 6 || yb - ya < 6) continue
            var tex = 0L
            var cnt = 0
            for (y in ya until yb step step) {
                val row = y * w
                for (x in xa until xb step step) {
                    tex += abs(ref[row + x + 1] - ref[row + x]) + abs(ref[row + w + x] - ref[row + x])
                    cnt++
                }
            }
            if (tex.toFloat() / max(1, cnt) < minTex) continue
            for (a in -R..R) for (b in -R..R) {
                val ox = cgx + b
                val oy = cgy + a
                var sum = 0L
                for (y in ya until yb step step) {
                    val fo = y * w
                    val ro = (y + oy) * w + ox
                    for (x in xa until xb step step) sum += abs(ref[ro + x] - f[fo + x])
                }
                sad[a + R][b + R] = sum
            }
            var ai = R
            var bi = R
            for (a in 0 until size) for (b in 0 until size) if (sad[a][b] < sad[ai][bi]) { ai = a; bi = b }
            if (ai == 0 || bi == 0 || ai == size - 1 || bi == size - 1) continue // am Rand = unsicher
            dx[k] = cgx + (bi - R) + parabola(sad[ai][bi - 1], sad[ai][bi], sad[ai][bi + 1])
            dy[k] = cgy + (ai - R) + parabola(sad[ai - 1][bi], sad[ai][bi], sad[ai + 1][bi])
            ok[k] = true
        }
        return ok
    }

    /** Ausreißer und Lücken: Median der sicheren Nachbarn (3×3), sonst globaler Wert. */
    private fun clean(nx: Int, ny: Int, dx: FloatArray, dy: FloatArray, ok: BooleanArray, gx: Float, gy: Float, tol: Float) {
        val ox = dx.copyOf()
        val oy = dy.copyOf()
        for (ty in 0 until ny) for (tx in 0 until nx) {
            val xs = ArrayList<Float>()
            val ys = ArrayList<Float>()
            for (a in -1..1) for (b in -1..1) {
                val x = tx + b
                val y = ty + a
                if (x < 0 || y < 0 || x >= nx || y >= ny) continue
                val q = y * nx + x
                if (ok[q]) { xs += ox[q]; ys += oy[q] }
            }
            val k = ty * nx + tx
            if (xs.size < 2) {
                if (!ok[k]) { dx[k] = gx; dy[k] = gy }
                continue
            }
            xs.sort(); ys.sort()
            val mx = xs[xs.size / 2]
            val my = ys[ys.size / 2]
            if (!ok[k] || abs(ox[k] - mx) > tol || abs(oy[k] - my) > tol) {
                dx[k] = mx; dy[k] = my
            }
        }
    }

    private fun parabola(l: Long, c: Long, r: Long): Float {
        val den = (l - 2 * c + r).toFloat()
        if (den <= 0f) return 0f
        return ((l - r).toFloat() / (2f * den)).coerceIn(-0.5f, 0.5f)
    }
}
