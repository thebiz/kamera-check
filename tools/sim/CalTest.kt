import de.john.kameracheck.Calib
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
fun main() {
    var bb = ByteBuffer.wrap(File("/tmp/cal/sharp.bin").readBytes()).order(ByteOrder.LITTLE_ENDIAN)
    val w = bb.int; val h = bb.int; val u = bb.float
    val planes = Array(3) { FloatArray(w * h) { bb.float } }
    val (sig, edges) = Calib.measureSharpness(planes, w, h, u, 0f)!!
    println("gemessen (Sensor-px): R %.3f/%.3f  G %.3f/%.3f  B %.3f/%.3f  (%d Kanten)".format(sig[0][0], sig[0][1], sig[1][0], sig[1][1], sig[2][0], sig[2][1], edges))
    val s = Calib.Sample(0.5f, 0.5f, sig, edges)
    println("Farbunterschied %.0f %%".format(Calib.colorSpread(s) * 100))
    bb = ByteBuffer.wrap(File("/tmp/cal/wedge.bin").readBytes()).order(ByteOrder.LITTLE_ENDIAN)
    val ww = bb.int; val wh = bb.int
    val y = FloatArray(ww * wh) { bb.float }
    val wd = Calib.analyzeWedge(y, ww, wh)
    if (wd == null) println("Graustufen nicht erkannt") else
        println("Graustufen: R² %.4f, Streulicht %.2f %%, Stufen %s".format(wd.r2, wd.flare * 100, wd.levels.joinToString { "%.2f".format(it) }))
    // Profil-Interpolation
    val samples = listOf(
        Calib.Sample(0.5f, 0.5f, arrayOf(floatArrayOf(.7f,.7f), floatArrayOf(.6f,.6f), floatArrayOf(.8f,.8f)), 100),
        Calib.Sample(0.2f, 0.2f, arrayOf(floatArrayOf(1.2f,1.2f), floatArrayOf(1f,1f), floatArrayOf(1.3f,1.3f)), 100),
    )
    println("Profil Mitte %s, nahe Ecke %s, dazwischen %s".format(
        Calib.sigmaAt(samples, .5f, .5f).joinToString { "%.2f".format(it) },
        Calib.sigmaAt(samples, .22f, .2f).joinToString { "%.2f".format(it) },
        Calib.sigmaAt(samples, .35f, .35f).joinToString { "%.2f".format(it) }))
}
