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
import kotlin.math.sqrt

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

    // ── Kalibrierung ──
    /** -1 = aus; 0..4 = Schärfe-Muster an den Positionen, 5 = Graustufen */
    private var calibStep = -1
    private val calibSamples = ArrayList<Calib.Sample>()
    private var pendingCalibShot = false
    /** Diagnosebild: alle Rechenschritte als Ausschnitt nebeneinander (für die Fehlersuche) */
    private var diagOn = true
    /**
     * Quelle Kamera-Zoom: Der Bildprozessor zoomt selbst bis 2× – dabei liest das Handy die Bildmitte
     * vermutlich in voller 50-MP-Auflösung aus (wie die Vorschau). Den Rest des Zooms rechnen wir.
     * Aus = bisheriger Weg (ganzes 12-MP-Bild + RAW).
     */
    private var ispZoomOn = true
    private lateinit var calibOverlay: CalibOverlay
    private lateinit var calibBar: LinearLayout
    private lateinit var calibText: TextView
    private lateinit var calibSecondary: TextView
    private lateinit var calibStatus: TextView
    /** Normale Bedienelemente, die während der Kalibrierung ausgeblendet werden */
    private val normalControls = ArrayList<View>()
    @Volatile private var profile: SharpProfile? = null

    private class SharpProfile(val samples: List<Calib.Sample>, val r2: Float?, val flare: Float?, val date: String)

    // ── Lagesensor ──
    private val gyro = GyroLog()
    @Volatile private var lastShakePx = 0f

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
        /** Kalibrierung: Schritt und Messrahmen im Ergebnis (Anteile x0, y0, x1, y1, Sensor-Ausrichtung) */
        val calibStep: Int = -1, val calibBox: FloatArray? = null,
        /** Zielrahmen als Anteile des (verkleinerten) Ausschnitts – nur dort wird gemessen */
        val keepBox: FloatArray? = null,
        /** Diagnose: Vorschau-Ausschnitt im Moment des Auslösens und der Ausschnitt (Anteile, Sensor-Ausrichtung) */
        val diagPreview: Bitmap? = null, val diagRect: FloatArray? = null,
        /** Zoom, den der Bildprozessor des Handys selbst macht (1 = ganzes Sensorbild, Ausschnitt rechnen wir) */
        val isp: Float = 1f,
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

        loadProfile()
        diagOn = getPreferences(MODE_PRIVATE).getBoolean("diag", true)
        ispZoomOn = getPreferences(MODE_PRIVATE).getBoolean("ispzoom", true)
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
        handleImportIntent(intent)
    }

    override fun onNewIntent(i: Intent) {
        super.onNewIntent(i)
        setIntent(i)
        handleImportIntent(i)
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
        calibOverlay = CalibOverlay(this)
        previewBox.addView(calibOverlay, FrameLayout.LayoutParams(-1, -1))

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
            text = "Mehr"
            setTextColor(FG)
            textSize = 13f
            setPadding(dp(12f), dp(6f), dp(12f), dp(6f))
            background = chipBg(false)
            setOnClickListener { v -> showMoreMenu(v) }
        }
        normalControls += info
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
        normalControls += strongChip
        previewBox.addView(strongChip, FrameLayout.LayoutParams(-2, -2, Gravity.START or Gravity.TOP).apply {
            topMargin = dp(12f); leftMargin = dp(12f)
        })

        // Die Vorschau bekommt den Platz, den das Bedienfeld übrig lässt – immer im Seitenverhältnis 3:4,
        // damit Auslöser und Hinweise nie abgeschnitten werden
        val previewArea = FrameLayout(this).apply { setBackgroundColor(BG) }
        previewArea.addView(previewBox, FrameLayout.LayoutParams(screenW, screenW * 4 / 3, Gravity.CENTER))
        previewArea.addOnLayoutChangeListener { v, l, t, r, b, _, _, _, _ ->
            val aw = r - l
            val ah = b - t
            if (aw <= 0 || ah <= 0) return@addOnLayoutChangeListener
            val w = min(aw, ah * 3 / 4)
            val h = w * 4 / 3
            val lp = previewBox.layoutParams as FrameLayout.LayoutParams
            if (lp.width != w || lp.height != h) {
                lp.width = w
                lp.height = h
                v.post { previewBox.layoutParams = lp }
            }
        }
        root.addView(previewArea, LinearLayout.LayoutParams(-1, 0, 1f))

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
        // 50 MP: Fotos mit der Xiaomi-Kamera machen, die App holt sie sich danach automatisch
        chips2.addView(chip("50 MP") { askXiaomi() }, chipParams())
        lupeChip = chip("KI-Lupe") { toggleLupe() }
        chips2.addView(lupeChip, chipParams())
        macroChip = chip("Makro") { toggleMacro() }
        macroChip.visibility = View.GONE
        chips2.addView(macroChip, chipParams())
        mfChip = chip("Manueller Fokus") { toggleManual() }
        chips2.addView(mfChip, chipParams())
        panel.addView(chips2, LinearLayout.LayoutParams(-2, -2).apply { topMargin = dp(8f) })
        normalControls += listOf(zoomLabel, seek, chips, chips2)

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
            setOnClickListener { if (calibStep >= 0) calibShutter() else shoot() }
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
        normalControls += hint

        root.addView(panel, LinearLayout.LayoutParams(-1, -2))

        // Kalibrier-Leiste im Bedienfeld unter der Vorschau (ersetzt Zoom und Chips während der Kalibrierung),
        // damit nichts über dem grünen Zielrahmen liegt
        calibBar = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16f), dp(10f), dp(16f), dp(10f))
            background = GradientDrawable().apply {
                cornerRadius = dp(16f).toFloat()
                setColor(0xF2181A1C.toInt())
                setStroke(dp(1.5f), 0xFF5BE38A.toInt())
            }
            visibility = View.GONE
            isClickable = true
        }
        calibText = TextView(this).apply {
            setTextColor(FG)
            textSize = 15f
            setLineSpacing(0f, 1.2f)
        }
        calibBar.addView(calibText)
        calibStatus = TextView(this).apply {
            setTextColor(0xFFFFD27A.toInt())
            textSize = 14f
            setPadding(0, dp(6f), 0, 0)
            visibility = View.GONE
        }
        calibBar.addView(calibStatus)
        calibSecondary = TextView(this).apply {
            setTextColor(0xFF5BE38A.toInt())
            textSize = 14f
            setPadding(0, dp(8f), 0, 0)
            setOnClickListener { calibSecondaryAction() }
        }
        calibBar.addView(calibSecondary)
        panel.addView(calibBar, 0, LinearLayout.LayoutParams(-1, -2))
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
        if (calibStep >= 0) {
            // im Kalibrier-Modus unten im Bedienfeld anzeigen, nicht über dem Zielrahmen
            status.visibility = View.GONE
            calibStatus.text = text ?: ""
            calibStatus.visibility = if (text == null) View.GONE else View.VISIBLE
        } else if (text == null) status.visibility = View.GONE
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
        checkXiaomiReturn()
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
        if (code == REQ_PHOTOS) {
            if (photoAccess() != PhotoAccess.NONE) launchXiaomi()
            else showStatus("Ohne Foto-Zugriff kann die App die 50-MP-Fotos nicht holen – Mehr › Fotos importieren geht trotzdem")
            return
        }
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
        // Kalibrieren: 1 Blende knapper belichten, damit Weiß (iPad) nicht ausbrennt
        if (calibStep >= 0) {
            val step = chars?.get(CameraCharacteristics.CONTROL_AE_COMPENSATION_STEP)?.toFloat() ?: 0f
            val range = chars?.get(CameraCharacteristics.CONTROL_AE_COMPENSATION_RANGE)
            if (step > 0f && range != null) {
                b.set(CaptureRequest.CONTROL_AE_EXPOSURE_COMPENSATION, (-1f / step).roundToInt().coerceIn(range.lower, range.upper))
            }
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
        if (pendingCalibShot) {
            pendingCalibShot = false
            status.postDelayed({ shoot() }, 250)
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
        val calibNow = calibStep >= 0
        val isp = if (ispZoomOn && !calibNow && z >= 2f) min(z, ISP_ZOOM) else 1f
        val withRaw = z >= 2f && rawReader != null && isp == 1f
        // RAW: so viele Bilder, wie ins Speicherbudget passen (8 bis 30)
        // Kalibrieren: nur den Zielrahmen (plus Rand) auslesen und 12 Bilder – viel schneller
        val calibBox = if (calibNow) calibBoxCrop() else null
        val region = if (calibBox != null) Region.forBox(yuvSize.width, yuvSize.height, z, calibBox)
            else Region.compute(yuvSize.width, yuvSize.height, z / isp)
        val rawBytes = region.rw.toLong() * region.rh * 2
        val count = when {
            calibBox != null -> 12
            isp > 1f -> (YUV_BUDGET / (region.rw.toLong() * region.rh * 3 / 2)).toInt().coerceIn(6, 16)
            withRaw -> (RAW_BUDGET / rawBytes).toInt().coerceIn(8, 30)
            z >= 2f -> 16
            else -> 8
        }
        val rotation = (sensorOrientation + deviceOrientation) % 360
        val keepBox = calibBox?.let { b ->
            val base = Region.compute(yuvSize.width, yuvSize.height, z)
            floatArrayOf(
                ((base.cx + b[0] * base.cw - region.cx) / region.cw).coerceIn(0f, 1f),
                ((base.cy + b[1] * base.ch - region.cy) / region.ch).coerceIn(0f, 1f),
                ((base.cx + b[2] * base.cw - region.cx) / region.cw).coerceIn(0f, 1f),
                ((base.cy + b[3] * base.ch - region.cy) / region.ch).coerceIn(0f, 1f),
            )
        }
        // Diagnose: Ausschnitt um den Fokuspunkt, so wie die Vorschau ihn gerade zeigt
        var diagPreview: Bitmap? = null
        var diagRect: FloatArray? = null
        if (diagOn && calibBox == null && texture.width > 0) {
            try {
                val tw = texture.width.toFloat()
                val th = texture.height.toFloat()
                val cxn = (if (focusView.shown) focusView.fx / tw else 0.5f)
                val cyn = (if (focusView.shown) focusView.fy / th else 0.5f)
                val hw = 0.16f
                val hh = hw * tw / th
                val l = (cxn - hw).coerceIn(0f, 1f - 2 * hw)
                val t = (cyn - hh).coerceIn(0f, 1f - 2 * hh)
                texture.bitmap?.let { full ->
                    diagPreview = Bitmap.createBitmap(full, (l * full.width).toInt(), (t * full.height).toInt(),
                        (2 * hw * full.width).toInt(), (2 * hh * full.height).toInt())
                    if (diagPreview !== full) full.recycle()
                }
                val a = previewToCrop(l, t)
                val b = previewToCrop(l + 2 * hw, t + 2 * hh)
                diagRect = floatArrayOf(min(a.first, b.first), min(a.second, b.second), max(a.first, b.first), max(a.second, b.second))
            } catch (_: Throwable) {
            }
        }
        val col = Collector(region, count, z, rotation, withRaw, calibStep, calibBox, keepBox, diagPreview, diagRect, isp)
        collecting = col
        showStatus(null)
        showHold("Ruhig halten …")

        handler.post {
            try {
                s.stopRepeating()
                val b = baseRequest(CameraDevice.TEMPLATE_PREVIEW)
                b.addTarget(reader!!.surface)
                if (withRaw) b.addTarget(rawReader!!.surface)
                // Ganzes Sensorbild holen (oder bis 2× vom Bildprozessor zoomen lassen), den Rest rechnen wir selbst
                b.set(CaptureRequest.CONTROL_ZOOM_RATIO, col.isp)
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
        if (calibStep >= 0) {
            holdLabel.visibility = View.GONE
            if (text != null) { calibStatus.text = text; calibStatus.visibility = View.VISIBLE }
        } else if (text == null) holdLabel.visibility = View.GONE
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
            if (col.calibBox != null) {
                calibStatus.text = "✓ Aufgenommen – wird vermessen …"
                calibStatus.visibility = View.VISIBLE
            } else {
                holdLabel.text = "✓ Fertig – du kannst loslassen"
                holdLabel.visibility = View.VISIBLE
            }
            holdLabel.postDelayed({ if (shutter.mode != ShutterView.Mode.CAPTURING) holdLabel.visibility = View.GONE }, 1800)
            pendingTrackStart = true
        }

        aiWorker.execute {
            try {
                val r = col.region

                // 1. YUV-Serie ausrichten und mitteln (Farbe, Helligkeits-Referenz)
                val yuvShake = if (!col.withRaw) shakeWeights(yuvKeys, col.results) else null
                val res = SuperRes.process(frames, r, yuvShake?.first) { showStatus(it) }

                // Diagnose: jeden Rechenschritt am selben Ausschnitt festhalten
                val diag = ArrayList<Pair<String, Bitmap>>()
                val dr = col.diagRect
                fun keep(name: String, b: Bitmap) {
                    if (dr != null) try { diag += name to diagCrop(b, dr, col.rotation) } catch (_: Throwable) {}
                }
                col.diagPreview?.let { diag += "1 Vorschau (Handy-Bildprozessor)" to diagScale(it) }
                keep("2 Kamera-Einzelbild (ohne Nachschärfen)", res.single)
                keep("3 YUV-Serie gemittelt", res.merged)

                // 2. Mehrbild-Superauflösung aus RAW (echte Details auf feinerem Raster)
                var real: Bitmap = res.merged
                var srNote = ""
                var half = false
                var gridU = 1f
                lastShakePx = 0f
                val info = ArrayList<String>()
                info += "${fmt(col.zoomAtShot)}× Zoom" + if (col.isp > 1f)
                    " · Quelle Kamera-Zoom ${fmt(col.isp)}× + eigener Ausschnitt ${fmt(col.zoomAtShot / col.isp)}× · ${r.cw}×${r.ch} px"
                else " · Quelle ganzes Sensorbild · ${r.cw}×${r.ch} px"
                if (raws != null && rawParamsBase != null) {
                    try {
                        val geo = RawSR.Geo(r.x0, r.y0, r.rw, r.rh, r.cx, r.cy, r.cw, r.ch)
                        // RAW-Bild zum selben Zeitpunkt wie das YUV-Referenzbild
                        val refTs = yuvKeys[res.refIdx]
                        val rawRef = rawKeys.indices.minByOrNull { kotlin.math.abs(rawKeys[it] - refTs) } ?: 0
                        val shake = shakeWeights(rawKeys, col.results)
                        if (shake != null) srNote += " · ${shake.second}"
                        val out = RawSR.run(raws, geo, rawParams(result), rawRef, res.refY, res.mergedY, 4, shake?.first,
                            col.keepBox) {
                            showStatus(it)
                        }
                        if (col.calibBox != null) {
                            raws.clear()
                            res.merged.recycle()
                            res.single.recycle()
                            handleCalibResult(col, out)
                            return@execute
                        }
                        real = SuperRes.toBitmap(out.y, res.chroma, r, out.u, out.wo, out.ho)
                        res.merged.recycle()
                        srNote = " · ${out.used} RAW-Bilder · ${fmt(out.u)}×"
                        half = true
                        gridU = out.u
                        info += "${out.used} RAW-Bilder verrechnet, Raster ${fmt(out.u)}× feiner"
                        if (shake != null) info += "Lagesensor: ${shake.second}"
                    } catch (e: OutOfMemoryError) {
                        srNote = " · ohne RAW (Speicher)"
                    }
                    raws.clear()
                }

                if (col.calibBox != null) {
                    calibMessage("RAW-Bilder waren nicht verfügbar – bitte nochmal auslösen.")
                    return@execute
                }
                if (!half) {
                    info += "${res.used} von ${res.total} Bildern verrechnet, kachelweise ausgerichtet" +
                        (if (SuperRes.dropped > 0) " (${SuperRes.dropped} unscharf/verrutscht aussortiert)" else "")
                    yuvShake?.let { info += "Lagesensor: ${it.second}" }
                }
                if (half) keep("4 RAW-Mehrbild (vor dem Entschärfen)", real)

                // 3. Unschärfe messen (Schrift als Eichmaß, je Richtung, je Bildbereich)
                showStatus("Messe Unschärfe …")
                val (blur, uniformity) = measureBlur(real, col.rotation)
                info += uniformity
                // Kleine Schrift: KI erfindet Striche statt Buchstaben (Diagnosebild 1.3) – Galerie ohne KI
                var galleryMix = if (blur.fromText) 0 else ViewerActivity.DEFAULT_MIX
                ViewerActivity.startMix = galleryMix

                // 4. Kameramodell: physikalisches Minimum, Messung darauf eingrenzen
                val phys = physicalMinimum(half, col.isp)
                val prof = if (half) profile else null
                val swY = yuvSize.width.toFloat()
                val shY = yuvSize.height.toFloat()
                // Schärfeprofil (kalibriert) an der Bildmitte, im Ergebnis-Raster
                val pc = prof?.let {
                    val c = Calib.sigmaAt(it.samples, (r.cx + r.cw / 2f) / swY, (r.cy + r.ch / 2f) / shY)
                    floatArrayOf(c[0] * gridU, c[1] * gridU)
                }
                val lo = pc?.let { min(it[0], it[1]) } ?: (phys.total * gridU)
                val hi = (if (pc != null) max(pc[0], pc[1]) / gridU else phys.total) * gridU + CameraModel.PLAUSIBLE_EXTRA_PX * gridU
                val sx = blur.sx.coerceIn(lo, hi)
                val sy = blur.sy.coerceIn(lo, hi)
                info += "Unschärfe gemessen ${fmt(blur.sx)}/${fmt(blur.sy)} px" +
                    (if (blur.fromText) " (an Schrift)" else "") +
                    (if (pc != null) " · Objektiv laut Profil ${fmt(pc[0])}/${fmt(pc[1])} px" else " · Physik-Minimum ${fmt(lo)} px")
                if (sx != blur.sx || sy != blur.sy) info += "→ physikalisch begrenzt auf ${fmt(sx)}/${fmt(sy)} px"

                // Je Bildbereich: Profil + Zusatz-Unschärfe dieses Fotos (Fokus, Verwacklung) quadratisch addiert
                val sigmaMap: ((Int, Int) -> FloatArray)? = if (prof != null && pc != null) {
                    val ex2 = max(0f, sx * sx - pc[0] * pc[0])
                    val ey2 = max(0f, sy * sy - pc[1] * pc[1])
                    info += "Schärfeprofil aktiv (kalibriert ${prof.date}) · Zusatz-Unschärfe ${fmt(sqrt(ex2))}/${fmt(sqrt(ey2))} px"
                    val fn: (Int, Int) -> FloatArray = { px, py ->
                        val p = Calib.sigmaAt(prof.samples, (r.cx + px / gridU) / swY, (r.cy + py / gridU) / shY)
                        floatArrayOf(sqrt((p[0] * gridU) * (p[0] * gridU) + ex2), sqrt((p[1] * gridU) * (p[1] * gridU) + ey2))
                    }
                    fn
                } else null

                // 5. Physikalisch entschärfen (nichts erfinden, nur Gedämpftes zurückholen)
                val tDec = SystemClock.elapsedRealtime()
                val (sharp, decIters) = deblurBitmap(real, sx, sy, if (strongOn) 150 else 80, if (strongOn) 60_000L else 25_000L, sigmaMap) { pct ->
                    showStatus("Entschärfe … $pct %")
                }
                info += "Entschärft: $decIters Durchgänge (Richardson-Lucy mit Kantenschutz, starke Kanten gebremst)"
                keep("5 entschärft (Echt)", sharp)
                val decSecs = (SystemClock.elapsedRealtime() - tDec) / 100 / 10f

                // 6a. KI-Entschärfen mit bekannter Unschärfe (DPIR) – rät nicht blind, nutzt die Messung/das Profil
                val dp = dpirBitmap(real, sharp, sx, sy, sigmaMap, info)
                if (sharp !== real) real.recycle()

                // 6b. sonst: Allzweck-KI auf das entschärfte Bild – nur sanft zugemischt
                val kiRaw = if (dp != null) {
                    galleryMix = 100
                    ViewerActivity.startMix = 100
                    dp
                } else {
                    val model = if (strongOn) AiUpscaler.getStrong(this) { showStatus(it) } else a
                    val budget = if (strongOn) 180_000L else 45_000L
                    val maxTiles = max(if (strongOn) 4L else 40L, budget / max(1L, model.msPerTile)).toInt()
                    val cap = if (half) 2048f else MAX_AI_INPUT.toFloat()
                    var sc = min(1f, cap / max(sharp.width, sharp.height))
                    while (sc > 0.1f && model.tilesFor((sharp.width * sc).toInt(), (sharp.height * sc).toInt()) > maxTiles) sc *= 0.9f
                    val iw = max(16, (sharp.width * sc).roundToInt())
                    val ih = max(16, (sharp.height * sc).roundToInt())
                    val input = if (iw != sharp.width || ih != sharp.height) Bitmap.createScaledBitmap(sharp, iw, ih, true) else sharp
                    val t0 = SystemClock.elapsedRealtime()
                    val label = if (strongOn) "Starke KI" else "KI"
                    val k0 = model.upscale(input, half) { done, total ->
                        val left = (total - done) * model.msPerTile / 1000
                        showStatus("$label … ${done * 100 / total} %" + if (left >= 10) " · noch ca. $left s" else "")
                    }
                    if (input !== sharp) input.recycle()
                    val secs = (SystemClock.elapsedRealtime() - t0) / 100 / 10f
                    info += "KI ${if (strongOn) "stark" else "schnell"} (${model.backend}): ${fmt(secs)} s · Entschärfen ${fmt(decSecs)} s"
                    if (galleryMix == 0) info += "Schrift erkannt → Galerie ohne KI (Regler im Viewer bleibt)"
                    k0
                }

                // Zielgröße: Mehrbild ×2 (RAW) bzw. Einzelbild ×4, lange Seite höchstens 4096 px
                val mult = if (half) 2f else 4f
                val fit = min(1f, 4096f / (max(sharp.width, sharp.height) * mult))
                val kw = max(1, (sharp.width * mult * fit).roundToInt())
                val kh = max(1, (sharp.height * mult * fit).roundToInt())
                val kiBmp = if (kiRaw.width != kw || kiRaw.height != kh)
                    Bitmap.createScaledBitmap(kiRaw, kw, kh, true).also { if (it !== kiRaw) kiRaw.recycle() }
                else kiRaw
                real = sharp
                keep(if (dp != null) "6 KI-Entschärfen (DPIR)" else "6 KI pur", kiBmp)

                showStatus("Speichere …")
                val stamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.GERMANY).format(Date())
                val tag = "${fmt(col.zoomAtShot).replace(',', '-')}x"

                // Normal (Einzelbild, digital gezoomt)
                val n = rotate(Bitmap.createScaledBitmap(res.single, kw, kh, true), col.rotation)
                cacheJpeg(n, ViewerActivity.NORMAL)
                if (n !== res.single) res.single.recycle()
                n.recycle()

                // Echt: Mehrbild, physikalisch entschärft
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
                    drawBitmap(k, 0f, 0f, android.graphics.Paint().apply { alpha = galleryMix * 255 / 100 })
                }
                sBmp.recycle()
                k.recycle()
                ViewerActivity.saveToGallery(this, mix, "zoom_${stamp}_${tag}")
                // Galerie-Mischung am Ausschnitt: dieselbe Mischung aus Kachel 5 und 6
                val t5 = diag.firstOrNull { it.first.startsWith("5") }?.second
                val t6 = diag.firstOrNull { it.first.startsWith("6") }?.second
                if (t5 != null && t6 != null) {
                    val m7 = t5.copy(Bitmap.Config.ARGB_8888, true)
                    android.graphics.Canvas(m7).drawBitmap(
                        Bitmap.createScaledBitmap(t6, m7.width, m7.height, true), 0f, 0f,
                        android.graphics.Paint().apply { alpha = galleryMix * 255 / 100 })
                    diag += "7 Galerie (Echt + $galleryMix % KI)" to m7
                }
                if (diag.isNotEmpty()) {
                    showStatus("Speichere Diagnosebild …")
                    val sheet = diagSheet(diag, info)
                    ViewerActivity.saveToGallery(this, sheet, "diagnose_${stamp}_${tag}")
                    sheet.recycle()
                }
                val small = Bitmap.createScaledBitmap(mix, dp(54f) * 2, dp(54f) * 2 * mix.height / mix.width, true)
                mix.recycle()

                File(cacheDir, ViewerActivity.INFO).writeText(info.joinToString("\n"))
                showStatus("Fertig ✓")
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
    private fun measureBlur(b: Bitmap, rotation: Int): Pair<BlurEstimate.Result, String> {
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

        // Gleichmäßigkeit: Bei ebenem Motiv muss jeder Bereich gleich scharf sein.
        val cols = 3
        val rows = 2
        val sig = Array(rows) { FloatArray(cols) { Float.NaN } }
        for (ry in 0 until rows) for (rx in 0 until cols) {
            val x0 = rx * w / cols
            val x1 = (rx + 1) * w / cols
            val y0 = ry * h / rows
            val y1 = (ry + 1) * h / rows
            val sw = x1 - x0
            val sh = y1 - y0
            val sub = FloatArray(sw * sh)
            for (yy in 0 until sh) System.arraycopy(y, (y0 + yy) * w + x0, sub, yy * sw, sw)
            val m = BlurEstimate.measure(sub, sw, sh)
            if (m.edges >= 40) sig[ry][rx] = m.mean * k
        }
        val vals = sig.flatMap { it.toList() }.filter { !it.isNaN() }
        val note = if (vals.size < 2) "Gleichmäßigkeit: zu wenige Kanten zum Prüfen" else {
            val mn = vals.min()
            val mx = vals.max()
            if (mx <= mn * 1.5f + 0.3f) "Schärfe gleichmäßig ✓ (${fmt(mn)}–${fmt(mx)} px)" else {
                var wr = 0
                var wc = 0
                for (ry in 0 until rows) for (rx in 0 until cols) if (sig[ry][rx] == mx) { wr = ry; wc = rx }
                // Lage in aufrechter Ansicht benennen
                val where = regionName(wc, wr, cols, rows, rotation)
                "Schärfe ungleichmäßig: $where unschärfer (${fmt(mn)} bis ${fmt(mx)} px)"
            }
        }
        return BlurEstimate.Result(r.sx * k, r.sy * k, r.edges, r.fromText) to note
    }

    /** Bereich (Spalte/Zeile im Sensorbild) als Lage im aufrecht gedrehten Foto benennen. */
    private fun regionName(c: Int, r: Int, cols: Int, rows: Int, rotation: Int): String {
        val nx = (c + 0.5f) / cols
        val ny = (r + 0.5f) / rows
        val (ux, uy) = when (rotation) {
            90 -> (1 - ny) to nx
            180 -> (1 - nx) to (1 - ny)
            270 -> ny to (1 - nx)
            else -> nx to ny
        }
        val v = if (uy < 0.4f) "oben" else if (uy > 0.6f) "unten" else ""
        val hz = if (ux < 0.4f) "links" else if (ux > 0.6f) "rechts" else ""
        return listOf(v, hz).filter { it.isNotEmpty() }.joinToString(" ").ifEmpty { "Mitte" }
    }

    /** Minimale Unschärfe laut Kameramodell (RAW-Pixel). */
    private fun physicalMinimum(merged: Boolean, isp: Float = 1f): CameraModel.Breakdown {
        val c = chars
        val n = c?.get(CameraCharacteristics.LENS_INFO_AVAILABLE_APERTURES)?.firstOrNull() ?: 1.6f
        val phys = c?.get(CameraCharacteristics.SENSOR_INFO_PHYSICAL_SIZE)
        val pix = c?.get(CameraCharacteristics.SENSOR_INFO_PIXEL_ARRAY_SIZE)
        val pitchUm = if (phys != null && pix != null) phys.width * 1000f / pix.width else 1.6f
        // Kamera-Zoom: ein Bildpunkt entspricht einer kleineren Fläche auf dem Sensor
        return CameraModel.minimum(n, pitchUm / isp, lastShakePx, merged)
    }

    /** Helligkeit entschärfen, Farbe beibehalten (Helligkeits-Unterschied wird auf R, G, B übertragen). */
    private fun deblurBitmap(
        b: Bitmap, sx: Float, sy: Float, iters: Int, budgetMs: Long,
        sigmaAt: ((Int, Int) -> FloatArray)?, progress: (Int) -> Unit,
    ): Pair<Bitmap, Int> {
        val w = b.width
        val h = b.height
        val px = IntArray(w * h)
        b.getPixels(px, 0, w, 0, 0, w, h)
        val y = FloatArray(w * h) { i ->
            val p = px[i]
            (((p shr 16) and 255) * 0.299f + ((p shr 8) and 255) * 0.587f + (p and 255) * 0.114f) / 255f
        }
        val (yd0, n) = Deconv.deconvolve(y, w, h, sx, sy, iters, 0.012f, 4, budgetMs, { pct -> progress(pct) }, sigmaAt)
        // starke Kanten bremsen, kein Überschwingen – damit alles gleichmäßig geschärft wirkt
        val yd = Deconv.tame(y, yd0, w, h)
        for (i in px.indices) {
            val d = (yd[i] - y[i]) * 255f
            val p = px[i]
            val r = (((p shr 16) and 255) + d).roundToInt().coerceIn(0, 255)
            val g = (((p shr 8) and 255) + d).roundToInt().coerceIn(0, 255)
            val bl = ((p and 255) + d).roundToInt().coerceIn(0, 255)
            px[i] = (0xFF shl 24) or (r shl 16) or (g shl 8) or bl
        }
        return Bitmap.createBitmap(px, w, h, Bitmap.Config.ARGB_8888) to n
    }

    /**
     * KI-Entschärfen mit bekannter Unschärfe (DPIR) auf der Helligkeit; Farbe vom Original.
     * base = physikalisch entschärftes Bild für Kacheln, die das Zeitbudget nicht mehr schafft.
     * Gibt null zurück, wenn das Modell nicht verfügbar ist.
     */
    private fun dpirBitmap(
        src: Bitmap, base: Bitmap, sx: Float, sy: Float,
        sigmaAt: ((Int, Int) -> FloatArray)?, info: MutableList<String>,
    ): Bitmap? {
        val net = try {
            DeblurNet.get(this) { showStatus(it) }
        } catch (e: Throwable) {
            info += "KI-Entschärfen nicht verfügbar (${e.message}) – Allzweck-KI stattdessen"
            return null
        }
        val w = src.width
        val h = src.height
        val px = IntArray(w * h)
        val bpx = IntArray(w * h)
        src.getPixels(px, 0, w, 0, 0, w, h)
        base.getPixels(bpx, 0, w, 0, 0, w, h)
        val lum = { p: Int -> (((p shr 16) and 255) * 0.299f + ((p shr 8) and 255) * 0.587f + (p and 255) * 0.114f) / 255f }
        val y = FloatArray(w * h) { lum(px[it]) }
        val yb = FloatArray(w * h) { lum(bpx[it]) }
        val noise = Dpir.noiseLevel(y, w, h)
        val t0 = SystemClock.elapsedRealtime()
        val sig: (Int, Int) -> FloatArray = { x, yy ->
            val s = sigmaAt?.invoke(x, yy) ?: floatArrayOf(sx, sy)
            floatArrayOf(s[0].coerceIn(0.4f, 3f), s[1].coerceIn(0.4f, 3f))
        }
        val budget = if (strongOn) 300_000L else 120_000L
        val out = Dpir.run(y, w, h, sig, noise, yb, budget, { t, s -> net.denoise(t, s) }) { done, total ->
            val per = (SystemClock.elapsedRealtime() - t0) / max(1, done)
            val left = (total - done) * per / 1000
            showStatus("KI-Entschärfen … $done/$total" + if (left >= 10) " · noch ca. ${min(left, (budget - (SystemClock.elapsedRealtime() - t0)) / 1000)} s" else "")
        }
        for (i in px.indices) {
            val d = (out.y[i] - y[i]) * 255f
            val p = px[i]
            val r = (((p shr 16) and 255) + d).roundToInt().coerceIn(0, 255)
            val g = (((p shr 8) and 255) + d).roundToInt().coerceIn(0, 255)
            val bl = ((p and 255) + d).roundToInt().coerceIn(0, 255)
            px[i] = (0xFF shl 24) or (r shl 16) or (g shl 8) or bl
        }
        info += "KI-Entschärfen (DPIR, Unschärfe bekannt, ${net.backend}): ${out.done} von ${out.total} Kacheln" +
            (if (out.done < out.total) " (Mitte zuerst, Rest physikalisch)" else "") +
            " · Rauschen ${fmt(noise * 255)}/255 · ${(SystemClock.elapsedRealtime() - t0) / 1000} s"
        return Bitmap.createBitmap(px, w, h, Bitmap.Config.ARGB_8888)
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
        lastShakePx = med
        val note = if (dropped > 0) "$dropped verwackelte Bilder aussortiert, Verwacklung ⌀ ${fmt(med)} px" else "Verwacklung ⌀ ${fmt(med)} px"
        return w to note
    }

    // ───────────────────────── Kalibrierung ─────────────────────────

    /** Positionen des Zielrahmens (Mitte der Vorschau, dann die vier Ecken) */
    private val calibSpots = listOf(
        "Mitte" to (0.5f to 0.5f),
        "oben links" to (0.23f to 0.2f),
        "oben rechts" to (0.77f to 0.2f),
        "unten links" to (0.23f to 0.8f),
        "unten rechts" to (0.77f to 0.8f),
    )

    private fun showMoreMenu(anchor: View) {
        val m = android.widget.PopupMenu(this, anchor)
        m.menu.add(0, 1, 0, if (profile != null) "Neu kalibrieren" else "Kalibrieren")
        if (profile != null) m.menu.add(0, 4, 1, "Kalibrierung ansehen")
        if (profile != null) m.menu.add(0, 2, 2, "Kalibrierung löschen")
        m.menu.add(0, 5, 3, if (diagOn) "Diagnosebild: an ✓" else "Diagnosebild: aus")
        m.menu.add(0, 6, 4, if (ispZoomOn) "Quelle: Kamera-Zoom ✓" else "Quelle: ganzes Sensorbild + RAW")
        m.menu.add(0, 7, 5, "Fotos importieren …")
        m.menu.add(0, 3, 6, "Kamera-Check")
        m.setOnMenuItemClickListener {
            when (it.itemId) {
                1 -> startCalibration()
                2 -> {
                    File(filesDir, PROFILE_FILE).delete()
                    profile = null
                    showStatus("Kalibrierung gelöscht")
                    status.postDelayed({ if (!busy) showStatus(null) }, 2000)
                }
                3 -> startActivity(Intent(this, CheckActivity::class.java))
                4 -> profile?.let { p -> showProfile(p, "Schärfeprofil", "Wird bei jedem Foto ab 2× genutzt.") }
                7 -> pickPhotos()
                6 -> {
                    ispZoomOn = !ispZoomOn
                    getPreferences(MODE_PRIVATE).edit().putBoolean("ispzoom", ispZoomOn).apply()
                    showStatus(if (ispZoomOn) "Quelle: Kamera-Zoom bis 2× (hochauflösende Bildmitte)" else "Quelle: ganzes Sensorbild + RAW (bisher)")
                    status.postDelayed({ if (!busy) showStatus(null) }, 2500)
                }
                5 -> {
                    diagOn = !diagOn
                    getPreferences(MODE_PRIVATE).edit().putBoolean("diag", diagOn).apply()
                    showStatus(if (diagOn) "Diagnosebild an: landet nach jedem Foto in der Galerie" else "Diagnosebild aus")
                    status.postDelayed({ if (!busy) showStatus(null) }, 2500)
                }
            }
            true
        }
        m.show()
    }

    private fun startCalibration() {
        if (rawReader == null) {
            showStatus("Kalibrieren braucht RAW – auf diesem Gerät nicht verfügbar")
            return
        }
        val pad = dp(20f)
        val box = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(pad, pad, pad, dp(8f))
            gravity = Gravity.CENTER_HORIZONTAL
        }
        box.addView(TextView(this).apply {
            text = "Öffne das Testmuster auf dem iPad – QR-Code mit der iPad-Kamera scannen:"
            setTextColor(FG)
            textSize = 15f
        })
        qrBitmap(PATTERN_URL, dp(220f))?.let { qr ->
            box.addView(ImageView(this).apply { setImageBitmap(qr) }, LinearLayout.LayoutParams(dp(220f), dp(220f)).apply {
                topMargin = dp(12f); bottomMargin = dp(12f)
            })
        }
        box.addView(TextView(this).apply {
            text = "Auf dem iPad: True Tone, Night Shift und Auto-Helligkeit aus, Helligkeit voll, „Schärfe-Muster“ antippen.\n\n" +
                "Dann führt dich die App durch 6 Aufnahmen: das Muster in der Mitte und in den vier Ecken, zum Schluss die Graustufen. " +
                "Stell dich so weit weg (ca. 1,5 m), dass das iPad den grünen Rahmen ausfüllt. Die App stellt selbst scharf und löst aus."
            setTextColor(MUTED)
            textSize = 13.5f
            setLineSpacing(0f, 1.2f)
        })
        android.app.AlertDialog.Builder(this, android.R.style.Theme_DeviceDefault_Dialog_Alert)
            .setTitle("Objektiv kalibrieren")
            .setView(android.widget.ScrollView(this).apply { addView(box) })
            .setPositiveButton("Los") { _, _ -> beginCalibration() }
            .setNegativeButton("Abbrechen", null)
            .show()
    }

    private fun beginCalibration() {
        calibSamples.clear()
        calibStep = 0
        clearFocusPoint()
        setZoom(CALIB_ZOOM)
        lupeOn = false
        lupeBox.visibility = View.GONE
        mfRow.visibility = View.GONE
        updateChips()
        // nur der grüne Zielrahmen zählt: Bedienelemente und Fokusrahmen ausblenden
        for (v in normalControls) v.visibility = View.GONE
        focusView.visibility = View.INVISIBLE
        status.visibility = View.GONE
        startPreview() // Belichtungskorrektur aktivieren
        updateCalibUi(null)
    }

    private fun endCalibration(): Unit = runOnUiThread {
        // läuft nach der Graustufen-Messung im Rechen-Thread – Ansicht nur im UI-Thread ändern
        // (sonst Absturz der Anzeige: Knöpfe blieben verschwunden, Zusammenfassung kam nicht)
        calibStep = -1
        pendingCalibShot = false
        calibOverlay.center = null
        calibBar.visibility = View.GONE
        calibStatus.visibility = View.GONE
        for (v in normalControls) v.visibility = View.VISIBLE
        focusView.visibility = View.VISIBLE
        clearFocusPoint()
        startPreview()
    }

    /** Anweisung für den aktuellen Schritt; note = Ergebnis des vorigen Schritts */
    private fun updateCalibUi(note: String?) = runOnUiThread {
        if (calibStep < 0) return@runOnUiThread
        val head = note?.let { "$it\n\n" } ?: ""
        if (calibStep < calibSpots.size) {
            val (name, pos) = calibSpots[calibStep]
            calibOverlay.center = pos
            calibOverlay.label = "Schärfe-Muster · $name"
            calibText.text = head + "Schritt ${calibStep + 1} von 6 · Schärfe $name\n" +
                "Halte das Handy so, dass das Schärfe-Muster im grünen Rahmen ${where(pos)} liegt, dann auslösen."
            calibSecondary.text = "Kalibrierung abbrechen"
        } else {
            calibOverlay.center = 0.5f to 0.5f
            calibOverlay.label = "Graustufen"
            calibText.text = head + "Schritt 6 von 6 · Graustufen (freiwillig)\n" +
                "Einmal aufs iPad tippen, damit es die Graustufen zeigt. Die Streifen in den Rahmen, auslösen – " +
                "schräg oder nicht ganz drin ist ok."
            calibSecondary.text = "Graustufen überspringen und Profil speichern"
        }
        calibBar.visibility = View.VISIBLE
    }

    private fun where(pos: Pair<Float, Float>): String = when {
        pos.first == 0.5f -> "in der Mitte"
        else -> (if (pos.second < 0.5f) "oben " else "unten ") + (if (pos.first < 0.5f) "links" else "rechts")
    }

    private fun calibSecondaryAction() {
        if (calibStep >= calibSpots.size && calibSamples.size >= 3) {
            saveProfile(null)
        } else {
            endCalibration()
            showStatus("Kalibrierung abgebrochen")
            status.postDelayed({ if (!busy) showStatus(null) }, 2000)
        }
    }

    /** Auslöser im Kalibrier-Modus: erst auf die Rahmenmitte scharfstellen, dann aufnehmen. */
    private fun calibShutter() {
        if (busy) return
        if (kotlin.math.abs(zoom - CALIB_ZOOM) > 0.01f) setZoom(CALIB_ZOOM)
        val r = calibOverlay.boxRect() ?: return
        pendingCalibShot = true
        showStatus("Stelle auf das Muster scharf …")
        setFocusPoint(r.centerX(), r.centerY())
        if (maxDiopter <= 0f) {
            pendingCalibShot = false
            shoot()
        }
    }

    /** Vorschau-Punkt (Hochformat, 0..1) → Ausschnitt in Sensor-Ausrichtung (0..1) */
    private fun previewToCrop(nx: Float, ny: Float): Pair<Float, Float> =
        if (sensorOrientation == 270) (1 - ny) to nx else ny to (1 - nx)

    /** Zielrahmen als Anteile des Ergebnisbildes (Sensor-Ausrichtung): x0, y0, x1, y1 */
    private fun calibBoxCrop(): FloatArray? {
        val r = calibOverlay.boxRect() ?: return null
        val w = calibOverlay.width.toFloat()
        val h = calibOverlay.height.toFloat()
        val a = previewToCrop(r.left / w, r.top / h)
        val b = previewToCrop(r.right / w, r.bottom / h)
        return floatArrayOf(min(a.first, b.first), min(a.second, b.second), max(a.first, b.first), max(a.second, b.second))
    }

    private fun calibMessage(msg: String) {
        showStatus(null)
        updateCalibUi("⚠ $msg")
    }

    private fun handleCalibResult(col: Collector, out: RawSR.Out) {
        val planes = out.planes ?: return calibMessage("Messung fehlgeschlagen – bitte nochmal.")
        val bw = out.boxW
        val bh = out.boxH
        val box = col.calibBox!!
        if (col.calibStep < calibSpots.size) {
            val m = Calib.measureSharpness(planes, bw, bh, out.u, lastShakePx)
                ?: return calibMessage("Keine klaren Kanten im Rahmen – liegt das Schärfe-Muster ganz im grünen Rahmen?")
            val (sig, edges) = m
            // Rahmenmitte → Position auf dem ganzen Sensor
            val z = col.zoomAtShot
            val cxN = (box[0] + box[2]) / 2f
            val cyN = (box[1] + box[3]) / 2f
            val sample = Calib.Sample(0.5f + (cxN - 0.5f) / z, 0.5f + (cyN - 0.5f) / z, sig, edges)
            calibSamples += sample
            val l = sample.luma()
            val name = calibSpots[col.calibStep].first
            val note = "✓ $name: Schärfe ${fmt2(l[0])}/${fmt2(l[1])} px · R ${fmt2(avg(sig[0]))} · G ${fmt2(avg(sig[1]))} · B ${fmt2(avg(sig[2]))}"
            calibStep = col.calibStep + 1
            showStatus(null)
            updateCalibUi(note)
        } else {
            val y = FloatArray(bw * bh) { 0.25f * planes[0][it] + 0.5f * planes[1][it] + 0.25f * planes[2][it] }
            val wedge = Calib.analyzeWedge(y, bw, bh)
                ?: return calibMessage("Graustufen nicht erkannt. Nochmal versuchen (iPad etwas näher, gerade halten) – " +
                    "oder unten „überspringen“, das Schärfeprofil ist schon vollständig.")
            saveProfile(wedge)
        }
    }

    private fun avg(a: FloatArray) = (a[0] + a[1]) / 2f

    private fun fmt2(v: Float): String = String.format(Locale.GERMANY, "%.2f", v)

    private fun saveProfile(wedge: Calib.Wedge?) {
        val date = SimpleDateFormat("dd.MM.yyyy", Locale.GERMANY).format(Date())
        val json = org.json.JSONObject().apply {
            put("date", date)
            put("device", "${android.os.Build.MANUFACTURER} ${android.os.Build.MODEL}")
            put("samples", org.json.JSONArray().apply {
                for (s in calibSamples) put(org.json.JSONObject().apply {
                    put("nx", s.nx.toDouble())
                    put("ny", s.ny.toDouble())
                    put("edges", s.edges)
                    put("sig", org.json.JSONArray().apply {
                        for (c in 0 until 3) put(org.json.JSONArray().apply { put(s.sig[c][0].toDouble()); put(s.sig[c][1].toDouble()) })
                    })
                })
            })
            if (wedge != null) {
                put("r2", wedge.r2.toDouble())
                put("flare", wedge.flare.toDouble())
            }
        }
        File(filesDir, PROFILE_FILE).writeText(json.toString(2))
        val p = SharpProfile(calibSamples.toList(), wedge?.r2, wedge?.flare, date)
        profile = p
        endCalibration()
        showProfile(p, "Kalibrierung gespeichert ✓",
            "Ab jetzt nutzt jedes Foto ab 2× dieses Schärfeprofil, um die Unschärfe gezielt herauszurechnen.")
    }

    /** Zusammenfassung des Schärfeprofils als Dialog (nach dem Kalibrieren und unter Mehr › Kalibrierung ansehen). */
    private fun showProfile(p: SharpProfile, title: String, footer: String) {
        val samples = p.samples
        val lines = ArrayList<String>()
        if (samples.isNotEmpty()) {
            val c = samples.first().luma()
            val cm = (c[0] + c[1]) / 2f
            lines += "Mitte: ${fmt2(cm)} px Unschärfe (Sensorpixel)"
            val names = calibSpots.map { it.first }
            for ((i, sm) in samples.withIndex().drop(1)) {
                val l = sm.luma()
                val v = (l[0] + l[1]) / 2f
                val pct = ((v / cm - 1f) * 100).roundToInt()
                lines += "${names.getOrElse(i) { "Punkt ${i + 1}" }}: ${fmt2(v)} px (${if (pct >= 0) "+" else ""}$pct %)"
            }
            val spread = samples.maxOf { Calib.colorSpread(it) }
            lines += "Farben: R/B weichen bis ${(spread * 100).roundToInt()} % von Grün ab"
        }
        if (p.r2 != null && p.flare != null) {
            lines += "Linearität des Sensors: R² ${String.format(Locale.GERMANY, "%.4f", p.r2)}"
            lines += "Streulicht/Spiegelung: ${String.format(Locale.GERMANY, "%.1f", p.flare * 100)} %" +
                if (p.flare > 0.05f) " – eher hoch, Raum abdunkeln wäre besser" else " ✓"
        } else lines += "Graustufen: nicht gemessen"
        runOnUiThread {
            android.app.AlertDialog.Builder(this, android.R.style.Theme_DeviceDefault_Dialog_Alert)
                .setTitle(title)
                .setMessage("Kalibriert am ${p.date}\n\n" + lines.joinToString("\n") + "\n\n" + footer)
                .setPositiveButton("Fertig", null)
                .show()
        }
    }

    private fun loadProfile() {
        try {
            val f = File(filesDir, PROFILE_FILE)
            if (!f.exists()) return
            val j = org.json.JSONObject(f.readText())
            val arr = j.getJSONArray("samples")
            val list = (0 until arr.length()).map { i ->
                val o = arr.getJSONObject(i)
                val sg = o.getJSONArray("sig")
                Calib.Sample(
                    o.getDouble("nx").toFloat(), o.getDouble("ny").toFloat(),
                    Array(3) { c -> val a = sg.getJSONArray(c); floatArrayOf(a.getDouble(0).toFloat(), a.getDouble(1).toFloat()) },
                    o.optInt("edges", 0),
                )
            }
            if (list.isNotEmpty()) {
                profile = SharpProfile(
                    list,
                    if (j.has("r2")) j.getDouble("r2").toFloat() else null,
                    if (j.has("flare")) j.getDouble("flare").toFloat() else null,
                    j.optString("date", ""),
                )
            }
        } catch (_: Throwable) {
        }
    }

    /** QR-Code als Bild (für den Testmuster-Link). */
    private fun qrBitmap(text: String, size: Int): Bitmap? = try {
        val m = com.google.zxing.qrcode.QRCodeWriter().encode(text, com.google.zxing.BarcodeFormat.QR_CODE, size, size,
            mapOf(com.google.zxing.EncodeHintType.MARGIN to 2))
        val px = IntArray(size * size) { i -> if (m.get(i % size, i / size)) Color.BLACK else Color.WHITE }
        Bitmap.createBitmap(px, size, size, Bitmap.Config.ARGB_8888)
    } catch (_: Throwable) {
        null
    }

    private fun cacheJpeg(b: Bitmap, name: String) {
        File(cacheDir, name).outputStream().use { b.compress(Bitmap.CompressFormat.JPEG, 95, it) }
    }

    // ───────────────────────── 50 MP über die Xiaomi-Kamera ─────────────────────────

    private enum class PhotoAccess { NONE, PARTIAL, FULL }

    private fun photoAccess(): PhotoAccess {
        val granted = { p: String -> checkSelfPermission(p) == PackageManager.PERMISSION_GRANTED }
        return when {
            android.os.Build.VERSION.SDK_INT >= 33 && granted(Manifest.permission.READ_MEDIA_IMAGES) -> PhotoAccess.FULL
            android.os.Build.VERSION.SDK_INT < 33 && granted(Manifest.permission.READ_EXTERNAL_STORAGE) -> PhotoAccess.FULL
            android.os.Build.VERSION.SDK_INT >= 34 && granted("android.permission.READ_MEDIA_VISUAL_USER_SELECTED") -> PhotoAccess.PARTIAL
            else -> PhotoAccess.NONE
        }
    }

    private fun askXiaomi() {
        if (busy) return
        android.app.AlertDialog.Builder(this, android.R.style.Theme_DeviceDefault_Dialog_Alert)
            .setTitle("50 MP mit der Xiaomi-Kamera")
            .setMessage("Die volle Auflösung gibt Xiaomi nur der eigenen Kamera-App frei. Deshalb:\n\n" +
                "1. In der Kamera-App oben auf „50 MP“ (bzw. „50M“) stellen.\n" +
                "2. 1 bis 8 Fotos vom selben Motiv machen, möglichst ruhig – mehrere werden übereinandergelegt.\n" +
                "3. Mit „Zurück“ hierher kommen: Die App holt die neuen Fotos automatisch.")
            .setPositiveButton("Kamera öffnen") { _, _ -> launchXiaomi() }
            .setNegativeButton("Abbrechen", null)
            .show()
    }

    private fun launchXiaomi() {
        if (photoAccess() == PhotoAccess.NONE) {
            val p = if (android.os.Build.VERSION.SDK_INT >= 33) Manifest.permission.READ_MEDIA_IMAGES else Manifest.permission.READ_EXTERNAL_STORAGE
            requestPermissions(arrayOf(p), REQ_PHOTOS)
            return
        }
        getPreferences(MODE_PRIVATE).edit().putLong("xiaomiSince", System.currentTimeMillis() / 1000 - 2).apply()
        try {
            startActivity(Intent(android.provider.MediaStore.INTENT_ACTION_STILL_IMAGE_CAMERA))
        } catch (_: Exception) {
            getPreferences(MODE_PRIVATE).edit().remove("xiaomiSince").apply()
            showStatus("Kamera-App nicht gefunden")
        }
    }

    /** Zurück aus der Xiaomi-Kamera: neue Fotos seit dem Start suchen und zum Ausschnitt-Wählen schicken. */
    private fun checkXiaomiReturn() {
        val prefs = getPreferences(MODE_PRIVATE)
        val since = prefs.getLong("xiaomiSince", 0L)
        if (since <= 0L) return
        prefs.edit().remove("xiaomiSince").apply()
        Thread {
            val uris = ArrayList<android.net.Uri>()
            try {
                val col = android.provider.MediaStore.Images.Media.EXTERNAL_CONTENT_URI
                contentResolver.query(
                    col, arrayOf(android.provider.MediaStore.Images.Media._ID),
                    "${android.provider.MediaStore.Images.Media.DATE_ADDED} >= ?", arrayOf(since.toString()),
                    "${android.provider.MediaStore.Images.Media.DATE_ADDED} DESC",
                )?.use { c ->
                    while (c.moveToNext() && uris.size < ImportActivity.MAX_PHOTOS) {
                        uris += android.content.ContentUris.withAppendedId(col, c.getLong(0))
                    }
                }
            } catch (_: Exception) {
            }
            // eigene Ergebnisse (zoom_, diagnose_) nicht mitnehmen: nur Fotos, die nach dem Start entstanden,
            // und die App speichert in der Zwischenzeit nichts
            uris.reverse()
            runOnUiThread {
                if (uris.isEmpty()) {
                    showStatus(if (photoAccess() == PhotoAccess.PARTIAL)
                        "Kein Zugriff auf neue Fotos – in den Einstellungen „Alle Fotos erlauben“ oder Mehr › Fotos importieren"
                    else "Kein neues Foto gefunden – oder über Mehr › Fotos importieren wählen")
                    status.postDelayed({ if (!busy) showStatus(null) }, 5000)
                } else openImport(uris)
            }
        }.start()
    }

    private fun pickPhotos() {
        val pick = Intent(android.provider.MediaStore.ACTION_PICK_IMAGES).apply {
            putExtra(android.provider.MediaStore.EXTRA_PICK_IMAGES_MAX, ImportActivity.MAX_PHOTOS)
        }
        val intent = if (pick.resolveActivity(packageManager) != null) pick else
            Intent(Intent.ACTION_GET_CONTENT).apply {
                type = "image/*"
                putExtra(Intent.EXTRA_ALLOW_MULTIPLE, true)
            }
        try {
            @Suppress("DEPRECATION")
            startActivityForResult(intent, REQ_PICK)
        } catch (_: Exception) {
            showStatus("Fotoauswahl nicht verfügbar")
        }
    }

    @Deprecated("Activity-Result-API")
    override fun onActivityResult(code: Int, result: Int, data: Intent?) {
        @Suppress("DEPRECATION")
        super.onActivityResult(code, result, data)
        if (code != REQ_PICK || result != RESULT_OK || data == null) return
        val uris = ArrayList<android.net.Uri>()
        data.clipData?.let { c -> for (k in 0 until c.itemCount) c.getItemAt(k).uri?.let { uris += it } }
        if (uris.isEmpty()) data.data?.let { uris += it }
        if (uris.isNotEmpty()) openImport(uris)
    }

    private fun openImport(uris: List<android.net.Uri>) {
        val i = Intent(this, ImportActivity::class.java).apply {
            putParcelableArrayListExtra(EXTRA_URIS, ArrayList(uris))
            val clip = android.content.ClipData.newRawUri("fotos", uris[0])
            for (u in uris.drop(1)) clip.addItem(android.content.ClipData.Item(u))
            clipData = clip
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        startActivity(i)
    }

    private fun handleImportIntent(i: Intent?) {
        if (i?.action != ACTION_IMPORT) return
        i.action = null // nicht nochmal bei Drehung/Neustart
        @Suppress("DEPRECATION")
        val uris = i.getParcelableArrayListExtra<android.net.Uri>(EXTRA_URIS) ?: return
        val rect = i.getFloatArrayExtra(EXTRA_RECT) ?: return
        val z = i.getFloatExtra(EXTRA_ZOOM, 4f)
        if (busy) {
            showStatus("Noch beschäftigt – bitte gleich nochmal")
            return
        }
        busy = true
        shutter.mode = ShutterView.Mode.PROCESSING
        aiWorker.execute { runImport(uris, rect, z) }
    }

    /** Importierte Fotos: Ausschnitt lesen, übereinanderlegen, entschärfen, KI, Diagnosebild. */
    private fun runImport(uris: List<android.net.Uri>, rect: FloatArray, z: Float) {
        try {
            val info = ArrayList<String>()
            showStatus("Lese Fotos …")
            val dims = PhotoIo.info(this, uris[0]) ?: throw IllegalStateException("Foto lässt sich nicht öffnen")
            // großer Rand: zwischen Fotos aus der Hand liegen bei 50 MP schnell Hunderte Pixel
            val margin = IMPORT_MAX_SHIFT
            val frames = ArrayList<Frame?>()
            var region: Region? = null
            var cropW = 0
            var cropH = 0
            for ((k, u) in uris.withIndex()) {
                showStatus("Lese Foto ${k + 1}/${uris.size} …")
                val d = PhotoIo.info(this, u) ?: continue
                if (d.uprightW != dims.uprightW || d.uprightH != dims.uprightH) continue // andere Auflösung
                val (bmp, inner) = PhotoIo.crop(this, u, rect, margin) ?: continue
                if (region == null) {
                    cropW = bmp.width
                    cropH = bmp.height
                    // so viele Fotos, wie in den Speicher passen
                    val maxN = (YUV_BUDGET / (cropW.toLong() * cropH * 3 / 2)).toInt().coerceIn(1, ImportActivity.MAX_PHOTOS)
                    if (frames.size >= maxN) { bmp.recycle(); break }
                    region = Region(cropW, cropH, 0, 0, cropW, cropH,
                        inner.left / 2 * 2, inner.top / 2 * 2, (inner.width() / 2 * 2).coerceAtMost(cropW), (inner.height() / 2 * 2).coerceAtMost(cropH))
                }
                if (bmp.width != cropW || bmp.height != cropH) { bmp.recycle(); continue }
                frames += argbToFrame(bmp)
                bmp.recycle()
                val maxN = (YUV_BUDGET / (cropW.toLong() * cropH * 3 / 2)).toInt().coerceIn(1, ImportActivity.MAX_PHOTOS)
                if (frames.size >= maxN) break
            }
            val r = region ?: throw IllegalStateException("Ausschnitt lässt sich nicht lesen")
            val mpx = dims.uprightW.toLong() * dims.uprightH / 1e6
            info += "Import Xiaomi-Kamera: ${frames.size} Foto${if (frames.size == 1) "" else "s"} · " +
                "${dims.uprightW}×${dims.uprightH} (${"%.0f".format(mpx)} MP) · Ausschnitt ${fmt(z)}× = ${r.cw}×${r.ch} px"
            if (mpx < 30) info += "Achtung: Foto hat nur ${"%.0f".format(mpx)} MP – in der Kamera-App auf 50 MP stellen"

            // 1. übereinanderlegen (kachelweise ausgerichtet, unscharfe aussortiert)
            val res = SuperRes.process(frames, r, null, IMPORT_MAX_SHIFT) { showStatus(it) }
            info += if (res.total > 1) "${res.used} von ${res.total} Fotos verrechnet" +
                (if (SuperRes.dropped > 0) " (${SuperRes.dropped} unscharf oder nicht deckungsgleich aussortiert)" else "") +
                " · Restfehler je Foto ${SuperRes.lastResiduals} (passt bis ${SuperRes.RESID_ABS.toInt()})"
            else "Einzelfoto (mit mehreren Fotos wird es ruhiger und schärfer)"

            // Diagnose: kleiner Ausschnitt in der Bildmitte, 1,8-fach vergrößert, damit Details sichtbar werden
            val diag = ArrayList<Pair<String, Bitmap>>()
            val fx = min(1f, DIAG_IMPORT_PX.toFloat() / res.single.width)
            val fy = min(1f, DIAG_IMPORT_PX.toFloat() / res.single.height)
            val dr = floatArrayOf(0.5f - fx / 2, 0.5f - fy / 2, 0.5f + fx / 2, 0.5f + fy / 2)
            fun keep(name: String, b: Bitmap) {
                if (diagOn) try { diag += name to diagCrop(b, dr, 0) } catch (_: Throwable) {}
            }
            keep("1 Xiaomi-Foto (Original, ${"%.0f".format(mpx)} MP)", res.single)
            if (diagOn) {
                val half = Bitmap.createScaledBitmap(res.single, res.single.width / 2, res.single.height / 2, true)
                keep("2 zum Vergleich: wie 12 MP", half)
                half.recycle()
            }
            if (res.used > 1) keep("3 ${res.used} Fotos übereinander", res.merged)

            // 2. Unschärfe messen und physikalisch entschärfen (gebremst)
            showStatus("Messe Unschärfe …")
            val (blur, uniformity) = measureBlur(res.merged, 0)
            info += uniformity
            val sx = blur.sx.coerceIn(0.5f, 2.5f)
            val sy = blur.sy.coerceIn(0.5f, 2.5f)
            info += "Unschärfe gemessen ${fmt(blur.sx)}/${fmt(blur.sy)} px" + (if (blur.fromText) " (an Schrift)" else "") +
                (if (sx != blur.sx || sy != blur.sy) " → begrenzt auf ${fmt(sx)}/${fmt(sy)} px" else "")
            val (sharp, decIters) = deblurBitmap(res.merged, sx, sy, if (strongOn) 150 else 80, if (strongOn) 60_000L else 25_000L, null) { pct ->
                showStatus("Entschärfe … $pct %")
            }
            info += "Entschärft: $decIters Durchgänge (Richardson-Lucy, starke Kanten gebremst)"
            keep("4 entschärft (gebremst)", sharp)

            // 3. KI-Entschärfen mit bekannter Unschärfe (DPIR); falls nicht verfügbar: Allzweck-KI
            val dp = dpirBitmap(res.merged, sharp, sx, sy, null, info)
            val kiRaw = dp ?: run {
                val model = if (strongOn) AiUpscaler.getStrong(this) { showStatus(it) } else ai!!
                val budget = if (strongOn) 180_000L else 45_000L
                val maxTiles = max(if (strongOn) 4L else 40L, budget / max(1L, model.msPerTile)).toInt()
                var sc = min(1f, 2048f / max(sharp.width, sharp.height))
                while (sc > 0.1f && model.tilesFor((sharp.width * sc).toInt(), (sharp.height * sc).toInt()) > maxTiles) sc *= 0.9f
                val iw = max(16, (sharp.width * sc).roundToInt())
                val ih = max(16, (sharp.height * sc).roundToInt())
                val input = if (iw != sharp.width || ih != sharp.height) Bitmap.createScaledBitmap(sharp, iw, ih, true) else sharp
                val t0 = SystemClock.elapsedRealtime()
                val k0 = model.upscale(input, true) { done, total -> showStatus("KI … ${done * 100 / total} %") }
                if (input !== sharp) input.recycle()
                info += "KI ${if (strongOn) "stark" else "schnell"} (${model.backend}): ${fmt((SystemClock.elapsedRealtime() - t0) / 1000f)} s"
                k0
            }

            // Ausgabegröße: Ausschnitt ×2, lange Seite höchstens 4096
            val fit = min(2f, 4096f / max(sharp.width, sharp.height))
            val kw = max(1, (sharp.width * fit).roundToInt())
            val kh = max(1, (sharp.height * fit).roundToInt())
            val kiBmp = if (kiRaw.width != kw || kiRaw.height != kh)
                Bitmap.createScaledBitmap(kiRaw, kw, kh, true).also { if (it !== kiRaw) kiRaw.recycle() } else kiRaw
            keep(if (dp != null) "5 KI-Entschärfen (DPIR)" else "5 KI pur", kiBmp)
            val galleryMix = if (dp != null) 100 else if (blur.fromText) 0 else ViewerActivity.DEFAULT_MIX
            ViewerActivity.startMix = galleryMix
            if (galleryMix == 0) info += "Schrift erkannt → Galerie ohne KI (Regler im Viewer bleibt)"

            showStatus("Speichere …")
            val stamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.GERMANY).format(Date())
            val n = Bitmap.createScaledBitmap(res.single, kw, kh, true)
            cacheJpeg(n, ViewerActivity.NORMAL)
            n.recycle()
            res.single.recycle()
            res.merged.recycle()
            val sBmp = Bitmap.createScaledBitmap(sharp, kw, kh, true)
            if (sBmp !== sharp) sharp.recycle()
            cacheJpeg(sBmp, ViewerActivity.REAL)
            cacheJpeg(kiBmp, ViewerActivity.KI)
            val mix = Bitmap.createBitmap(kw, kh, Bitmap.Config.ARGB_8888)
            android.graphics.Canvas(mix).apply {
                drawBitmap(sBmp, 0f, 0f, null)
                drawBitmap(kiBmp, 0f, 0f, android.graphics.Paint().apply { alpha = galleryMix * 255 / 100 })
            }
            sBmp.recycle()
            kiBmp.recycle()
            ViewerActivity.saveToGallery(this, mix, "import_${stamp}_${fmt(z).replace(',', '-')}x")
            val t4 = diag.firstOrNull { it.first.startsWith("4") }?.second
            val t5 = diag.firstOrNull { it.first.startsWith("5") }?.second
            if (t4 != null && t5 != null) {
                val m6 = t4.copy(Bitmap.Config.ARGB_8888, true)
                android.graphics.Canvas(m6).drawBitmap(Bitmap.createScaledBitmap(t5, m6.width, m6.height, true), 0f, 0f,
                    android.graphics.Paint().apply { alpha = galleryMix * 255 / 100 })
                diag += "6 Galerie (Echt + $galleryMix % KI)" to m6
            }
            if (diag.isNotEmpty()) {
                val sheet = diagSheet(diag, info)
                ViewerActivity.saveToGallery(this, sheet, "diagnose_import_${stamp}")
                sheet.recycle()
            }
            val small = Bitmap.createScaledBitmap(mix, dp(54f) * 2, dp(54f) * 2 * mix.height / mix.width, true)
            mix.recycle()
            File(cacheDir, ViewerActivity.INFO).writeText(info.joinToString("\n"))
            showStatus("Fertig ✓")
            runOnUiThread {
                thumb.setImageBitmap(small)
                thumb.visibility = View.VISIBLE
                startActivity(Intent(this, ViewerActivity::class.java))
            }
        } catch (e: OutOfMemoryError) {
            showStatus("Zu wenig Speicher – kleineren Ausschnitt (mehr Zoom) oder weniger Fotos")
        } catch (e: Throwable) {
            showStatus("Fehler beim Import: ${e.message}")
        } finally {
            busy = false
            runOnUiThread { shutter.mode = ShutterView.Mode.IDLE }
            status.postDelayed({ if (!busy) showStatus(null) }, 5000)
        }
    }

    /** ARGB-Bild → Helligkeit (volle Auflösung) + Farbe (halbe Auflösung), wie ein Kamerabild (JPEG-Farbraum). */
    private fun argbToFrame(b: Bitmap): Frame {
        val w = b.width
        val h = b.height
        val px = IntArray(w * h)
        b.getPixels(px, 0, w, 0, 0, w, h)
        val y = ByteArray(w * h)
        for (i in px.indices) {
            val p = px[i]
            y[i] = (0.299f * ((p shr 16) and 255) + 0.587f * ((p shr 8) and 255) + 0.114f * (p and 255)).roundToInt().coerceIn(0, 255).toByte()
        }
        val cw = w / 2
        val ch = h / 2
        val u = ByteArray(cw * ch)
        val v = ByteArray(cw * ch)
        for (yy in 0 until ch) for (xx in 0 until cw) {
            var rs = 0
            var gs = 0
            var bs = 0
            for (a in 0..1) for (c in 0..1) {
                val p = px[(yy * 2 + a) * w + xx * 2 + c]
                rs += (p shr 16) and 255; gs += (p shr 8) and 255; bs += p and 255
            }
            val r = rs / 4f
            val g = gs / 4f
            val bl = bs / 4f
            u[yy * cw + xx] = (-0.168736f * r - 0.331264f * g + 0.5f * bl + 128f).roundToInt().coerceIn(0, 255).toByte()
            v[yy * cw + xx] = (0.5f * r - 0.418688f * g - 0.081312f * bl + 128f).roundToInt().coerceIn(0, 255).toByte()
        }
        return Frame(y, u, v)
    }

    // ───────────────────────── Diagnosebild ─────────────────────────

    /** Ausschnitt r (Anteile, Sensor-Ausrichtung) aus b, gedreht und auf Kachelgröße gebracht. */
    private fun diagCrop(b: Bitmap, r: FloatArray, rot: Int): Bitmap {
        val x0 = (r[0] * b.width).toInt().coerceIn(0, b.width - 2)
        val y0 = (r[1] * b.height).toInt().coerceIn(0, b.height - 2)
        val w = ((r[2] - r[0]) * b.width).toInt().coerceIn(2, b.width - x0)
        val h = ((r[3] - r[1]) * b.height).toInt().coerceIn(2, b.height - y0)
        val c = Bitmap.createBitmap(b, x0, y0, w, h)
        val c2 = if (c === b) c.copy(Bitmap.Config.ARGB_8888, false) else c
        return diagScale(rotate(c2, rot))
    }

    private fun diagScale(b: Bitmap): Bitmap {
        val s = Bitmap.createScaledBitmap(b, DIAG_TILE, DIAG_TILE * b.height / max(1, b.width), true)
        if (s !== b) b.recycle()
        return s
    }

    /** Alle Schritte als Raster (2 Spalten) mit Beschriftung, darunter die Infozeilen. */
    private fun diagSheet(tiles: List<Pair<String, Bitmap>>, info: List<String>): Bitmap {
        val gap = 12
        val lab = 44
        val th = tiles.maxOf { it.second.height }
        val rows = (tiles.size + 1) / 2
        val textP = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply {
            color = 0xFFFFFFFF.toInt(); textSize = 26f
        }
        val infoP = android.graphics.Paint(textP).apply { textSize = 22f; color = 0xFFBBBBBB.toInt() }
        val infoH = 30 * (info.size + 1) + gap
        val w = 2 * DIAG_TILE + 3 * gap
        val h = rows * (th + lab + gap) + gap + infoH
        val out = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        val cv = android.graphics.Canvas(out)
        cv.drawColor(0xFF111111.toInt())
        for ((i, t) in tiles.withIndex()) {
            val x = gap + (i % 2) * (DIAG_TILE + gap)
            val y = gap + (i / 2) * (th + lab + gap)
            cv.drawText(t.first, x.toFloat(), (y + 32).toFloat(), textP)
            cv.drawBitmap(t.second, x.toFloat(), (y + lab).toFloat(), null)
            t.second.recycle()
        }
        var y = rows * (th + lab + gap) + gap + 26
        for (l in info) {
            cv.drawText(l, gap.toFloat(), y.toFloat(), infoP)
            y += 30
        }
        return out
    }

    private fun rotate(b: Bitmap, deg: Int): Bitmap {
        if (deg == 0) return b
        val r = Bitmap.createBitmap(b, 0, 0, b.width, b.height, Matrix().apply { postRotate(deg.toFloat()) }, true)
        if (r !== b) b.recycle()
        return r
    }

    companion object {
        private const val DIAG_TILE = 540
        /** Import: so viele Pixel des Ausschnitts zeigt eine Diagnose-Kachel (vergrößert auf DIAG_TILE) */
        private const val DIAG_IMPORT_PX = 300
        /** Import: so weit (Pixel) dürfen Fotos gegeneinander verrutscht sein */
        private const val IMPORT_MAX_SHIFT = 400
        const val ACTION_IMPORT = "de.john.kameracheck.IMPORT"
        const val EXTRA_URIS = "uris"
        const val EXTRA_RECT = "rect"
        const val EXTRA_ZOOM = "zoom"
        private const val REQ_PHOTOS = 2
        private const val REQ_PICK = 7
        /** Bis zu diesem Zoom macht der Bildprozessor den Ausschnitt (In-Sensor-Zoom des 50-MP-Sensors) */
        private const val ISP_ZOOM = 2f
        private const val YUV_BUDGET = 150_000_000L
        private const val BG = 0xFF111111.toInt()
        private const val FG = 0xFFEDEDED.toInt()
        private const val MUTED = 0xFF8A8A8A.toInt()
        private const val MAX_AI_INPUT = 1024
        private const val SWEEP_WAIT = 2
        private const val CALIB_ZOOM = 2f
        private const val PROFILE_FILE = "schaerfeprofil.json"
        private const val PATTERN_URL = "https://claude.ai/artifact/STqv4hLLZ5vm1NSBR2tb6k"
        private const val RAW_BUDGET = 150_000_000L
        private const val RECHECK_MS = 5000L
    }
}
