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
import android.view.HapticFeedbackConstants
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
    private lateinit var seek: BigSlider
    private lateinit var shutter: ShutterView
    private lateinit var focusView: FocusView
    private lateinit var holdLabel: TextView
    private lateinit var macroChip: TextView
    private lateinit var mfChip: TextView
    private lateinit var mfRow: LinearLayout
    private lateinit var mfLabel: TextView
    private lateinit var mfSeek: BigSlider
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
    private var afRegion: MeteringRectangle? = null
    private var macroAvailable = false
    private var macro = false
    private var sensorOrientation = 90
    private var deviceOrientation = 0
    private lateinit var orientationListener: OrientationEventListener

    // ── Fokus-Verfolgung ──
    private val tracker = Tracker()
    private var trackBmp: Bitmap? = null
    private var trackPx = IntArray(0)
    private var trackGray = IntArray(0)
    private var pendingTrackStart = false
    private var draggingFocus = false

    // ── Lagesensor ──
    private val gyro = GyroLog()

    // ── KI ──
    @Volatile private var ai: AiUpscaler? = null
    private val aiWorker = Executors.newSingleThreadExecutor()
    private var lupeOn = true
    private var strongOn = false
    private lateinit var strongChip: TextView
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

        /** Bei RAW: so viele YUV-Bilder (Farbe/Helligkeit) – der Rest der Serie ist nur RAW. */
        val yuvCount = if (withRaw) min(count, 10) else count

        /** Fortschritt 0..count */
        fun complete(): Int = if (withRaw) min(raw.size, if (yuv.size >= yuvCount) count else raw.size) else yuv.size
        fun finished(): Boolean = if (withRaw) raw.size >= count && yuv.size >= yuvCount else yuv.size >= count
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

        strongOn = getSharedPreferences("ki", MODE_PRIVATE).getBoolean("strong_on", false)
        updateChips()

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
        if (strongOn) preloadStrong()
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
        focusView = FocusView(this)
        previewBox.addView(focusView, FrameLayout.LayoutParams(-1, -1))

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
        val lupeSize = min(512, (screenW * 0.42f).roundToInt())
        previewBox.addView(lupeBox, FrameLayout.LayoutParams(lupeSize, lupeSize, Gravity.END or Gravity.BOTTOM).apply {
            rightMargin = dp(12f); bottomMargin = dp(64f)
        })

        // Große Ansage beim Auslösen
        holdLabel = TextView(this).apply {
            setTextColor(FG)
            textSize = 20f
            typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
            gravity = Gravity.CENTER
            setPadding(dp(20f), dp(12f), dp(20f), dp(12f))
            background = GradientDrawable().apply {
                cornerRadius = dp(24f).toFloat()
                setColor(0xD9000000.toInt())
            }
            visibility = View.GONE
        }
        previewBox.addView(holdLabel, FrameLayout.LayoutParams(-2, -2, Gravity.CENTER_HORIZONTAL or Gravity.TOP).apply {
            topMargin = dp(64f)
        })

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

        // KI-Stärke: schnell (kleines Modell) oder stark (großes Modell, dauert länger)
        strongChip = TextView(this).apply {
            setTextColor(FG)
            textSize = 13f
            setPadding(dp(12f), dp(6f), dp(12f), dp(6f))
            setOnClickListener { toggleStrong() }
        }
        previewBox.addView(strongChip, FrameLayout.LayoutParams(-2, -2, Gravity.START or Gravity.TOP).apply {
            topMargin = dp(12f); leftMargin = dp(12f)
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

        seek = BigSlider(this).apply {
            onChange = { v -> setZoom(Math.pow(maxZoom.toDouble(), v.toDouble()).toFloat(), fromSeek = true) }
        }
        panel.addView(seek, LinearLayout.LayoutParams(-1, -2))

        val chips = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
        }
        for (z in floatArrayOf(1f, 2f, 4f, 8f)) {
            val c = chip("${z.toInt()}×") { setZoom(z) }
            presetChips += z to c
            chips.addView(c, chipParams())
        }
        panel.addView(chips, LinearLayout.LayoutParams(-2, -2).apply { topMargin = dp(2f) })

        val chips2 = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
        }
        lupeChip = chip("KI-Lupe") { toggleLupe() }
        chips2.addView(lupeChip, chipParams())
        macroChip = chip("Makro") { toggleMacro() }
        macroChip.visibility = View.GONE
        chips2.addView(macroChip, chipParams())
        mfChip = chip("Manueller Fokus") { toggleManual() }
        chips2.addView(mfChip, chipParams())
        panel.addView(chips2, LinearLayout.LayoutParams(-2, -2).apply { topMargin = dp(8f) })

        // Manueller Fokus: schwebt über der Vorschau, damit das Bedienfeld nicht zusammengedrückt wird
        mfRow = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            visibility = View.GONE
            setPadding(dp(14f), dp(8f), dp(14f), dp(4f))
            background = GradientDrawable().apply {
                cornerRadius = dp(18f).toFloat()
                setColor(0xCC000000.toInt())
            }
            isClickable = true
        }
        mfLabel = TextView(this).apply {
            setTextColor(FG)
            textSize = 13f
            gravity = Gravity.CENTER
        }
        mfRow.addView(mfLabel, LinearLayout.LayoutParams(-1, -2))
        val mfScale = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        mfScale.addView(TextView(this).apply { text = "nah"; setTextColor(MUTED); textSize = 12f })
        mfSeek = BigSlider(this).apply {
            onChange = { v -> setManualFocus((v * 1000).roundToInt()) }
        }
        mfScale.addView(mfSeek, LinearLayout.LayoutParams(0, -2, 1f))
        mfScale.addView(TextView(this).apply { text = "∞"; setTextColor(MUTED); textSize = 14f })
        mfRow.addView(mfScale, LinearLayout.LayoutParams(-1, -2))
        previewBox.addView(mfRow, FrameLayout.LayoutParams(-1, -2, Gravity.TOP).apply {
            topMargin = dp(56f); leftMargin = dp(12f); rightMargin = dp(12f)
        })

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
        shutter = ShutterView(this).apply {
            setOnClickListener { shoot() }
        }
        shotRow.addView(shutter, LinearLayout.LayoutParams(dp(74f), dp(74f)).apply {
            leftMargin = dp(32f); rightMargin = dp(32f)
        })
        shotRow.addView(View(this), LinearLayout.LayoutParams(dp(54f), dp(54f)))
        panel.addView(shotRow, LinearLayout.LayoutParams(-2, -2).apply { topMargin = dp(14f) })

        val hint = TextView(this).apply {
            text = "Tippen: Fokuspunkt · Rahmen ziehen: verschieben · Doppeltippen: Autofokus"
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
            override fun onSurfaceTextureUpdated(s: SurfaceTexture) {
                sweepFrame()
                trackFrame()
                feedLupe()
            }
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
                setFocusPoint(e.x, e.y)
                return true
            }
            override fun onDoubleTap(e: MotionEvent): Boolean {
                resetFocus()
                return true
            }
        })
        texture.setOnTouchListener { _, e ->
            scale.onTouchEvent(e)
            if (scale.isInProgress || e.pointerCount > 1) {
                if (draggingFocus) {
                    draggingFocus = false
                    focusView.dragging = false
                    setFocusPoint(focusView.fx, focusView.fy)
                }
                return@setOnTouchListener true
            }
            when (e.actionMasked) {
                MotionEvent.ACTION_DOWN -> if (focusView.contains(e.x, e.y)) {
                    draggingFocus = true
                    focusView.dragging = true
                    tracker.stop()
                }
                MotionEvent.ACTION_MOVE -> if (draggingFocus) {
                    focusView.show(e.x.coerceIn(0f, texture.width.toFloat()), e.y.coerceIn(0f, texture.height.toFloat()))
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> if (draggingFocus) {
                    draggingFocus = false
                    focusView.dragging = false
                    setFocusPoint(focusView.fx, focusView.fy)
                    return@setOnTouchListener true
                }
            }
            if (!draggingFocus) taps.onTouchEvent(e)
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

    private fun styleChip(c: TextView, sel: Boolean) {
        c.background = chipBg(sel)
        c.setTextColor(if (sel) BG else FG)
    }

    private fun toggleStrong() {
        strongOn = !strongOn
        getSharedPreferences("ki", MODE_PRIVATE).edit().putBoolean("strong_on", strongOn).apply()
        updateChips()
        if (strongOn) preloadStrong()
    }

    /** Großes Modell im Hintergrund laden (beim ersten Mal wird gemessen). */
    private fun preloadStrong() {
        aiWorker.execute {
            try {
                val m = AiUpscaler.getStrong(this) { showStatus(it) }
                showStatus("Starke KI bereit · ${m.backend} · ${fmt(m.msPerTile / 1000f)} s je Kachel")
                status.postDelayed({ if (!busy) showStatus(null) }, 3000)
            } catch (e: Throwable) {
                strongOn = false
                runOnUiThread { updateChips() }
                showStatus("Starke KI nicht verfügbar: ${e.message}")
            }
        }
    }

    private fun updateChips() {
        strongChip.text = if (strongOn) "KI: stark" else "KI: schnell"
        styleChip(strongChip, strongOn)
        for ((z, c) in presetChips) styleChip(c, kotlin.math.abs(zoom - z) < 0.05f)
        styleChip(macroChip, macro)
        styleChip(mfChip, mfRow.visibility == View.VISIBLE)
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
        val T = AiUpscaler.T
        val sx = if (focusView.shown) focusView.fx.roundToInt() else tw / 2
        val sy = if (focusView.shown) focusView.fy.roundToInt() else th / 2
        val crop = Bitmap.createBitmap(
            full, (sx - T / 2).coerceIn(0, tw - T), (sy - T / 2).coerceIn(0, th - T), T, T,
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
        gyro.start(getSystemService(android.hardware.SensorManager::class.java))
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
        gyro.stop()
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
        // Nächste Fokus-Entfernung (Dioptrien); Xiaomi meldet sie teils nicht → 10 (= 10 cm)
        // (0 = Fixfokus, dann kein eigener Fokus)
        maxDiopter = c.get(CameraCharacteristics.LENS_INFO_MINIMUM_FOCUS_DISTANCE) ?: 10f
        runOnUiThread {
            macroChip.visibility = if (maxDiopter > 0f) View.VISIBLE else View.GONE
            mfChip.visibility = if (maxDiopter > 0f) View.VISIBLE else View.GONE
        }

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
                    if (col != null && !col.done && col.yuv.size < col.yuvCount) {
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

    // ───────────────────────── Fokus ─────────────────────────
    //
    // Statt Xiaomis Autofokus (der gern den Vordergrund nimmt) fährt die App den
    // Fokus selbst durch und misst die Schärfe genau im Rahmen. Die beste Entfernung
    // wird festgehalten; alle paar Sekunden wird fein nachgeprüft.

    private enum class FocusMode { AUTO, SWEEP, LOCKED, MANUAL }

    private var focusMode = FocusMode.AUTO
    /** Aktuelle Fokus-Entfernung in Dioptrien (0 = unendlich). */
    private var focusDiopter = 0f
    private var maxDiopter = 10f
    private var sweep: Sweep? = null
    private var sweepBmp: Bitmap? = null
    private var sweepPx = IntArray(0)
    private var lastSweepAt = 0L
    private var sweepX = 0f
    private var sweepY = 0f

    private class Sweep(val steps: FloatArray, val fine: Boolean, val quiet: Boolean, val restore: Float) {
        var i = 0
        var wait = 0
        val score = FloatArray(steps.size)
    }

    private fun baseRequest(template: Int): CaptureRequest.Builder {
        val b = device!!.createCaptureRequest(template)
        b.set(CaptureRequest.CONTROL_MODE, CaptureRequest.CONTROL_MODE_AUTO)
        if (focusMode == FocusMode.AUTO) {
            b.set(CaptureRequest.CONTROL_AF_MODE, CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_PICTURE)
        } else {
            b.set(CaptureRequest.CONTROL_AF_MODE, CaptureRequest.CONTROL_AF_MODE_OFF)
            b.set(CaptureRequest.LENS_FOCUS_DISTANCE, focusDiopter)
        }
        // Belichtung am Fokuspunkt messen
        afRegion?.let { b.set(CaptureRequest.CONTROL_AE_REGIONS, arrayOf(it)) }
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
        val old = zoom
        zoom = z.coerceIn(1f, maxZoom)
        if (focusView.shown && !draggingFocus && old != zoom) {
            val cx = texture.width / 2f
            val cy = texture.height / 2f
            val f = zoom / old
            val nx = cx + (focusView.fx - cx) * f
            val ny = cy + (focusView.fy - cy) * f
            if (nx in 0f..texture.width.toFloat() && ny in 0f..texture.height.toFloat()) {
                focusView.show(nx, ny)
                pendingTrackStart = true
                afRegion = regionFor(nx, ny)
            } else clearFocusPoint()
        }
        zoomLabel.text = "${fmt(zoom)}×"
        if (!fromSeek) seek.setValue(ln(zoom) / ln(maxZoom))
        updateChips()
        if (!busy) bg?.post { startPreview() }
    }

    private fun fmt(v: Float): String {
        val r = (v * 10).roundToInt() / 10f
        return if (r == r.toInt().toFloat()) r.toInt().toString() else r.toString().replace('.', ',')
    }

    /** Dioptrien → lesbare Entfernung. */
    private fun distText(d: Float): String = when {
        d < 0.05f -> "∞"
        1f / d >= 1f -> "${fmt(1f / d)} m"
        else -> "${(100f / d).roundToInt()} cm"
    }

    /** Messbereich (Sensorkoordinaten) für einen Punkt der Vorschau – klein, damit nur das Ziel zählt. */
    private fun regionFor(vx: Float, vy: Float): MeteringRectangle? {
        val c = chars ?: return null
        val active = c.get(CameraCharacteristics.SENSOR_INFO_ACTIVE_ARRAY_SIZE) ?: return null
        val nx = (vx / texture.width).coerceIn(0f, 1f)
        val ny = (vy / texture.height).coerceIn(0f, 1f)
        // Hochformat → Sensorkoordinaten
        val (sxN, syN) = if (sensorOrientation == 270) (1 - ny) to nx else ny to (1 - nx)
        val sx = (sxN * active.width()).roundToInt()
        val sy = (syN * active.height()).roundToInt()
        val half = active.width() / 32
        val rect = Rect(
            max(0, sx - half), max(0, sy - half),
            min(active.width() - 1, sx + half), min(active.height() - 1, sy + half),
        )
        return MeteringRectangle(rect, MeteringRectangle.METERING_WEIGHT_MAX - 1)
    }

    /** Fokuspunkt setzen: Durchlauf starten, danach festhalten und verfolgen. */
    private fun setFocusPoint(vx: Float, vy: Float) {
        focusView.show(vx, vy)
        focusView.macro = macro
        pendingTrackStart = true
        afRegion = regionFor(vx, vy)
        if (maxDiopter <= 0f) {
            // Fixfokus-Kamera: nur Belichtung messen
            focusView.state = FocusView.State.SHARP
            bg?.post { startPreview() }
            return
        }
        startSweep(fineAround = null, quiet = false)
    }

    private fun linspace(a: Float, b: Float, n: Int) = FloatArray(n) { a + (b - a) * it / (n - 1) }

    private fun startSweep(fineAround: Float?, quiet: Boolean) {
        val steps = when {
            fineAround != null -> {
                val r = if (quiet) 0.35f else max(0.5f, maxDiopter / 12f)
                linspace(max(0f, fineAround - r), min(maxDiopter, fineAround + r), if (quiet) 5 else 7)
            }
            macro -> linspace(maxDiopter * 0.25f, maxDiopter, 10)
            // Grob: dicht bei „fern“ (kleine Dioptrien), dort ist die Schärfentiefe am engsten verteilt
            else -> FloatArray(12) { i -> val t = i / 11f; t * t * maxDiopter }
        }
        sweep = Sweep(steps, fineAround != null, quiet, focusDiopter)
        focusMode = FocusMode.SWEEP
        focusDiopter = steps[0]
        sweepX = focusView.fx
        sweepY = focusView.fy
        if (!quiet) focusView.state = FocusView.State.SEARCHING
        bg?.post { startPreview() }
    }

    /** Pro Vorschaubild: Schärfe messen, nächste Stufe anfahren. */
    private fun sweepFrame() {
        val sw = sweep ?: return
        if (busy) return
        if (sw.wait < SWEEP_WAIT) {
            sw.wait++
            return
        }
        sw.score[sw.i] = measureSharpness(focusView.fx, focusView.fy)
        sw.i++
        sw.wait = 0
        if (sw.i < sw.steps.size) {
            focusDiopter = sw.steps[sw.i]
            bg?.post { startPreview() }
            return
        }
        // Auswertung: bestes Ergebnis, mit Parabel verfeinert
        var bi = 0
        for (i in sw.score.indices) if (sw.score[i] > sw.score[bi]) bi = i
        var best = sw.steps[bi]
        if (bi in 1 until sw.steps.size - 1) {
            val l = sw.score[bi - 1]
            val c = sw.score[bi]
            val r = sw.score[bi + 1]
            val den = l - 2 * c + r
            if (den < 0f) {
                val off = (0.5f * (l - r) / den).coerceIn(-0.5f, 0.5f)
                best += off * (sw.steps[bi + 1] - sw.steps[bi])
            }
        }
        best = best.coerceIn(0f, maxDiopter)
        sweep = null
        if (!sw.fine) {
            startSweep(fineAround = best, quiet = sw.quiet)
            return
        }
        focusDiopter = best
        focusMode = FocusMode.LOCKED
        lastSweepAt = SystemClock.elapsedRealtime()
        pendingTrackStart = true
        bg?.post { startPreview() }
        focusView.state = FocusView.State.SHARP
        if (!sw.quiet) {
            showStatus("Fokus ${distText(best)}")
            status.postDelayed({ if (!busy) showStatus(null) }, 1200)
        }
    }

    /** Kontrast (Gradientenenergie) in einem Feld um den Punkt, unabhängig von der Helligkeit. */
    private fun measureSharpness(fx: Float, fy: Float): Float {
        val tw = texture.width
        val th = texture.height
        var b = sweepBmp
        if (b == null || b.width != tw || b.height != th) {
            b = Bitmap.createBitmap(tw, th, Bitmap.Config.ARGB_8888)
            sweepBmp = b
        }
        texture.getBitmap(b)
        val n = max(48, (focusView.size * 0.8f).toInt())
        val x0 = (fx.toInt() - n / 2).coerceIn(0, tw - n)
        val y0 = (fy.toInt() - n / 2).coerceIn(0, th - n)
        if (sweepPx.size != n * n) sweepPx = IntArray(n * n)
        b.getPixels(sweepPx, 0, n, x0, y0, n, n)
        val g = IntArray(n * n)
        Tracker.gray(sweepPx, g)
        var e = 0.0
        var sum = 0.0
        for (y in 1 until n - 1) {
            val row = y * n
            for (x in 1 until n - 1) {
                val i = row + x
                val gx = g[i + 1] - g[i - 1]
                val gy = g[i + n] - g[i - n]
                e += (gx * gx + gy * gy).toDouble()
                sum += g[i]
            }
        }
        val mean = sum / ((n - 2) * (n - 2)) + 8.0
        return (e / (mean * mean)).toFloat()
    }

    private fun clearFocusPoint() {
        focusView.hide()
        tracker.stop()
        sweep = null
        pendingTrackStart = false
        afRegion = null
        if (focusMode != FocusMode.MANUAL) focusMode = FocusMode.AUTO
        bg?.post { startPreview() }
    }

    private fun resetFocus() {
        macro = false
        updateChips()
        focusMode = FocusMode.AUTO
        mfRow.visibility = View.GONE
        clearFocusPoint()
        showStatus("Autofokus")
        status.postDelayed({ if (!busy) showStatus(null) }, 1000)
    }

    private fun toggleMacro() {
        macro = !macro
        updateChips()
        focusView.macro = macro
        if (focusView.shown) setFocusPoint(focusView.fx, focusView.fy)
        else if (macro) setFocusPoint(texture.width / 2f, texture.height / 2f)
    }

    /** Manueller Fokus: Regler 0 = nah … 1000 = unendlich. */
    private fun setManualFocus(p: Int) {
        sweep = null
        focusMode = FocusMode.MANUAL
        val t = 1f - p / 1000f
        focusDiopter = t * t * maxDiopter
        mfLabel.text = "Fokus: ${distText(focusDiopter)}"
        if (focusView.shown) focusView.state = FocusView.State.SHARP
        bg?.post { startPreview() }
    }

    private fun toggleManual() {
        if (mfRow.visibility == View.VISIBLE) {
            mfRow.visibility = View.GONE
            if (focusMode == FocusMode.MANUAL) {
                focusMode = if (focusView.shown) FocusMode.LOCKED else FocusMode.AUTO
                if (focusView.shown) startSweep(null, false) else bg?.post { startPreview() }
            }
        } else {
            mfRow.visibility = View.VISIBLE
            val t = kotlin.math.sqrt((focusDiopter / maxDiopter).coerceIn(0f, 1f))
            mfSeek.setValue(1f - t)
            setManualFocus(((1f - t) * 1000).roundToInt())
        }
        updateChips()
    }

    /** Pro Vorschaubild: Ziel im verkleinerten Bild suchen und Rahmen nachführen. */
    private fun trackFrame() {
        if (!focusView.shown || draggingFocus || busy || device == null) return
        if (sweep != null) return // während des Durchlaufs ändert sich die Schärfe – nicht verfolgen
        val tw = texture.width / 4
        val th = texture.height / 4
        if (tw < 40 || th < 40) return
        var b = trackBmp
        if (b == null || b.width != tw || b.height != th) {
            b = Bitmap.createBitmap(tw, th, Bitmap.Config.ARGB_8888)
            trackBmp = b
            trackPx = IntArray(tw * th)
            trackGray = IntArray(tw * th)
        }
        texture.getBitmap(b)
        b.getPixels(trackPx, 0, tw, 0, 0, tw, th)
        Tracker.gray(trackPx, trackGray)

        if (pendingTrackStart || !tracker.active) {
            tracker.start(trackGray, tw, th, focusView.fx / 4f, focusView.fy / 4f)
            pendingTrackStart = false
            return
        }
        if (!tracker.update(trackGray, tw, th)) {
            focusView.state = FocusView.State.LOST
            return
        }
        if (focusView.state == FocusView.State.LOST) focusView.state = FocusView.State.SHARP
        val nx = tracker.x * 4f + 2f
        val ny = tracker.y * 4f + 2f
        focusView.show(nx, ny)
        afRegion = regionFor(nx, ny)

        // Nachprüfen: alle paar Sekunden fein um die aktuelle Entfernung
        val now = SystemClock.elapsedRealtime()
        if (focusMode == FocusMode.LOCKED && now - lastSweepAt > RECHECK_MS) {
            lastSweepAt = now
            startSweep(fineAround = focusDiopter, quiet = true)
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
        sweep?.let { sw ->
            // stilles Nachprüfen einfach abbrechen und alte Entfernung behalten
            if (sw.quiet) {
                sweep = null
                focusDiopter = sw.restore
                focusMode = FocusMode.LOCKED
            }
        }
        if (sweep != null) {
            showStatus("Stellt noch scharf – einen Moment")
            status.postDelayed({ if (!busy) showStatus(null) }, 1200)
            return
        }
        busy = true
        shutter.performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY)
        shutter.mode = ShutterView.Mode.CAPTURING
        lupeBox.visibility = View.GONE

        val z = zoom
        val withRaw = z >= 2f && rawReader != null
        // RAW: so viele Bilder, wie ins Speicherbudget passen (8 bis 30)
        val region = Region.compute(yuvSize.width, yuvSize.height, z)
        val rawBytes = region.rw.toLong() * region.rh * 2
        val count = if (withRaw) (RAW_BUDGET / rawBytes).toInt().coerceIn(8, 30) else if (z >= 2f) 16 else 8
        val rotation = (sensorOrientation + deviceOrientation) % 360
        val col = Collector(region, count, z, rotation, withRaw)
        collecting = col
        showStatus(null)
        showHold("Ruhig halten …")

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
                // Spätere Bilder der Serie nur noch als RAW (spart Speicher)
                val rawOnly = if (withRaw) {
                    b.removeTarget(reader!!.surface)
                    b.build()
                } else req
                s.captureBurst(List(count) { if (it < col.yuvCount) req else rawOnly }, object : CameraCaptureSession.CaptureCallback() {
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
                    val enough = if (col.withRaw) col.raw.size >= 6 && col.yuv.size >= 3 else col.yuv.size >= 4
                    if (enough) finishBurst(col) else failShot("Zu wenige Bilder bekommen")
                }
            }, 5000)
        }
    }

    private fun onBurstImage(col: Collector) {
        val n = col.complete()
        runOnUiThread { if (shutter.mode == ShutterView.Mode.CAPTURING) shutter.progress = n.toFloat() / col.count }
        if (col.finished()) finishBurst(col)
    }

    private fun showHold(text: String?) = runOnUiThread {
        if (text == null) holdLabel.visibility = View.GONE
        else {
            holdLabel.text = text
            holdLabel.visibility = View.VISIBLE
        }
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
        showHold(null)
        runOnUiThread { shutter.mode = ShutterView.Mode.IDLE }
        showStatus(msg)
        status.postDelayed({ showStatus(null) }, 2500)
    }

    private fun finishBurst(col: Collector) {
        if (col.done) return
        col.done = true
        collecting = null
        startPreview()
        val a = ai!!

        val yuvKeys = col.yuv.keys.toList()
        val rawKeys = col.raw.keys.toList()
        val keys = yuvKeys
        val frames: MutableList<Frame?> = yuvKeys.map { col.yuv[it] }.toMutableList()
        val raws: MutableList<ShortArray?>? = if (col.withRaw) rawKeys.map { col.raw[it] }.toMutableList() else null
        val result = keys.firstNotNullOfOrNull { col.results[it] } ?: col.results.values.firstOrNull()
        col.yuv.clear()
        col.raw.clear()
        col.results.clear()

        // Aufnahme fertig: kurz vibrieren, Handy darf bewegt werden
        runOnUiThread {
            shutter.performHapticFeedback(HapticFeedbackConstants.CONFIRM)
            shutter.mode = ShutterView.Mode.PROCESSING
            holdLabel.text = "✓ Fertig – du kannst loslassen"
            holdLabel.visibility = View.VISIBLE
            holdLabel.postDelayed({ if (shutter.mode != ShutterView.Mode.CAPTURING) holdLabel.visibility = View.GONE }, 1800)
            pendingTrackStart = true
        }

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
                        // RAW-Bild zum selben Zeitpunkt wie das YUV-Referenzbild
                        val refTs = yuvKeys[res.refIdx]
                        val rawRef = rawKeys.indices.minByOrNull { kotlin.math.abs(rawKeys[it] - refTs) } ?: 0
                        val shake = shakeWeights(rawKeys, col.results)
                        if (shake != null) srNote += " · ${shake.second}"
                        val out = RawSR.run(raws, geo, rawParams(result), rawRef, res.refY, res.mergedY, 4, shake?.first) {
                            showStatus(it)
                        }
                        real = SuperRes.toBitmap(out.y, res.chroma, r, out.u, out.wo, out.ho)
                        res.merged.recycle()
                        srNote = " · ${out.used} RAW-Bilder · ${fmt(out.u)}×"
                        half = true
                    } catch (e: OutOfMemoryError) {
                        srNote = " · ohne RAW (Speicher)"
                    }
                    raws.clear()
                }

                // 3. Unschärfe messen: Die KI kennt nur ≈ 1 Pixel Unschärfe. Ist das Bild
                //    unschärfer, wird es vorher passend verkleinert – sonst hält die KI den
                //    breiten Übergang für ein echtes Band und erzeugt einen Saum.
                showStatus("Messe Unschärfe …")
                val blur = measureBlur(real, col.rotation)
                val fx = BlurEstimate.aiScale(blur.sx)
                val fy = BlurEstimate.aiScale(blur.sy)

                // 4. KI – Größe zusätzlich nach Zeitbudget begrenzen (schnell ≈ 45 s, stark ≈ 3 min)
                val model = if (strongOn) AiUpscaler.getStrong(this) { showStatus(it) } else a
                val budget = if (strongOn) 180_000L else 45_000L
                val maxTiles = max(if (strongOn) 4L else 40L, budget / max(1L, model.msPerTile)).toInt()
                // je Richtung verkleinern (einseitige Verwacklung), dann gemeinsam nach Größe/Zeit begrenzen
                var sc = min(1f, MAX_AI_INPUT.toFloat() / max(real.width * fx, real.height * fy))
                while (sc > 0.1f && model.tilesFor((real.width * fx * sc).toInt(), (real.height * fy * sc).toInt()) > maxTiles) sc *= 0.9f
                val iw = max(16, (real.width * fx * sc).roundToInt())
                val ih = max(16, (real.height * fy * sc).roundToInt())
                val input = if (iw != real.width || ih != real.height)
                    Bitmap.createScaledBitmap(real, iw, ih, true)
                else real

                val t0 = SystemClock.elapsedRealtime()
                val label = if (strongOn) "Starke KI" else "KI"
                val kiRaw = model.upscale(input, false) { done, total ->
                    val left = (total - done) * model.msPerTile / 1000
                    showStatus("$label rekonstruiert … ${done * 100 / total} %" + if (left >= 10) " · noch ca. $left s" else "")
                }
                if (input !== real) input.recycle()
                val secs = (SystemClock.elapsedRealtime() - t0) / 100 / 10f

                // Zielgröße: Mehrbild ×2 (RAW) bzw. Einzelbild ×4, lange Seite höchstens 4096 px
                val mult = if (half) 2f else 4f
                val fit = min(1f, 4096f / (max(real.width, real.height) * mult))
                val kw = max(1, (real.width * mult * fit).roundToInt())
                val kh = max(1, (real.height * mult * fit).roundToInt())
                val kiBmp = if (kiRaw.width != kw || kiRaw.height != kh)
                    Bitmap.createScaledBitmap(kiRaw, kw, kh, true).also { if (it !== kiRaw) kiRaw.recycle() }
                else kiRaw
                srNote += if (kotlin.math.abs(blur.sx - blur.sy) > 0.25f * max(blur.sx, blur.sy))
                    " · Unschärfe ${fmt(blur.sx)}/${fmt(blur.sy)} px"
                else " · Unschärfe ${fmt(blur.mean)} px"
                if (blur.fromText) srNote += " (Schrift)"

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

                showStatus("Fertig ✓$srNote · ${if (strongOn) "starke " else ""}KI ${fmt(secs)} s")
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
                runOnUiThread { shutter.mode = ShutterView.Mode.IDLE }
                status.postDelayed({ if (!busy) showStatus(null) }, 5000)
            }
        }
    }

    /**
     * Unschärfe je Richtung (in Pixeln dieses Bildes). Gefundene Schrift dient als Eichmaß:
     * gedruckte Buchstaben haben echte Sprungkanten. Große Bilder werden zum Messen halbiert.
     */
    private fun measureBlur(b: Bitmap, rotation: Int): BlurEstimate.Result {
        val half = max(b.width, b.height) > 1400
        val src = if (half) Bitmap.createScaledBitmap(b, b.width / 2, b.height / 2, true) else b
        val w = src.width
        val h = src.height
        val px = IntArray(w * h)
        src.getPixels(px, 0, w, 0, 0, w, h)
        val mask = try {
            textMask(src, rotation)
        } catch (_: Throwable) {
            null
        }
        if (src !== b) src.recycle()
        val y = FloatArray(w * h) { i ->
            val p = px[i]
            (((p shr 16) and 255) * 0.299f + ((p shr 8) and 255) * 0.587f + (p and 255) * 0.114f) / 255f
        }
        val r = BlurEstimate.measure(y, w, h, mask)
        val k = if (half) 2f else 1f
        return BlurEstimate.Result(r.sx * k, r.sy * k, r.edges, r.fromText)
    }

    /**
     * Schrift im Bild finden (Texterkennung läuft auf dem Gerät). Das Bild wird dafür
     * aufrecht gedreht; die gefundenen Bereiche werden zurück in Bildkoordinaten gerechnet.
     */
    private fun textMask(b: Bitmap, rotation: Int): BooleanArray? {
        val w = b.width
        val h = b.height
        val upright = if (rotation == 0) b else
            Bitmap.createBitmap(b, 0, 0, w, h, Matrix().apply { postRotate(rotation.toFloat()) }, true)
        val rec = com.google.mlkit.vision.text.TextRecognition.getClient(
            com.google.mlkit.vision.text.latin.TextRecognizerOptions.DEFAULT_OPTIONS,
        )
        val text = try {
            com.google.android.gms.tasks.Tasks.await(
                rec.process(com.google.mlkit.vision.common.InputImage.fromBitmap(upright, 0)),
                8, java.util.concurrent.TimeUnit.SECONDS,
            )
        } finally {
            rec.close()
            if (upright !== b) upright.recycle()
        }
        val mask = BooleanArray(w * h)
        var any = false
        for (block in text.textBlocks) for (line in block.lines) for (el in line.elements) {
            if (el.text.none { it.isLetterOrDigit() }) continue
            val box = el.boundingBox ?: continue
            // Ecken zurück in die ungedrehten Koordinaten
            fun back(ux: Int, uy: Int): Pair<Int, Int> = when (rotation) {
                90 -> uy to (h - 1 - ux)
                180 -> (w - 1 - ux) to (h - 1 - uy)
                270 -> (w - 1 - uy) to ux
                else -> ux to uy
            }
            val c1 = back(box.left, box.top)
            val c2 = back(box.right, box.bottom)
            val pad = 4
            val x0 = (min(c1.first, c2.first) - pad).coerceIn(0, w - 1)
            val x1 = (max(c1.first, c2.first) + pad).coerceIn(0, w - 1)
            val y0 = (min(c1.second, c2.second) - pad).coerceIn(0, h - 1)
            val y1 = (max(c1.second, c2.second) + pad).coerceIn(0, h - 1)
            for (yy in y0..y1) for (xx in x0..x1) mask[yy * w + xx] = true
            any = true
        }
        return if (any) mask else null
    }

    /**
     * Verwacklung je RAW-Bild aus dem Lagesensor: Drehgeschwindigkeit × Belichtungszeit ×
     * Brennweite in Pixeln = Weg, den das Bild während der Belichtung über den Sensor wandert.
     * Gibt Gewichte (ruhig = 1, verwackelt → klein) und eine kurze Notiz zurück.
     */
    private fun shakeWeights(keys: List<Long>, results: Map<Long, TotalCaptureResult>): Pair<FloatArray, String>? {
        val c = chars ?: return null
        if (c.get(CameraCharacteristics.SENSOR_INFO_TIMESTAMP_SOURCE) !=
            CameraCharacteristics.SENSOR_INFO_TIMESTAMP_SOURCE_REALTIME) return null
        val f = c.get(CameraCharacteristics.LENS_INFO_AVAILABLE_FOCAL_LENGTHS)?.firstOrNull() ?: return null
        val phys = c.get(CameraCharacteristics.SENSOR_INFO_PHYSICAL_SIZE) ?: return null
        val pix = c.get(CameraCharacteristics.SENSOR_INFO_PIXEL_ARRAY_SIZE) ?: return null
        val focalPx = f / (phys.width / pix.width)
        if (keys.isEmpty()) return null
        val blur = FloatArray(keys.size)
        for (i in keys.indices) {
            val ts = keys[i]
            val exp = results[ts]?.get(CaptureResult.SENSOR_EXPOSURE_TIME) ?: 16_000_000L
            val om = gyro.meanOmega(ts, ts + exp) ?: return null
            blur[i] = om * (exp / 1e9f) * focalPx
        }
        val sorted = blur.sorted()
        val med = sorted[sorted.size / 2]
        var dropped = 0
        val w = FloatArray(blur.size) { i ->
            val b = blur[i]
            if (b > med * 3f + 1f) { dropped++; 0f } else 1f / (1f + (b / 0.8f) * (b / 0.8f))
        }
        val note = if (dropped > 0) "$dropped verwackelte Bilder aussortiert" else "Verwacklung ⌀ ${fmt(med)} px"
        return w to note
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
        private const val SWEEP_WAIT = 2
        private const val RAW_BUDGET = 150_000_000L
        private const val RECHECK_MS = 5000L
    }
}
