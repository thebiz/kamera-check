package de.john.kameracheck

import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import android.content.Context
import android.graphics.Bitmap
import java.nio.FloatBuffer
import kotlin.math.max
import kotlin.math.min

/**
 * Tiefenkarte aus einem einzigen Foto (Johns Idee „Plastizität“, 15.11.2026):
 * MiDaS v2.1 small (Ranftl et al., MIT-Lizenz), 256 × 256, umgewandelt mit tools/export_midas.py.
 * Liefert relative Nähe 0..1 (1 = nah, 0 = fern) auf einem 256er-Raster – keine Meter, nur die Reihenfolge.
 */
class Depth(val near: FloatArray, val n: Int = N) {

    /** Nähe an einer Bildstelle (Anteile 0..1), bilinear */
    fun at(fx: Float, fy: Float): Float {
        val x = (fx * n - 0.5f).coerceIn(0f, n - 1f)
        val y = (fy * n - 0.5f).coerceIn(0f, n - 1f)
        val x0 = x.toInt(); val y0 = y.toInt()
        val x1 = min(n - 1, x0 + 1); val y1 = min(n - 1, y0 + 1)
        val ax = x - x0; val ay = y - y0
        return (near[y0 * n + x0] * (1 - ax) + near[y0 * n + x1] * ax) * (1 - ay) +
            (near[y1 * n + x0] * (1 - ax) + near[y1 * n + x1] * ax) * ay
    }

    /** Auf Bildgröße w × h hochrechnen */
    fun upscale(w: Int, h: Int): FloatArray {
        val out = FloatArray(w * h)
        for (y in 0 until h) {
            val fy = (y + 0.5f) / h
            for (x in 0 until w) out[y * w + x] = at((x + 0.5f) / w, fy)
        }
        return out
    }

    companion object {
        const val N = 256
        @Volatile private var session: OrtSession? = null

        private fun session(ctx: Context): OrtSession {
            session?.let { return it }
            synchronized(this) {
                session?.let { return it }
                val env = OrtEnvironment.getEnvironment()
                val bytes = ctx.applicationContext.assets.open("midas256.onnx").use { it.readBytes() }
                val opt = OrtSession.SessionOptions().apply {
                    setIntraOpNumThreads(max(2, min(6, Runtime.getRuntime().availableProcessors() - 2)))
                    setOptimizationLevel(OrtSession.SessionOptions.OptLevel.ALL_OPT)
                }
                return env.createSession(bytes, opt).also { session = it }
            }
        }

        fun estimate(ctx: Context, b: Bitmap): Depth {
            val small = Bitmap.createScaledBitmap(b, N, N, true)
            val px = IntArray(N * N).also { small.getPixels(it, 0, N, 0, 0, N, N) }
            if (small !== b) small.recycle()
            val mean = floatArrayOf(0.485f, 0.456f, 0.406f)
            val std = floatArrayOf(0.229f, 0.224f, 0.225f)
            val buf = FloatBuffer.allocate(3 * N * N)
            for (c in 0 until 3) for (i in 0 until N * N) {
                val v = ((px[i] shr (16 - 8 * c)) and 255) / 255f
                buf.put((v - mean[c]) / std[c])
            }
            buf.rewind()
            val env = OrtEnvironment.getEnvironment()
            val s = session(ctx)
            val raw = OnnxTensor.createTensor(env, buf, longArrayOf(1, 3, N.toLong(), N.toLong())).use { t ->
                s.run(mapOf(s.inputNames.first() to t)).use { r ->
                    val o = (r[0] as OnnxTensor).floatBuffer
                    FloatArray(N * N).also { o.get(it) }
                }
            }
            // robust auf 0..1 (2 %/98 %), dann leicht glätten: die Karte ist grob, Kanten sollen nicht hart springen
            val sorted = raw.copyOf().also { it.sort() }
            val lo = sorted[(sorted.size * 0.02f).toInt()]
            val hi = sorted[(sorted.size * 0.98f).toInt()]
            val span = max(1e-6f, hi - lo)
            val norm = FloatArray(N * N) { ((raw[it] - lo) / span).coerceIn(0f, 1f) }
            return Depth(blur(norm, N, 2))
        }

        private fun blur(a: FloatArray, n: Int, r: Int): FloatArray {
            val t = FloatArray(a.size)
            val o = FloatArray(a.size)
            for (y in 0 until n) for (x in 0 until n) {
                var s = 0f; var c = 0
                for (k in max(0, x - r)..min(n - 1, x + r)) { s += a[y * n + k]; c++ }
                t[y * n + x] = s / c
            }
            for (y in 0 until n) for (x in 0 until n) {
                var s = 0f; var c = 0
                for (k in max(0, y - r)..min(n - 1, y + r)) { s += t[k * n + x]; c++ }
                o[y * n + x] = s / c
            }
            return o
        }
    }
}
