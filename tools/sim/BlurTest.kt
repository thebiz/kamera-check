import de.john.kameracheck.BlurEstimate
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder

fun main(args: Array<String>) {
    for (name in args) {
        val bb = ByteBuffer.wrap(File(name).readBytes()).order(ByteOrder.LITTLE_ENDIAN)
        val w = bb.int; val h = bb.int
        val y = FloatArray(w * h) { bb.float }
        val t0 = System.nanoTime()
        val s = BlurEstimate.sigma(y, w, h)
        println("$name: σ = %.2f, Faktor %.2f (%d ms)".format(s, BlurEstimate.aiScale(s), (System.nanoTime() - t0) / 1_000_000))
    }
}
