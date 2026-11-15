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

        /**
         * Nur ein Teil des Zoom-Ausschnitts (box = x0, y0, x1, y1 als Anteile davon) – für die Kalibrierung:
         * weniger Pixel auslesen und verrechnen, dadurch viel schneller.
         */
        fun forBox(sw: Int, sh: Int, zoom: Float, box: FloatArray): Region {
            val base = compute(sw, sh, zoom)
            // etwas Luft um den Rahmen, falls das Handy beim Auslösen leicht wandert
            val pad = 0.06f
            val bx0 = (box[0] - pad).coerceIn(0f, 1f)
            val by0 = (box[1] - pad).coerceIn(0f, 1f)
            val bx1 = (box[2] + pad).coerceIn(0f, 1f)
            val by1 = (box[3] + pad).coerceIn(0f, 1f)
            val cw = max(64, ((bx1 - bx0) * base.cw).roundToInt() / 2 * 2)
            val ch = max(64, ((by1 - by0) * base.ch).roundToInt() / 2 * 2)
            val cx = (base.cx + (bx0 * base.cw).roundToInt()).coerceIn(0, sw - cw) / 2 * 2
            val cy = (base.cy + (by0 * base.ch).roundToInt()).coerceIn(0, sh - ch) / 2 * 2
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
) {
    /** Vergleich A/B: die zweite Rechnung derselben Serie (feines Raster bzw. alle statt nur der ersten Bilder) */
    var fine: Result? = null
    /** Raster dieses Ergebnisses gegenüber dem Ausschnitt (1 = gleich, 2 = doppelt so fein) */
    var grid = 1f
}

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
    /** Bilder, die weniger als diesen Anteil der Schärfe des besten Bildes haben, fliegen raus. */
    const val SHARP_KEEP = 0.8f

    /** Restfehler (Helligkeitsstufen), bis zu dem ein ausgerichtetes Bild sicher passt */
    const val RESID_ABS = 6f

    /** Restfehler der Bilder beim letzten Durchlauf (für die Infozeile) */
    @Volatile var lastResiduals = ""

    /** Anzahl beim letzten Durchlauf aussortierter (unscharfer/verrutschter) Bilder */
    @Volatile var dropped = 0

    fun process(
        frames: MutableList<Frame?>,
        r: Region,
        frameWeights: FloatArray? = null,
        /** > 0: Fotos können weit gegeneinander verrutscht sein (Import), so weit wird gesucht */
        maxShift: Int = 0,
        /** > 1: zusätzlich auf ein so viel feineres Raster eintragen (→ [Result.fine]) */
        fineU: Int = 0,
        /**
         * > 0: Vergleich „mehr Bilder“: das Hauptergebnis nutzt nur das Referenzbild und die ersten
         * (subset − 1) passenden Bilder, [Result.fine] alle passenden Bilder derselben Serie
         */
        subset: Int = 0,
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
            val s = if (maxShift > 0) Align.alignLarge(ref.y, refSmall, frames[i]!!.y, w, h, maxShift)
                else Align.align(ref.y, refSmall, frames[i]!!.y, w, h)
            shifts.add(floatArrayOf(s[0], s[1], s[2], i.toFloat()))
        }
        val costs = shifts.map { it[2] }.sorted()
        val median = if (costs.isEmpty()) 0f else costs[costs.size / 2]
        // Nur Bilder, die fast so scharf sind wie das schärfste: ein unscharfes Bild im Mittel
        // macht das Ergebnis weicher statt besser (Befund Diagnosebild 1.3)
        val good0 = shifts.filter { it[2] <= median * 1.6f + 1f && sharp[it[3].toInt()] >= SHARP_KEEP * sharp[refIdx] }
        // kachelweise ausrichten (Handdrehung) und prüfen, ob das Bild danach wirklich passt –
        // sonst entstehen Doppelbilder (Befund Import 1.5)
        val fields = HashMap<Int, Align.ShiftField>()
        val resid = HashMap<Int, Float>()
        for ((k, sft) in good0.withIndex()) {
            progress("Prüfe Passgenauigkeit ${k + 1}/${good0.size} …")
            val idx = sft[3].toInt()
            val fld = Align.localField(ref.y, frames[idx]!!.y, w, h, sft[0], sft[1])
            fields[idx] = fld
            resid[idx] = Align.residual(ref.y, frames[idx]!!.y, w, h, fld)
        }
        val minRes = resid.values.minOrNull() ?: 0f
        val good = good0.filter { resid[it[3].toInt()]!! <= max(RESID_ABS, minRes * 1.5f + 1f) }
        lastResiduals = good0.joinToString(" ") { "%.1f".format(resid[it[3].toInt()]) }
        dropped = total - 1 - good.size

        val u = 1
        val wo = r.cw
        val ho = r.ch
        val acc = FloatArray(wo * ho)
        val wt = FloatArray(wo * ho)
        val fu = if (fineU > 1) fineU else if (subset > 0) 1 else 0
        // Vergleich „mehr Bilder“: welche Bilder ins Hauptergebnis dürfen
        val inMain = HashSet<Int>()
        if (subset > 0) good.sortedBy { it[3] }.take(subset - 1).forEach { inMain += it[3].toInt() }
        val fwo = wo * fu
        val fho = ho * fu
        val fAcc = if (fu > 0) FloatArray(fwo * fho) else null
        val fWt = if (fu > 0) FloatArray(fwo * fho) else null

        // Farbe (halbe Auflösung) ebenfalls über alle Bilder mitteln
        val cwh = w / 2
        val chh = h / 2
        val uAcc = FloatArray(cwh * chh)
        val vAcc = FloatArray(cwh * chh)
        val cWt = FloatArray(cwh * chh)

        progress("Verrechne 1/${good.size + 1} …")
        splat(ref.y, Align.ShiftField.constant(w, h, 0f, 0f), 1f, ref.y, r, u, acc, wt, wo, ho)
        if (fAcc != null) splat(ref.y, Align.ShiftField.constant(w, h, 0f, 0f), 1f, ref.y, r, fu, fAcc, fWt!!, fwo, fho)
        addChroma(ref, 0f, 0f, cwh, chh, uAcc, vAcc, cWt)
        good.forEachIndexed { k, s ->
            progress("Verrechne ${k + 2}/${good.size + 1} …")
            val idx = s[3].toInt()
            val f = frames[idx]!!
            // Gewicht nach Schärfe
            val field = fields[idx]!!
            val q = sharp[idx] / sharp[refIdx]
            val fw = (frameWeights?.getOrNull(idx) ?: 1f) * q * q * q * q
            if (subset == 0 || idx in inMain) splat(f.y, field, fw, ref.y, r, u, acc, wt, wo, ho)
            if (fAcc != null) splat(f.y, field, fw, ref.y, r, fu, fAcc, fWt!!, fwo, fho)
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
        val res = Result(merged, single, if (subset > 0) inMain.size + 1 else good.size + 1, total, refIdx, ref.y, acc, avgFrame)
        if (fAcc != null) {
            // feines Raster: dieselbe Regel, Lücken (kaum getroffene Stellen) füllt das Referenzbild
            for (oy in 0 until fho) {
                val ly = r.cy + (oy + 0.5f) / fu - 0.5f - r.y0
                for (ox in 0 until fwo) {
                    val lx = r.cx + (ox + 0.5f) / fu - 0.5f - r.x0
                    val k = oy * fwo + ox
                    fAcc[k] = (fAcc[k] + prior * bilinear(ref.y, w, h, lx, ly)) / (fWt!![k] + prior)
                }
            }
            val fMerged = toBitmap(fAcc, avgFrame, r, fu.toFloat(), fwo, fho)
            val fSingle = if (fu == 1) single.copy(Bitmap.Config.ARGB_8888, false) else Bitmap.createScaledBitmap(single, fwo, fho, true)
            res.fine = Result(fMerged, fSingle, good.size + 1, total, refIdx, ref.y, fAcc, avgFrame).also { it.grid = fu.toFloat() }
        }
        return res
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
        fy: ByteArray, field: Align.ShiftField, fw: Float, refY: ByteArray,
        r: Region, u: Int, acc: FloatArray, wt: FloatArray, wo: Int, ho: Int,
    ) {
        val w = r.rw
        val h = r.rh
        val uf = u.toFloat()
        val d = FloatArray(2)
        for (j in 0 until h) {
            val row = j * w
            for (i in 0 until w) {
                field.at(i.toFloat(), j.toFloat(), d)
                val lx = i + d[0]
                val ly = j + d[1]
                if (lx < 0f || lx >= w - 1 || ly < 0f || ly >= h - 1) continue
                val ox = (r.x0 + lx - r.cx + 0.5f) * uf - 0.5f
                val oy = (r.y0 + ly - r.cy + 0.5f) * uf - 0.5f
                if (ox <= -1f || ox >= wo || oy <= -1f || oy >= ho) continue
                val v = (fy[row + i].toInt() and 255).toFloat()
                val rx = lx.toInt()
                val ry = ly.toInt()
                val rfx = lx - rx
                val rfy = ly - ry
                val r0 = ry * w + rx
                val p00 = (refY[r0].toInt() and 255).toFloat()
                val p10 = (refY[r0 + 1].toInt() and 255).toFloat()
                val p01 = (refY[r0 + w].toInt() and 255).toFloat()
                val p11 = (refY[r0 + w + 1].toInt() and 255).toFloat()
                val pred = (p00 * (1 - rfx) + p10 * rfx) * (1 - rfy) + (p01 * (1 - rfx) + p11 * rfx) * rfy
                val rob = ROBUST[min(255, abs(v - pred).toInt())] * fw

                val ix = floor(ox).toInt()
                val iy = floor(oy).toInt()
                val fxw = ox - ix
                val fyw = oy - iy
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
