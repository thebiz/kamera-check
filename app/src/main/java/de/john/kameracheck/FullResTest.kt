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
        /** Hersteller-Schalter (Name → Wert), die in Anfrage und Sitzung gesetzt werden */
        val vendor: List<Pair<String, Int>> = emptyList(),
        val sessionType: Int = SessionConfiguration.SESSION_REGULAR,
        val tag: String = "",
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
        log("\nFertig Stufe 1: $ok von ${todo.size} Versuchen lieferten ein Bild.")

        // ── Stufe 2: Xiaomi-/MediaTek-Schalter ausprobieren ──
        log("\n══ Stufe 2: Hersteller-Schalter (Remosaic = volle 50-MP-Pixel) ══")
        for (id in listOf("0", "3")) {
            val c = try { cm.getCameraCharacteristics(id) } catch (_: Exception) { continue }
            vendorStage(id, c)
        }
        log("\nFertig. Bilder „test50mp_…“ in der Galerie: normal und remosaic vergleichen (Bildausschnitt und Schärfe).")
        log("Wichtig: Ein Bild mit 50-MP-Größe kann trotzdem nur hochgerechnet sein – die gespeicherten test50mp-Bilder bitte vergleichen.")
    }

    /** Werte der Hersteller-Schlüssel zeigen und mit den Remosaic-Schaltern wirklich aufnehmen. */
    private fun vendorStage(id: String, c: CameraCharacteristics) {
        log("\n── Kamera $id: Werte")
        val interesting = listOf("superresolution", "remosaic", "qcfa", "binning", "sat.rawsize", "fakesat", "quad")
        var srSizes = ArrayList<Pair<Int, Size>>()
        for (k in c.keys) {
            val n = k.name
            if (interesting.none { n.lowercase().contains(it) }) continue
            val v = try { c.get(k) } catch (e: Throwable) { "?" }
            log("   $n = ${show(v)}")
            if (n.contains("availableSuperResolutionStreamConfigurations") && v is IntArray) {
                // Vermutlich wie die Android-Liste: Format, Breite, Höhe, Eingang
                var i = 0
                while (i + 3 < v.size) {
                    val sz = Size(v[i + 1], v[i + 2])
                    if (v[i + 3] == 0 && sz.width.toLong() * sz.height > 14_000_000L) srSizes += v[i] to sz
                    i += 4
                }
            }
        }
        val reqNames = c.availableCaptureRequestKeys.map { it.name }
        val remosaic = reqNames.filter { it.lowercase().contains("remosaic") && !it.lowercase().contains("type") }
            .map { it to 1 }
        log("   Remosaic-Schalter in Anfragen: ${if (remosaic.isEmpty()) "keine" else remosaic.joinToString { it.first }}")
        srSizes = ArrayList(srSizes.distinctBy { "${it.first}/${it.second}" })
        if (srSizes.isNotEmpty()) log("   Super-Resolution-Größen: ${srSizes.joinToString { "Format ${it.first}: ${mp(it.second)}" }}")

        val base = Size(4096, 3072)
        val list = ArrayList<Attempt>()
        list += Attempt(id, null, base, ImageFormat.YUV_420_888, false, "Kamera $id · YUV 12 MP normal (Vergleich)", tag = "normal")
        if (remosaic.isNotEmpty()) {
            list += Attempt(id, null, base, ImageFormat.YUV_420_888, false, "Kamera $id · YUV 12 MP mit Remosaic", remosaic, tag = "remosaic")
            list += Attempt(id, null, base, ImageFormat.RAW_SENSOR, false, "Kamera $id · RAW 12 MP mit Remosaic", remosaic, tag = "remosaic_raw")
        }
        for ((fmt, sz) in srSizes) {
            val f = when (fmt) { 0x23 -> ImageFormat.YUV_420_888; 0x21 -> ImageFormat.JPEG; 0x20 -> ImageFormat.RAW_SENSOR; else -> continue }
            list += Attempt(id, null, sz, f, false, "Kamera $id · Super-Resolution ${sz.width}×${sz.height} (Format $fmt) mit Remosaic", remosaic, tag = "sr")
        }
        // Hersteller-Sitzungsarten (eigene Betriebsmodi der Xiaomi-Kamera) – nur mit der größten Größe
        val big = srSizes.filter { it.first == 0x23 }.maxByOrNull { it.second.width.toLong() * it.second.height }?.second
            ?: Size(8192, 6144)
        for (st in listOf(0x8001, 0x8002, 0x8003, 0x8005, 0x8007, 0x8009, 0x9000, 0x9001)) {
            list += Attempt(id, null, big, ImageFormat.YUV_420_888, false,
                "Kamera $id · ${big.width}×${big.height} in Sitzungsart 0x${Integer.toHexString(st)}", remosaic, st, "mode${Integer.toHexString(st)}")
        }
        for (t in list) {
            val r = try { capture(t) } catch (e: Throwable) { "✗ ${e.javaClass.simpleName}: ${e.message?.take(120)}" }
            log("${t.label}\n   → $r")
        }
    }

    private fun show(v: Any?): String = when (v) {
        null -> "–"
        is IntArray -> if (v.size > 24) v.take(24).joinToString(",", "[", ", … (${v.size})]") else v.joinToString(",", "[", "]")
        is LongArray -> v.joinToString(",", "[", "]")
        is FloatArray -> v.joinToString(",", "[", "]")
        is ByteArray -> if (v.size > 24) "${v.size} Bytes" else v.joinToString(",", "[", "]")
        is Array<*> -> v.joinToString(",", "[", "]")
        else -> v.toString()
    }

    /** Hersteller-Schalter setzen – der Datentyp ist unbekannt, also der Reihe nach probieren. */
    @Suppress("UNCHECKED_CAST")
    private fun setVendor(b: CaptureRequest.Builder, c: CameraCharacteristics, name: String, v: Int): Boolean {
        val key = c.availableCaptureRequestKeys.firstOrNull { it.name == name } as CaptureRequest.Key<Any>? ?: return false
        for (cand in listOf<Any>(v, intArrayOf(v), v.toByte(), byteArrayOf(v.toByte()), v.toLong(), longArrayOf(v.toLong()), v != 0)) {
            try {
                b.set(key, cand)
                return true
            } catch (_: Throwable) {
            }
        }
        return false
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
            val chars = cm.getCameraCharacteristics(t.camId)
            val sc = SessionConfiguration(t.sessionType, listOf(cfg), exec,
                object : CameraCaptureSession.StateCallback() {
                    override fun onConfigured(s: CameraCaptureSession) { session = s; ready.countDown() }
                    override fun onConfigureFailed(s: CameraCaptureSession) { sErr = "Sitzung abgelehnt"; ready.countDown() }
                })
            // Hersteller-Schalter auch als Sitzungs-Parameter: Remosaic schaltet den Sensor-Modus um
            var setOk = 0
            if (t.vendor.isNotEmpty()) {
                val sp = d.createCaptureRequest(CameraDevice.TEMPLATE_STILL_CAPTURE)
                for ((n, v) in t.vendor) if (setVendor(sp, chars, n, v)) setOk++
                sc.sessionParameters = sp.build()
            }
            d.createCaptureSession(sc)
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
            for ((n, v) in t.vendor) setVendor(b, chars, n, v)
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
                val kind = t.tag.ifEmpty { if (t.maxRes) "ultra" else "hr" }
                val name = "test50mp_${t.camId}${t.physId?.let { "-$it" } ?: ""}_${kind}_${System.currentTimeMillis() % 100000}"
                saveJpeg(jb, name)
                " · gespeichert als $name"
            } ?: ""
            val verdict = if (mpx > 30) "✓ BILD IN VOLLER GRÖSSE" else "✓ Bild, aber nur"
            val sw = if (t.vendor.isNotEmpty()) " · Schalter gesetzt: $setOk/${t.vendor.size}" else ""
            return "$verdict $sizeTxt$saved$sw"
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
