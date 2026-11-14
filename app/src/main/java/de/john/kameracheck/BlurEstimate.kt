package de.john.kameracheck

import kotlin.math.PI
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

/**
 * Misst, wie unscharf ein Bild ist (Breite σ einer Gauß-Unschärfe in Pixeln).
 *
 * Idee: Ein scharfer Hell-Dunkel-Sprung der Höhe A wird durch Unschärfe σ zu einer
 * Rampe mit maximaler Steigung A / (σ·√(2π)). An den kräftigsten Kanten des Bildes
 * misst man Höhe und Steigung und rechnet σ zurück. Reine Rechenlogik.
 *
 * Wozu: Die KI ist auf leichte Unschärfe (≈ 1 Pixel) trainiert. Ist das Bild viel
 * unschärfer, hält sie den breiten Übergang für ein echtes Farbband und schärft
 * ihn nach (brauner Saum um Schrift). Verkleinert man das Bild vorher so weit,
 * dass die Unschärfe wieder ≈ 1 Pixel beträgt, rekonstruiert sie echte Kanten.
 */
object BlurEstimate {

    /** y = Helligkeit 0..1. Gibt σ zurück, oder 1 wenn zu wenige Kanten da sind. */
    fun sigma(y: FloatArray, w: Int, h: Int): Float {
        var s = 2f
        val g = FloatArray(w * h)
        for (yy in 1 until h - 1) for (xx in 1 until w - 1) {
            val i = yy * w + xx
            // Sobel / 8 → Steigung je Pixel
            val gx = (y[i - w + 1] + 2 * y[i + 1] + y[i + w + 1] - y[i - w - 1] - 2 * y[i - 1] - y[i + w - 1]) / 8f
            val gy = (y[i + w - 1] + 2 * y[i + w] + y[i + w + 1] - y[i - w - 1] - 2 * y[i - w] - y[i - w + 1]) / 8f
            g[i] = hypot(gx, gy)
        }
        val mx = FloatArray(w * h)
        val mn = FloatArray(w * h)
        val tmp = FloatArray(w * h)
        repeat(4) {
            val win = max(5, (6 * s).toInt() or 1)
            rangeFilter(y, w, h, win, mx, mn, tmp)
            val sig = ArrayList<Float>()
            for (yy in 1 until h - 1) for (xx in 1 until w - 1) {
                val i = yy * w + xx
                val gv = g[i]
                if (gv <= 0.01f) continue
                val r = mx[i] - mn[i]
                if (r <= 0.15f) continue
                // lokales Maximum der Steigung (3×3)
                var peak = true
                loop@ for (a in -1..1) for (b in -1..1) {
                    if ((a != 0 || b != 0) && g[i + a * w + b] > gv) { peak = false; break@loop }
                }
                if (peak) sig += r / (gv * sqrt(2 * PI).toFloat())
            }
            if (sig.size < 20) return if (it == 0) 1f else s
            sig.sort()
            // untere Hälfte: echte Sprünge (Linien/Muster ergäben zu große Werte)
            val k = max(20, sig.size / 2)
            s = sig[min(sig.size - 1, k / 2)]
        }
        return s
    }

    /** Maximum und Minimum in einem win×win-Fenster (getrennt nach Zeilen und Spalten). */
    private fun rangeFilter(y: FloatArray, w: Int, h: Int, win: Int, mx: FloatArray, mn: FloatArray, tmp: FloatArray) {
        val r = win / 2
        // Maximum
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
        // Minimum
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
     * Wie stark verkleinern, bevor die KI rechnet. Die günstigste Rest-Unschärfe wächst
     * leicht mit der Ausgangs-Unschärfe (in Tests: 1,0 bei σ≈1,5 … 1,7 bei σ≈7).
     */
    fun aiScale(sigma: Float): Float {
        val target = (0.9f + 0.12f * sigma).coerceIn(1.0f, 1.8f)
        return min(1f, target / max(sigma, 0.1f))
    }
}
