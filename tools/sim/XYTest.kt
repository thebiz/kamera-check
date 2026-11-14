import de.john.kameracheck.BlurEstimate
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
fun main(args: Array<String>) {
    for (n in args) {
        val bb = ByteBuffer.wrap(File(n).readBytes()).order(ByteOrder.LITTLE_ENDIAN)
        val w = bb.int; val h = bb.int
        val y = FloatArray(w * h) { bb.float }
        val r = BlurEstimate.measure(y, w, h)
        // Maske: nur obere Hälfte (Test des Masken-Pfads)
        val mask = BooleanArray(w * h) { it / w < h / 2 }
        val m = BlurEstimate.measure(y, w, h, mask)
        println("$n → σx=%.2f σy=%.2f (%d Kanten) | mit Maske σx=%.2f σy=%.2f text=%b".format(r.sx, r.sy, r.edges, m.sx, m.sy, m.fromText))
    }
}
