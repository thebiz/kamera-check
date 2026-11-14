package de.john.kameracheck

import android.Manifest
import android.annotation.SuppressLint
import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import android.graphics.ImageFormat
import android.graphics.Matrix
import android.graphics.Outline
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
import android.hardware.camera2.CaptureResult
import android.hardware.camera2.TotalCaptureResult
import android.hardware.camera2.params.MeteringRectangle
import android.hardware.camera2.params.OutputConfiguration
import android.hardware.camera2.params.SessionConfiguration
import android.media.Image
import android.media.ImageReader
import android.os.Bundle
import android.os.Handler
import android.os.HandlerThread
import android.os.SystemClock
import android.util.Size
import android.view.GestureDetector
import android.view.Gravity
import android.view.MotionEvent
import android.view.OrientationEventListener
import android.view.ScaleGestureDetector
import android.view.Surface
import android.view.TextureView
import android.view.View
import android.view.ViewOutlineProvider
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.SeekBar
import android.widget.TextView
import java.io.File
import java.nio.ByteOrder
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.Executor
import java.util.concurrent.Executors
import kotlin.math.ln
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

class MainActivity : Activity() {

    // ── Oberfläche ──
    private lateinit var texture: TextureView
    private lateinit var zoomLabel: TextView
    private lateinit var status: TextView
    private lateinit var seek: SeekBar
    private lateinit var shutter: View
    private lateinit var macroChip: TextView
    private lateinit var lupeChip: TextView
    private lateinit var lupeBox: FrameLayout
    private lateinit var lupeImage: ImageView
    private lateinit var lupeLabel: TextView
    private lateinit var thumb: ImageView
    private val presetChips = mutableListOf<Pair<Float, TextView>>()

    // ── Kamera ──
    private val camId = "0"
    private var bgThread: HandlerThread? = null
    private var bg: Handler? = null
    private var chars: CameraCharacteristics? = null
    private var device: CameraDevice? = null
    private var session: CameraCaptureSession? = null
    private var reader: ImageReader? = null
    private var rawReader: ImageReader? = null
    private var rawParamsBase: RawBase? = null
    private var previewSurface: Surface? = null
    private var previewSize = Size(1440, 1080)
    private var yuvSize = Size(4096, 3072)
    private var wantOpen = false

    private var zoom = 1f
    private var maxZoom = 10f
    private var afMode = CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_PICTURE
    private var afRegion: MeteringRectangle? = null
    private var macroAvailable = false
    private var macro = false
    private var sensorOrientation = 90
    private var deviceOrientation = 0
    private lateinit var orientationListener: OrientationEventListener

    // ── KI ──
    @Volatile private var ai: AiUpscaler? = null
    private val aiWorker = Executors.newSingleThreadExecutor()
    private var lupeOn = true
    @Volatile private var lupeBusy = false

    // ── Bildserie ──
    @Volatile private var busy = false
    private var collecting: Collector? = null

    /** Fest vom Sensor: Bayer-Muster, Weißpunkt, Schwarzwerte. */
    private class RawBase(val cfa: Int, val white: Float, val black: FloatArray)

    private inner class Collector(
        val region: Region, val count: Int, val zoomAtShot: Float, val rotation: Int, val withRaw: Boolean,
    ) {
        val yuv = java.util.TreeMap<Long, Frame>()
        val raw = java.util.TreeMap<Long, ShortArray>()
        val results = java.util.TreeMap<Long, TotalCaptureResult>()
        var done = false

        fun complete(): Int = if (withRaw) yuv.keys.count { it in raw } else yuv.size
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

        // Vorschaubild des letzten Fotos
        val last = File(cacheDir, ViewerActivity.KI)
        if (last.exists()) {
            BitmapFactory.decodeFile(last.path, BitmapFactory.Options().apply { inSampleSize = 16 })?.let {
                thumb.setImageBitmap(it)
                thumb.visibility = View.VISIBLE
            }
        }

        // KI laden und schnellsten Rechenweg ermitteln
        showStatus("KI wird geladen …")
        aiWorker.execute {
            try {
                val a = AiUpscaler.get(this)
                ai = a
                showStatus("KI bereit · ${a.backend} · ${a.msPerTile} ms")
                status.postDelayed({ if (!busy) showStatus(null) }, 2500)
            } catch (e: Throwable) {
                showStatus("KI nicht verfügbar: ${e.message}")
            }
        }
    }

    override fun onDestroy() {
        aiWorker.shutdown()
        super.onDestroy()
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

        // KI-Lupe: Mitte des Bildes, von der KI ×4 rekonstruiert
        lupeImage = ImageView(this).apply { scaleType = ImageView.ScaleType.FIT_XY }
        lupeLabel = TextView(this).apply {
            text = "KI ×4"
            setTextColor(FG)
            textSize = 11f
            setPadding(dp(8f), dp(3f), dp(8f), dp(3f))
            background = GradientDrawable().apply {
                cornerRadius = dp(10f).toFloat()
                setColor(0xAA000000.toInt())
            }
        }
        val radius = dp(18f).toFloat()
        lupeBox = FrameLayout(this).apply {
            background = GradientDrawable().apply {
                cornerRadius = radius
                setColor(0xFF000000.toInt())
                setStroke(dp(2f), FG)
            }
            outlineProvider = object : ViewOutlineProvider() {
                override fun getOutline(v: View, o: Outline) = o.setRoundRect(0, 0, v.width, v.height, radius)
            }
            clipToOutline = true
            addView(lupeImage, FrameLayout.LayoutParams(-1, -1).apply {
                setMargins(dp(2f), dp(2f), dp(2f), dp(2f))
            })
            addView(lupeLabel, FrameLayout.LayoutParams(-2, -2, Gravity.TOP or Gravity.START).apply {
                topMargin = dp(8f); leftMargin = dp(8f)
            })
            visibility = View.GONE
        }
        val lupeSize = min(512, (screenW * 0.62f).roundToInt())
        previewBox.addView(lupeBox, FrameLayout.LayoutParams(lupeSize, lupeSize, Gravity.CENTER))

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
            setPadding(dp(20f), dp(12f), dp(20f), dp(12f))
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
                    if (fromUser) setZoom(Math.pow(maxZoom.toDouble(), p / 1000.0).toFloat(), fromSeek = true)
                }
                override fun onStartTrackingTouch(s: SeekBar) {}
                override fun onStopTrackingTouch(s: SeekBar) {}
            })
        }
        panel.addView(seek, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(4f) })

        val chips = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
        }
        for (z in floatArrayOf(1f, 2f, 4f, 8f)) {
            val c = chip("${z.toInt()}×") { setZoom(z) }
            presetChips += z to c
            chips.addView(c, chipParams())
        }
        panel.addView(chips, LinearLayout.LayoutParams(-2, -2).apply { topMargin = dp(8f) })

        val chips2 = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
        }
        lupeChip = chip("KI-Lupe") { toggleLupe() }
        chips2.addView(lupeChip, chipParams())
        macroChip = chip("Makro") { toggleMacro() }
        macroChip.visibility = View.GONE
        chips2.addView(macroChip, chipParams())
        panel.addView(chips2, LinearLayout.LayoutParams(-2, -2).apply { topMargin = dp(8f) })

        // Auslöser-Zeile: Vorschaubild · Auslöser · Platzhalter
        val shotRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        thumb = ImageView(this).apply {
            scaleType = ImageView.ScaleType.CENTER_CROP
            val r = dp(12f).toFloat()
            background = GradientDrawable().apply {
                cornerRadius = r
                setColor(0xFF000000.toInt())
                setStroke(dp(1.5f), 0xFF555555.toInt())
            }
            outlineProvider = object : ViewOutlineProvider() {
                override fun getOutline(v: View, o: Outline) = o.setRoundRect(0, 0, v.width, v.height, r)
            }
            clipToOutline = true
            visibility = View.INVISIBLE
            setOnClickListener { startActivity(Intent(this@MainActivity, ViewerActivity::class.java)) }
        }
        shotRow.addView(thumb, LinearLayout.LayoutParams(dp(54f), dp(54f)))
        shutter = View(this).apply {
            background = shutterBg()
            setOnClickListener { shoot() }
        }
        shotRow.addView(shutter, LinearLayout.LayoutParams(dp(74f), dp(74f)).apply {
            leftMargin = dp(32f); rightMargin = dp(32f)
        })
        shotRow.addView(View(this), LinearLayout.LayoutParams(dp(54f), dp(54f)))
        panel.addView(shotRow, LinearLayout.LayoutParams(-2, -2).apply { topMargin = dp(14f) })

        val hint = TextView(this).apply {
            text = "Tippen: Fokus · Doppeltippen: Autofokus · Zwei Finger: Zoom"
            setTextColor(MUTED)
            textSize = 12f
            gravity = Gravity.CENTER
        }
        panel.addView(hint, LinearLayout.LayoutParams(-2, -2).apply { topMargin = dp(10f) })

        root.addView(panel, LinearLayout.LayoutParams(-1, 0, 1f))
        setContentView(root)

        texture.surfaceTextureListener = object : TextureView.SurfaceTextureListener {
            override fun onSurfaceTextureAvailable(s: SurfaceTexture, w: Int, h: Int) {
                if (wantOpen) bg?.post { openCamera() }
            }
            override fun onSurfaceTextureSizeChanged(s: SurfaceTexture, w: Int, h: Int) {}
            override fun onSurfaceTextureDestroyed(s: SurfaceTexture) = true
            override fun onSurfaceTextureUpdated(s: SurfaceTexture) = feedLupe()
        }

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

    private fun styleChip(c: TextView, sel: Boolean) {
        c.background = chipBg(sel)
        c.setTextColor(if (sel) BG else FG)
    }

    private fun updateChips() {
        for ((z, c) in presetChips) styleChip(c, kotlin.math.abs(zoom - z) < 0.05f)
        styleChip(macroChip, macro)
        styleChip(lupeChip, lupeOn)
    }

    private fun showStatus(text: String?) = runOnUiThread {
        if (text == null) status.visibility = View.GONE
        else {
            status.text = text
            status.visibility = View.VISIBLE
        }
    }

    // ───────────────────────── KI-Lupe ─────────────────────────

    private fun toggleLupe() {
        lupeOn = !lupeOn
        if (!lupeOn) lupeBox.visibility = View.GONE
        updateChips()
    }

    /** Wird bei jedem Vorschaubild aufgerufen; schickt die Bildmitte an die KI, sobald sie frei ist. */
    private fun feedLupe() {
        val a = ai ?: return
        if (!lupeOn || lupeBusy || busy || device == null) return
        val tw = texture.width
        val th = texture.height
        if (tw < AiUpscaler.T || th < AiUpscaler.T) return
        lupeBusy = true
        val full = texture.bitmap ?: run { lupeBusy = false; return }
        val crop = Bitmap.createBitmap(
            full, (tw - AiUpscaler.T) / 2, (th - AiUpscaler.T) / 2, AiUpscaler.T, AiUpscaler.T,
        )
        full.recycle()
        aiWorker.execute {
            try {
                val t0 = SystemClock.elapsedRealtime()
                val out = a.upscale(crop)
                val ms = SystemClock.elapsedRealtime() - t0
                runOnUiThread {
                    if (lupeOn && !busy) {
                        lupeImage.setImageBitmap(out)
                        lupeLabel.text = "KI ×4 · ${a.backend} · $ms ms"
                        lupeBox.visibility = View.VISIBLE
                    }
                    lupeBusy = false
                }
            } catch (_: Throwable) {
                lupeBusy = false
            }
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
        wantOpen = false
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
        wantOpen = true
        if (texture.isAvailable) bg?.post { openCamera() }
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
        if (device != null || !wantOpen) return
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

        reader = ImageReader.newInstance(yuvSize.width, yuvSize.height, ImageFormat.YUV_420_888, 8).apply {
            setOnImageAvailableListener({ r ->
                val img = r.acquireNextImage() ?: return@setOnImageAvailableListener
                try {
                    val col = collecting
                    if (col != null && !col.done && col.yuv.size < col.count) {
                        col.yuv[img.timestamp] = SuperRes.copyFrame(img, col.region)
                        onBurstImage(col)
                    }
                } finally {
                    img.close()
                }
            }, handler)
        }

        // RAW nur, wenn das Bayer-Muster bekannt ist und RAW genauso groß ist wie YUV
        rawReader = null
        rawParamsBase = null
        val caps = c.get(CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES) ?: IntArray(0)
        val cfa = c.get(CameraCharacteristics.SENSOR_INFO_COLOR_FILTER_ARRANGEMENT) ?: -1
        val rawSize = map.getOutputSizes(ImageFormat.RAW_SENSOR)?.maxByOrNull { it.width.toLong() * it.height }
        if (CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES_RAW in caps && cfa in 0..3 && rawSize == yuvSize) {
            val white = (c.get(CameraCharacteristics.SENSOR_INFO_WHITE_LEVEL) ?: 1023).toFloat()
            val bp = c.get(CameraCharacteristics.SENSOR_BLACK_LEVEL_PATTERN)
            val black = FloatArray(4) { i -> (bp?.getOffsetForIndex(i and 1, i shr 1) ?: 64).toFloat() }
            rawParamsBase = RawBase(cfa, white, black)
            rawReader = ImageReader.newInstance(rawSize.width, rawSize.height, ImageFormat.RAW_SENSOR, 6).apply {
                setOnImageAvailableListener({ r ->
                    val img = r.acquireNextImage() ?: return@setOnImageAvailableListener
                    try {
                        val col = collecting
                        if (col != null && col.withRaw && !col.done && col.raw.size < col.count) {
                            col.raw[img.timestamp] = copyRaw(img, col.region)
                            onBurstImage(col)
                        }
                    } finally {
                        img.close()
                    }
                }, handler)
            }
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
        val outputs = mutableListOf(OutputConfiguration(ps), OutputConfiguration(reader!!.surface))
        rawReader?.let { outputs += OutputConfiguration(it.surface) }
        val exec = Executor { handler.post(it) }
        d.createCaptureSession(SessionConfiguration(SessionConfiguration.SESSION_REGULAR, outputs, exec,
            object : CameraCaptureSession.StateCallback() {
                override fun onConfigured(s: CameraCaptureSession) {
                    session = s
                    startPreview()
                }
                override fun onConfigureFailed(s: CameraCaptureSession) {
                    if (rawReader != null) {
                        // Ohne RAW nochmal versuchen
                        rawReader?.close()
                        rawReader = null
                        rawParamsBase = null
                        createSession()
                    } else showStatus("Kamera ließ sich nicht starten")
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
        rawReader?.close()
        rawReader = null
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
        status.postDelayed({ if (!busy) showStatus(null) }, 1200)
    }

    private fun resetFocus() {
        afRegion = null
        macro = false
        afMode = CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_PICTURE
        updateChips()
        bg?.post { startPreview() }
        showStatus("Autofokus")
        status.postDelayed({ if (!busy) showStatus(null) }, 1000)
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
        if (ai == null) {
            showStatus("KI lädt noch – einen Moment")
            return
        }
        busy = true
        shutter.alpha = 0.4f
        lupeBox.visibility = View.GONE

        val z = zoom
        val withRaw = z >= 2f && rawReader != null
        val count = if (withRaw) 12 else if (z >= 2f) 16 else 8
        val rotation = (sensorOrientation + deviceOrientation) % 360
        val col = Collector(Region.compute(yuvSize.width, yuvSize.height, z), count, z, rotation, withRaw)
        collecting = col
        showStatus("Halt still …")

        handler.post {
            try {
                s.stopRepeating()
                val b = baseRequest(CameraDevice.TEMPLATE_PREVIEW)
                b.addTarget(reader!!.surface)
                if (withRaw) b.addTarget(rawReader!!.surface)
                // Ganzes Sensorbild holen, den Ausschnitt rechnen wir selbst
                b.set(CaptureRequest.CONTROL_ZOOM_RATIO, 1f)
                b.set(CaptureRequest.CONTROL_AE_LOCK, true)
                b.set(CaptureRequest.CONTROL_AWB_LOCK, true)
                // Kamera-eigenes Schärfen und Glätten aus: echtes Detail behalten,
                // das Rauschen mitteln wir über die Bildserie selbst weg
                b.set(CaptureRequest.EDGE_MODE, CaptureRequest.EDGE_MODE_OFF)
                val nr = chars?.get(CameraCharacteristics.NOISE_REDUCTION_AVAILABLE_NOISE_REDUCTION_MODES) ?: IntArray(0)
                val nrMode = when {
                    CaptureRequest.NOISE_REDUCTION_MODE_OFF in nr -> CaptureRequest.NOISE_REDUCTION_MODE_OFF
                    CaptureRequest.NOISE_REDUCTION_MODE_MINIMAL in nr -> CaptureRequest.NOISE_REDUCTION_MODE_MINIMAL
                    else -> CaptureRequest.NOISE_REDUCTION_MODE_FAST
                }
                b.set(CaptureRequest.NOISE_REDUCTION_MODE, nrMode)
                val req = b.build()
                s.captureBurst(List(count) { req }, object : CameraCaptureSession.CaptureCallback() {
                    override fun onCaptureCompleted(
                        session: CameraCaptureSession, request: CaptureRequest, result: TotalCaptureResult,
                    ) {
                        val ts = result.get(CaptureResult.SENSOR_TIMESTAMP) ?: return
                        if (collecting === col) col.results[ts] = result
                    }
                }, handler)
            } catch (e: Exception) {
                failShot("Aufnahme fehlgeschlagen")
                return@post
            }
            handler.postDelayed({
                if (collecting === col && !col.done) {
                    if (col.complete() >= 4) finishBurst(col) else failShot("Zu wenige Bilder bekommen")
                }
            }, 5000)
        }
    }

    private fun onBurstImage(col: Collector) {
        val n = col.complete()
        showStatus("Halt still … $n/${col.count}")
        if (n >= col.count) finishBurst(col)
    }

    /** RAW-Ausschnitt (16 Bit je Pixel) kopieren. */
    private fun copyRaw(img: Image, r: Region): ShortArray {
        val p = img.planes[0]
        val buf = p.buffer.order(ByteOrder.LITTLE_ENDIAN)
        val rs = p.rowStride
        val ps = p.pixelStride
        val out = ShortArray(r.rw * r.rh)
        for (row in 0 until r.rh) {
            buf.position((r.y0 + row) * rs + r.x0 * ps)
            buf.asShortBuffer().get(out, row * r.rw, r.rw)
        }
        return out
    }

    private fun rawParams(res: TotalCaptureResult?): RawSR.Params {
        val base = rawParamsBase!!
        val dyn = res?.get(CaptureResult.SENSOR_DYNAMIC_BLACK_LEVEL)
        val black = if (dyn != null && dyn.size == 4) dyn.copyOf() else base.black
        val white = res?.get(CaptureResult.SENSOR_DYNAMIC_WHITE_LEVEL)?.toFloat() ?: base.white
        val g = res?.get(CaptureResult.COLOR_CORRECTION_GAINS)
        val wb = if (g != null) floatArrayOf(g.red, (g.greenEven + g.greenOdd) / 2f, g.blue) else floatArrayOf(2f, 1f, 1.6f)
        return RawSR.Params(base.cfa, white, black, wb)
    }

    private fun failShot(msg: String) {
        collecting = null
        busy = false
        startPreview()
        runOnUiThread { shutter.alpha = 1f }
        showStatus(msg)
        status.postDelayed({ showStatus(null) }, 2500)
    }

    private fun finishBurst(col: Collector) {
        if (col.done) return
        col.done = true
        collecting = null
        startPreview()
        val a = ai!!

        val keys = if (col.withRaw) col.yuv.keys.filter { it in col.raw } else col.yuv.keys.toList()
        val frames: MutableList<Frame?> = keys.map { col.yuv[it] }.toMutableList()
        val raws: MutableList<ShortArray?>? = if (col.withRaw) keys.map { col.raw[it] }.toMutableList() else null
        val result = keys.firstNotNullOfOrNull { col.results[it] } ?: col.results.values.firstOrNull()
        col.yuv.clear()
        col.raw.clear()
        col.results.clear()

        aiWorker.execute {
            try {
                val r = col.region

                // 1. YUV-Serie ausrichten und mitteln (Farbe, Helligkeits-Referenz)
                val res = SuperRes.process(frames, r) { showStatus(it) }

                // 2. Mehrbild-Superauflösung aus RAW (echte Details auf feinerem Raster)
                var real: Bitmap = res.merged
                var srNote = ""
                var half = false
                if (raws != null && rawParamsBase != null) {
                    try {
                        val geo = RawSR.Geo(r.x0, r.y0, r.rw, r.rh, r.cx, r.cy, r.cw, r.ch)
                        val out = RawSR.run(raws, geo, rawParams(result), res.refIdx, res.refY, res.mergedY, 4) {
                            showStatus(it)
                        }
                        real = SuperRes.toBitmap(out.y, res.chroma, r, out.u, out.wo, out.ho)
                        res.merged.recycle()
                        srNote = " · RAW ${fmt(out.u)}×"
                        half = true
                    } catch (e: OutOfMemoryError) {
                        srNote = " · ohne RAW (Speicher)"
                    }
                    raws.clear()
                }

                // 3. KI auf das Mehrbild – Größe nach Zeitbudget (≈ 45 s) begrenzen
                val maxTiles = max(40L, 45_000L / max(1L, a.msPerTile)).toInt()
                val longCap = if (half) 2400f else MAX_AI_INPUT.toFloat()
                var sc = min(1f, longCap / max(real.width, real.height))
                while (sc > 0.3f && a.tilesFor((real.width * sc).toInt(), (real.height * sc).toInt()) > maxTiles) sc *= 0.9f
                val input = if (sc < 0.999f)
                    Bitmap.createScaledBitmap(real, (real.width * sc).roundToInt(), (real.height * sc).roundToInt(), true)
                else real

                val t0 = SystemClock.elapsedRealtime()
                val kiBmp = a.upscale(input, half) { done, total ->
                    showStatus("KI rekonstruiert … ${done * 100 / total} %")
                }
                if (input !== real) input.recycle()
                val secs = (SystemClock.elapsedRealtime() - t0) / 100 / 10f
                val kw = kiBmp.width
                val kh = kiBmp.height

                showStatus("Speichere …")
                val stamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.GERMANY).format(Date())
                val tag = "${fmt(col.zoomAtShot).replace(',', '-')}x"

                // Normal (Einzelbild, digital gezoomt)
                val n = rotate(Bitmap.createScaledBitmap(res.single, kw, kh, true), col.rotation)
                cacheJpeg(n, ViewerActivity.NORMAL)
                if (n !== res.single) res.single.recycle()
                n.recycle()

                // Mehrbild
                val sBmp = rotate(Bitmap.createScaledBitmap(real, kw, kh, true), col.rotation)
                if (sBmp !== real) real.recycle()
                cacheJpeg(sBmp, ViewerActivity.REAL)

                // KI
                val k = rotate(kiBmp, col.rotation)
                cacheJpeg(k, ViewerActivity.KI)

                // In die Galerie: Mischung mit Standard-Anteil
                val mix = Bitmap.createBitmap(k.width, k.height, Bitmap.Config.ARGB_8888)
                android.graphics.Canvas(mix).apply {
                    drawBitmap(sBmp, 0f, 0f, null)
                    drawBitmap(k, 0f, 0f, android.graphics.Paint().apply { alpha = ViewerActivity.DEFAULT_MIX * 255 / 100 })
                }
                sBmp.recycle()
                k.recycle()
                ViewerActivity.saveToGallery(this, mix, "zoom_${stamp}_${tag}")
                val small = Bitmap.createScaledBitmap(mix, dp(54f) * 2, dp(54f) * 2 * mix.height / mix.width, true)
                mix.recycle()

                showStatus("Fertig ✓  ${res.used} Bilder$srNote · KI ${fmt(secs)} s")
                runOnUiThread {
                    thumb.setImageBitmap(small)
                    thumb.visibility = View.VISIBLE
                    startActivity(Intent(this, ViewerActivity::class.java))
                }
            } catch (e: OutOfMemoryError) {
                showStatus("Zu wenig Speicher – etwas mehr zoomen")
            } catch (e: Throwable) {
                showStatus("Fehler: ${e.message}")
            } finally {
                busy = false
                runOnUiThread { shutter.alpha = 1f }
                status.postDelayed({ if (!busy) showStatus(null) }, 5000)
            }
        }
    }

    private fun cacheJpeg(b: Bitmap, name: String) {
        File(cacheDir, name).outputStream().use { b.compress(Bitmap.CompressFormat.JPEG, 95, it) }
    }

    private fun rotate(b: Bitmap, deg: Int): Bitmap {
        if (deg == 0) return b
        val r = Bitmap.createBitmap(b, 0, 0, b.width, b.height, Matrix().apply { postRotate(deg.toFloat()) }, true)
        if (r !== b) b.recycle()
        return r
    }

    companion object {
        private const val BG = 0xFF111111.toInt()
        private const val FG = 0xFFEDEDED.toInt()
        private const val MUTED = 0xFF8A8A8A.toInt()
        private const val MAX_AI_INPUT = 1024
    }
}
