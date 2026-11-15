package de.john.kameracheck

import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import ai.onnxruntime.providers.NNAPIFlags
import android.content.Context
import java.nio.FloatBuffer
import java.util.EnumSet
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

/**
 * Entrausch-Netz DRUNet (Graustufen, 256 × 256) für das KI-Entschärfen [Dpir].
 * Beim ersten Mal wird gemessen, ob KI-Chip oder Prozessor schneller (und richtig) rechnet.
 * Gewichte: KAIR / DPIR von Kai Zhang (MIT-Lizenz), umgewandelt mit tools/export_drunet.py.
 */
class DeblurNet private constructor(
    private val env: OrtEnvironment,
    private val session: OrtSession,
    val backend: String,
    val msPerTile: Long,
) {
    fun denoise(x: FloatArray, sigma: Float): FloatArray {
        val n = Dpir.T * Dpir.T
        val buf = FloatBuffer.allocate(2 * n)
        buf.put(x)
        for (i in 0 until n) buf.put(sigma)
        buf.rewind()
        OnnxTensor.createTensor(env, buf, longArrayOf(1, 2, Dpir.T.toLong(), Dpir.T.toLong())).use { t ->
            session.run(mapOf("x" to t)).use { r ->
                val out = (r[0] as OnnxTensor).floatBuffer
                return FloatArray(n).also { out.get(it) }
            }
        }
    }

    companion object {
        @Volatile private var inst: DeblurNet? = null

        fun get(ctx: Context, progress: (String) -> Unit): DeblurNet {
            inst?.let { return it }
            synchronized(this) {
                inst?.let { return it }
                return create(ctx.applicationContext, progress).also { inst = it }
            }
        }

        private fun cpuOptions() = OrtSession.SessionOptions().apply {
            setIntraOpNumThreads(max(2, min(6, Runtime.getRuntime().availableProcessors() - 2)))
            setOptimizationLevel(OrtSession.SessionOptions.OptLevel.ALL_OPT)
        }

        private fun npuOptions() = OrtSession.SessionOptions().apply {
            addNnapi(EnumSet.of(NNAPIFlags.USE_FP16, NNAPIFlags.CPU_DISABLED))
        }

        private fun create(ctx: Context, progress: (String) -> Unit): DeblurNet {
            val env = OrtEnvironment.getEnvironment()
            val bytes = ctx.assets.open("drunet256.onnx").use { it.readBytes() }
            val prefs = ctx.getSharedPreferences("ki", Context.MODE_PRIVATE)
            val known = prefs.getString("dpir_backend", null)
            val knownMs = prefs.getLong("dpir_ms", 0L)
            if (known != null && knownMs > 0) {
                progress("Schärfe-KI wird geladen …")
                return DeblurNet(env, env.createSession(bytes, if (known == "KI-Chip") npuOptions() else cpuOptions()), known, knownMs)
            }
            // Erstes Mal: beide Wege messen
            val test = FloatArray(Dpir.T * Dpir.T) { i -> if ((i / 7 + i % 13) % 5 == 0) 0.2f else 0.8f }
            progress("Schärfe-KI: messe Prozessor …")
            val cpu = DeblurNet(env, env.createSession(bytes, cpuOptions()), "Prozessor", 0)
            var t0 = System.nanoTime()
            val a = cpu.denoise(test, 10f / 255f)
            val cpuMs = (System.nanoTime() - t0) / 1_000_000
            var result = DeblurNet(env, cpu.session, "Prozessor", cpuMs)
            try {
                progress("Schärfe-KI: messe KI-Chip …")
                val npu = DeblurNet(env, env.createSession(bytes, npuOptions()), "KI-Chip", 0)
                npu.denoise(test, 10f / 255f)
                t0 = System.nanoTime()
                val b = npu.denoise(test, 10f / 255f)
                val npuMs = (System.nanoTime() - t0) / 1_000_000
                var diff = 0f
                for (i in a.indices) diff = max(diff, abs(a[i] - b[i]))
                if (diff < 0.05f && npuMs < cpuMs) {
                    cpu.session.close()
                    result = DeblurNet(env, npu.session, "KI-Chip", npuMs)
                } else npu.session.close()
            } catch (_: Throwable) {
            }
            prefs.edit().putString("dpir_backend", result.backend).putLong("dpir_ms", result.msPerTile).apply()
            return result
        }
    }
}
