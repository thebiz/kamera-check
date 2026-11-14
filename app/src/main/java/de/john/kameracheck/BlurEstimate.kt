package de.john.kameracheck

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

/**
 * Misst, wie unscharf ein Bild ist – getrennt nach Richtung (σx waagerecht, σy senkrecht).
 *
 * Physik: Ein scharfer Hell-Dunkel-Sprung der Höhe A wird durch Unschärfe σ zu einer
 * Rampe mit maximaler Steigung A / (σ·√(2π)). An den kräftigsten Kanten misst man
 * Höhe und Steigung und rechnet σ zurück. Senkrechte Kanten verraten die waagerechte
 * Unschärfe, waagerechte Kanten die senkrechte – so wird einseitige Verwacklung sichtbar.
 *
 * Eichmaß: Gedruckte Schrift ist (fast) schwarz-weiß, ihre Kanten sind echte Sprünge.
 * Liegt eine Maske mit Schrift-Bereichen vor, werden bevorzugt diese Kanten gemessen.
 *
 * Wozu: Die KI ist auf ≈ 1 Pixel Unschärfe trainiert. Wird das Bild vorher je Richtung
 * passend verkleinert, sieht sie wieder gewohnte Kanten statt breiter Übergänge.
 */
object BlurEstimate {

    class Result(val sx: Float, val sy: Float, val edges: Int, val fromText: Boolean) {
        val mean get() = sqrt(sx * sy)
    }

    /**
     * y = Helligkeit 0..1. mask (optional) = Schrift-Bereiche; werden dort genug Kanten
     * gefunden, zählen nur diese. Ohne verwertbare Kanten: σ = 1 (nichts verkleinern).
     */
    fun measure(y: FloatArray, w: Int, h: Int, mask: BooleanArray? = null): Result {
        if (mask != null) {
            val r = run(y, w, h, mask)
            if (r != null && r.edges >= 40) return Result(r.sx, r.sy, r.edges, true)
        }
        return run(y, w, h, null) ?: Result(1f, 1f, 0, false)
    }

    private fun run(y: FloatArray, w: Int, h: Int, mask: BooleanArray?): Result? {
        val gxA = FloatArray(w * h)
        val gyA = FloatArray(w * h)
        val g = FloatArray(w * h)
        for (yy in 1 until h - 1) for (xx in 1 until w - 1) {
            val i = yy * w + xx
            // Sobel / 8 → Steigung je Pixel
            val gx = (y[i - w + 1] + 2 * y[i + 1] + y[i + w + 1] - y[i - w - 1] - 2 * y[i - 1] - y[i + w - 1]) / 8f
            val gy = (y[i + w - 1] + 2 * y[i + w] + y[i + w + 1] - y[i - w - 1] - 2 * y[i - w] - y[i - w + 1]) / 8f
            gxA[i] = gx
            gyA[i] = gy
            g[i] = hypot(gx, gy)
        }
        val mx = FloatArray(w * h)
        val mn = FloatArray(w * h)
        val tmp = FloatArray(w * h)
        var sx = 2f
        var sy = 2f
        var edges = 0
        var found = false
        repeat(4) {
            val win = max(5, (6 * max(sx, sy)).toInt() or 1)
            rangeFilter(y, w, h, win, mx, mn, tmp)
            val vx = ArrayList<Float>()
            val vy = ArrayList<Float>()
            for (yy in 1 until h - 1) for (xx in 1 until w - 1) {
                val i = yy * w + xx
                if (mask != null && !mask[i]) continue
                val gv = g[i]
                if (gv <= 0.01f) continue
                val r = mx[i] - mn[i]
                if (r <= 0.15f) continue
                var peak = true
                loop@ for (a in -1..1) for (b in -1..1) {
                    if ((a != 0 || b != 0) && g[i + a * w + b] > gv) { peak = false; break@loop }
                }
                if (!peak) continue
                val sig = r / (gv * sqrt(2 * PI).toFloat())
                val ax = abs(gxA[i])
                val ay = abs(gyA[i])
                if (ax > 2 * ay) vx += sig else if (ay > 2 * ax) vy += sig
            }
            val ex = lowMedian(vx)
            val ey = lowMedian(vy)
            if (ex == null && ey == null) return if (found) Result(sx, sy, edges, false) else null
            sx = ex ?: ey!!
            sy = ey ?: ex!!
            edges = vx.size + vy.size
            found = true
        }
        return Result(sx, sy, edges, false)
    }

    /** Median der unteren Hälfte: echte Sprünge (Linien/Muster ergäben zu große Werte). */
    private fun lowMedian(v: ArrayList<Float>): Float? {
        if (v.size < 20) return null
        v.sort()
        val k = max(20, v.size / 2)
        return v[min(v.size - 1, k / 2)]
    }

    /** Maximum und Minimum in einem win×win-Fenster (getrennt nach Zeilen und Spalten). */
    private fun rangeFilter(y: FloatArray, w: Int, h: Int, win: Int, mx: FloatArray, mn: FloatArray, tmp: FloatArray) {
        val r = win / 2
        for (yy in 0 until h) {
            val row = yy * w
            for (xx in 0 until w) {
                var m = -1f
                for (t in max(0, xx - r)..min(w - 1, xx + r)) m = max(m, y[row + t])
                tmp[row + xx] = m
            }
        }
        for (xx in 0 until w) for (yy in 0 until h) {
            var m = -1f
            for (t in max(0, yy - r)..min(h - 1, yy + r)) m = max(m, tmp[t * w + xx])
            mx[yy * w + xx] = m
        }
        for (yy in 0 until h) {
            val row = yy * w
            for (xx in 0 until w) {
                var m = 2f
                for (t in max(0, xx - r)..min(w - 1, xx + r)) m = min(m, y[row + t])
                tmp[row + xx] = m
            }
        }
        for (xx in 0 until w) for (yy in 0 until h) {
            var m = 2f
            for (t in max(0, yy - r)..min(h - 1, yy + r)) m = min(m, tmp[t * w + xx])
            mn[yy * w + xx] = m
        }
    }

    /**
     * Wie stark (je Richtung) verkleinern, bevor die KI rechnet. Die günstigste
     * Rest-Unschärfe wächst leicht mit der Ausgangs-Unschärfe (Tests: 1,0 bei σ≈1,5 … 1,7 bei σ≈7).
     */
    fun aiScale(sigma: Float): Float {
        val target = (0.9f + 0.12f * sigma).coerceIn(1.0f, 1.8f)
        return min(1f, target / max(sigma, 0.1f))
    }
}
