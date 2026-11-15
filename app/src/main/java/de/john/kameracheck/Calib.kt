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
        /** Bestimmtheitsmaß der Geraden Messwert ↔ Licht (1 = perfekt linear) */
        val r2: Float,
        /** Rest-Helligkeit bei Schwarz relativ zu Weiß (Streulicht, Spiegelung, Schwarzwert-Fehler) */
        val flare: Float,
        /** Anzahl Stufen, die in die Gerade eingingen (überbelichtete Stufen zählen nicht) */
        val used: Int,
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

    /**
     * y = lineares Helligkeitsbild im Messrahmen. Sucht die 11 Stufen waagerecht oder senkrecht.
     * Robust gegen Schräglage (schmale Streifen statt ganzer Breite), Perspektive (ungleich breite
     * Stufen) und Überbelichtung (oben verschmolzene, abgeschnittene Stufen werden nicht gewertet).
     */
    fun analyzeWedge(y: FloatArray, w: Int, h: Int): Wedge? {
        var best: Pair<Wedge, Float>? = null
        for (vertical in listOf(false, true)) {
            val len = if (vertical) h else w
            val across = if (vertical) w else h
            val thick = max(4, across / 12)
            for (pos in listOf(0.5f, 0.38f, 0.62f, 0.27f, 0.73f)) {
                val c0 = ((across * pos).toInt() - thick / 2).coerceIn(0, across - thick)
                val prof = FloatArray(len) { i ->
                    var s = 0f
                    for (k in c0 until c0 + thick) s += if (vertical) y[i * w + k] else y[k * w + i]
                    s / thick
                }
                val r = findSteps(prof) ?: continue
                if (best == null || r.second > best.second) best = r
            }
        }
        return best?.first
    }

    /** Sucht die Stufentreppe in einem Helligkeitsverlauf. Liefert Ergebnis + Güte (erkannte Breite). */
    private fun findSteps(p0: FloatArray): Pair<Wedge, Float>? {
        val n = p0.size
        if (n < 60) return null
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
        val thr = 0.3f * range / 10f
        val minGap = max(3, n / 60)
        val edges = ArrayList<Int>()
        for (i in 1 until n - 1) {
            if (d[i] > thr && d[i] >= d[i - 1] && d[i] >= d[i + 1]) {
                if (edges.isNotEmpty() && i - edges.last() < minGap) {
                    if (d[i] > d[edges.last()]) edges[edges.size - 1] = i
                } else edges += i
            }
        }
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
        if (segs.size < 7) return null

        var best: Pair<Wedge, Float>? = null
        // Läufe von 11 bis 7 Abschnitten: bei Überbelichtung verschmelzen die hellsten Stufen zu einem
        for (len in 11 downTo 7) {
            for (start in 0..segs.size - len) {
                val run0 = segs.subList(start, start + len)
                val asc = (1 until len).all { run0[it][2] > run0[it - 1][2] }
                val desc = (1 until len).all { run0[it][2] < run0[it - 1][2] }
                if (!asc && !desc) continue
                val run = if (asc) run0.toList() else run0.reversed()
                // innere Abschnitte = saubere einzelne Stufen, ähnlich breit (Perspektive bis Faktor 2,2)
                val innerW = (1 until len - 1).map { run[it][1] - run[it][0] }
                if (innerW.max() > innerW.min() * 2.2f) continue
                val medW = innerW.sorted()[innerW.size / 2]
                val needed = 11 - (len - 2)          // Stufen, die auf die beiden Enden entfallen
                val brightBands = needed - 1           // dunkles Ende = Stufe 0, Rest steckt im hellen Ende
                val brightW = run.last()[1] - run.last()[0]
                if (brightBands > 1 && brightW < (brightBands - 0.6f) * medW) continue
                // Messpunkte: innere Stufen sicher, Enden nur wenn nicht verschmolzen/zu breit
                val xs = ArrayList<Float>()
                val ys = ArrayList<Float>()
                val darkW = run.first()[1] - run.first()[0]
                if (darkW < 1.5f * medW) { xs += WEDGE_LIGHT[0]; ys += run.first()[2] }
                for (i in 1 until len - 1) { xs += WEDGE_LIGHT[i]; ys += run[i][2] }
                if (brightBands == 1 && brightW < 1.5f * medW) { xs += WEDGE_LIGHT[len - 1]; ys += run.last()[2] }
                // oben abgeschnittene Stufen (kaum noch Anstieg) weglassen
                while (ys.size > 6) {
                    val steps = (1 until ys.size).map { ys[it] - ys[it - 1] }.sorted()
                    val med = steps[steps.size / 2]
                    if (ys[ys.size - 1] - ys[ys.size - 2] < 0.4f * med) {
                        xs.removeAt(xs.size - 1); ys.removeAt(ys.size - 1)
                    } else break
                }
                if (ys.size < 6) continue
                val wdg = fit(xs.toFloatArray(), ys.toFloatArray())
                if (wdg.r2 < 0.97f) continue
                val q = len * 1000f + (run.last()[1] - run.first()[0])
                if (best == null || q > best.second) best = wdg to q
            }
            if (best != null) break
        }
        return best
    }

    private fun fit(x: FloatArray, levels: FloatArray): Wedge {
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
        return Wedge(r2, flare, n)
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
