import de.john.kameracheck.Deconv
import kotlin.math.*

fun main() {
    val w = 240; val h = 120
    // links blasse Striche (Kontrast 0,12), rechts kräftige (Kontrast 0,75), je 2 px breit
    val sharp = FloatArray(w * h) { i ->
        val x = i % w
        val stripe = (x % 8) < 2
        if (!stripe) 0.85f else if (x < w / 2) 0.73f else 0.10f
    }
    val s = 1.2f
    val k = FloatArray(9) { exp(-((it - 4f) * (it - 4f)) / (2 * s * s)) }.let { a -> val t = a.sum(); FloatArray(9) { a[it] / t } }
    fun blur(src: FloatArray): FloatArray {
        val t = FloatArray(w * h); val o = FloatArray(w * h)
        for (y in 0 until h) for (x in 0 until w) { var a = 0f; for (j in -4..4) a += k[j + 4] * src[y * w + (x + j).coerceIn(0, w - 1)]; t[y * w + x] = a }
        for (y in 0 until h) for (x in 0 until w) { var a = 0f; for (j in -4..4) a += k[j + 4] * t[(y + j).coerceIn(0, h - 1) * w + x]; o[y * w + x] = a }
        return o
    }
    val b = blur(sharp)
    val (d, _) = Deconv.deconvolve(b, w, h, s, s, 80, 0.012f, 2, 60000L, {}, null)
    val t = Deconv.tame(b, d, w, h)
    fun contrast(a: FloatArray, left: Boolean): Float {
        val y = h / 2; val xs = if (left) 20 until w / 2 - 20 else w / 2 + 20 until w - 20
        val v = xs.map { a[y * w + it] }; return v.max() - v.min()
    }
    fun halo(a: FloatArray): Float { val y = h / 2; return (w / 2 + 20 until w - 20).maxOf { a[y * w + it] } - 0.85f }
    fun under(a: FloatArray): Float { val y = h / 2; return (w / 2 + 20 until w - 20).minOf { a[y * w + it] } }
    for ((n, a) in listOf("Original scharf" to sharp, "verwischt" to b, "Richardson-Lucy" to d, "RL gezähmt" to t))
        println("%-16s blass %.3f · kräftig %.3f · dunkel %.3f · heller Saum %+.3f".format(n, contrast(a, true), contrast(a, false), under(a), halo(a)))
}
