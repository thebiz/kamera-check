import de.john.kameracheck.RawSR
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder

fun main(args: Array<String>) {
    RawSR.localAlign = args.isNotEmpty() && args[0] == "lokal"
    val bb = ByteBuffer.wrap(File("/tmp/simsr/data.bin").readBytes()).order(ByteOrder.LITTLE_ENDIAN)
    val rw = bb.int; val rh = bb.int; val n = bb.int; val cx = bb.int; val cy = bb.int; val cw = bb.int; val ch = bb.int
    val raws = MutableList<ShortArray?>(n) { ShortArray(rw * rh).also { a -> for (i in a.indices) a[i] = bb.short } }
    val yref = ByteArray(rw * rh).also { bb.get(it) }
    val merged = FloatArray(cw * ch) { bb.float }
    val p = RawSR.Params(0, 1023f, floatArrayOf(64f, 64f, 64f, 64f), floatArrayOf(2f, 1f, 1.43f))
    val out = RawSR.run(raws, RawSR.Geo(0, 0, rw, rh, cx, cy, cw, ch), p, 0, yref, merged, 4) { }
    File("/tmp/simsr/sr.pgm").outputStream().use { o ->
        o.write("P5\n${out.wo} ${out.ho}\n255\n".toByteArray())
        o.write(ByteArray(out.y.size) { out.y[it].toInt().coerceIn(0, 255).toByte() })
    }
}
