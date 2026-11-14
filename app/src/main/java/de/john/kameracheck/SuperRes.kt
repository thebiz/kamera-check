package de.john.kameracheck

import android.graphics.Bitmap
import android.media.Image
import kotlin.math.abs
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

class Result(
    val merged: Bitmap,
    val single: Bitmap,
    val used: Int,
    val total: Int,
    /** Index des Referenzbildes in der Serie */
    val refIdx: Int,
    /** Helligkeit des Referenzbildes (Ausschnitt mit Rand) */
    val refY: ByteArray,
    /** Gemittelte Helligkeit im Ausschnitt (cw × ch) */
    val mergedY: FloatArray,
    /** Gemittelte Farbe (Ausschnitt mit Rand, halbe Auflösung) */
    val chroma: Frame,
)

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

    /**
     * Richtet die Bildserie aus und mittelt sie (rauschärmer, ohne Nachschärfen –
     * das übernimmt danach die KI). Liefert Mittelwert und Referenz-Einzelbild
     * in Ausschnittsgröße.
     */
    fun process(
        frames: MutableList<Frame?>,
        r: Region,
        progress: (String) -> Unit,
    ): Result {
        val w = r.rw
        val h = r.rh
        val total = frames.size

        progress("Suche schärfstes Bild …")
        val sharp = FloatArray(total) { Align.sharpness(frames[it]!!.y, w, h) }
        var refIdx = 0
        for (i in 1 until total) if (sharp[i] > sharp[refIdx]) refIdx = i
        val ref = frames[refIdx]!!
        val refSmall = Align.downscale4(ref.y, w, h)

        val shifts = ArrayList<FloatArray>()
        var n = 0
        for (i in 0 until total) {
            if (i == refIdx) continue
            n++
            progress("Richte aus … $n/${total - 1}")
            val s = Align.align(ref.y, refSmall, frames[i]!!.y, w, h)
            shifts.add(floatArrayOf(s[0], s[1], s[2], i.toFloat()))
        }
        val costs = shifts.map { it[2] }.sorted()
        val median = if (costs.isEmpty()) 0f else costs[costs.size / 2]
        val good = shifts.filter { it[2] <= median * 1.6f + 1f }

        val u = 1
        val wo = r.cw
        val ho = r.ch
        val acc = FloatArray(wo * ho)
        val wt = FloatArray(wo * ho)

        // Farbe (halbe Auflösung) ebenfalls über alle Bilder mitteln
        val cwh = w / 2
        val chh = h / 2
        val uAcc = FloatArray(cwh * chh)
        val vAcc = FloatArray(cwh * chh)
        val cWt = FloatArray(cwh * chh)

        progress("Verrechne 1/${good.size + 1} …")
        splat(ref.y, 0f, 0f, ref.y, r, u, acc, wt, wo, ho)
        addChroma(ref, 0f, 0f, cwh, chh, uAcc, vAcc, cWt)
        good.forEachIndexed { k, s ->
            progress("Verrechne ${k + 2}/${good.size + 1} …")
            val idx = s[3].toInt()
            val f = frames[idx]!!
            splat(f.y, s[0], s[1], ref.y, r, u, acc, wt, wo, ho)
            addChroma(f, s[0], s[1], cwh, chh, uAcc, vAcc, cWt)
            frames[idx] = null
        }
        val uAvg = ByteArray(cwh * chh)
        val vAvg = ByteArray(cwh * chh)
        for (i in uAvg.indices) {
            val wgt = cWt[i]
            uAvg[i] = (if (wgt > 0f) (uAcc[i] / wgt).roundToInt() else (ref.u[i].toInt() and 255)).coerceIn(0, 255).toByte()
            vAvg[i] = (if (wgt > 0f) (vAcc[i] / wgt).roundToInt() else (ref.v[i].toInt() and 255)).coerceIn(0, 255).toByte()
        }
        val avgFrame = Frame(ref.y, uAvg, vAvg)

        progress("Setze zusammen …")
        val prior = 0.02f
        for (oy in 0 until ho) {
            val ly = (r.cy + oy) - r.y0.toFloat()
            for (ox in 0 until wo) {
                val lx = (r.cx + ox) - r.x0.toFloat()
                val refv = bilinear(ref.y, w, h, lx, ly)
                val k = oy * wo + ox
                acc[k] = (acc[k] + prior * refv) / (wt[k] + prior)
                wt[k] = refv
            }
        }

        val px = IntArray(wo * ho)
        val merged = toBitmap(acc, avgFrame, r, u.toFloat(), wo, ho, px)
        val single = toBitmap(wt, ref, r, u.toFloat(), wo, ho, px)
        return Result(merged, single, good.size + 1, total, refIdx, ref.y, acc, avgFrame)
    }

    /** Farbebenen eines Bildes verschoben aufaddieren: Referenz(q) ≈ Bild(q − d). */
    private fun addChroma(
        f: Frame, dx: Float, dy: Float, cw: Int, ch: Int,
        uAcc: FloatArray, vAcc: FloatArray, cWt: FloatArray,
    ) {
        val hx = dx / 2f
        val hy = dy / 2f
        for (y in 0 until ch) {
            val sy = y - hy
            if (sy < 0f || sy >= ch - 1) continue
            val iy = sy.toInt()
            val ay = sy - iy
            for (x in 0 until cw) {
                val sx = x - hx
                if (sx < 0f || sx >= cw - 1) continue
                val ix = sx.toInt()
                val i = iy * cw + ix
                val ax = sx - ix
                val k = y * cw + x
                uAcc[k] += lerp2(f.u, i, cw, ax, ay)
                vAcc[k] += lerp2(f.v, i, cw, ax, ay)
                cWt[k] += 1f
            }
        }
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

    /** Helligkeit (Raster u-fach über dem Ausschnitt) + Farbe → Bild. */
    fun toBitmap(yv: FloatArray, ref: Frame, r: Region, u: Float, wo: Int, ho: Int, px: IntArray = IntArray(wo * ho)): Bitmap {
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
}
