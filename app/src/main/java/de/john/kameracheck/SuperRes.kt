package de.john.kameracheck

import android.graphics.Bitmap
import android.media.Image
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.exp
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * Ausschnitt auf dem Sensor.
 * cx/cy/cw/ch = der gezoomte Bildausschnitt, x0/y0/rw/rh = Ausschnitt plus Rand fürs Ausrichten.
 */
class Region(
    val sw: Int, val sh: Int,
    val x0: Int, val y0: Int, val rw: Int, val rh: Int,
    val cx: Int, val cy: Int, val cw: Int, val ch: Int,
) {
    companion object {
        fun compute(sw: Int, sh: Int, zoom: Float): Region {
            val cw = ((sw / zoom).roundToInt() / 2 * 2).coerceIn(64, sw)
            val ch = ((sh / zoom).roundToInt() / 2 * 2).coerceIn(64, sh)
            val cx = (sw - cw) / 4 * 2
            val cy = (sh - ch) / 4 * 2
            val m = 64
            val x0 = max(0, cx - m) / 2 * 2
            val y0 = max(0, cy - m) / 2 * 2
            val x1 = min(sw, cx + cw + m)
            val y1 = min(sh, cy + ch + m)
            return Region(sw, sh, x0, y0, (x1 - x0) / 2 * 2, (y1 - y0) / 2 * 2, cx, cy, cw, ch)
        }
    }
}

class Frame(val y: ByteArray, val u: ByteArray, val v: ByteArray)

class Result(val merged: Bitmap, val single: Bitmap, val used: Int, val total: Int)

object SuperRes {

    // ───────────────────────── Kopieren aus dem Kamerabild ─────────────────────────

    fun copyFrame(img: Image, r: Region): Frame {
        val y = ByteArray(r.rw * r.rh)
        copyPlane(img.planes[0], r.x0, r.y0, r.rw, r.rh, y)
        val cw = r.rw / 2
        val ch = r.rh / 2
        val u = ByteArray(cw * ch)
        val v = ByteArray(cw * ch)
        copyPlane(img.planes[1], r.x0 / 2, r.y0 / 2, cw, ch, u)
        copyPlane(img.planes[2], r.x0 / 2, r.y0 / 2, cw, ch, v)
        return Frame(y, u, v)
    }

    private fun copyPlane(p: Image.Plane, x0: Int, y0: Int, w: Int, h: Int, dst: ByteArray) {
        val buf = p.buffer
        val rs = p.rowStride
        val ps = p.pixelStride
        if (ps == 1) {
            for (row in 0 until h) {
                buf.position((y0 + row) * rs + x0)
                buf.get(dst, row * w, w)
            }
        } else {
            val need = (w - 1) * ps + 1
            val tmp = ByteArray(need)
            for (row in 0 until h) {
                buf.position((y0 + row) * rs + x0 * ps)
                buf.get(tmp, 0, need)
                var o = row * w
                var i = 0
                for (c in 0 until w) {
                    dst[o++] = tmp[i]
                    i += ps
                }
            }
        }
    }

    // ───────────────────────── Verrechnen ─────────────────────────

    /** Robustheit: Pixel, die stark vom Referenzbild abweichen (Bewegung), zählen weniger. */
    private val ROBUST = FloatArray(256) { d -> exp(-(d / 35f) * (d / 35f)) }

    fun process(
        frames: MutableList<Frame?>,
        r: Region,
        zoom: Float,
        progress: (String) -> Unit,
    ): Result {
        val w = r.rw
        val h = r.rh
        val total = frames.size

        progress("Suche schärfstes Bild …")
        val sharp = FloatArray(total) { sharpness(frames[it]!!.y, w, h) }
        var refIdx = 0
        for (i in 1 until total) if (sharp[i] > sharp[refIdx]) refIdx = i
        val ref = frames[refIdx]!!
        val refSmall = downscale4(ref.y, w, h)

        // Verschiebung jedes Bildes gegenüber der Referenz bestimmen
        val shifts = ArrayList<FloatArray>()
        var n = 0
        for (i in 0 until total) {
            if (i == refIdx) continue
            n++
            progress("Richte aus … $n/${total - 1}")
            val s = align(ref.y, refSmall, frames[i]!!.y, w, h)
            shifts.add(floatArrayOf(s[0], s[1], s[2], i.toFloat()))
        }
        val costs = shifts.map { it[2] }.sorted()
        val median = if (costs.isEmpty()) 0f else costs[costs.size / 2]
        val good = shifts.filter { it[2] <= median * 1.6f + 1f }

        // Auf feineres Raster verteilen
        val u = if (zoom >= 2f) 2 else 1
        val wo = r.cw * u
        val ho = r.ch * u
        val acc = FloatArray(wo * ho)
        val wt = FloatArray(wo * ho)

        progress("Verrechne 1/${good.size + 1} …")
        splat(ref.y, 0f, 0f, ref.y, r, u, acc, wt, wo, ho)
        good.forEachIndexed { k, s ->
            progress("Verrechne ${k + 2}/${good.size + 1} …")
            val idx = s[3].toInt()
            splat(frames[idx]!!.y, s[0], s[1], ref.y, r, u, acc, wt, wo, ho)
            frames[idx] = null // Speicher freigeben
        }

        // Ergebnis-Helligkeit in acc, Einzelbild-Helligkeit in wt
        progress("Setze zusammen …")
        val prior = 0.02f
        for (oy in 0 until ho) {
            val ly = (r.cy + (oy + 0.5f) / u - 0.5f) - r.y0
            for (ox in 0 until wo) {
                val lx = (r.cx + (ox + 0.5f) / u - 0.5f) - r.x0
                val refv = bilinear(ref.y, w, h, lx, ly)
                val k = oy * wo + ox
                acc[k] = (acc[k] + prior * refv) / (wt[k] + prior)
                wt[k] = refv
            }
        }

        progress("Schärfe nach …")
        val tmp = FloatArray(wo * ho)
        val sigma = if (u == 2) 1.4f else 1.0f
        val amount = if (u == 2) 0.8f else 0.5f
        sharpen(acc, tmp, wo, ho, sigma, amount)
        sharpen(wt, tmp, wo, ho, sigma, amount)

        progress("Erzeuge Bilder …")
        val px = IntArray(wo * ho)
        val merged = toBitmap(acc, ref, r, u, wo, ho, px)
        val single = toBitmap(wt, ref, r, u, wo, ho, px)

        return Result(upscale(merged), upscale(single), good.size + 1, total)
    }

    private fun sharpness(y: ByteArray, w: Int, h: Int): Float {
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

    private fun downscale4(y: ByteArray, w: Int, h: Int): IntArray {
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

    /** Liefert dx, dy, Fehler – so dass Bild(p) ≈ Referenz(p + d). */
    private fun align(refY: ByteArray, refS: IntArray, fy: ByteArray, w: Int, h: Int): FloatArray {
        val dw = w / 4
        val dh = h / 4
        val fs = downscale4(fy, w, h)
        val R = 10

        // Grob: auf Viertel-Auflösung
        var bdx = 0
        var bdy = 0
        val wx0 = max(R + 1, dw / 2 - 160)
        val wx1 = min(dw - R - 1, dw / 2 + 160)
        val wy0 = max(R + 1, dh / 2 - 120)
        val wy1 = min(dh - R - 1, dh / 2 + 120)
        if (wx1 > wx0 && wy1 > wy0) {
            var best = Long.MAX_VALUE
            for (ddy in -R..R) for (ddx in -R..R) {
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
        val m = R * 4 + F + 2
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

    private fun parabola(l: Long, c: Long, r: Long): Float {
        val den = (l - 2 * c + r).toFloat()
        if (den <= 0f) return 0f
        return ((l - r).toFloat() / (2f * den)).coerceIn(-0.5f, 0.5f)
    }

    private fun splat(
        fy: ByteArray, dx: Float, dy: Float, refY: ByteArray,
        r: Region, u: Int, acc: FloatArray, wt: FloatArray, wo: Int, ho: Int,
    ) {
        val w = r.rw
        val h = r.rh
        val uf = u.toFloat()
        for (j in 0 until h) {
            val ly = j + dy
            if (ly < 0f || ly >= h - 1) continue
            val oy = (r.y0 + ly - r.cy + 0.5f) * uf - 0.5f
            if (oy <= -1f || oy >= ho) continue
            val iy = floor(oy).toInt()
            val fyw = oy - iy
            val ry = ly.toInt()
            val rfy = ly - ry
            val rRow0 = ry * w
            val rRow1 = rRow0 + w
            val row = j * w

            var lx = dx
            var ox = (r.x0 + dx - r.cx + 0.5f) * uf - 0.5f
            for (i in 0 until w) {
                if (lx >= 0f && lx < w - 1 && ox > -1f && ox < wo) {
                    val v = (fy[row + i].toInt() and 255).toFloat()
                    val rx = lx.toInt()
                    val rfx = lx - rx
                    val p00 = (refY[rRow0 + rx].toInt() and 255).toFloat()
                    val p10 = (refY[rRow0 + rx + 1].toInt() and 255).toFloat()
                    val p01 = (refY[rRow1 + rx].toInt() and 255).toFloat()
                    val p11 = (refY[rRow1 + rx + 1].toInt() and 255).toFloat()
                    val pred = (p00 * (1 - rfx) + p10 * rfx) * (1 - rfy) + (p01 * (1 - rfx) + p11 * rfx) * rfy
                    val rob = ROBUST[min(255, abs(v - pred).toInt())]

                    val ix = floor(ox).toInt()
                    val fxw = ox - ix
                    val w00 = (1 - fxw) * (1 - fyw) * rob
                    val w10 = fxw * (1 - fyw) * rob
                    val w01 = (1 - fxw) * fyw * rob
                    val w11 = fxw * fyw * rob
                    if (iy >= 0) {
                        val b = iy * wo
                        if (ix >= 0) { acc[b + ix] += w00 * v; wt[b + ix] += w00 }
                        if (ix + 1 < wo) { acc[b + ix + 1] += w10 * v; wt[b + ix + 1] += w10 }
                    }
                    if (iy + 1 < ho) {
                        val b = (iy + 1) * wo
                        if (ix >= 0) { acc[b + ix] += w01 * v; wt[b + ix] += w01 }
                        if (ix + 1 < wo) { acc[b + ix + 1] += w11 * v; wt[b + ix + 1] += w11 }
                    }
                }
                lx += 1f
                ox += uf
            }
        }
    }

    private fun bilinear(y: ByteArray, w: Int, h: Int, fx: Float, fy: Float): Float {
        val x = fx.coerceIn(0f, w - 1.001f)
        val yy = fy.coerceIn(0f, h - 1.001f)
        val ix = x.toInt()
        val iy = yy.toInt()
        val ax = x - ix
        val ay = yy - iy
        val i = iy * w + ix
        val p00 = (y[i].toInt() and 255).toFloat()
        val p10 = (y[i + 1].toInt() and 255).toFloat()
        val p01 = (y[i + w].toInt() and 255).toFloat()
        val p11 = (y[i + w + 1].toInt() and 255).toFloat()
        return (p00 * (1 - ax) + p10 * ax) * (1 - ay) + (p01 * (1 - ax) + p11 * ax) * ay
    }

    /** Unscharf maskieren, in-place. */
    private fun sharpen(img: FloatArray, tmp: FloatArray, w: Int, h: Int, sigma: Float, amount: Float) {
        val rad = ceil(3 * sigma).toInt()
        val k = FloatArray(2 * rad + 1) { i -> val d = (i - rad).toFloat(); exp(-d * d / (2 * sigma * sigma)) }
        val sum = k.sum()
        for (i in k.indices) k[i] /= sum

        for (y in 0 until h) {
            val row = y * w
            for (x in 0 until w) {
                var s = 0f
                for (t in -rad..rad) {
                    val xx = (x + t).coerceIn(0, w - 1)
                    s += img[row + xx] * k[t + rad]
                }
                tmp[row + x] = s
            }
        }
        for (y in 0 until h) {
            for (x in 0 until w) {
                var s = 0f
                for (t in -rad..rad) {
                    val yy = (y + t).coerceIn(0, h - 1)
                    s += tmp[yy * w + x] * k[t + rad]
                }
                val i = y * w + x
                img[i] = img[i] + amount * (img[i] - s)
            }
        }
    }

    private fun toBitmap(yv: FloatArray, ref: Frame, r: Region, u: Int, wo: Int, ho: Int, px: IntArray): Bitmap {
        val cw = r.rw / 2
        val ch = r.rh / 2
        val ccx = FloatArray(wo) { ox ->
            val xs = r.cx + (ox + 0.5f) / u - 0.5f
            ((xs - r.x0 - 0.5f) / 2f).coerceIn(0f, cw - 1.001f)
        }
        for (oy in 0 until ho) {
            val ys = r.cy + (oy + 0.5f) / u - 0.5f
            val cy = ((ys - r.y0 - 0.5f) / 2f).coerceIn(0f, ch - 1.001f)
            val iy = cy.toInt()
            val ay = cy - iy
            for (ox in 0 until wo) {
                val cx = ccx[ox]
                val ix = cx.toInt()
                val ax = cx - ix
                val i = iy * cw + ix
                val uu = lerp2(ref.u, i, cw, ax, ay) - 128f
                val vv = lerp2(ref.v, i, cw, ax, ay) - 128f
                val k = oy * wo + ox
                val yy = yv[k]
                val rr = clamp(yy + 1.402f * vv)
                val gg = clamp(yy - 0.344136f * uu - 0.714136f * vv)
                val bb = clamp(yy + 1.772f * uu)
                px[k] = (0xFF shl 24) or (rr shl 16) or (gg shl 8) or bb
            }
        }
        return Bitmap.createBitmap(px, wo, ho, Bitmap.Config.ARGB_8888)
    }

    private fun lerp2(a: ByteArray, i: Int, w: Int, ax: Float, ay: Float): Float {
        val p00 = (a[i].toInt() and 255).toFloat()
        val p10 = (a[i + 1].toInt() and 255).toFloat()
        val p01 = (a[i + w].toInt() and 255).toFloat()
        val p11 = (a[i + w + 1].toInt() and 255).toFloat()
        return (p00 * (1 - ax) + p10 * ax) * (1 - ay) + (p01 * (1 - ax) + p11 * ax) * ay
    }

    private fun clamp(v: Float): Int = v.roundToInt().coerceIn(0, 255)

    /** Kleine Ergebnisse auf mindestens 2400 px lange Seite bringen. */
    private fun upscale(b: Bitmap): Bitmap {
        val long = max(b.width, b.height)
        if (long >= 2400) return b
        val f = 2400f / long
        val s = Bitmap.createScaledBitmap(b, (b.width * f).roundToInt(), (b.height * f).roundToInt(), true)
        if (s !== b) b.recycle()
        return s
    }
}
