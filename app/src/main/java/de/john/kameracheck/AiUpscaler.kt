package de.john.kameracheck

import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import ai.onnxruntime.providers.NNAPIFlags
import android.content.Context
import android.graphics.Bitmap
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer
import java.util.EnumSet
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

/**
 * KI-Hochskalierung ×4 mit Real-ESRGAN.
 * - schnell: realesr-general-x4v3 (kompakt) – Modell rechnet nur Faltungen,
 *   Pixel-Shuffle und Basis-Addition passieren hier
 * - stark: RealESRGAN_x4plus (RRDB, ca. 12× Rechenaufwand) – Ausgabe direkt als Bild
 */
class AiUpscaler private constructor(
    private val env: OrtEnvironment,
    private val session: OrtSession,
    val backend: String,
    val msPerTile: Long,
    /** true = Modell liefert das fertige Bild (stark), false = Pixel-Shuffle + Basis (schnell) */
    private val direct: Boolean,
) {
    companion object {
        const val T = 128
        private const val TT = T * T
        private const val OV = 8
        private const val ST = T - 2 * OV

        @Volatile private var instance: AiUpscaler? = null
        @Volatile private var strong: AiUpscaler? = null

        fun get(ctx: Context): AiUpscaler {
            instance?.let { return it }
            synchronized(this) {
                instance?.let { return it }
                return create(ctx.applicationContext).also { instance = it }
            }
        }

        /** Großes Modell. Beim ersten Mal wird der schnellste Rechenweg gemessen und gemerkt. */
        fun getStrong(ctx: Context, progress: (String) -> Unit): AiUpscaler {
            strong?.let { return it }
            synchronized(this) {
                strong?.let { return it }
                return createStrong(ctx.applicationContext, progress).also { strong = it }
            }
        }

        private fun cpuOptions() = OrtSession.SessionOptions().apply {
            setIntraOpNumThreads(max(2, min(6, Runtime.getRuntime().availableProcessors() - 2)))
            setOptimizationLevel(OrtSession.SessionOptions.OptLevel.ALL_OPT)
        }

        private fun npuOptions() = OrtSession.SessionOptions().apply {
            addNnapi(EnumSet.of(NNAPIFlags.USE_FP16, NNAPIFlags.CPU_DISABLED))
        }

        private fun createStrong(ctx: Context, progress: (String) -> Unit): AiUpscaler {
            val env = OrtEnvironment.getEnvironment()
            val bytes = ctx.assets.open("sr_strong.onnx").use { it.readBytes() }
            val prefs = ctx.getSharedPreferences("ki", Context.MODE_PRIVATE)
            val known = prefs.getString("strong_backend", null)
            val knownMs = prefs.getLong("strong_ms", 0L)
            if (known != null && knownMs > 0) {
                progress("Starke KI wird geladen …")
                val s = env.createSession(bytes, if (known == "KI-Chip") npuOptions() else cpuOptions())
                return AiUpscaler(env, s, known, knownMs, true)
            }
            // Erstes Mal: beide Wege messen (dauert etwas)
            val test = testTile()
            progress("Starke KI: messe Prozessor …")
            val cpuSession = env.createSession(bytes, cpuOptions())
            var t0 = System.nanoTime()
            val cpuOut = run(env, cpuSession, test)
            val cpuMs = (System.nanoTime() - t0) / 1_000_000
            var result = AiUpscaler(env, cpuSession, "Prozessor", cpuMs, true)
            try {
                progress("Starke KI: messe KI-Chip …")
                val npuSession = env.createSession(bytes, npuOptions())
                run(env, npuSession, test)
                t0 = System.nanoTime()
                val npuOut = run(env, npuSession, test)
                val npuMs = (System.nanoTime() - t0) / 1_000_000
                var diff = 0f
                for (i in cpuOut.indices) diff = max(diff, abs(cpuOut[i] - npuOut[i]))
                if (diff < 0.08f && npuMs < cpuMs) {
                    cpuSession.close()
                    result = AiUpscaler(env, npuSession, "KI-Chip", npuMs, true)
                } else npuSession.close()
            } catch (_: Throwable) {
            }
            prefs.edit().putString("strong_backend", result.backend).putLong("strong_ms", result.msPerTile).apply()
            return result
        }

        private fun create(ctx: Context): AiUpscaler {
            val env = OrtEnvironment.getEnvironment()
            val bytes = ctx.assets.open("sr128.onnx").use { it.readBytes() }

            val cpu = OrtSession.SessionOptions().apply {
                setIntraOpNumThreads(max(2, min(6, Runtime.getRuntime().availableProcessors() - 2)))
                setOptimizationLevel(OrtSession.SessionOptions.OptLevel.ALL_OPT)
            }
            val cpuSession = env.createSession(bytes, cpu)
            val test = testTile()
            val cpuOut = run(env, cpuSession, test)
            val cpuMs = bench(env, cpuSession, test)

            // KI-Chip (NNAPI) probieren – nur nehmen, wenn er schneller ist und richtig rechnet
            try {
                val npu = OrtSession.SessionOptions().apply {
                    addNnapi(EnumSet.of(NNAPIFlags.USE_FP16, NNAPIFlags.CPU_DISABLED))
                }
                val npuSession = env.createSession(bytes, npu)
                val npuOut = run(env, npuSession, test)
                var diff = 0f
                for (i in cpuOut.indices) diff = max(diff, abs(cpuOut[i] - npuOut[i]))
                val npuMs = bench(env, npuSession, test)
                if (diff < 0.08f && npuMs < cpuMs) {
                    cpuSession.close()
                    return AiUpscaler(env, npuSession, "KI-Chip", npuMs, false)
                }
                npuSession.close()
            } catch (_: Throwable) {
            }
            return AiUpscaler(env, cpuSession, "Prozessor", cpuMs, false)
        }

        private fun testTile() = FloatArray(3 * TT) { i ->
            val p = i % TT
            val x = p % T
            val y = p / T
            (((x * 7 + y * 13 + (i / TT) * 50) % 256) / 255f) * 0.5f + if ((x / 8 + y / 8) % 2 == 0) 0.4f else 0.1f
        }

        private fun bench(env: OrtEnvironment, s: OrtSession, input: FloatArray): Long {
            run(env, s, input)
            val t0 = System.nanoTime()
            repeat(2) { run(env, s, input) }
            return (System.nanoTime() - t0) / 2_000_000
        }

        private fun run(env: OrtEnvironment, s: OrtSession, input: FloatArray): FloatArray {
            val buf: FloatBuffer = ByteBuffer.allocateDirect(input.size * 4).order(ByteOrder.nativeOrder()).asFloatBuffer()
            buf.put(input)
            buf.rewind()
            OnnxTensor.createTensor(env, buf, longArrayOf(1, 3, T.toLong(), T.toLong())).use { t ->
                s.run(mapOf("input" to t)).use { r ->
                    val fb = (r[0] as OnnxTensor).floatBuffer
                    val out = FloatArray(fb.remaining())
                    fb.get(out)
                    return out
                }
            }
        }
    }

    private fun starts(n: Int): IntArray =
        if (n <= T) intArrayOf(0) else IntArray((n + ST - 1) / ST) { it * ST }

    /** Anzahl Kacheln für ein Bild dieser Größe. */
    fun tilesFor(w: Int, h: Int) = starts(w).size * starts(h).size

    /**
     * Skaliert ein Bild ×4 hoch – oder mit half = true ×2 (KI rechnet ×4,
     * je 2×2 Pixel werden gemittelt: feinere Kanten als direktes ×2).
     * progress(fertig, gesamt)
     */
    fun upscale(src: Bitmap, half: Boolean = false, progress: ((Int, Int) -> Unit)? = null): Bitmap {
        val w = src.width
        val h = src.height
        val px = IntArray(w * h)
        src.getPixels(px, 0, w, 0, 0, w, h)
        val f = if (half) 2 else 4
        val wf = w * f
        val out = IntArray(wf * h * f)
        val inBuf = FloatArray(3 * TT)

        val ys = starts(h)
        val xs = starts(w)
        val total = ys.size * xs.size
        var done = 0

        for (ty in ys) for (tx in xs) {
            val y0 = if (h <= T) 0 else min(max(ty - OV, 0), h - T)
            val x0 = if (w <= T) 0 else min(max(tx - OV, 0), w - T)

            // Kachel füllen (am Rand mit Randpixeln auffüllen)
            for (yy in 0 until T) {
                val sy = min(y0 + yy, h - 1)
                for (xx in 0 until T) {
                    val p = px[sy * w + min(x0 + xx, w - 1)]
                    val i = yy * T + xx
                    inBuf[i] = ((p shr 16) and 255) / 255f
                    inBuf[TT + i] = ((p shr 8) and 255) / 255f
                    inBuf[2 * TT + i] = (p and 255) / 255f
                }
            }

            val o = run(env, session, inBuf)

            // Nur den Innenbereich übernehmen, damit keine Kachelnähte entstehen
            val vy0 = if (h <= T) 0 else ty
            val vy1 = if (h <= T) h else min(ty + ST, h)
            val vx0 = if (w <= T) 0 else tx
            val vx1 = if (w <= T) w else min(tx + ST, w)
            for (y in vy0 until vy1) {
                val ly = y - y0
                for (x in vx0 until vx1) {
                    val idx = ly * T + (x - x0)
                    if (direct) {
                        // Ausgabe [3, 4T, 4T]: Pixel (ly*4+i, lx*4+j)
                        val T4 = 4 * T
                        val plane = T4 * T4
                        val lx = x - x0
                        if (!half) {
                            for (i in 0 until 4) {
                                val row = (y * 4 + i) * wf + x * 4
                                val src = (ly * 4 + i) * T4 + lx * 4
                                for (j in 0 until 4) {
                                    val q = src + j
                                    out[row + j] = (0xFF shl 24) or (to8(o[q]) shl 16) or (to8(o[plane + q]) shl 8) or to8(o[2 * plane + q])
                                }
                            }
                        } else {
                            for (i in 0 until 2) {
                                val row = (y * 2 + i) * wf + x * 2
                                for (j in 0 until 2) {
                                    val q = (ly * 4 + 2 * i) * T4 + lx * 4 + 2 * j
                                    val r = (o[q] + o[q + 1] + o[q + T4] + o[q + T4 + 1]) / 4f
                                    val g = (o[plane + q] + o[plane + q + 1] + o[plane + q + T4] + o[plane + q + T4 + 1]) / 4f
                                    val b = (o[2 * plane + q] + o[2 * plane + q + 1] + o[2 * plane + q + T4] + o[2 * plane + q + T4 + 1]) / 4f
                                    out[row + j] = (0xFF shl 24) or (to8(r) shl 16) or (to8(g) shl 8) or to8(b)
                                }
                            }
                        }
                        continue
                    }
                    val br = inBuf[idx]
                    val bgv = inBuf[TT + idx]
                    val bb = inBuf[2 * TT + idx]
                    if (!half) {
                        for (i in 0 until 4) {
                            val row = (y * 4 + i) * wf + x * 4
                            for (j in 0 until 4) {
                                val k = i * 4 + j
                                val r = to8(o[k * TT + idx] + br)
                                val g = to8(o[(16 + k) * TT + idx] + bgv)
                                val b = to8(o[(32 + k) * TT + idx] + bb)
                                out[row + j] = (0xFF shl 24) or (r shl 16) or (g shl 8) or b
                            }
                        }
                    } else {
                        for (i in 0 until 2) {
                            val row = (y * 2 + i) * wf + x * 2
                            for (j in 0 until 2) {
                                var r = 0f
                                var g = 0f
                                var b = 0f
                                for (a in 0 until 2) for (c in 0 until 2) {
                                    val k = (2 * i + a) * 4 + (2 * j + c)
                                    r += o[k * TT + idx]
                                    g += o[(16 + k) * TT + idx]
                                    b += o[(32 + k) * TT + idx]
                                }
                                out[row + j] = (0xFF shl 24) or (to8(r / 4f + br) shl 16) or
                                    (to8(g / 4f + bgv) shl 8) or to8(b / 4f + bb)
                            }
                        }
                    }
                }
            }
            done++
            progress?.invoke(done, total)
        }
        return Bitmap.createBitmap(out, wf, h * f, Bitmap.Config.ARGB_8888)
    }

    private fun to8(v: Float): Int {
        val i = (v * 255f + 0.5f).toInt()
        return if (i < 0) 0 else if (i > 255) 255 else i
    }
}
