import de.john.kameracheck.Align
import kotlin.math.*
import java.util.Random

fun main() {
    val w = 946; val h = 742
    val rnd = Random(3)
    // Motiv: viele kleine "Buchstaben"-Striche auf hellem Grund, leicht weich
    val base = FloatArray(w * h) { 230f }
    repeat(2500) {
        val x0 = rnd.nextInt(w); val y0 = rnd.nextInt(h)
        val len = 4 + rnd.nextInt(14); val ang = rnd.nextDouble() * PI
        for (t in 0 until len) {
            val x = (x0 + t * cos(ang)).toInt(); val y = (y0 + t * sin(ang)).toInt()
            for (a in 0..1) for (b in 0..1) if (x + b in 0 until w && y + a in 0 until h) base[(y + a) * w + x + b] = 40f
        }
    }
    fun sample(x: Float, y: Float): Float {
        val ix = x.toInt().coerceIn(0, w - 2); val iy = y.toInt().coerceIn(0, h - 2)
        val fx = (x - ix).coerceIn(0f, 1f); val fy = (y - iy).coerceIn(0f, 1f)
        val k = iy * w + ix
        return (base[k] * (1 - fx) + base[k + 1] * fx) * (1 - fy) + (base[k + w] * (1 - fx) + base[k + w + 1] * fx) * fy
    }
    fun frame(angDeg: Double, tx: Float, ty: Float, scale: Double): Pair<ByteArray, (Float, Float) -> FloatArray> {
        val a = Math.toRadians(angDeg); val c = cos(a) * scale; val s = sin(a) * scale
        val cx = w / 2f; val cy = h / 2f
        val T: (Float, Float) -> FloatArray = { x, y ->
            floatArrayOf((c * (x - cx) - s * (y - cy) + cx + tx).toFloat(), (s * (x - cx) + c * (y - cy) + cy + ty).toFloat())
        }
        val out = ByteArray(w * h)
        for (y in 0 until h) for (x in 0 until w) {
            val p = T(x.toFloat(), y.toFloat())
            out[y * w + x] = (sample(p[0], p[1]) + rnd.nextGaussian().toFloat() * 4f).roundToInt().coerceIn(0, 255).toByte()
        }
        return out to T
    }
    val (ref, _) = frame(0.0, 0f, 0f, 1.0)
    val refS = Align.downscale4(ref, w, h)
    for ((name, p) in listOf("nur Verschiebung" to doubleArrayOf(0.0, 3.4, -2.7, 1.0),
            "Drehung 0,4°" to doubleArrayOf(0.4, 3.4, -2.7, 1.0),
            "Drehung 0,8° + Zoom 0,3 %" to doubleArrayOf(0.8, -5.2, 4.1, 1.003))) {
        val (f, T) = frame(p[0], p[1].toFloat(), p[2].toFloat(), p[3])
        val g = Align.align(ref, refS, f, w, h)
        val fld = Align.localField(ref, f, w, h, g[0], g[1])
        var eg = 0.0; var el = 0.0; var n = 0
        val o = FloatArray(2)
        for (y in 40 until h - 40 step 20) for (x in 40 until w - 40 step 20) {
            val t = T(x.toFloat(), y.toFloat()); val tdx = t[0] - x; val tdy = t[1] - y
            eg += (g[0] - tdx).pow(2) + (g[1] - tdy).pow(2)
            fld.at(x.toFloat(), y.toFloat(), o)
            el += (o[0] - tdx).pow(2) + (o[1] - tdy).pow(2); n++
        }
        println("%-28s Fehler global %.2f px · kachelweise %.2f px".format(name, sqrt(eg / n), sqrt(el / n)))
    }
}
