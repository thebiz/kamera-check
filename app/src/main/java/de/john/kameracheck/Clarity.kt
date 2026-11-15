package de.john.kameracheck

import android.graphics.Bitmap
import kotlin.math.max
import kotlin.math.min

/**
 * „Klarheit“ wie in Galerie-Apps (Johns Test 15.11.: hat das Ergebnis sichtbar verbessert) – selbst gebaut:
 *
 * Grundbild = großflächig weichgezeichnet. Detail = Bild − Grundbild, weich gedeckelt (große Abweichungen an
 * Kanten zählen kaum, aufhellen noch weniger als abdunkeln). Ausgabe = Bild + Stärke · Detail, vor allem in den
 * Mitteltönen. Nur Helligkeit, Farben bleiben.
 * (1.16 nutzte einen Guided Filter: der ließ um Kanten einen glatten, hellen Streifen ohne Struktur – Johns Screenshot.)
 */
class Clarity(src: Bitmap) {
    val w = src.width
    val h = src.height
    private val px = IntArray(w * h).also { src.getPixels(it, 0, w, 0, 0, w, h) }
    private val lum = FloatArray(w * h)
    private val detail: FloatArray

    init {
        for (i in px.indices) {
            val p = px[i]
            lum[i] = (((p shr 16) and 255) * 0.299f + ((p shr 8) and 255) * 0.587f + (p and 255) * 0.114f) / 255f
        }
        detail = detailLayer(lum, w, h)
    }

    /** Neues Bild mit Klarheit (0 = aus, 1 = kräftig) in [out] schreiben. */
    fun apply(amount: Float, out: Bitmap) {
        val k = amount * MAX_GAIN
        val res = IntArray(w * h)
        for (i in res.indices) {
            val p = px[i]
            val l = lum[i]
            val t = 2f * l - 1f
            val mid = 0.25f + 0.75f * (1f - t * t)          // Mitteltöne am stärksten, Lichter/Tiefen sanft
            val dv = k * detail[i] * mid * 255f
            val r = (((p shr 16) and 255) + dv).toInt().coerceIn(0, 255)
            val g = (((p shr 8) and 255) + dv).toInt().coerceIn(0, 255)
            val b = ((p and 255) + dv).toInt().coerceIn(0, 255)
            res[i] = (0xFF shl 24) or (r shl 16) or (g shl 8) or b
        }
        out.setPixels(res, 0, w, 0, 0, w, h)
    }

    companion object {
        /** Verstärkung des Details bei voller Stärke */
        const val MAX_GAIN = 1.5f
        private const val SUB = 4

        fun detailLayer(l: FloatArray, w: Int, h: Int): FloatArray {
            // Grundbild: großflächig weichgezeichnet (dreimal Kastenfilter ≈ Gauß) auf Viertel-Auflösung
            val sw = max(1, w / SUB)
            val sh = max(1, h / SUB)
            val r = max(2, min(sw, sh) / 120)
            val s = FloatArray(sw * sh)
            for (y in 0 until sh) for (x in 0 until sw) {
                var acc = 0f
                var n = 0
                for (j in y * SUB until min(h, y * SUB + SUB)) for (i in x * SUB until min(w, x * SUB + SUB)) {
                    acc += l[j * w + i]; n++
                }
                s[y * sw + x] = acc / max(1, n)
            }
            val base = box(box(box(s, sw, sh, r), sw, sh, r), sw, sh, r)
            val d = FloatArray(w * h)
            for (y in 0 until h) {
                val fy = ((y + 0.5f) / SUB - 0.5f).coerceIn(0f, sh - 1f)
                val y0 = fy.toInt()
                val y1 = min(sh - 1, y0 + 1)
                val ay = fy - y0
                for (x in 0 until w) {
                    val fx = ((x + 0.5f) / SUB - 0.5f).coerceIn(0f, sw - 1f)
                    val x0 = fx.toInt()
                    val x1 = min(sw - 1, x0 + 1)
                    val ax = fx - x0
                    val bb = (base[y0 * sw + x0] * (1 - ax) + base[y0 * sw + x1] * ax) * (1 - ay) +
                        (base[y1 * sw + x0] * (1 - ax) + base[y1 * sw + x1] * ax) * ay
                    val k = y * w + x
                    d[k] = soft(l[k] - bb)
                }
            }
            return d
        }

        /**
         * Kantenschutz (1.16.1, nach Johns Screenshot): Feine Struktur (kleine Abweichung) wird voll verstärkt –
         * gleichmäßig bis direkt an die Kante, kein glatter Streifen mehr. Große Abweichungen (= kräftige Kanten)
         * werden weich gedeckelt, nach oben (aufhellen) stärker als nach unten: keine hellen Säume.
         */
        private fun soft(v: Float): Float =
            if (v >= 0f) T_UP * kotlin.math.tanh(v / T_UP) else T_DOWN * kotlin.math.tanh(v / T_DOWN)

        /** Deckel in Helligkeit 0..1: aufhellen höchstens ≈ 6, abdunkeln ≈ 13 von 255 (vor der Verstärkung) */
        private const val T_UP = 0.024f
        private const val T_DOWN = 0.05f

        /** Mittelwert im Quadrat (2r+1)², getrennt in x und y, am Rand verkürzt. */
        private fun box(src: FloatArray, w: Int, h: Int, r: Int): FloatArray {
            val tmp = FloatArray(src.size)
            val out = FloatArray(src.size)
            for (y in 0 until h) {
                val row = y * w
                var acc = 0f
                var lo = 0
                var hi = -1
                for (x in 0 until w) {
                    val nh = min(w - 1, x + r)
                    val nl = max(0, x - r)
                    while (hi < nh) { hi++; acc += src[row + hi] }
                    while (lo < nl) { acc -= src[row + lo]; lo++ }
                    tmp[row + x] = acc / (hi - lo + 1)
                }
            }
            for (x in 0 until w) {
                var acc = 0f
                var lo = 0
                var hi = -1
                for (y in 0 until h) {
                    val nh = min(h - 1, y + r)
                    val nl = max(0, y - r)
                    while (hi < nh) { hi++; acc += tmp[hi * w + x] }
                    while (lo < nl) { acc -= tmp[lo * w + x]; lo++ }
                    out[y * w + x] = acc / (hi - lo + 1)
                }
            }
            return out
        }
    }
}
