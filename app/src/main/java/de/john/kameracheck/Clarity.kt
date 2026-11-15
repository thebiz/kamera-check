package de.john.kameracheck

import android.graphics.Bitmap
import kotlin.math.max
import kotlin.math.min

/**
 * „Klarheit“ wie in Galerie-Apps (Johns Test 15.11.: hat das Ergebnis sichtbar verbessert) – selbst gebaut:
 *
 * Grundbild = kantenschonend stark weichgezeichnet (Guided Filter, He et al. 2010, schnelle Fassung auf
 * Viertel-Auflösung). Detail = Bild − Grundbild. Ausgabe = Bild + Stärke · Detail, vor allem in den Mitteltönen.
 * Der Guided Filter folgt kräftigen Kanten (dort ist Detail ≈ 0 → keine hellen Säume) und glättet nur
 * mittelstarke Struktur – genau die wird angehoben. Nur Helligkeit, Farben bleiben.
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
        /** Ab dieser Helligkeitsschwankung (Varianz, 0..1) gilt Struktur als kräftige Kante und bleibt unberührt */
        private const val EPS = 0.01f
        private const val SUB = 4

        fun detailLayer(l: FloatArray, w: Int, h: Int): FloatArray {
            // Radius: großflächig wie in Galerie-Apps, an die Bildgröße angepasst
            val sw = max(1, w / SUB)
            val sh = max(1, h / SUB)
            val r = max(2, min(sw, sh) / 60)
            val s = FloatArray(sw * sh)
            for (y in 0 until sh) for (x in 0 until sw) {
                var acc = 0f
                var n = 0
                for (j in y * SUB until min(h, y * SUB + SUB)) for (i in x * SUB until min(w, x * SUB + SUB)) {
                    acc += l[j * w + i]; n++
                }
                s[y * sw + x] = acc / max(1, n)
            }
            val mI = box(s, sw, sh, r)
            val mII = box(FloatArray(s.size) { s[it] * s[it] }, sw, sh, r)
            val a = FloatArray(s.size)
            val b = FloatArray(s.size)
            for (i in s.indices) {
                val v = max(0f, mII[i] - mI[i] * mI[i])
                a[i] = v / (v + EPS)
                b[i] = mI[i] - a[i] * mI[i]
            }
            val ma = box(a, sw, sh, r)
            val mb = box(b, sw, sh, r)
            // hochrechnen (bilinear) und Grundbild = A·I + B
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
                    val i00 = y0 * sw + x0; val i10 = y0 * sw + x1; val i01 = y1 * sw + x0; val i11 = y1 * sw + x1
                    val aa = (ma[i00] * (1 - ax) + ma[i10] * ax) * (1 - ay) + (ma[i01] * (1 - ax) + ma[i11] * ax) * ay
                    val bb = (mb[i00] * (1 - ax) + mb[i10] * ax) * (1 - ay) + (mb[i01] * (1 - ax) + mb[i11] * ax) * ay
                    val k = y * w + x
                    d[k] = l[k] - (aa * l[k] + bb)
                }
            }
            return d
        }

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
