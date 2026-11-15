package de.john.kameracheck

import android.annotation.SuppressLint
import android.app.Activity
import android.graphics.ImageFormat
import android.graphics.Rect
import android.graphics.YuvImage
import android.hardware.camera2.CameraCaptureSession
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraDevice
import android.hardware.camera2.CameraManager
import android.hardware.camera2.CameraMetadata
import android.hardware.camera2.CaptureRequest
import android.hardware.camera2.params.OutputConfiguration
import android.hardware.camera2.params.SessionConfiguration
import android.media.ImageReader
import android.os.Build
import android.os.Handler
import android.os.HandlerThread
import android.util.Size
import java.io.ByteArrayOutputStream
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executor
import java.util.concurrent.TimeUnit

/**
 * 50-MP-Test: Versucht auf allen bekannten Wegen, ein Bild in voller Sensorauflösung zu bekommen,
 * obwohl Xiaomi „Ultra-HR“ nicht offiziell freigibt.
 *
 * Wege:
 * 1. „High-Resolution“-Größen (Android listet sie getrennt, weil sie langsamer sind)
 * 2. Maximal-Auflösungs-Modus (SENSOR_PIXEL_MODE) – der offizielle Ultra-HR-Weg
 * 3. die einzelnen physischen Kameras hinter der logischen Kamera
 * 4. versteckte Kamera-Nummern, die das System nicht auflistet
 *
 * Jeder Versuch nimmt wirklich ein Bild auf und meldet dessen tatsächliche Größe.
 * Gelungene Bilder landen als „test50mp_…“ in der Galerie, damit man die Schärfe prüfen kann.
 */
class FullResTest(private val a: Activity, private val log: (String) -> Unit) {

    private val cm = a.getSystemService(CameraManager::class.java)

    private class Attempt(
        val camId: String, val physId: String?, val size: Size, val format: Int,
        val maxRes: Boolean, val label: String,
    )

    fun run() {
        log("50-MP-Test · ${Build.MANUFACTURER} ${Build.MODEL} · Android ${Build.VERSION.RELEASE}")
        log("App-Name (Paket): ${a.packageName} · Kameras laut System: ${cm.cameraIdList.joinToString()}\n")
        val ids = LinkedHashSet<String>()
        ids += cm.cameraIdList
        for (i in 0..9) ids += i.toString()

        val attempts = ArrayList<Attempt>()
        for (id in ids) {
            val c = try { cm.getCameraCharacteristics(id) } catch (_: Exception) { continue }
            val listed = id in cm.cameraIdList
            val facing = if (c.get(CameraCharacteristics.LENS_FACING) == CameraCharacteristics.LENS_FACING_FRONT) "Front" else "Rück"
            if (facing == "Front") continue
            log("── Kamera $id ($facing${if (listed) "" else ", versteckt"})")
            collect(id, null, c, attempts)
            for (p in c.physicalCameraIds) {
                val pc = try { cm.getCameraCharacteristics(p) } catch (_: Exception) { continue }
                log("   physisch $p:")
                collect(id, p, pc, attempts)
            }
            // Hersteller-Schlüssel, die nach hoher Auflösung klingen
            val keys = (c.keys.map { it.name } + c.availableCaptureRequestKeys.map { it.name })
                .filter { k -> listOf("remosaic", "superres", "ultra", "hires", "highres", "fullsize", "binning", "sat.", "qcfa", "quad").any { k.lowercase().contains(it) } }
                .distinct()
            if (keys.isNotEmpty()) log("   Hersteller-Schlüssel: ${keys.joinToString(", ")}")
        }

        // nur Versuche, die mehr als das normale 12-MP-Bild versprechen
        val todo = attempts.filter { it.size.width.toLong() * it.size.height > 14_000_000L }
            .distinctBy { "${it.camId}/${it.physId}/${it.size}/${it.format}/${it.maxRes}" }
        log("\n${todo.size} Versuche mit mehr als 14 MP:\n")
        if (todo.isEmpty()) log("Keine Größe über 14 MP angeboten – die Sperre sitzt schon in der Liste.")
        var ok = 0
        for (t in todo) {
            val r = try { capture(t) } catch (e: Throwable) { "✗ ${e.javaClass.simpleName}: ${e.message?.take(120)}" }
            log("${t.label}\n   → $r")
            if (r.startsWith("✓")) ok++
        }
        log("\nFertig: $ok von ${todo.size} Versuchen lieferten ein Bild.")
        log("Wichtig: Ein Bild mit 50-MP-Größe kann trotzdem nur hochgerechnet sein – die gespeicherten test50mp-Bilder bitte vergleichen.")
    }

    private fun collect(id: String, phys: String?, c: CameraCharacteristics, out: MutableList<Attempt>) {
        val tag = if (phys != null) "Kamera $id › physisch $phys" else "Kamera $id"
        val map = c.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP)
        val parts = ArrayList<String>()
        for ((fmt, fname) in listOf(ImageFormat.JPEG to "JPEG", ImageFormat.YUV_420_888 to "YUV", ImageFormat.RAW_SENSOR to "RAW")) {
            val normal = largest(map?.getOutputSizes(fmt))
            val high = largest(map?.getHighResolutionOutputSizes(fmt))
            normal?.let { parts += "$fname ${mp(it)}"; out += Attempt(id, phys, it, fmt, false, "$tag · $fname normal ${it.width}×${it.height}") }
            high?.let { parts += "$fname-hochaufl. ${mp(it)}"; out += Attempt(id, phys, it, fmt, false, "$tag · $fname „High-Resolution“ ${it.width}×${it.height}") }
        }
        if (Build.VERSION.SDK_INT >= 31) {
            val full = c.get(CameraCharacteristics.SENSOR_INFO_PIXEL_ARRAY_SIZE_MAXIMUM_RESOLUTION)
            if (full != null) parts += "Sensor voll ${mp(full)}"
            val fm = c.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP_MAXIMUM_RESOLUTION)
            for ((fmt, fname) in listOf(ImageFormat.JPEG to "JPEG", ImageFormat.YUV_420_888 to "YUV", ImageFormat.RAW_SENSOR to "RAW")) {
                largest(fm?.getOutputSizes(fmt))?.let {
                    parts += "$fname-Ultra-HR ${mp(it)}"
                    out += Attempt(id, phys, it, fmt, true, "$tag · $fname Ultra-HR-Modus ${it.width}×${it.height}")
                }
            }
        }
        log("   " + if (parts.isEmpty()) "keine Ausgabegrößen" else parts.joinToString(" · "))
    }

    @SuppressLint("MissingPermission")
    private fun capture(t: Attempt): String {
        val ht = HandlerThread("test50").also { it.start() }
        val h = Handler(ht.looper)
        val exec = Executor { h.post(it) }
        var dev: CameraDevice? = null
        var session: CameraCaptureSession? = null
        val reader = ImageReader.newInstance(t.size.width, t.size.height, t.format, 3)
        try {
            // Kamera öffnen
            val opened = CountDownLatch(1)
            var openErr: String? = null
            cm.openCamera(t.camId, object : CameraDevice.StateCallback() {
                override fun onOpened(d: CameraDevice) { dev = d; opened.countDown() }
                override fun onDisconnected(d: CameraDevice) { openErr = "getrennt"; d.close(); opened.countDown() }
                override fun onError(d: CameraDevice, e: Int) { openErr = "Fehler $e"; d.close(); opened.countDown() }
            }, h)
            if (!opened.await(6, TimeUnit.SECONDS)) return "✗ Kamera öffnet nicht (Zeit abgelaufen)"
            val d = dev ?: return "✗ Kamera öffnet nicht ($openErr)"

            // Sitzung
            val cfg = OutputConfiguration(reader.surface)
            if (t.physId != null) cfg.setPhysicalCameraId(t.physId)
            if (t.maxRes && Build.VERSION.SDK_INT >= 31) cfg.addSensorPixelModeUsed(CameraMetadata.SENSOR_PIXEL_MODE_MAXIMUM_RESOLUTION)
            val ready = CountDownLatch(1)
            var sErr: String? = null
            d.createCaptureSession(SessionConfiguration(SessionConfiguration.SESSION_REGULAR, listOf(cfg), exec,
                object : CameraCaptureSession.StateCallback() {
                    override fun onConfigured(s: CameraCaptureSession) { session = s; ready.countDown() }
                    override fun onConfigureFailed(s: CameraCaptureSession) { sErr = "Sitzung abgelehnt"; ready.countDown() }
                }))
            if (!ready.await(6, TimeUnit.SECONDS)) return "✗ Sitzung: Zeit abgelaufen"
            val s = session ?: return "✗ ${sErr ?: "Sitzung abgelehnt"}"

            // Aufnehmen: erst ein paar Bilder zum Einpendeln von Belichtung/Fokus, das letzte zählt
            var lastW = 0
            var lastH = 0
            var bytes: ByteArray? = null
            val got = CountDownLatch(4)
            reader.setOnImageAvailableListener({ r ->
                val img = r.acquireLatestImage() ?: return@setOnImageAvailableListener
                lastW = img.width
                lastH = img.height
                bytes = when (t.format) {
                    ImageFormat.JPEG -> {
                        val b = img.planes[0].buffer
                        ByteArray(b.remaining()).also { b.get(it) }
                    }
                    ImageFormat.YUV_420_888 -> yuvToJpeg(img)
                    else -> null
                }
                img.close()
                got.countDown()
            }, h)
            val b = d.createCaptureRequest(CameraDevice.TEMPLATE_STILL_CAPTURE)
            b.addTarget(reader.surface)
            b.set(CaptureRequest.CONTROL_AF_MODE, CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_PICTURE)
            if (t.maxRes && Build.VERSION.SDK_INT >= 31) {
                b.set(CaptureRequest.SENSOR_PIXEL_MODE, CameraMetadata.SENSOR_PIXEL_MODE_MAXIMUM_RESOLUTION)
            }
            val req = b.build()
            val t0 = System.currentTimeMillis()
            try {
                s.setRepeatingRequest(req, null, h)
            } catch (_: Exception) {
                // manche Modi erlauben nur Einzelaufnahmen
                repeat(4) { s.capture(req, null, h) }
            }
            got.await(12, TimeUnit.SECONDS)
            val ms = System.currentTimeMillis() - t0
            try { s.stopRepeating() } catch (_: Exception) {}
            if (lastW == 0) return "✗ kein Bild geliefert (${ms / 1000} s gewartet)"
            val mpx = lastW.toLong() * lastH / 1e6
            val sizeTxt = "${lastW}×${lastH} (${"%.1f".format(mpx)} MP)"
            val saved = bytes?.let { jb ->
                val name = "test50mp_${t.camId}${t.physId?.let { "-$it" } ?: ""}_${if (t.maxRes) "ultra" else "hr"}_${System.currentTimeMillis() % 100000}"
                saveJpeg(jb, name)
                " · gespeichert als $name"
            } ?: ""
            val verdict = if (mpx > 30) "✓ BILD IN VOLLER GRÖSSE" else "✓ Bild, aber nur"
            return "$verdict $sizeTxt$saved"
        } finally {
            try { session?.close() } catch (_: Exception) {}
            try { dev?.close() } catch (_: Exception) {}
            reader.close()
            ht.quitSafely()
        }
    }

    private fun yuvToJpeg(img: android.media.Image): ByteArray {
        val w = img.width
        val h = img.height
        val nv21 = ByteArray(w * h * 3 / 2)
        val yP = img.planes[0]
        val yb = yP.buffer
        for (r in 0 until h) {
            yb.position(r * yP.rowStride)
            yb.get(nv21, r * w, w)
        }
        val u = img.planes[1]
        val v = img.planes[2]
        var o = w * h
        for (r in 0 until h / 2) for (c in 0 until w / 2) {
            nv21[o++] = v.buffer.get(r * v.rowStride + c * v.pixelStride)
            nv21[o++] = u.buffer.get(r * u.rowStride + c * u.pixelStride)
        }
        val out = ByteArrayOutputStream()
        YuvImage(nv21, ImageFormat.NV21, w, h, null).compressToJpeg(Rect(0, 0, w, h), 95, out)
        return out.toByteArray()
    }

    private fun saveJpeg(b: ByteArray, name: String) {
        val values = android.content.ContentValues().apply {
            put(android.provider.MediaStore.Images.Media.DISPLAY_NAME, "$name.jpg")
            put(android.provider.MediaStore.Images.Media.MIME_TYPE, "image/jpeg")
            put(android.provider.MediaStore.Images.Media.RELATIVE_PATH, "Pictures/KameraCheck")
        }
        val uri = a.contentResolver.insert(android.provider.MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values) ?: return
        a.contentResolver.openOutputStream(uri)?.use { it.write(b) }
    }

    private fun largest(s: Array<Size>?): Size? = s?.maxByOrNull { it.width.toLong() * it.height }
    private fun mp(s: Size) = "%.1f MP (%d×%d)".format(s.width.toLong() * s.height / 1e6, s.width, s.height)
}
