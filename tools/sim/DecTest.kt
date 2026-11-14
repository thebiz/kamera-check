import de.john.kameracheck.Deconv
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
fun load(n: String): Triple<FloatArray, Int, Int> {
    val bb = ByteBuffer.wrap(File(n).readBytes()).order(ByteOrder.LITTLE_ENDIAN)
    val w = bb.int; val h = bb.int
    return Triple(FloatArray(w * h) { bb.float }, w, h)
}
fun main() {
    val (y, w, h) = load("/tmp/dec/y.bin")
    val (out, n) = Deconv.deconvolve(y, w, h, 2.5f, 2.5f, 150, 0.012f, 4, 600_000)
    println("klein: $n Durchgänge")
    val bb = ByteBuffer.allocate(out.size * 4).order(ByteOrder.LITTLE_ENDIAN); out.forEach { bb.putFloat(it) }
    File("/tmp/dec/yout2.bin").writeBytes(bb.array())
    val (yb, wb, hb) = load("/tmp/dec/ybig.bin")
    val t0 = System.nanoTime()
    val (_, nb) = Deconv.deconvolve(yb, wb, hb, 2.5f, 2.5f, 150, 0.012f, 4, 30_000)
    println("2048×1536 mit 30 s Budget: $nb Durchgänge in %.1f s".format((System.nanoTime() - t0) / 1e9))
}
