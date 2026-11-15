package de.john.kameracheck

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.ln
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * KI-Entschärfen mit bekannter Unschärfe (DPIR, Zhang et al. 2021, „Plug-and-Play Image
 * Restoration with Deep Denoiser Prior“) – reine Rechenlogik, die KI selbst wird übergeben.
 *
 * Im Wechsel:
 *  1. Physik-Schritt: Was muss das scharfe Bild sein, damit es – mit der gemessenen Unschärfe
 *     (Kalibrierung, Messung) verwischt – das Foto ergibt? Exakt lösbar im Frequenzraum (FFT).
 *  2. KI-Schritt: Ein Entrausch-Netz (DRUNet) räumt die dabei entstehenden Störungen weg.
 *     Es kennt nur „wie sehen saubere Bilder aus“, erfindet aber keine Formen aus dem Nichts.
 * Die Rausch-Stufe des KI-Schritts sinkt von Durchgang zu Durchgang.
 *
 * Gekachelt (256 × 256, davon 24 px Rand als Kontext), Mitte zuerst, mit Zeitbudget:
 * Kacheln, die nicht mehr drankommen, behalten das Ersatzbild (physikalisch entschärft).
 */
object Dpir {
    const val T = 256
    const val PAD = 24
    const val INNER = T - 2 * PAD
    const val ITERS = 3

    /** done = gerechnete Kacheln, flat = ohne Struktur übersprungen (dort gibt es nichts zu holen) */
    class Out(val y: FloatArray, val done: Int, val total: Int, val flat: Int = 0)

    /** Kacheln mit weniger Helligkeitsschwankung (Standardabweichung, 0..1) gelten als flach */
    const val FLAT_STD = 0.012f

    fun run(
        y: FloatArray, w: Int, h: Int,
        /** Unschärfe (σx, σy, Pixel) an einer Bildstelle */
        sigmaAt: (Int, Int) -> FloatArray,
        noise: Float,
        fallback: FloatArray,
        budgetMs: Long,
        denoise: (FloatArray, Float) -> FloatArray,
        progress: (Int, Int) -> Unit,
    ): Out {
        val out = fallback.copyOf()
        val nx = (w + INNER - 1) / INNER
        val ny = (h + INNER - 1) / INNER
        // Mitte zuerst – dort ist meist das Motiv
        val order = (0 until nx * ny).sortedBy { k ->
            val cx = (k % nx + 0.5f) * INNER - w / 2f
            val cy = (k / nx + 0.5f) * INNER - h / 2f
            cx * cx + cy * cy
        }
        val t0 = System.currentTimeMillis()
        var done = 0
        var flat = 0
        var computed = 0
        val tile = FloatArray(T * T)
        for (k in order) {
            if (computed > 0) {
                val per = (System.currentTimeMillis() - t0) / computed
                if (System.currentTimeMillis() - t0 + per > budgetMs) break
            }
            val tx = (k % nx) * INNER
            val ty = (k / nx) * INNER
            // flache Kacheln (Himmel, Wand) überspringen: kein Detail, nur Zeit
            if (std(y, w, h, tx, ty) < FLAT_STD) {
                flat++
                done++
                progress(done, nx * ny)
                continue
            }
            // Kachel mit echtem Umfeld; am Bildrand gespiegelt
            for (j in 0 until T) {
                val sy = mirror(ty - PAD + j, h)
                for (i in 0 until T) tile[j * T + i] = y[sy * w + mirror(tx - PAD + i, w)]
            }
            val s = sigmaAt(min(w - 1, tx + INNER / 2), min(h - 1, ty + INNER / 2))
            val r = restoreTile(tile, s[0], s[1], noise, ITERS, denoise)
            for (j in 0 until min(INNER, h - ty)) for (i in 0 until min(INNER, w - tx)) {
                out[(ty + j) * w + tx + i] = r[(PAD + j) * T + PAD + i]
            }
            done++
            computed++
            progress(done, nx * ny)
        }
        return Out(out, done, nx * ny, flat)
    }

    private fun std(y: FloatArray, w: Int, h: Int, tx: Int, ty: Int): Float {
        var s = 0.0
        var s2 = 0.0
        var n = 0
        for (j in ty until min(h, ty + INNER) step 2) for (i in tx until min(w, tx + INNER) step 2) {
            val v = y[j * w + i].toDouble()
            s += v; s2 += v * v; n++
        }
        if (n == 0) return 0f
        val m = s / n
        return sqrt(max(0.0, s2 / n - m * m)).toFloat()
    }

    private fun mirror(i: Int, n: Int): Int {
        var k = i
        if (k < 0) k = -k - 1
        if (k >= n) k = 2 * n - k - 1
        return k.coerceIn(0, n - 1)
    }

    /** Eine Kachel T×T entschärfen. */
    fun restoreTile(y: FloatArray, sx: Float, sy: Float, noise: Float, iters: Int, denoise: (FloatArray, Float) -> FloatArray): FloatArray {
        // Unschärfe im Frequenzraum: Gauß ist trennbar und symmetrisch → reell, Produkt zweier 1D-Antworten
        val kx = gaussOtf(sx)
        val ky = gaussOtf(sy)
        val yRe = y.copyOf()
        val yIm = FloatArray(T * T)
        fft2(yRe, yIm, false)
        val sig1 = 49f / 255f
        val sig2 = max(noise, 0.255f / 255f)
        var x = y.copyOf()
        val xIm = FloatArray(T * T)
        for (it in 0 until iters) {
            val sigma = if (iters == 1) sig2 else exp(ln(sig1) + (ln(sig2) - ln(sig1)) * it / (iters - 1))
            val rho = 0.23f * noise * noise / (sigma * sigma)
            // Physik-Schritt: X = (K·Y + ρ·F(x)) / (K² + ρ)
            java.util.Arrays.fill(xIm, 0f)
            fft2(x, xIm, false)
            for (v in 0 until T) for (u in 0 until T) {
                val i = v * T + u
                val k = kx[u] * ky[v]
                val den = k * k + rho
                x[i] = (k * yRe[i] + rho * x[i]) / den
                xIm[i] = (k * yIm[i] + rho * xIm[i]) / den
            }
            fft2(x, xIm, true)
            // KI-Schritt: entrauschen auf der aktuellen Stufe
            x = denoise(x, sigma)
        }
        return x
    }

    /** Frequenzantwort eines Gauß-Kerns (Mitte bei 0, zyklisch) der Länge T – reell. */
    private fun gaussOtf(s: Float): FloatArray {
        val re = FloatArray(T)
        val im = FloatArray(T)
        val r = max(1, ceil(3.5f * s).toInt())
        var sum = 0f
        for (d in -r..r) {
            val g = exp(-(d * d) / (2f * s * s))
            re[(d + T) % T] += g
            sum += g
        }
        for (i in 0 until T) re[i] /= sum
        fft1(re, im, 0, 1, false)
        return re
    }

    /** 2D-FFT (in place), inverse inkl. Normierung. */
    fun fft2(re: FloatArray, im: FloatArray, inverse: Boolean) {
        for (r in 0 until T) fft1(re, im, r * T, 1, inverse)
        for (c in 0 until T) fft1(re, im, c, T, inverse)
        if (inverse) {
            val n = (T * T).toFloat()
            for (i in re.indices) { re[i] /= n; im[i] /= n }
        }
    }

    private val bitrev = IntArray(T).also { b ->
        val bits = Integer.numberOfTrailingZeros(T)
        for (i in 0 until T) b[i] = Integer.reverse(i) ushr (32 - bits)
    }
    private val cosT = FloatArray(T / 2) { cos(2 * PI * it / T).toFloat() }
    private val sinT = FloatArray(T / 2) { sin(2 * PI * it / T).toFloat() }

    /** 1D-FFT der Länge T auf re/im ab off mit Schrittweite st (Radix 2, ohne Normierung). */
    private fun fft1(re: FloatArray, im: FloatArray, off: Int, st: Int, inverse: Boolean) {
        for (i in 0 until T) {
            val j = bitrev[i]
            if (j > i) {
                val a = off + i * st
                val b = off + j * st
                var t = re[a]; re[a] = re[b]; re[b] = t
                t = im[a]; im[a] = im[b]; im[b] = t
            }
        }
        var len = 2
        while (len <= T) {
            val half = len / 2
            val step = T / len
            var i = 0
            while (i < T) {
                for (k in 0 until half) {
                    val wr = cosT[k * step]
                    val wi = if (inverse) sinT[k * step] else -sinT[k * step]
                    val a = off + (i + k) * st
                    val b = off + (i + k + half) * st
                    val xr = re[b] * wr - im[b] * wi
                    val xi = re[b] * wi + im[b] * wr
                    re[b] = re[a] - xr
                    im[b] = im[a] - xi
                    re[a] += xr
                    im[a] += xi
                }
                i += len
            }
            len *= 2
        }
    }

    /** Rauschstärke (0..1) aus den Unterschieden benachbarter Pixel, robust (Median). */
    fun noiseLevel(y: FloatArray, w: Int, h: Int): Float {
        val d = ArrayList<Float>()
        var j = 1
        while (j < h - 1) {
            var i = 1
            while (i < w - 1) {
                val k = j * w + i
                d += abs(y[k + 1] - y[k])
                i += 3
            }
            j += 3
        }
        if (d.isEmpty()) return 2f / 255f
        d.sort()
        return (d[d.size / 2] / (0.6745f * sqrt(2f))).coerceIn(0.5f / 255f, 10f / 255f)
    }
}
