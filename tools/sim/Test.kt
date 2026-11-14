import de.john.kameracheck.RawSR
import java.io.DataInputStream
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder

fun main() {
    val bytes = File("/tmp/simsr/data.bin").readBytes()
    val bb = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
    val rw = bb.int; val rh = bb.int; val n = bb.int; val cx = bb.int; val cy = bb.int; val cw = bb.int; val ch = bb.int
    val raws = MutableList<ShortArray?>(n) { ShortArray(rw * rh).also { a -> for (i in a.indices) a[i] = bb.short } }
    val yref = ByteArray(rw * rh).also { bb.get(it) }
    val merged = FloatArray(cw * ch) { bb.float }
    val p = RawSR.Params(0, 1023f, floatArrayOf(64f, 64f, 64f, 64f), floatArrayOf(2f, 1f, 1.43f))
    val g = RawSR.Geo(0, 0, rw, rh, cx, cy, cw, ch)
    val t0 = System.nanoTime()
    val out = RawSR.run(raws, g, p, 0, yref, merged, 4) { println(it) }
    println("Zeit ${(System.nanoTime() - t0) / 1_000_000} ms, u=${out.u}, ${out.wo}x${out.ho}, Bilder=${out.used}, rawToYuv=${out.rawToYuv.toList()}")
    val f = File("/tmp/simsr/sr.pgm")
    f.outputStream().use { o ->
        o.write("P5\n${out.wo} ${out.ho}\n255\n".toByteArray())
        o.write(ByteArray(out.y.size) { out.y[it].toInt().coerceIn(0, 255).toByte() })
    }
}
