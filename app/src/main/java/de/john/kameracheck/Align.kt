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

    /**
     * Liefert dx, dy, Fehler – so dass Bild(p) ≈ Referenz(p + d).
     * (gx, gy) = Vorab-Schätzung in Pixeln (z. B. aus [alignLarge]); die Grobsuche läuft ±40 px darum.
     */
    fun align(refY: ByteArray, refS: IntArray, fy: ByteArray, w: Int, h: Int, gx: Int = 0, gy: Int = 0): FloatArray {
        val dw = w / 4
        val dh = h / 4
        val fs = downscale4(fy, w, h)
        val R = 10
        val ox = Math.round(gx / 4f)
        val oy = Math.round(gy / 4f)

        // Grob: auf Viertel-Auflösung
        var bdx = ox
        var bdy = oy
        val mx = R + abs(ox) + 1
        val my = R + abs(oy) + 1
        val wx0 = max(mx, dw / 2 - 160)
        val wx1 = min(dw - mx, dw / 2 + 160)
        val wy0 = max(my, dh / 2 - 120)
        val wy1 = min(dh - my, dh / 2 + 120)
        if (wx1 > wx0 && wy1 > wy0) {
            var best = Long.MAX_VALUE
            for (ddy in oy - R..oy + R) for (ddx in ox - R..ox + R) {
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
        val m = max(abs(cdx), abs(cdy)) + F + 2
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
     * Große Verschiebungen (Fotos aus der Hand, Sekunden auseinander: bei 50 MP schnell Hunderte
     * Pixel): Suche auf 1/16 Auflösung bis ±maxShift, dann [align] um diese Schätzung herum.
     */
    fun alignLarge(refY: ByteArray, refS: IntArray, fy: ByteArray, w: Int, h: Int, maxShift: Int): FloatArray {
        val k = 16
        val dw = w / k
        val dh = h / k
        val a = downscaleN(refY, w, h, k)
        val b = downscaleN(fy, w, h, k)
        val R = max(2, maxShift / k)
        var bx = 0
        var by = 0
        var best = Float.MAX_VALUE
        for (dy in -R..R) for (dx in -R..R) {
            // nur die Überlappung vergleichen, mittlerer Fehler (sonst gewinnt die kleinste Überlappung nicht unfair)
            val x0 = max(0, -dx)
            val x1 = min(dw, dw - dx)
            val y0 = max(0, -dy)
            val y1 = min(dh, dh - dy)
            if ((x1 - x0) * (y1 - y0) < dw * dh / 3) continue
            var s = 0L
            for (y in y0 until y1) {
                val ro = (y + dy) * dw + dx
                val fo = y * dw
                for (x in x0 until x1) s += abs(a[ro + x] - b[fo + x])
            }
            val m = s.toFloat() / ((x1 - x0) * (y1 - y0))
            if (m < best) { best = m; bx = dx; by = dy }
        }
        return align(refY, refS, fy, w, h, bx * k, by * k)
    }

    /** Mittelwert über k×k-Blöcke. */
    fun downscaleN(y: ByteArray, w: Int, h: Int, k: Int): IntArray {
        val dw = w / k
        val dh = h / k
        val out = IntArray(dw * dh)
        for (dy in 0 until dh) for (dx in 0 until dw) {
            var s = 0
            for (a in 0 until k) {
                val row = (dy * k + a) * w + dx * k
                for (c in 0 until k) s += y[row + c].toInt() and 255
            }
            out[dy * dw + dx] = s / (k * k)
        }
        return out
    }

    /**
     * Restfehler nach dem Ausrichten (mittlere Helligkeitsabweichung 0..255) im inneren Bereich –
     * zeigt, ob ein Bild wirklich passt (Doppelbilder entstehen, wenn nicht).
     */
    fun residual(ref: ByteArray, f: ByteArray, w: Int, h: Int, field: ShiftField): Float {
        val d = FloatArray(2)
        var s = 0.0
        var n = 0
        for (y in h / 8 until h - h / 8 step 3) for (x in w / 8 until w - w / 8 step 3) {
            field.at(x.toFloat(), y.toFloat(), d)
            val rx = x + d[0]
            val ry = y + d[1]
            if (rx < 0f || ry < 0f || rx >= w - 1 || ry >= h - 1) continue
            val ix = rx.toInt()
            val iy = ry.toInt()
            val ax = rx - ix
            val ay = ry - iy
            val i = iy * w + ix
            val r = ((ref[i].toInt() and 255) * (1 - ax) + (ref[i + 1].toInt() and 255) * ax) * (1 - ay) +
                ((ref[i + w].toInt() and 255) * (1 - ax) + (ref[i + w + 1].toInt() and 255) * ax) * ay
            s += abs(r - (f[y * w + x].toInt() and 255))
            n++
        }
        return if (n == 0) 255f else (s / n).toFloat()
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
     * Viertel-Auflösung (±20 px um die globale Schätzung), dann fein in voller Auflösung.
     * Kacheln ohne Struktur oder mit Ausreißern bekommen den Wert ihrer Nachbarn.
     */
    fun localField(ref: ByteArray, f: ByteArray, w: Int, h: Int, gx: Float, gy: Float, tile: Int = 96): ShiftField {
        val nx = max(1, w / tile)
        val ny = max(1, h / tile)
        val refI = IntArray(w * h) { ref[it].toInt() and 255 }
        val fI = IntArray(w * h) { f[it].toInt() and 255 }
        // grob (Viertel-Auflösung, Suche ±5 = ±20 Pixel – reicht für ≈ 1° Drehung bei großen Ausschnitten)
        val dw = w / 4
        val dh = h / 4
        val rs = downscale4(ref, w, h)
        val fs = downscale4(f, w, h)
        val cx = FloatArray(nx * ny) { gx / 4f }
        val cy = FloatArray(nx * ny) { gy / 4f }
        val okC = tileSearch(rs, fs, dw, dh, nx, ny, cx, cy, 5, 1, 12f)
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
            // nur Stellen, an denen Bild und verschobene Referenz beide gültig sind
            // (einseitig: bei großer Verschiebung bleibt die andere Bildseite nutzbar)
            val xa = max(tx * w / nx, max(1, R - cgx))
            val xb = min((tx + 1) * w / nx, min(w - 2, w - 2 - cgx - R))
            val ya = max(ty * h / ny, max(1, R - cgy))
            val yb = min((ty + 1) * h / ny, min(h - 2, h - 2 - cgy - R))
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
