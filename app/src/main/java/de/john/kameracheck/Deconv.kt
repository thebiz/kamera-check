package de.john.kameracheck

import kotlin.math.ceil
import kotlin.math.exp
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

/**
 * Physikalisches Entschärfen (Dekonvolution) – reine Rechenlogik.
 *
 * Verfahren: Richardson-Lucy mit Kantenschutz (Total-Variation-Regularisierung).
 * Richardson-Lucy fragt in jedem Schritt: „Wenn mein geschätztes scharfes Bild mit der
 * bekannten Unschärfe verwischt würde – stimmt es dann mit dem Foto überein?“ und
 * korrigiert die Schätzung, bis es passt. Die Unschärfe (Gauß, σx/σy) stammt aus der
 * Messung an Kanten, begrenzt durch das Kameramodell.
 * Der Kantenschutz bremst Überschwinger (Ringe, Lichthöfe) und das Aufschaukeln von Rauschen.
 *
 * Anders als die KI erfindet das Verfahren nichts: Es verstärkt nur, was im Bild
 * gedämpft vorhanden ist.
 */
object Deconv {

    /**
     * Ganzes Bild kachelweise entschärfen (wenig Speicher, alle Kerne parallel).
     * Die Anzahl der Durchgänge wird so gewählt, dass das Zeitbudget reicht
     * (mindestens 20, höchstens iters). Gibt die entschärfte Helligkeit und die
     * tatsächlich verwendete Anzahl Durchgänge zurück.
     */
    fun deconvolve(
        y: FloatArray, w: Int, h: Int, sx: Float, sy: Float,
        iters: Int, lambda: Float, threads: Int, budgetMs: Long,
        progress: ((Int) -> Unit)? = null,
    ): Pair<FloatArray, Int> {
        val tile = 512
        val margin = max(24, ceil(4.0 * max(sx, sy)).toInt() + 8)
        val tiles = ArrayList<IntArray>()
        var ty = 0
        while (ty < h) {
            var tx = 0
            while (tx < w) {
                tiles += intArrayOf(tx, ty, min(w, tx + tile), min(h, ty + tile))
                tx += tile
            }
            ty += tile
        }
        // Tempo messen: erste Kachel mit wenigen Durchgängen
        val probe = tiles[0]
        val t0 = System.nanoTime()
        runTile(y, w, h, probe, margin, sx, sy, 4, lambda, null)
        val perPixIter = (System.nanoTime() - t0) / 1e6 / 4 /
            ((probe[2] - probe[0] + 2 * margin).toDouble() * (probe[3] - probe[1] + 2 * margin))
        val pixels = tiles.sumOf { (it[2] - it[0] + 2 * margin).toDouble() * (it[3] - it[1] + 2 * margin) }
        val affordable = (budgetMs * max(1, threads) / (perPixIter * pixels)).toInt()
        val n = affordable.coerceIn(20, iters)

        val out = FloatArray(w * h)
        val next = java.util.concurrent.atomic.AtomicInteger(0)
        val done = java.util.concurrent.atomic.AtomicInteger(0)
        val workers = (0 until max(1, threads)).map {
            Thread {
                while (true) {
                    val k = next.getAndIncrement()
                    if (k >= tiles.size) break
                    runTile(y, w, h, tiles[k], margin, sx, sy, n, lambda, out)
                    progress?.invoke(done.incrementAndGet() * 100 / tiles.size)
                }
            }.also { it.start() }
        }
        workers.forEach { it.join() }
        return out to n
    }

    /** Eine Kachel mit Rand ausschneiden, entschärfen, Innenbereich zurückschreiben. */
    private fun runTile(
        y: FloatArray, w: Int, h: Int, t: IntArray, margin: Int,
        sx: Float, sy: Float, iters: Int, lambda: Float, out: FloatArray?,
    ) {
        val x0 = max(0, t[0] - margin)
        val y0 = max(0, t[1] - margin)
        val x1 = min(w, t[2] + margin)
        val y1 = min(h, t[3] + margin)
        val tw = x1 - x0
        val th = y1 - y0
        val sub = FloatArray(tw * th)
        for (yy in 0 until th) System.arraycopy(y, (y0 + yy) * w + x0, sub, yy * tw, tw)
        val r = richardsonLucyTV(sub, tw, th, sx, sy, iters, lambda, 1)
        if (out == null) return
        for (yy in t[1] until t[3]) {
            System.arraycopy(r, (yy - y0) * tw + (t[0] - x0), out, yy * w + t[0], t[2] - t[0])
        }
    }

    /**
     * y = Helligkeit 0..1 (w×h). Gibt die entschärfte Helligkeit zurück.
     * iters ≈ 60 (schnell) … 150 (gründlich), lambda ≈ 0,012.
     */
    fun richardsonLucyTV(
        y: FloatArray, w: Int, h: Int, sx: Float, sy: Float,
        iters: Int, lambda: Float, threads: Int, progress: ((Int, Int) -> Unit)? = null,
    ): FloatArray {
        val kx = kernel(sx)
        val ky = kernel(sy)
        val n = w * h
        val obs = FloatArray(n) { max(1e-4f, y[it]) }
        val est = obs.copyOf()
        val tmp = FloatArray(n)
        val conv = FloatArray(n)
        val div = FloatArray(n)
        val px = FloatArray(n)
        val py = FloatArray(n)

        for (it in 0 until iters) {
            // 1. aktuelle Schätzung verwischen
            blur(est, conv, tmp, w, h, kx, ky, threads)
            // 2. Verhältnis Foto / verwischte Schätzung
            for (i in 0 until n) conv[i] = obs[i] / max(conv[i], 1e-4f)
            // 3. Verhältnis zurückverwischen (Gauß ist symmetrisch → gleiche Operation)
            blur(conv, conv, tmp, w, h, kx, ky, threads)
            // 4. Kantenschutz: Divergenz der normierten Steigung
            tvDivergence(est, div, px, py, w, h)
            for (i in 0 until n) {
                val den = max(1f - lambda * div[i], 0.3f)
                est[i] = (est[i] * conv[i] / den).coerceIn(1e-4f, 1.5f)
            }
            progress?.invoke(it + 1, iters)
        }
        return est
    }

    private fun kernel(s: Float): FloatArray {
        if (s < 0.15f) return floatArrayOf(1f)
        val r = max(1, ceil(3.0 * s).toInt())
        val k = FloatArray(2 * r + 1) { i -> val d = (i - r).toFloat(); exp(-d * d / (2 * s * s)) }
        val sum = k.sum()
        for (i in k.indices) k[i] /= sum
        return k
    }

    /** Getrennter Gauß (erst Zeilen, dann Spalten), Ränder werden fortgesetzt. src darf gleich dst sein. */
    private fun blur(src: FloatArray, dst: FloatArray, tmp: FloatArray, w: Int, h: Int, kx: FloatArray, ky: FloatArray, threads: Int) {
        val rx = kx.size / 2
        val ry = ky.size / 2
        parallel(h, threads) { y0, y1 ->
            for (y in y0 until y1) {
                val row = y * w
                for (x in 0 until w) {
                    var s = 0f
                    for (t in -rx..rx) {
                        val xx = (x + t).coerceIn(0, w - 1)
                        s += src[row + xx] * kx[t + rx]
                    }
                    tmp[row + x] = s
                }
            }
        }
        parallel(w, threads) { x0, x1 ->
            for (x in x0 until x1) {
                for (y in 0 until h) {
                    var s = 0f
                    for (t in -ry..ry) {
                        val yy = (y + t).coerceIn(0, h - 1)
                        s += tmp[yy * w + x] * ky[t + ry]
                    }
                    dst[y * w + x] = s
                }
            }
        }
    }

    /** div( ∇e / |∇e| ) mit Vorwärts-/Rückwärtsdifferenzen; am Rand Steigung 0 (keine Randstreifen). */
    private fun tvDivergence(e: FloatArray, div: FloatArray, px: FloatArray, py: FloatArray, w: Int, h: Int) {
        val eps = 1e-3f
        for (y in 0 until h) for (x in 0 until w) {
            val i = y * w + x
            val gx = if (x < w - 1) e[i + 1] - e[i] else 0f
            val gy = if (y < h - 1) e[i + w] - e[i] else 0f
            val nrm = sqrt(gx * gx + gy * gy + eps * eps)
            px[i] = gx / nrm
            py[i] = gy / nrm
        }
        for (y in 0 until h) for (x in 0 until w) {
            val i = y * w + x
            val dx = (if (x < w - 1) px[i] else 0f) - (if (x > 0) px[i - 1] else 0f)
            val dy = (if (y < h - 1) py[i] else 0f) - (if (y > 0) py[i - w] else 0f)
            div[i] = dx + dy
        }
    }

    private fun parallel(n: Int, threads: Int, body: (Int, Int) -> Unit) {
        val k = max(1, min(threads, n))
        if (k == 1) {
            body(0, n)
            return
        }
        val ts = (0 until k).map { i ->
            Thread { body(n * i / k, n * (i + 1) / k) }.also { it.start() }
        }
        ts.forEach { it.join() }
    }
}
