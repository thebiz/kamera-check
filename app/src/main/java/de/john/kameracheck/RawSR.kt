package de.john.kameracheck

import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.exp
import kotlin.math.floor
import kotlin.math.ln
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

/**
 * Mehrbild-Superauflösung aus RAW-Daten (Bayer-Muster) – reine Rechenlogik.
 *
 * Idee (wie „Handheld Multi-Frame Super-Resolution“): Durch das Zittern der Hand
 * liegen die Farbpixel jedes Bildes leicht versetzt. Alle Bayer-Abtastwerte aller
 * Bilder werden an ihrer exakten Position auf ein feineres Raster verteilt. So
 * entsteht echtes zusätzliches Detail – und nebenbei ein Demosaicing ohne Raten.
 *
 * Damit Helligkeit und Farbe zum Kamerabild passen, wird das Ergebnis an das
 * gemittelte YUV-Bild der Kamera angeglichen (Tonkurve + großflächige Helligkeit).
 */
object RawSR {

    class Params(
        /** 0 = RGGB, 1 = GRBG, 2 = GBRG, 3 = BGGR */
        val cfa: Int,
        val white: Float,
        /** Schwarzwert je Bayer-Position (Zeile & 1) * 2 + (Spalte & 1) */
        val black: FloatArray,
        /** Weißabgleich je Farbe R, G, B */
        val wb: FloatArray,
    )

    /** Geometrie: Ausschnitt mit Rand (x0, y0, rw, rh) und Zielausschnitt (cx, cy, cw, ch) in Sensorpixeln. */
    class Geo(val x0: Int, val y0: Int, val rw: Int, val rh: Int, val cx: Int, val cy: Int, val cw: Int, val ch: Int)

    class Out(
        val y: FloatArray, val wo: Int, val ho: Int, val u: Float, val used: Int, val rawToYuv: FloatArray,
        /** Lineare Farbebenen R, G, B im Messrahmen (nur bei Kalibrierung), Größe boxW × boxH */
        val planes: Array<FloatArray>? = null, val boxW: Int = 0, val boxH: Int = 0,
    )

    private const val MAX_OUT_PIXELS = 8_400_000f

    /** Kachelweises Ausrichten (kann zum Testen abgeschaltet werden). */
    var localAlign = true

    /** Kachelgröße in halbauflösenden Pixeln (= 2× in RAW-Pixeln). */
    private const val TILE = 48

    /**
     * Verschiebungsfeld eines Bildes (RAW-Pixel): für jede Kachelmitte dx, dy und ein
     * Gewicht (Bewegung/Fehlausrichtung → kleines Gewicht). Bild(p) ≈ Referenz(p + d).
     */
    class Field(val nx: Int, val ny: Int, val dx: FloatArray, val dy: FloatArray, val wt: FloatArray) {
        companion object {
            fun constant(dx: Float, dy: Float) = Field(1, 1, floatArrayOf(dx), floatArrayOf(dy), floatArrayOf(1f))
        }
    }

    fun run(
        raws: MutableList<ShortArray?>,
        g: Geo,
        p: Params,
        refIdx: Int,
        yuvRefY: ByteArray,
        mergedY: FloatArray,
        threads: Int,
        /** Gewicht je Bild (z. B. aus dem Lagesensor: verwackelt → klein), null = alle gleich */
        frameWeights: FloatArray? = null,
        /** Kalibrierung: Messrahmen (x0, y0, x1, y1 als Anteil 0..1 des Ergebnisses) – dort werden die linearen Farbebenen aufgehoben */
        keepBox: FloatArray? = null,
        progress: (String) -> Unit,
    ): Out {
        val w = g.rw
        val h = g.rh
        val hw = w / 2
        val hh = h / 2
        val color = IntArray(4) { colorAt(p.cfa, it shr 1, it and 1) }
        val scale = FloatArray(4) { i -> p.wb[color[i]] / max(1f, p.white - p.black[i]) }

        // ── 1. Halbauflösende Helligkeitsbilder zum Ausrichten ──
        progress("RAW: richte aus …")
        val ref = raws[refIdx]!!
        val refHalf = halfLuma(ref, w, h, p, scale)
        val refHalfSmall = Align.downscale4(refHalf, hw, hh)

        val shifts = HashMap<Int, FloatArray>()
        val fields = HashMap<Int, Field>()
        shifts[refIdx] = floatArrayOf(0f, 0f, 0f)
        fields[refIdx] = Field.constant(0f, 0f)
        var n = 0
        for (i in raws.indices) {
            if (i == refIdx || raws[i] == null) continue
            n++
            progress("RAW: richte aus … $n/${raws.size - 1}")
            val half = halfLuma(raws[i]!!, w, h, p, scale)
            val s = Align.align(refHalf, refHalfSmall, half, hw, hh)
            shifts[i] = floatArrayOf(s[0] * 2f, s[1] * 2f, s[2])
            fields[i] = if (localAlign) localField(refHalf, half, hw, hh, s[0], s[1])
            else Field.constant(s[0] * 2f, s[1] * 2f)
        }
        val costs = shifts.filterKeys { it != refIdx }.values.map { it[2] }.sorted()
        val median = if (costs.isEmpty()) 0f else costs[costs.size / 2]
        val good = shifts.filter { (k, s) -> k == refIdx || s[2] <= median * 1.6f + 1f }.keys.sorted()

        // ── 2. Versatz RAW → YUV (beide Ströme aus derselben Aufnahme) ──
        val yuvHalf = normalize(halfOfBytes(yuvRefY, w, h))
        val rawHalfN = normalize(refHalf)
        val o = Align.align(yuvHalf, Align.downscale4(yuvHalf, hw, hh), rawHalfN, hw, hh)
        var ox = o[0] * 2f
        var oy = o[1] * 2f
        if (abs(ox) > 24f || abs(oy) > 24f) {
            ox = 0f; oy = 0f
        }

        // ── 3. Feines Raster ──
        val u = min(2f, sqrt(MAX_OUT_PIXELS / (g.cw.toFloat() * g.ch)))
        val wo = (g.cw * u).toInt()
        val ho = (g.ch * u).toInt()
        val lum = FloatArray(wo * ho)
        val acc = FloatArray(wo * ho)
        val wt = FloatArray(wo * ho)

        // Grobe Vorlage je Farbe aus dem Referenzbild (füllt Lücken)
        val prior = halfPlanes(ref, w, h, color, p, scale)

        val coef = floatArrayOf(0.25f, 0.5f, 0.25f)
        val bx0 = ((keepBox?.get(0) ?: 0f) * wo).toInt().coerceIn(0, wo - 1)
        val by0 = ((keepBox?.get(1) ?: 0f) * ho).toInt().coerceIn(0, ho - 1)
        val bw = (((keepBox?.get(2) ?: 0f) * wo).toInt() - bx0).coerceIn(1, wo - bx0)
        val bh = (((keepBox?.get(3) ?: 0f) * ho).toInt() - by0).coerceIn(1, ho - by0)
        val planes = if (keepBox != null) Array(3) { FloatArray(bw * bh) } else null
        val sigmaRaw = floatArrayOf(0.42f, 0.28f, 0.42f)
        for (c in 0 until 3) {
            progress("RAW: verrechne ${"RGB"[c]} …")
            java.util.Arrays.fill(acc, 0f)
            java.util.Arrays.fill(wt, 0f)
            val sigma = sigmaRaw[c] * u
            val rad = max(1, ceil(2.0 * sigma).toInt())
            val inv2s2 = 1f / (2f * sigma * sigma)

            val bands = splitBands(ho, threads)
            val workers = bands.map { (b0, b1) ->
                Thread {
                    for (k in good) {
                        val raw = raws[k] ?: continue
                        val fwk = frameWeights?.getOrNull(k) ?: 1f
                        if (fwk <= 0f) continue
                        splatChannel(raw, w, h, g, c, color, p, scale, fields[k]!!, ox, oy, u, rad, inv2s2, acc, wt, wo, b0, b1, fwk)
                    }
                }.also { it.start() }
            }
            workers.forEach { it.join() }

            // Normieren, Lücken aus der Vorlage
            val pl = prior[c]
            val plane = planes?.get(c)
            val pw = 0.03f
            for (yy in 0 until ho) {
                val sy = (yy + 0.5f) / u - 0.5f + g.cy - oy - g.y0
                val hy = (sy - 0.5f) / 2f
                for (xx in 0 until wo) {
                    val sx = (xx + 0.5f) / u - 0.5f + g.cx - ox - g.x0
                    val hx = (sx - 0.5f) / 2f
                    val pv = bilinearF(pl, hw, hh, hx, hy)
                    val i = yy * wo + xx
                    val v = (acc[i] + pw * pv) / (wt[i] + pw)
                    lum[i] += coef[c] * v
                    if (plane != null && xx in bx0 until bx0 + bw && yy in by0 until by0 + bh) {
                        plane[(yy - by0) * bw + (xx - bx0)] = v
                    }
                }
            }
        }
        for (k in raws.indices) raws[k] = null

        // ── 4. An das Kamerabild angleichen ──
        progress("RAW: gleiche Farben an …")
        val yOut = calibrate(lum, wo, ho, u, mergedY, g.cw, g.ch, acc, wt)
        return Out(yOut, wo, ho, u, good.size, floatArrayOf(ox, oy), planes, bw, bh)
    }

    // ───────────────────────── Bausteine ─────────────────────────

    fun colorAt(cfa: Int, row: Int, col: Int): Int {
        val pos = row * 2 + col
        return when (cfa) {
            0 -> intArrayOf(0, 1, 1, 2)[pos]
            1 -> intArrayOf(1, 0, 2, 1)[pos]
            2 -> intArrayOf(1, 2, 0, 1)[pos]
            else -> intArrayOf(2, 1, 1, 0)[pos]
        }
    }

    private fun value(raw: ShortArray, i: Int, pos: Int, p: Params, scale: FloatArray): Float =
        max(0f, ((raw[i].toInt() and 0xFFFF) - p.black[pos]) * scale[pos])

    /** 2×2-Blöcke → Helligkeit (Wurzel-Kurve) als Bytes. */
    private fun halfLuma(raw: ShortArray, w: Int, h: Int, p: Params, scale: FloatArray): ByteArray {
        val hw = w / 2
        val hh = h / 2
        val out = ByteArray(hw * hh)
        for (y in 0 until hh) {
            for (x in 0 until hw) {
                val i = (2 * y) * w + 2 * x
                val s = value(raw, i, 0, p, scale) + value(raw, i + 1, 1, p, scale) +
                    value(raw, i + w, 2, p, scale) + value(raw, i + w + 1, 3, p, scale)
                out[y * hw + x] = (sqrt(min(1f, s / 4f)) * 255f + 0.5f).toInt().toByte()
            }
        }
        return out
    }

    private fun halfOfBytes(y: ByteArray, w: Int, h: Int): ByteArray {
        val hw = w / 2
        val hh = h / 2
        val out = ByteArray(hw * hh)
        for (yy in 0 until hh) for (xx in 0 until hw) {
            val i = (2 * yy) * w + 2 * xx
            val s = (y[i].toInt() and 255) + (y[i + 1].toInt() and 255) + (y[i + w].toInt() and 255) + (y[i + w + 1].toInt() and 255)
            out[yy * hw + xx] = ((s + 2) / 4).toByte()
        }
        return out
    }

    /** Auf gleiche Helligkeit/Kontrast bringen, damit RAW und YUV vergleichbar sind. */
    private fun normalize(a: ByteArray): ByteArray {
        var sum = 0.0
        var sq = 0.0
        for (b in a) {
            val v = (b.toInt() and 255).toDouble()
            sum += v
            sq += v * v
        }
        val mean = sum / a.size
        val std = sqrt(max(1e-6, sq / a.size - mean * mean))
        return ByteArray(a.size) { i ->
            ((((a[i].toInt() and 255) - mean) / std * 40.0 + 128.0).coerceIn(0.0, 255.0)).toInt().toByte()
        }
    }

    /** Je Farbe eine halbauflösende Ebene (G = Mittel der beiden Grünwerte). */
    private fun halfPlanes(raw: ShortArray, w: Int, h: Int, color: IntArray, p: Params, scale: FloatArray): Array<FloatArray> {
        val hw = w / 2
        val hh = h / 2
        val planes = Array(3) { FloatArray(hw * hh) }
        val count = IntArray(3)
        for (pos in 0 until 4) count[color[pos]]++
        for (y in 0 until hh) for (x in 0 until hw) {
            val base = (2 * y) * w + 2 * x
            val o = y * hw + x
            for (pos in 0 until 4) {
                val i = base + (pos shr 1) * w + (pos and 1)
                val c = color[pos]
                planes[c][o] += value(raw, i, pos, p, scale) / count[c]
            }
        }
        return planes
    }

    private fun splitBands(n: Int, parts: Int): List<Pair<Int, Int>> {
        val k = max(1, parts)
        return (0 until k).map { i -> (n * i / k) to (n * (i + 1) / k) }.filter { it.first < it.second }
    }

    private val EXP_LUT = FloatArray(1025) { exp(-it / 64f) }

    /** Lokales Verschiebungsfeld um die globale Verschiebung (gx, gy in Halbpixeln). */
    private fun localField(ref: ByteArray, f: ByteArray, hw: Int, hh: Int, gx: Float, gy: Float): Field {
        val nx = max(1, hw / TILE)
        val ny = max(1, hh / TILE)
        val dxA = FloatArray(nx * ny)
        val dyA = FloatArray(nx * ny)
        val cost = FloatArray(nx * ny)
        val texture = FloatArray(nx * ny)
        val R = 3
        val cgx = Math.round(gx)
        val cgy = Math.round(gy)
        val size = 2 * R + 1
        val sad = Array(size) { LongArray(size) }
        for (ty in 0 until ny) for (tx in 0 until nx) {
            val x0 = tx * hw / nx
            val x1 = (tx + 1) * hw / nx
            val y0 = ty * hh / ny
            val y1 = (ty + 1) * hh / ny
            // Rand so wählen, dass ref(p + d) im Bild bleibt
            val m = R + max(abs(cgx), abs(cgy)) + 1
            val xa = max(x0, m)
            val xb = min(x1, hw - m)
            val ya = max(y0, m)
            val yb = min(y1, hh - m)
            val k = ty * nx + tx
            if (xb - xa < 8 || yb - ya < 8) {
                dxA[k] = gx; dyA[k] = gy; cost[k] = -1f
                continue
            }
            // Struktur im Referenzstück (flache Flächen lassen sich nicht ausrichten)
            var tex = 0L
            var cnt = 0
            for (y in ya until yb step 2) for (x in xa until xb - 1 step 2) {
                val i = y * hw + x
                tex += abs((ref[i + 1].toInt() and 255) - (ref[i].toInt() and 255))
                cnt++
            }
            texture[k] = tex.toFloat() / max(1, cnt)
            for (a in -R..R) for (b in -R..R) {
                val ddx = cgx + b
                val ddy = cgy + a
                var sum = 0L
                for (y in ya until yb step 2) {
                    val ro = (y + ddy) * hw + ddx
                    val fo = y * hw
                    for (x in xa until xb step 2) {
                        sum += abs((ref[ro + x].toInt() and 255) - (f[fo + x].toInt() and 255))
                    }
                }
                sad[a + R][b + R] = sum
            }
            var ai = R
            var bi = R
            for (a in 0 until size) for (b in 0 until size) if (sad[a][b] < sad[ai][bi]) { ai = a; bi = b }
            val subX = if (bi in 1 until size - 1) parab(sad[ai][bi - 1], sad[ai][bi], sad[ai][bi + 1]) else 0f
            val subY = if (ai in 1 until size - 1) parab(sad[ai - 1][bi], sad[ai][bi], sad[ai + 1][bi]) else 0f
            dxA[k] = cgx + (bi - R) + subX
            dyA[k] = cgy + (ai - R) + subY
            cost[k] = sad[ai][bi].toFloat() / max(1, cnt)
        }

        // Flache Kacheln → globale Verschiebung; Ausreißer → Median der Nachbarn
        val dxO = dxA.copyOf()
        val dyO = dyA.copyOf()
        for (ty in 0 until ny) for (tx in 0 until nx) {
            val k = ty * nx + tx
            if (cost[k] < 0f || texture[k] < 2.0f) {
                dxO[k] = gx; dyO[k] = gy
                continue
            }
            val nbx = ArrayList<Float>()
            val nby = ArrayList<Float>()
            for (a in -1..1) for (b in -1..1) {
                val yy = ty + a
                val xx = tx + b
                if (yy !in 0 until ny || xx !in 0 until nx || (a == 0 && b == 0)) continue
                val j = yy * nx + xx
                if (cost[j] >= 0f && texture[j] >= 2.0f) { nbx += dxA[j]; nby += dyA[j] }
            }
            if (nbx.size >= 3) {
                nbx.sort(); nby.sort()
                val mx = nbx[nbx.size / 2]
                val my = nby[nby.size / 2]
                if (abs(dxA[k] - mx) > 0.75f || abs(dyA[k] - my) > 0.75f) { dxO[k] = mx; dyO[k] = my }
            }
        }

        // Glätten: Mittel mit den Nachbarkacheln (Drehung/Verzerrung ändern sich nur langsam)
        val dxS = FloatArray(nx * ny)
        val dyS = FloatArray(nx * ny)
        for (ty in 0 until ny) for (tx in 0 until nx) {
            var sx = 0f
            var sy = 0f
            var c = 0f
            for (a in -1..1) for (b in -1..1) {
                val yy = ty + a
                val xx = tx + b
                if (yy !in 0 until ny || xx !in 0 until nx) continue
                val wgt = if (a == 0 && b == 0) 2f else 1f
                val j = yy * nx + xx
                sx += dxO[j] * wgt
                sy += dyO[j] * wgt
                c += wgt
            }
            dxS[ty * nx + tx] = sx / c
            dyS[ty * nx + tx] = sy / c
        }
        // Nur echte lokale Abweichungen übernehmen – sonst ist die globale Schätzung genauer
        for (k in dxO.indices) {
            val ddx = dxS[k] - gx
            val ddy = dyS[k] - gy
            val dev = kotlin.math.hypot(ddx, ddy)
            val t = ((dev - 0.12f) / 0.25f).coerceIn(0f, 1f)
            dxO[k] = gx + ddx * t
            dyO[k] = gy + ddy * t
        }

        // Gewicht: Kacheln mit auffällig großem Restfehler (Bewegung) zählen wenig
        val valid = cost.filter { it >= 0f }.sorted()
        val med = if (valid.isEmpty()) 0f else valid[valid.size / 2]
        val wt = FloatArray(nx * ny) { k ->
            if (cost[k] < 0f) 1f else if (cost[k] > med * 2.2f + 4f) 0.1f else 1f
        }
        // Halbpixel → RAW-Pixel
        for (k in dxO.indices) { dxO[k] *= 2f; dyO[k] *= 2f }
        return Field(nx, ny, dxO, dyO, wt)
    }

    private fun parab(l: Long, c: Long, r: Long): Float {
        val den = (l - 2 * c + r).toFloat()
        if (den <= 0f) return 0f
        return ((l - r).toFloat() / (2f * den)).coerceIn(-0.5f, 0.5f)
    }

    /** Alle Abtastwerte einer Farbe eines Bildes auf das feine Raster verteilen (nur Zeilen b0 until b1). */
    private fun splatChannel(
        raw: ShortArray, w: Int, h: Int, g: Geo, c: Int, color: IntArray, p: Params, scale: FloatArray,
        fld: Field, ox: Float, oy: Float, u: Float, rad: Int, inv2s2: Float,
        acc: FloatArray, wt: FloatArray, wo: Int, b0: Int, b1: Int, frameWeight: Float = 1f,
    ) {
        val wx = FloatArray(2 * rad + 2)
        val wy = FloatArray(2 * rad + 2)
        val nx = fld.nx
        val ny = fld.ny
        val rdx = FloatArray(nx)
        val rdy = FloatArray(nx)
        val rwt = FloatArray(nx)
        // Kachelmitten in RAW-Pixeln: Halbpixel-Kachel k deckt [k*hw/nx, (k+1)*hw/nx)
        val tileW = w.toFloat() / nx
        val tileH = h.toFloat() / ny
        var dyMin = Float.MAX_VALUE
        var dyMax = -Float.MAX_VALUE
        for (v in fld.dy) { dyMin = min(dyMin, v); dyMax = max(dyMax, v) }

        for (rowPar in 0..1) for (colPar in 0..1) {
            val pos = rowPar * 2 + colPar
            if (color[pos] != c) continue
            var j = rowPar
            while (j < h) {
                // Grob prüfen, ob diese Zeile das eigene Band überhaupt trifft
                val oLo = (g.y0 + j + dyMin + oy - g.cy + 0.5f) * u - 0.5f
                val oHi = (g.y0 + j + dyMax + oy - g.cy + 0.5f) * u - 0.5f
                if (floor(oHi).toInt() + rad < b0 || floor(oLo).toInt() - rad + 1 >= b1) {
                    j += 2
                    continue
                }
                // Verschiebungsfeld für diese Zeile (senkrecht interpoliert)
                val fy = ((j + 0.5f) / tileH - 0.5f).coerceIn(0f, (ny - 1).toFloat())
                val ky = min(ny - 2, fy.toInt()).coerceAtLeast(0)
                val ay = if (ny > 1) fy - ky else 0f
                for (kx in 0 until nx) {
                    val i0 = ky * nx + kx
                    val i1 = if (ny > 1) i0 + nx else i0
                    rdx[kx] = fld.dx[i0] * (1 - ay) + fld.dx[i1] * ay
                    rdy[kx] = fld.dy[i0] * (1 - ay) + fld.dy[i1] * ay
                    rwt[kx] = fld.wt[i0] * (1 - ay) + fld.wt[i1] * ay
                }
                val row = j * w
                var i = colPar
                while (i < w) {
                    // waagerecht interpolieren
                    val fx = ((i + 0.5f) / tileW - 0.5f).coerceIn(0f, (nx - 1).toFloat())
                    val kx = min(nx - 2, fx.toInt()).coerceAtLeast(0)
                    val ax = if (nx > 1) fx - kx else 0f
                    val k1 = if (nx > 1) kx + 1 else kx
                    val dx = rdx[kx] * (1 - ax) + rdx[k1] * ax + ox
                    val dy = rdy[kx] * (1 - ax) + rdy[k1] * ax + oy
                    val fw = (rwt[kx] * (1 - ax) + rwt[k1] * ax) * frameWeight

                    val oyc = (g.y0 + j + dy - g.cy + 0.5f) * u - 0.5f
                    val iy0 = floor(oyc).toInt() - rad + 1
                    val iy1 = floor(oyc).toInt() + rad
                    val oxc = (g.x0 + i + dx - g.cx + 0.5f) * u - 0.5f
                    val ix0 = floor(oxc).toInt() - rad + 1
                    val ix1 = floor(oxc).toInt() + rad
                    if (iy1 >= b0 && iy0 < b1 && ix1 >= 0 && ix0 < wo) {
                        val v = value(raw, row + i, pos, p, scale)
                        var nY = 0
                        for (t in iy0..iy1) {
                            val d = t - oyc
                            val q = (d * d * inv2s2 * 64f).toInt()
                            wy[nY++] = if (q < 1024) EXP_LUT[q] * fw else 0f
                        }
                        var nX = 0
                        for (t in ix0..ix1) {
                            val d = t - oxc
                            val q = (d * d * inv2s2 * 64f).toInt()
                            wx[nX++] = if (q < 1024) EXP_LUT[q] else 0f
                        }
                        var a = 0
                        for (ty in iy0..iy1) {
                            val wyv = wy[a++]
                            if (ty < b0 || ty >= b1 || wyv == 0f) continue
                            val base = ty * wo
                            var bb = 0
                            for (tx in ix0..ix1) {
                                val wv = wx[bb++] * wyv
                                if (tx < 0 || tx >= wo || wv == 0f) continue
                                acc[base + tx] += wv * v
                                wt[base + tx] += wv
                            }
                        }
                    }
                    i += 2
                }
                j += 2
            }
        }
    }

    private fun bilinearF(a: FloatArray, w: Int, h: Int, fx: Float, fy: Float): Float {
        val x = fx.coerceIn(0f, w - 1.001f)
        val y = fy.coerceIn(0f, h - 1.001f)
        val ix = x.toInt()
        val iy = y.toInt()
        val ax = x - ix
        val ay = y - iy
        val i = iy * w + ix
        return (a[i] * (1 - ax) + a[i + 1] * ax) * (1 - ay) + (a[i + w] * (1 - ax) + a[i + w + 1] * ax) * ay
    }

    /**
     * Lineare RAW-Helligkeit → Helligkeit wie im Kamerabild:
     * 1) Tonkurve aus Wertepaaren (RAW, Kamera) schätzen,
     * 2) großflächige Abweichungen (Vignette, lokale Tonung) per Verstärkungskarte übernehmen.
     */
    private fun calibrate(
        lum: FloatArray, wo: Int, ho: Int, u: Float,
        mergedY: FloatArray, cw: Int, ch: Int,
        tmpA: FloatArray, tmpB: FloatArray,
    ): FloatArray {
        // Tonkurve: 256 Fächer über log2(L)
        val bins = 256
        val lo = -14f
        val sumY = DoubleArray(bins)
        val cnt = IntArray(bins)
        fun bin(l: Float): Float = ((ln(max(l, 1e-5f)) / ln(2f) - lo) / -lo * (bins - 1)).coerceIn(0f, bins - 1f)
        for (yy in 0 until ch) {
            val fy = (yy + 0.5f) * u - 0.5f
            for (xx in 0 until cw) {
                val fx = (xx + 0.5f) * u - 0.5f
                val l = bilinearF(lum, wo, ho, fx, fy)
                val b = bin(l).toInt()
                sumY[b] += mergedY[yy * cw + xx]
                cnt[b]++
            }
        }
        val lut = FloatArray(bins) { if (cnt[it] > 0) (sumY[it] / cnt[it]).toFloat() else Float.NaN }
        // Lücken füllen und monoton machen
        var last = Float.NaN
        for (i in 0 until bins) if (!lut[i].isNaN()) { last = lut[i]; break }
        if (last.isNaN()) last = 0f
        for (i in 0 until bins) if (lut[i].isNaN()) lut[i] = last else last = lut[i]
        for (i in 1 until bins) if (lut[i] < lut[i - 1]) lut[i] = lut[i - 1]

        val yt = FloatArray(wo * ho)
        for (i in yt.indices) {
            val b = bin(lum[i])
            val b0 = b.toInt()
            val b1 = min(bins - 1, b0 + 1)
            val a = b - b0
            yt[i] = lut[b0] * (1 - a) + lut[b1] * a
        }

        // Kamerabild aufs feine Raster
        for (yy in 0 until ho) {
            val fy = (yy + 0.5f) / u - 0.5f
            for (xx in 0 until wo) {
                val fx = (xx + 0.5f) / u - 0.5f
                tmpA[yy * wo + xx] = bilinearF(mergedY, cw, ch, fx, fy)
            }
        }
        System.arraycopy(yt, 0, tmpB, 0, yt.size)
        val r = max(4, (6 * u).toInt())
        boxBlur(tmpA, wo, ho, r, lum)
        boxBlur(tmpB, wo, ho, r, lum)
        for (i in yt.indices) {
            val gain = ((tmpA[i] + 2f) / (tmpB[i] + 2f)).coerceIn(0.6f, 1.6f)
            yt[i] = (yt[i] * gain).coerceIn(0f, 255f)
        }
        return yt
    }

    /** Dreifacher Kastenfilter (≈ Gauß), in-place, tmp als Zwischenspeicher. */
    private fun boxBlur(a: FloatArray, w: Int, h: Int, r: Int, tmp: FloatArray) {
        repeat(3) {
            // horizontal a → tmp
            for (y in 0 until h) {
                val row = y * w
                var s = 0f
                for (x in -r..r) s += a[row + x.coerceIn(0, w - 1)]
                for (x in 0 until w) {
                    tmp[row + x] = s / (2 * r + 1)
                    s += a[row + (x + r + 1).coerceAtMost(w - 1)] - a[row + (x - r).coerceAtLeast(0)]
                }
            }
            // vertikal tmp → a
            for (x in 0 until w) {
                var s = 0f
                for (y in -r..r) s += tmp[y.coerceIn(0, h - 1) * w + x]
                for (y in 0 until h) {
                    a[y * w + x] = s / (2 * r + 1)
                    s += tmp[(y + r + 1).coerceAtMost(h - 1) * w + x] - tmp[(y - r).coerceAtLeast(0) * w + x]
                }
            }
        }
    }
}
