package de.john.kameracheck

import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.sqrt

/**
 * Kalibrierung des Objektivs mit einem Testmuster – reine Rechenlogik.
 *
 * 1. Schärfe-Muster (schräge schwarze Quadrate auf Weiß): An den Kanten wird für jede
 *    Farbe (R, G, B) und Richtung (x, y) die Unschärfe σ gemessen – in den linearen
 *    RAW-Daten, also im echten Licht. Mehrere Positionen (Mitte, Ecken) ergeben ein
 *    Schärfeprofil über den Sensor.
 * 2. Graustufen (11 Stufen mit gleichen Lichtschritten): prüft, ob die RAW-Werte
 *    linear zum Licht sind, und misst Streulicht/Spiegelung (Rest-Helligkeit bei „Schwarz“).
 */
object Calib {

    /** Eine Messung: Position auf dem Sensor (0..1) und σ[Farbe][Richtung] in Sensorpixeln. */
    class Sample(val nx: Float, val ny: Float, val sig: Array<FloatArray>, val edges: Int) {
        /** Helligkeits-σ je Richtung (Mischung wie im RAW-Rechenkern: 25 % R, 50 % G, 25 % B). */
        fun luma(): FloatArray = FloatArray(2) { d ->
            sqrt(0.25f * sig[0][d] * sig[0][d] + 0.5f * sig[1][d] * sig[1][d] + 0.25f * sig[2][d] * sig[2][d])
        }
    }

    class Wedge(
        /** gemessene Werte der 11 Stufen, von dunkel nach hell (relativ) */
        val levels: FloatArray,
        /** Bestimmtheitsmaß der Geraden Messwert ↔ Licht (1 = perfekt linear) */
        val r2: Float,
        /** Rest-Helligkeit bei Schwarz relativ zu Weiß (Streulicht, Spiegelung, Schwarzwert-Fehler) */
        val flare: Float,
    )

    /** Leuchtdichte der 11 Graustufen, wie die Testmuster-Seite sie zeigt (8-Bit-gerundet, sRGB). */
    val WEDGE_LIGHT: FloatArray by lazy {
        FloatArray(11) { i ->
            val l = i / 10.0
            val s = if (l <= 0.0031308) 12.92 * l else 1.055 * l.pow(1 / 2.4) - 0.055
            val v = Math.round(255 * s) / 255.0
            (if (v <= 0.04045) v / 12.92 else ((v + 0.055) / 1.055).pow(2.4)).toFloat()
        }
    }

    // ───────────────────────── Schärfe ─────────────────────────

    /**
     * planes = lineare Farbebenen R, G, B im Messrahmen (w×h, Raster u-fach feiner als der Sensor),
     * shakePx = Verwacklung während der Belichtung (Sensorpixel, Lagesensor).
     * Gibt σ[Farbe][Richtung] in Sensorpixeln zurück, oder null bei zu wenigen Kanten.
     */
    fun measureSharpness(planes: Array<FloatArray>, w: Int, h: Int, u: Float, shakePx: Float): Pair<Array<FloatArray>, Int>? {
        val motion = shakePx / sqrt(12f)
        var minEdges = Int.MAX_VALUE
        val out = Array(3) { FloatArray(2) }
        for (c in 0 until 3) {
            val y = normalize(planes[c])
            val r = BlurEstimate.measure(y, w, h)
            if (r.edges < 60) return null
            minEdges = min(minEdges, r.edges)
            // Raster → Sensorpixel, Verwacklung herausrechnen (Quellen addieren sich quadratisch)
            out[c][0] = sqrt(max(0.04f, (r.sx / u) * (r.sx / u) - motion * motion))
            out[c][1] = sqrt(max(0.04f, (r.sy / u) * (r.sy / u) - motion * motion))
        }
        return out to minEdges
    }

    /** Auf 0..1 strecken (1. bis 99,5. Perzentil), damit die Kanten-Schwelle passt. */
    private fun normalize(a: FloatArray): FloatArray {
        val s = a.copyOf()
        s.sort()
        val lo = s[(s.size * 0.01).toInt()]
        val hi = s[min(s.size - 1, (s.size * 0.995).toInt())]
        val span = max(1e-6f, hi - lo)
        return FloatArray(a.size) { ((a[it] - lo) / span).coerceIn(0f, 1.2f) }
    }

    // ───────────────────────── Graustufen ─────────────────────────

    /** y = lineares Helligkeitsbild im Messrahmen. Sucht die 11 Stufen waagerecht oder senkrecht. */
    fun analyzeWedge(y: FloatArray, w: Int, h: Int): Wedge? {
        val cols = FloatArray(w)
        val r0 = h / 5
        val r1 = h - h / 5
        for (x in 0 until w) {
            var s = 0f
            for (yy in r0 until r1) s += y[yy * w + x]
            cols[x] = s / (r1 - r0)
        }
        val rows = FloatArray(h)
        val c0 = w / 5
        val c1 = w - w / 5
        for (yy in 0 until h) {
            var s = 0f
            for (x in c0 until c1) s += y[yy * w + x]
            rows[yy] = s / (c1 - c0)
        }
        val a = findSteps(cols)
        val b = findSteps(rows)
        val levels = when {
            a == null && b == null -> return null
            a == null -> b!!.first
            b == null -> a.first
            else -> if (a.second >= b.second) a.first else b.first
        }
        return fit(levels)
    }

    /** Findet 11 aufeinanderfolgende, gleichmäßig ansteigende (oder fallende) Stufen. Liefert Stufen + Güte. */
    private fun findSteps(p0: FloatArray): Pair<FloatArray, Float>? {
        val n = p0.size
        if (n < 60) return null
        // leicht glätten
        val p = FloatArray(n) { i ->
            var s = 0f
            var c = 0
            for (k in max(0, i - 1)..min(n - 1, i + 1)) { s += p0[k]; c++ }
            s / c
        }
        val sorted = p.sortedArray()
        val range = sorted[(n * 0.99).toInt()] - sorted[(n * 0.01).toInt()]
        if (range <= 1e-6f) return null
        val k = max(2, n / 200)
        val d = FloatArray(n) { i -> abs(p[min(n - 1, i + k)] - p[max(0, i - k)]) }
        val thr = 0.35f * range / 10f
        val minGap = max(3, n / 45)
        // Kanten: lokale Maxima der Änderung
        val edges = ArrayList<Int>()
        var i = 1
        while (i < n - 1) {
            if (d[i] > thr && d[i] >= d[i - 1] && d[i] >= d[i + 1]) {
                if (edges.isNotEmpty() && i - edges.last() < minGap) {
                    if (d[i] > d[edges.last()]) edges[edges.size - 1] = i
                } else edges += i
            }
            i++
        }
        // Abschnitte zwischen den Kanten
        val bounds = listOf(0) + edges + listOf(n)
        val segs = ArrayList<FloatArray>() // [start, end, median]
        for (s in 0 until bounds.size - 1) {
            val a = bounds[s]
            val b = bounds[s + 1]
            if (b - a < 3) continue
            val m = (b - a) / 5
            val inner = p.copyOfRange(a + m, b - m).sortedArray()
            if (inner.isEmpty()) continue
            segs += floatArrayOf(a.toFloat(), b.toFloat(), inner[inner.size / 2])
        }
        var best: Pair<FloatArray, Float>? = null
        for (start in 0..segs.size - 11) {
            val run = segs.subList(start, start + 11)
            val vals = FloatArray(11) { run[it][2] }
            val up = (1 until 11).all { vals[it] > vals[it - 1] }
            val down = (1 until 11).all { vals[it] < vals[it - 1] }
            if (!up && !down) continue
            // innere 9 Stufen ähnlich breit (Randstufen dürfen angeschnitten sein)
            val widths = (1 until 10).map { run[it][1] - run[it][0] }
            if (widths.max() > widths.min() * 1.7f) continue
            val width = run.last()[1] - run.first()[0]
            val levels = if (up) vals else vals.reversedArray()
            if (best == null || width > best.second) best = levels to width
        }
        return best
    }

    private fun fit(levels: FloatArray): Wedge {
        val x = WEDGE_LIGHT
        val n = levels.size
        val mx = x.average().toFloat()
        val my = levels.average().toFloat()
        var sxy = 0f
        var sxx = 0f
        var syy = 0f
        for (i in 0 until n) {
            sxy += (x[i] - mx) * (levels[i] - my)
            sxx += (x[i] - mx) * (x[i] - mx)
            syy += (levels[i] - my) * (levels[i] - my)
        }
        val slope = sxy / sxx
        val offset = my - slope * mx
        val r2 = if (syy > 0f) (sxy * sxy) / (sxx * syy) else 0f
        val flare = (offset / max(1e-6f, offset + slope)).coerceIn(-1f, 1f)
        val rel = FloatArray(n) { levels[it] / max(1e-6f, levels.max()) }
        return Wedge(rel, r2, flare)
    }

    // ───────────────────────── Profil ─────────────────────────

    /**
     * Schärfeprofil an einer Sensorposition (0..1): Mittel der Messungen, gewichtet nach Nähe
     * (inverse Abstandsquadrate). Gibt Helligkeits-σ (x, y) in Sensorpixeln zurück.
     */
    fun sigmaAt(samples: List<Sample>, nx: Float, ny: Float): FloatArray {
        if (samples.isEmpty()) return floatArrayOf(Float.NaN, Float.NaN)
        var wx = 0f
        var wy = 0f
        var ws = 0f
        for (s in samples) {
            val d2 = (s.nx - nx) * (s.nx - nx) + (s.ny - ny) * (s.ny - ny)
            val l = s.luma()
            if (d2 < 1e-6f) return l
            val wgt = 1f / d2
            wx += l[0] * wgt
            wy += l[1] * wgt
            ws += wgt
        }
        return floatArrayOf(wx / ws, wy / ws)
    }

    /** Farbunterschied der Schärfe: größte Abweichung von R oder B gegenüber G (relativ). */
    fun colorSpread(s: Sample): Float {
        val g = (s.sig[1][0] + s.sig[1][1]) / 2f
        val r = (s.sig[0][0] + s.sig[0][1]) / 2f
        val b = (s.sig[2][0] + s.sig[2][1]) / 2f
        return max(abs(r - g), abs(b - g)) / max(1e-3f, g)
    }
}
