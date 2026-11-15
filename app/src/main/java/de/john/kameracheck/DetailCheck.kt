package de.john.kameracheck

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Rect
import android.net.Uri
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

/**
 * „Echte 50 MP oder nur hochgerechnet?“ – prüft ein Foto in Originalauflösung.
 *
 * Idee: Ein hochgerechnetes 12-MP-Bild enthält keine Struktur, die feiner ist als das 12-MP-Raster.
 * Verkleinert man es auf die Hälfte und vergrößert wieder, bleibt es fast gleich. Echte 50 MP
 * verlieren dabei sichtbar Detail. Gemessen wird die Feinstruktur-Energie
 *   E = mittleres (Bild − halb verkleinert und wieder vergrößert)²
 * und verglichen mit dem Wert, den ein wirklich hochgerechnetes Bild an derselben Stelle hätte.
 * Getrennt nach Kanten (echtes Detail) und glatten Flächen (dort wäre es nur Rauschen).
 *
 * Dazu ein Vergleichsbild: die drei strukturreichsten Stellen 1:1 neben ihrer 12-MP-Fassung.
 */
object DetailCheck {

    class Result(val text: String, val sheet: Bitmap)

    private const val TILE = 256

    fun run(ctx: Context, uri: Uri): Result {
        val inf = PhotoIo.info(ctx, uri) ?: throw IllegalStateException("Foto lässt sich nicht öffnen")
        val w = inf.uprightW
        val h = inf.uprightH
        // Mitte 3072 × 3072 (oder kleiner) in voller Auflösung lesen
        val cw = min(w, 3072)
        val ch = min(h, 3072)
        val n = floatArrayOf((w - cw) / 2f / w, (h - ch) / 2f / h, (w + cw) / 2f / w, (h + ch) / 2f / h)
        val (bmp, _) = PhotoIo.crop(ctx, uri, n, 0) ?: throw IllegalStateException("Ausschnitt lässt sich nicht lesen")
        val bw = bmp.width / 2 * 2
        val bh = bmp.height / 2 * 2
        val px = IntArray(bmp.width * bmp.height).also { bmp.getPixels(it, 0, bmp.width, 0, 0, bmp.width, bmp.height) }
        val y = FloatArray(bw * bh) { i ->
            val p = px[(i / bw) * bmp.width + i % bw]
            ((p shr 16) and 255) * 0.299f + ((p shr 8) and 255) * 0.587f + (p and 255) * 0.114f
        }
        val r1 = residual(y, bw, bh)                       // Bild selbst
        val up = upsample(downsample(y, bw, bh), bw / 2, bh / 2)
        val r0 = residual(up, bw, bh)                      // so viel hätte ein hochgerechnetes Bild

        // Kacheln nach Struktur ordnen: Kanten (oberstes Viertel) und glatte Flächen (unterstes Viertel)
        val tilesX = bw / TILE
        val tilesY = bh / TILE
        class T(val x: Int, val y: Int, val grad: Double, val e1: Double, val e0: Double)
        val tiles = ArrayList<T>()
        for (ty in 0 until tilesY) for (tx in 0 until tilesX) {
            var g = 0.0
            var e1 = 0.0
            var e0 = 0.0
            for (j in ty * TILE + 1 until (ty + 1) * TILE - 1) for (i in tx * TILE + 1 until (tx + 1) * TILE - 1) {
                val k = j * bw + i
                g += kotlin.math.abs(y[k + 1] - y[k - 1]) + kotlin.math.abs(y[k + bw] - y[k - bw])
                e1 += r1[k] * r1[k]
                e0 += r0[k] * r0[k]
            }
            tiles += T(tx, ty, g, e1, e0)
        }
        tiles.sortByDescending { it.grad }
        val q = max(1, tiles.size / 4)
        val edge = tiles.take(q)
        val flat = tiles.takeLast(q)
        val ratioEdge = edge.sumOf { it.e1 } / max(1e-9, edge.sumOf { it.e0 })
        val ratioFlat = flat.sumOf { it.e1 } / max(1e-9, flat.sumOf { it.e0 })
        val rmsEdge = sqrt(edge.sumOf { it.e1 } / (edge.size * TILE * TILE.toDouble()))
        val rmsFlat = sqrt(flat.sumOf { it.e1 } / (flat.size * TILE * TILE.toDouble()))

        val verdict = when {
            ratioEdge < 2.0 -> "→ praktisch nur hochgerechnet: kaum Struktur feiner als 12 MP"
            rmsEdge < 1.5 * rmsFlat -> "→ Feinstruktur vorhanden, aber an Kanten kaum mehr als in glatten Flächen – vor allem Rauschen/Körnung"
            ratioEdge < 3.5 -> "→ etwas echtes Mehr-Detail an Kanten, aber deutlich weniger als echte 50 MP"
            else -> "→ deutliches echtes Mehr-Detail an Kanten"
        }
        val text = buildString {
            appendLine("Foto: ${w} × ${h} (${"%.1f".format(w.toLong() * h / 1e6)} MP), geprüft: Mitte ${bw} × ${bh}")
            appendLine("Feinstruktur jenseits 12 MP (≈ 1,7 = wie hochgerechnet, echte 50 MP ≈ 5):")
            appendLine("  an Kanten:        ${"%.1f".format(ratioEdge)}×  (Stärke ${"%.1f".format(rmsEdge)} Helligkeitsstufen)")
            appendLine("  in glatten Flächen: ${"%.1f".format(ratioFlat)}×  (Stärke ${"%.1f".format(rmsFlat)} – Rauschen)")
            append(verdict)
        }

        // Vergleichsbild: 3 strukturreichste Stellen, je 1:1 und als 12-MP-Fassung, 2× vergrößert
        val crop = 300
        val scale = 2
        val pick = tiles.take(3)
        val labelH = 46
        val cellW = crop * scale
        val out = Bitmap.createBitmap(cellW * 2 + 30, pick.size * (cellW + labelH + 10) + 10, Bitmap.Config.ARGB_8888)
        val cv = Canvas(out)
        cv.drawColor(0xFF111111.toInt())
        val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xFFFFFFFF.toInt(); textSize = 28f }
        for ((r, t) in pick.withIndex()) {
            val cx = min(bw - crop, max(0, t.x * TILE + TILE / 2 - crop / 2))
            val cy = min(bh - crop, max(0, t.y * TILE + TILE / 2 - crop / 2))
            val orig = Bitmap.createBitmap(bmp, cx, cy, crop, crop)
            val small = Bitmap.createScaledBitmap(orig, crop / 2, crop / 2, true)
            val back = Bitmap.createScaledBitmap(small, crop, crop, true)
            val top = 10 + r * (cellW + labelH + 10)
            cv.drawText("Original 1:1", 10f, top + 32f, paint)
            cv.drawText("auf 12 MP verkleinert", (cellW + 20).toFloat(), top + 32f, paint)
            cv.drawBitmap(orig, Rect(0, 0, crop, crop), Rect(10, top + labelH, 10 + cellW, top + labelH + cellW), null)
            cv.drawBitmap(back, Rect(0, 0, crop, crop), Rect(cellW + 20, top + labelH, cellW * 2 + 20, top + labelH + cellW), null)
            orig.recycle(); small.recycle(); back.recycle()
        }
        bmp.recycle()
        return Result(text, out)
    }

    /** Bild minus (halb verkleinert und wieder vergrößert). */
    private fun residual(y: FloatArray, w: Int, h: Int): FloatArray {
        val u = upsample(downsample(y, w, h), w / 2, h / 2)
        return FloatArray(w * h) { y[it] - u[it] }
    }

    private fun downsample(y: FloatArray, w: Int, h: Int): FloatArray {
        val dw = w / 2
        val dh = h / 2
        return FloatArray(dw * dh) { i ->
            val x = (i % dw) * 2
            val yy = (i / dw) * 2
            (y[yy * w + x] + y[yy * w + x + 1] + y[(yy + 1) * w + x] + y[(yy + 1) * w + x + 1]) / 4f
        }
    }

    /** Bilineare Verdopplung (Pixelmitten korrekt ausgerichtet). */
    private fun upsample(s: FloatArray, sw: Int, sh: Int): FloatArray {
        val w = sw * 2
        val h = sh * 2
        return FloatArray(w * h) { i ->
            val fx = ((i % w) + 0.5f) / 2f - 0.5f
            val fy = ((i / w) + 0.5f) / 2f - 0.5f
            val x0 = fx.toInt().coerceIn(0, sw - 1)
            val y0 = fy.toInt().coerceIn(0, sh - 1)
            val x1 = min(sw - 1, x0 + 1)
            val y1 = min(sh - 1, y0 + 1)
            val ax = (fx - x0).coerceIn(0f, 1f)
            val ay = (fy - y0).coerceIn(0f, 1f)
            (s[y0 * sw + x0] * (1 - ax) + s[y0 * sw + x1] * ax) * (1 - ay) + (s[y1 * sw + x0] * (1 - ax) + s[y1 * sw + x1] * ax) * ay
        }
    }
}
