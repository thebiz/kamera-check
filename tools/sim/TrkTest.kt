import de.john.kameracheck.Tracker
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.*

fun main() {
    val SW = 900; val SH = 700
    val bb = ByteBuffer.wrap(File("/tmp/trk/scene.bin").readBytes()).order(ByteOrder.LITTLE_ENDIAN)
    val scene = IntArray(SW * SH) { bb.int }
    val w = 305; val h = 407           // Vorschau ÷4
    fun frame(ox: Int, oy: Int, gain: Float) = IntArray(w * h) { i ->
        val x = i % w + ox; val y = i / w + oy
        (scene[y.coerceIn(0, SH - 1) * SW + x.coerceIn(0, SW - 1)] * gain).toInt().coerceIn(0, 255)
    }
    val t = Tracker()
    var ox = 300; var oy = 150
    // Ziel: Szenenpunkt (300+120, 150+200) = Klippe
    val tx = 420; val ty = 350
    t.start(frame(ox, oy, 1f), w, h, (tx - ox).toFloat(), (ty - oy).toFloat())
    var maxErr = 0f; var lostCount = 0
    for (k in 1..200) {
        ox += (6 * sin(k / 7.0) + 3 * cos(k / 3.1)).roundToInt()   // Kamera schwenkt / zittert
        oy += (5 * cos(k / 9.0)).roundToInt()
        val gain = 1f + 0.25f * sin(k / 20.0).toFloat()            // Belichtung schwankt
        t.update(frame(ox, oy, gain), w, h)
        if (t.lost) lostCount++
        val err = hypot(t.x - (tx - ox), t.y - (ty - oy))
        maxErr = max(maxErr, err)
    }
    println("max. Abweichung %.1f px (Vorschau ÷4), verloren: %d von 200".format(maxErr, lostCount))
}
