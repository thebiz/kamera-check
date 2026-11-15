import de.john.kameracheck.Calib
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
fun main() {
    val bb = ByteBuffer.wrap(File("/tmp/cal/wedges.bin").readBytes()).order(ByteOrder.LITTLE_ENDIAN)
    repeat(bb.int) {
        val nb = ByteArray(bb.int); bb.get(nb)
        val w = bb.int; val h = bb.int
        val y = FloatArray(w * h) { bb.float }
        val r = Calib.analyzeWedge(y, w, h)
        println("%-30s %s".format(String(nb), if (r == null) "NICHT erkannt" else "R² %.4f, Streulicht %.2f %%, %d Stufen genutzt".format(r.r2, r.flare * 100, r.used)))
    }
}
