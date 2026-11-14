package de.john.kameracheck

import kotlin.math.abs

/**
 * Verfolgt eine Bildstelle von Vorschaubild zu Vorschaubild (Graustufen, kleine Auflösung).
 * Vergleicht ein Musterstück per mittelwertbereinigter Differenz – robust gegen
 * Helligkeitsänderungen. Reine Rechenlogik ohne Android-Abhängigkeiten.
 */
class Tracker(private val patch: Int = 21, private val search: Int = 14) {

    var x = 0f
        private set
    var y = 0f
        private set
    var lost = false
        private set
    val active get() = tmpl != null

    private var tmpl: IntArray? = null
    private var frames = 0
    private val half = patch / 2

    fun start(gray: IntArray, w: Int, h: Int, px: Float, py: Float) {
        x = px.coerceIn(half.toFloat(), (w - half - 1).toFloat())
        y = py.coerceIn(half.toFloat(), (h - half - 1).toFloat())
        tmpl = extract(gray, w, x.toInt(), y.toInt())
        lost = false
        frames = 0
    }

    fun stop() {
        tmpl = null
        lost = false
    }

    /** Neues Bild auswerten. Gibt true zurück, wenn die Stelle gefunden wurde. */
    fun update(gray: IntArray, w: Int, h: Int): Boolean {
        val t = tmpl ?: return false
        val cx = x.toInt()
        val cy = y.toInt()
        var best = Long.MAX_VALUE
        var bx = cx
        var by = cy
        for (dy in -search..search) {
            val yy = cy + dy
            if (yy - half < 0 || yy + half >= h) continue
            for (dx in -search..search) {
                val xx = cx + dx
                if (xx - half < 0 || xx + half >= w) continue
                val s = cost(gray, w, xx, yy, t, best)
                // leichte Bevorzugung kleiner Bewegungen
                val sp = s + (abs(dx) + abs(dy)).toLong() * patch
                if (sp < best) {
                    best = sp
                    bx = xx
                    by = yy
                }
            }
        }
        val perPixel = best.toFloat() / (patch * patch)
        if (perPixel > LOST) {
            lost = true
            return false
        }
        lost = false
        x = bx.toFloat()
        y = by.toFloat()
        frames++
        // Muster gelegentlich auffrischen (Zoom, Licht, Perspektive)
        if (perPixel < REFRESH && frames % 12 == 0) tmpl = extract(gray, w, bx, by)
        return true
    }

    private fun extract(g: IntArray, w: Int, cx: Int, cy: Int): IntArray {
        val out = IntArray(patch * patch)
        var sum = 0
        var k = 0
        for (dy in -half..half) for (dx in -half..half) {
            val v = g[(cy + dy) * w + cx + dx]
            out[k++] = v
            sum += v
        }
        val mean = sum / out.size
        for (i in out.indices) out[i] -= mean
        return out
    }

    private fun cost(g: IntArray, w: Int, cx: Int, cy: Int, t: IntArray, limit: Long): Long {
        var sum = 0
        for (dy in -half..half) {
            val row = (cy + dy) * w + cx
            for (dx in -half..half) sum += g[row + dx]
        }
        val mean = sum / (patch * patch)
        var s = 0L
        var k = 0
        for (dy in -half..half) {
            val row = (cy + dy) * w + cx
            for (dx in -half..half) {
                s += abs(g[row + dx] - mean - t[k++])
            }
            if (s >= limit) return s
        }
        return s
    }

    companion object {
        private const val LOST = 28f
        private const val REFRESH = 10f

        /** ARGB → Graustufen (0..255). */
        fun gray(px: IntArray, out: IntArray) {
            for (i in px.indices) {
                val p = px[i]
                out[i] = (((p shr 16) and 255) * 77 + ((p shr 8) and 255) * 150 + (p and 255) * 29) shr 8
            }
        }
    }
}
