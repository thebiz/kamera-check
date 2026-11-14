package de.john.kameracheck

import android.Manifest
import android.annotation.SuppressLint
import android.app.Activity
import android.content.ContentValues
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.ImageFormat
import android.graphics.Matrix
import android.graphics.Rect
import android.graphics.SurfaceTexture
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.InsetDrawable
import android.graphics.drawable.LayerDrawable
import android.hardware.camera2.CameraCaptureSession
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraDevice
import android.hardware.camera2.CameraManager
import android.hardware.camera2.CaptureRequest
import android.hardware.camera2.params.MeteringRectangle
import android.hardware.camera2.params.OutputConfiguration
import android.hardware.camera2.params.SessionConfiguration
import android.media.ImageReader
import android.os.Bundle
import android.os.Handler
import android.os.HandlerThread
import android.provider.MediaStore
import android.util.Size
import android.view.GestureDetector
import android.view.Gravity
import android.view.MotionEvent
import android.view.OrientationEventListener
import android.view.ScaleGestureDetector
import android.view.Surface
import android.view.TextureView
import android.view.View
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.SeekBar
import android.widget.TextView
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.Executor
import kotlin.math.ln
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.roundToInt

class MainActivity : Activity() {

    // ── Oberfläche ──
    private lateinit var texture: TextureView
    private lateinit var zoomLabel: TextView
    private lateinit var status: TextView
    private lateinit var seek: SeekBar
    private lateinit var shutter: View
    private lateinit var macroChip: TextView
    private val presetChips = mutableListOf<Pair<Float, TextView>>()

    // ── Kamera ──
    private val camId = "0"
    private var bgThread: HandlerThread? = null
    private var bg: Handler? = null
    private var chars: CameraCharacteristics? = null
    private var device: CameraDevice? = null
    private var session: CameraCaptureSession? = null
    private var reader: ImageReader? = null
    private var previewSurface: Surface? = null
    private var previewSize = Size(1440, 1080)
    private var yuvSize = Size(4096, 3072)

    private var zoom = 1f
    private var maxZoom = 10f
    private var afMode = CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_PICTURE
    private var afRegion: MeteringRectangle? = null
    private var macroAvailable = false
    private var macro = false
    private var sensorOrientation = 90
    private var deviceOrientation = 0
    private lateinit var orientationListener: OrientationEventListener

    // ── Bildserie ──
    @Volatile private var busy = false
    private var collecting: Collector? = null

    private inner class Collector(val region: Region, val count: Int, val zoomAtShot: Float, val rotation: Int) {
        val frames = mutableListOf<Frame?>()
        var done = false
    }

    // ───────────────────────── Aufbau ─────────────────────────

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.statusBarColor = BG
        window.navigationBarColor = BG
        buildUi()

        orientationListener = object : OrientationEventListener(this) {
            override fun onOrientationChanged(o: Int) {
                if (o != ORIENTATION_UNKNOWN) deviceOrientation = ((o + 45) / 90 * 90) % 360
            }
        }
    }

    private fun dp(v: Float) = (v * resources.displayMetrics.density).roundToInt()

    @SuppressLint("ClickableViewAccessibility")
    private fun buildUi() {
        val screenW = resources.displayMetrics.widthPixels

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(BG)
        }

        // Vorschau im 4:3-Hochformat
        val previewBox = FrameLayout(this)
        texture = TextureView(this)
        previewBox.addView(texture, FrameLayout.LayoutParams(-1, -1))

        status = TextView(this).apply {
            setTextColor(FG)
            textSize = 15f
            gravity = Gravity.CENTER
            setPadding(dp(14f), dp(8f), dp(14f), dp(8f))
            background = GradientDrawable().apply {
                cornerRadius = dp(18f).toFloat()
                setColor(0xCC000000.toInt())
            }
            visibility = View.GONE
        }
        previewBox.addView(status, FrameLayout.LayoutParams(-2, -2, Gravity.CENTER_HORIZONTAL or Gravity.BOTTOM).apply {
            bottomMargin = dp(16f)
        })

        val info = TextView(this).apply {
            text = "Check"
            setTextColor(FG)
            textSize = 13f
            setPadding(dp(12f), dp(6f), dp(12f), dp(6f))
            background = chipBg(false)
            setOnClickListener { startActivity(Intent(this@MainActivity, CheckActivity::class.java)) }
        }
        previewBox.addView(info, FrameLayout.LayoutParams(-2, -2, Gravity.END or Gravity.TOP).apply {
            topMargin = dp(12f); rightMargin = dp(12f)
        })

        root.addView(previewBox, LinearLayout.LayoutParams(screenW, screenW * 4 / 3))

        // Bedienfeld
        val panel = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            setPadding(dp(20f), dp(14f), dp(20f), dp(14f))
        }

        zoomLabel = TextView(this).apply {
            text = "1×"
            setTextColor(FG)
            textSize = 22f
            typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
            gravity = Gravity.CENTER
        }
        panel.addView(zoomLabel)

        seek = SeekBar(this).apply {
            max = 1000
            setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(s: SeekBar, p: Int, fromUser: Boolean) {
                    if (fromUser) setZoom(10f.pow(p / 1000f * log10(maxZoom)), fromSeek = true)
                }
                override fun onStartTrackingTouch(s: SeekBar) {}
                override fun onStopTrackingTouch(s: SeekBar) {}
            })
        }
        panel.addView(seek, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(6f) })

        val chips = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
        }
        for (z in floatArrayOf(1f, 2f, 4f, 8f)) {
            val c = chip("${z.toInt()}×") { setZoom(z) }
            presetChips += z to c
            chips.addView(c, chipParams())
        }
        macroChip = chip("Makro") { toggleMacro() }
        macroChip.visibility = View.GONE
        chips.addView(macroChip, chipParams())
        panel.addView(chips, LinearLayout.LayoutParams(-2, -2).apply { topMargin = dp(10f) })

        shutter = View(this).apply {
            background = shutterBg()
            setOnClickListener { shoot() }
        }
        panel.addView(shutter, LinearLayout.LayoutParams(dp(76f), dp(76f)).apply { topMargin = dp(18f) })

        val hint = TextView(this).apply {
            text = "Tippen: Fokus · Doppeltippen: Autofokus · Zwei Finger: Zoom"
            setTextColor(MUTED)
            textSize = 12f
            gravity = Gravity.CENTER
        }
        panel.addView(hint, LinearLayout.LayoutParams(-2, -2).apply { topMargin = dp(12f) })

        root.addView(panel, LinearLayout.LayoutParams(-1, 0, 1f))
        setContentView(root)

        // Gesten auf der Vorschau
        val scale = ScaleGestureDetector(this, object : ScaleGestureDetector.SimpleOnScaleGestureListener() {
            override fun onScale(d: ScaleGestureDetector): Boolean {
                setZoom(zoom * d.scaleFactor)
                return true
            }
        })
        val taps = GestureDetector(this, object : GestureDetector.SimpleOnGestureListener() {
            override fun onSingleTapConfirmed(e: MotionEvent): Boolean {
                focusAt(e.x, e.y)
                return true
            }
            override fun onDoubleTap(e: MotionEvent): Boolean {
                resetFocus()
                return true
            }
        })
        texture.setOnTouchListener { _, e ->
            scale.onTouchEvent(e)
            if (!scale.isInProgress) taps.onTouchEvent(e)
            true
        }
        updateChips()
    }

    private fun log10(v: Float) = (ln(v) / ln(10f))

    private fun chipBg(selected: Boolean) = GradientDrawable().apply {
        cornerRadius = dp(16f).toFloat()
        if (selected) setColor(FG) else {
            setColor(Color.TRANSPARENT)
            setStroke(dp(1.5f), 0xFF555555.toInt())
        }
    }

    private fun chip(label: String, onClick: () -> Unit) = TextView(this).apply {
        text = label
        textSize = 14f
        gravity = Gravity.CENTER
        setPadding(dp(14f), dp(7f), dp(14f), dp(7f))
        setTextColor(FG)
        background = chipBg(false)
        setOnClickListener { onClick() }
    }

    private fun chipParams() = LinearLayout.LayoutParams(-2, -2).apply {
        leftMargin = dp(4f); rightMargin = dp(4f)
    }

    private fun shutterBg(): LayerDrawable {
        val ring = GradientDrawable().apply {
            shape = GradientDrawable.OVAL
            setColor(Color.TRANSPARENT)
            setStroke(dp(4f), FG)
        }
        val dot = GradientDrawable().apply {
            shape = GradientDrawable.OVAL
            setColor(FG)
        }
        return LayerDrawable(arrayOf(ring, InsetDrawable(dot, dp(9f))))
    }

    private fun updateChips() {
        for ((z, c) in presetChips) {
            val sel = abs(zoom - z) < 0.05f
            c.background = chipBg(sel)
            c.setTextColor(if (sel) BG else FG)
        }
        macroChip.background = chipBg(macro)
        macroChip.setTextColor(if (macro) BG else FG)
    }

    private fun abs(v: Float) = kotlin.math.abs(v)

    private fun showStatus(text: String?) = runOnUiThread {
        if (text == null) status.visibility = View.GONE
        else {
            status.text = text
            status.visibility = View.VISIBLE
        }
    }

    // ───────────────────────── Lebenszyklus ─────────────────────────

    override fun onResume() {
        super.onResume()
        orientationListener.enable()
        startBg()
        if (checkSelfPermission(Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(arrayOf(Manifest.permission.CAMERA), 1)
            return
        }
        startWhenReady()
    }

    override fun onPause() {
        orientationListener.disable()
        closeCamera()
        stopBg()
        super.onPause()
    }

    override fun onRequestPermissionsResult(code: Int, perms: Array<out String>, results: IntArray) {
        if (results.isNotEmpty() && results[0] == PackageManager.PERMISSION_GRANTED) startWhenReady()
        else showStatus("Ohne Kamera-Erlaubnis geht's leider nicht")
    }

    private fun startWhenReady() {
        if (texture.isAvailable) openCamera()
        else texture.surfaceTextureListener = object : TextureView.SurfaceTextureListener {
            override fun onSurfaceTextureAvailable(s: SurfaceTexture, w: Int, h: Int) = openCamera()
            override fun onSurfaceTextureSizeChanged(s: SurfaceTexture, w: Int, h: Int) {}
            override fun onSurfaceTextureDestroyed(s: SurfaceTexture) = true
            override fun onSurfaceTextureUpdated(s: SurfaceTexture) {}
        }
    }

    private fun startBg() {
        if (bgThread != null) return
        bgThread = HandlerThread("kamera").also { it.start() }
        bg = Handler(bgThread!!.looper)
    }

    private fun stopBg() {
        bgThread?.quitSafely()
        bgThread = null
        bg = null
    }

    // ───────────────────────── Kamera öffnen ─────────────────────────

    @SuppressLint("MissingPermission")
    private fun openCamera() {
        if (device != null) return
        val handler = bg ?: return
        val cm = getSystemService(CameraManager::class.java)
        val c = cm.getCameraCharacteristics(camId)
        chars = c
        sensorOrientation = c.get(CameraCharacteristics.SENSOR_ORIENTATION) ?: 90
        maxZoom = c.get(CameraCharacteristics.CONTROL_ZOOM_RATIO_RANGE)?.upper ?: 10f

        val afModes = c.get(CameraCharacteristics.CONTROL_AF_AVAILABLE_MODES) ?: IntArray(0)
        macroAvailable = CaptureRequest.CONTROL_AF_MODE_MACRO in afModes
        runOnUiThread { macroChip.visibility = if (macroAvailable) View.VISIBLE else View.GONE }

        val map = c.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP)!!
        yuvSize = map.getOutputSizes(ImageFormat.YUV_420_888).maxByOrNull { it.width.toLong() * it.height }!!
        previewSize = map.getOutputSizes(SurfaceTexture::class.java)
            .filter { it.width * 3 == it.height * 4 && it.width <= 1920 }
            .maxByOrNull { it.width } ?: Size(1440, 1080)

        reader = ImageReader.newInstance(yuvSize.width, yuvSize.height, ImageFormat.YUV_420_888, 14).apply {
            setOnImageAvailableListener({ r ->
                val img = r.acquireNextImage() ?: return@setOnImageAvailableListener
                try {
                    val col = collecting
                    if (col != null && !col.done && col.frames.size < col.count) {
                        col.frames.add(SuperRes.copyFrame(img, col.region))
                        showStatus("Halt still … ${col.frames.size}/${col.count}")
                        if (col.frames.size == col.count) finishBurst(col)
                    }
                } finally {
                    img.close()
                }
            }, handler)
        }

        cm.openCamera(camId, object : CameraDevice.StateCallback() {
            override fun onOpened(d: CameraDevice) {
                device = d
                createSession()
            }
            override fun onDisconnected(d: CameraDevice) {
                d.close()
                device = null
            }
            override fun onError(d: CameraDevice, error: Int) {
                d.close()
                device = null
                showStatus("Kamera-Fehler $error")
            }
        }, handler)
    }

    private fun createSession() {
        val d = device ?: return
        val handler = bg ?: return
        val st = texture.surfaceTexture ?: return
        st.setDefaultBufferSize(previewSize.width, previewSize.height)
        val ps = Surface(st)
        previewSurface = ps
        val outputs = listOf(OutputConfiguration(ps), OutputConfiguration(reader!!.surface))
        val exec = Executor { handler.post(it) }
        d.createCaptureSession(SessionConfiguration(SessionConfiguration.SESSION_REGULAR, outputs, exec,
            object : CameraCaptureSession.StateCallback() {
                override fun onConfigured(s: CameraCaptureSession) {
                    session = s
                    startPreview()
                }
                override fun onConfigureFailed(s: CameraCaptureSession) {
                    showStatus("Kamera ließ sich nicht starten")
                }
            }))
    }

    private fun closeCamera() {
        collecting?.let { if (!it.done) busy = false }
        collecting = null
        try { session?.close() } catch (_: Exception) {}
        session = null
        device?.close()
        device = null
        reader?.close()
        reader = null
        previewSurface?.release()
        previewSurface = null
    }

    // ───────────────────────── Vorschau, Zoom, Fokus ─────────────────────────

    private fun baseRequest(template: Int): CaptureRequest.Builder {
        val b = device!!.createCaptureRequest(template)
        b.set(CaptureRequest.CONTROL_MODE, CaptureRequest.CONTROL_MODE_AUTO)
        b.set(CaptureRequest.CONTROL_AF_MODE, afMode)
        afRegion?.let {
            b.set(CaptureRequest.CONTROL_AF_REGIONS, arrayOf(it))
            b.set(CaptureRequest.CONTROL_AE_REGIONS, arrayOf(it))
        }
        val ois = chars?.get(CameraCharacteristics.LENS_INFO_AVAILABLE_OPTICAL_STABILIZATION)
        if (ois?.contains(CaptureRequest.LENS_OPTICAL_STABILIZATION_MODE_ON) == true) {
            b.set(CaptureRequest.LENS_OPTICAL_STABILIZATION_MODE, CaptureRequest.LENS_OPTICAL_STABILIZATION_MODE_ON)
        }
        return b
    }

    private fun startPreview() {
        val s = session ?: return
        val ps = previewSurface ?: return
        try {
            val b = baseRequest(CameraDevice.TEMPLATE_PREVIEW)
            b.addTarget(ps)
            b.set(CaptureRequest.CONTROL_ZOOM_RATIO, zoom)
            s.setRepeatingRequest(b.build(), null, bg)
        } catch (_: Exception) {
        }
    }

    private fun setZoom(z: Float, fromSeek: Boolean = false) {
        zoom = z.coerceIn(1f, maxZoom)
        zoomLabel.text = "${fmt(zoom)}×"
        if (!fromSeek) seek.progress = (ln(zoom) / ln(maxZoom) * 1000).roundToInt()
        updateChips()
        if (!busy) bg?.post { startPreview() }
    }

    private fun fmt(v: Float): String {
        val r = (v * 10).roundToInt() / 10f
        return if (r == r.toInt().toFloat()) r.toInt().toString() else r.toString().replace('.', ',')
    }

    private fun focusAt(vx: Float, vy: Float) {
        val c = chars ?: return
        val active = c.get(CameraCharacteristics.SENSOR_INFO_ACTIVE_ARRAY_SIZE) ?: return
        val nx = (vx / texture.width).coerceIn(0f, 1f)
        val ny = (vy / texture.height).coerceIn(0f, 1f)
        // Hochformat → Sensorkoordinaten
        val (sxN, syN) = if (sensorOrientation == 270) (1 - ny) to nx else ny to (1 - nx)
        val sx = (sxN * active.width()).roundToInt()
        val sy = (syN * active.height()).roundToInt()
        val half = active.width() / 14
        val rect = Rect(
            max(0, sx - half), max(0, sy - half),
            min(active.width() - 1, sx + half), min(active.height() - 1, sy + half),
        )
        afRegion = MeteringRectangle(rect, MeteringRectangle.METERING_WEIGHT_MAX - 1)
        afMode = if (macro) CaptureRequest.CONTROL_AF_MODE_MACRO else CaptureRequest.CONTROL_AF_MODE_AUTO
        triggerAf()
        showStatus(if (macro) "Makro-Fokus" else "Fokus fixiert")
        bg?.postDelayed({ if (!busy) showStatus(null) }, 1200)
    }

    private fun resetFocus() {
        afRegion = null
        macro = false
        afMode = CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_PICTURE
        updateChips()
        bg?.post { startPreview() }
        showStatus("Autofokus")
        bg?.postDelayed({ if (!busy) showStatus(null) }, 1000)
    }

    private fun toggleMacro() {
        macro = !macro
        updateChips()
        if (macro) focusAt(texture.width / 2f, texture.height / 2f) else resetFocus()
    }

    private fun triggerAf() {
        val handler = bg ?: return
        handler.post {
            val s = session ?: return@post
            val ps = previewSurface ?: return@post
            try {
                s.stopRepeating()
                val t = baseRequest(CameraDevice.TEMPLATE_PREVIEW)
                t.addTarget(ps)
                t.set(CaptureRequest.CONTROL_ZOOM_RATIO, zoom)
                t.set(CaptureRequest.CONTROL_AF_TRIGGER, CaptureRequest.CONTROL_AF_TRIGGER_CANCEL)
                s.capture(t.build(), null, handler)
                t.set(CaptureRequest.CONTROL_AF_TRIGGER, CaptureRequest.CONTROL_AF_TRIGGER_START)
                s.capture(t.build(), null, handler)
                startPreview()
            } catch (_: Exception) {
            }
        }
    }

    // ───────────────────────── Auslösen ─────────────────────────

    private fun shoot() {
        if (busy) return
        val s = session ?: return
        val handler = bg ?: return
        busy = true
        shutter.alpha = 0.4f

        val z = zoom
        val count = if (z >= 2f) 12 else 8
        val rotation = (sensorOrientation + deviceOrientation) % 360
        val col = Collector(Region.compute(yuvSize.width, yuvSize.height, z), count, z, rotation)
        collecting = col
        showStatus("Halt still …")

        handler.post {
            try {
                s.stopRepeating()
                val b = baseRequest(CameraDevice.TEMPLATE_PREVIEW)
                b.addTarget(reader!!.surface)
                // Ganzes Sensorbild holen, Ausschnitt rechnen wir selbst
                b.set(CaptureRequest.CONTROL_ZOOM_RATIO, 1f)
                b.set(CaptureRequest.CONTROL_AE_LOCK, true)
                b.set(CaptureRequest.EDGE_MODE, CaptureRequest.EDGE_MODE_OFF)
                b.set(CaptureRequest.NOISE_REDUCTION_MODE, CaptureRequest.NOISE_REDUCTION_MODE_FAST)
                val req = b.build()
                s.captureBurst(List(count) { req }, null, handler)
            } catch (e: Exception) {
                failShot("Aufnahme fehlgeschlagen")
                return@post
            }
            // Falls Bilder verloren gehen: nach 4 s mit dem weitermachen, was da ist
            handler.postDelayed({
                if (collecting === col && !col.done) {
                    if (col.frames.size >= 3) finishBurst(col) else failShot("Zu wenige Bilder bekommen")
                }
            }, 4000)
        }
    }

    private fun failShot(msg: String) {
        collecting = null
        busy = false
        startPreview()
        runOnUiThread { shutter.alpha = 1f }
        showStatus(msg)
        bg?.postDelayed({ showStatus(null) }, 2500)
    }

    private fun finishBurst(col: Collector) {
        col.done = true
        collecting = null
        startPreview()
        val frames = col.frames
        Thread {
            try {
                val res = SuperRes.process(frames, col.region, col.zoomAtShot) { showStatus(it) }
                showStatus("Speichere …")
                val stamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.GERMANY).format(Date())
                val tag = "${fmt(col.zoomAtShot).replace(',', '-')}x"
                save(rotate(res.single, col.rotation), "zoom_${stamp}_${tag}_1-einzel")
                save(rotate(res.merged, col.rotation), "zoom_${stamp}_${tag}_2-mehrbild")
                showStatus("Gespeichert ✓  ${res.used} von ${res.total} Bildern verrechnet")
            } catch (e: OutOfMemoryError) {
                showStatus("Zu wenig Speicher – etwas mehr zoomen")
            } catch (e: Exception) {
                showStatus("Fehler: ${e.message}")
            } finally {
                busy = false
                runOnUiThread { shutter.alpha = 1f }
                status.postDelayed({ if (!busy) showStatus(null) }, 3500)
            }
        }.start()
    }

    private fun rotate(b: Bitmap, deg: Int): Bitmap {
        if (deg == 0) return b
        val r = Bitmap.createBitmap(b, 0, 0, b.width, b.height, Matrix().apply { postRotate(deg.toFloat()) }, true)
        if (r !== b) b.recycle()
        return r
    }

    private fun save(b: Bitmap, name: String) {
        val values = ContentValues().apply {
            put(MediaStore.Images.Media.DISPLAY_NAME, "$name.jpg")
            put(MediaStore.Images.Media.MIME_TYPE, "image/jpeg")
            put(MediaStore.Images.Media.RELATIVE_PATH, "Pictures/Kamera-Check")
            put(MediaStore.Images.Media.IS_PENDING, 1)
        }
        val uri = contentResolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values)
            ?: throw IllegalStateException("Galerie nicht erreichbar")
        contentResolver.openOutputStream(uri)!!.use { b.compress(Bitmap.CompressFormat.JPEG, 95, it) }
        values.clear()
        values.put(MediaStore.Images.Media.IS_PENDING, 0)
        contentResolver.update(uri, values, null, null)
        b.recycle()
    }

    companion object {
        private const val BG = 0xFF111111.toInt()
        private const val FG = 0xFFEDEDED.toInt()
        private const val MUTED = 0xFF8A8A8A.toInt()
    }
}
