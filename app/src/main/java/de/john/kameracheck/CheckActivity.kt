package de.john.kameracheck

import android.app.Activity
import android.content.Intent
import android.graphics.ImageFormat
import android.graphics.Typeface
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraManager
import android.os.Build
import android.os.Bundle
import android.util.Size
import android.view.Gravity
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import kotlin.math.sqrt

class CheckActivity : Activity() {

    private val testLog = StringBuilder()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.statusBarColor = BG
        window.navigationBarColor = BG

        val report = try {
            buildReport()
        } catch (e: Exception) {
            "Fehler beim Auslesen: ${e.message}"
        }

        val dp = resources.displayMetrics.density
        val pad = (20 * dp).toInt()

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(pad, pad, pad, pad)
        }

        root.addView(TextView(this).apply {
            text = "Kamera-Check"
            textSize = 26f
            setTextColor(FG)
            typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
        })

        root.addView(TextView(this).apply {
            text = "Was dein Handy an Kamera-Funktionen freigibt"
            textSize = 14f
            setTextColor(MUTED)
            setPadding(0, (4 * dp).toInt(), 0, (16 * dp).toInt())
        })

        root.addView(Button(this).apply {
            text = "Bericht teilen"
            isAllCaps = false
            gravity = Gravity.CENTER
            setOnClickListener {
                val send = Intent(Intent.ACTION_SEND).apply {
                    type = "text/plain"
                    putExtra(Intent.EXTRA_SUBJECT, "Kamera-Check")
                    putExtra(Intent.EXTRA_TEXT, if (testLog.isEmpty()) report else "$testLog\n\n$report")
                }
                startActivity(Intent.createChooser(send, "Bericht teilen"))
            }
        })

        // 50-MP-Test: probiert alle Wege zur vollen Sensorauflösung und nimmt wirklich auf
        val testOut = TextView(this).apply {
            textSize = 12.5f
            setTextColor(0xFF5BE38A.toInt())
            typeface = Typeface.MONOSPACE
            setTextIsSelectable(true)
            setLineSpacing(0f, 1.15f)
            setPadding(0, (12 * dp).toInt(), 0, 0)
        }
        root.addView(Button(this).apply {
            text = "50-MP-Test starten"
            isAllCaps = false
            setOnClickListener { btn ->
                btn.isEnabled = false
                testLog.setLength(0)
                testOut.text = "Läuft … (dauert bis zu 2 Minuten, Handy ruhig hinlegen)"
                Thread {
                    try {
                        FullResTest(this@CheckActivity) { line ->
                            synchronized(testLog) { testLog.appendLine(line) }
                            runOnUiThread { testOut.text = testLog.toString() }
                        }.run()
                    } catch (e: Throwable) {
                        synchronized(testLog) { testLog.appendLine("Abbruch: ${e.message}") }
                    }
                    runOnUiThread {
                        testOut.text = testLog.toString()
                        btn.isEnabled = true
                        (btn as Button).text = "50-MP-Test nochmal"
                    }
                }.start()
            }
        })
        root.addView(testOut)

        root.addView(TextView(this).apply {
            text = report
            textSize = 12.5f
            setTextColor(FG)
            typeface = Typeface.MONOSPACE
            setTextIsSelectable(true)
            setLineSpacing(0f, 1.15f)
            setPadding(0, (16 * dp).toInt(), 0, 0)
        })

        setContentView(ScrollView(this).apply {
            setBackgroundColor(BG)
            fitsSystemWindows = true
            addView(root)
        })
    }

    // ───────────────────────── Bericht ─────────────────────────

    private fun buildReport(): String {
        val cm = getSystemService(CameraManager::class.java)
        val sb = StringBuilder()

        sb.appendLine("${Build.MANUFACTURER} ${Build.MODEL}")
        sb.appendLine("Android ${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT})")
        sb.appendLine()

        val listed = cm.cameraIdList.toList()
        sb.appendLine("Offiziell gemeldete Kameras: ${listed.joinToString(", ")}")

        // Physische Einzelkameras hinter logischen Kameras einsammeln
        val physical = linkedSetOf<String>()
        for (id in listed) {
            val c = cm.getCameraCharacteristics(id)
            physical.addAll(c.physicalCameraIds.filter { it !in listed })
        }
        if (physical.isNotEmpty()) {
            sb.appendLine("Physische Kameras dahinter: ${physical.joinToString(", ")}")
        }

        // Versteckte Kameras: IDs ausprobieren, die nicht gemeldet wurden
        val known = listed.toSet() + physical
        val hidden = mutableListOf<String>()
        for (i in 0..63) {
            val id = i.toString()
            if (id in known) continue
            try {
                cm.getCameraCharacteristics(id)
                hidden += id
            } catch (_: Exception) {
            }
        }
        sb.appendLine(
            if (hidden.isEmpty()) "Versteckte Kameras: keine gefunden"
            else "Versteckte Kameras: ${hidden.joinToString(", ")}  ← spannend!"
        )

        for (id in listed) describe(sb, cm, id, "Kamera $id")
        for (id in physical) describe(sb, cm, id, "Physische Kamera $id")
        for (id in hidden) describe(sb, cm, id, "Versteckte Kamera $id")

        sb.appendLine()
        sb.appendLine("━━━━━━━━━━━━━━━━━━━━━━━━━━━━")
        sb.appendLine("Legende")
        sb.appendLine("RAW – unbearbeitete Sensordaten, ideal für eigene KI-Verarbeitung")
        sb.appendLine("Manueller Sensor – Belichtungszeit und ISO selbst steuern")
        sb.appendLine("Burst – viele Bilder schnell hintereinander (Mehrbild-Zoom)")
        sb.appendLine("Volle Auflösung – echte Sensorpixel statt zusammengefasster")
        sb.appendLine("Naheinstellgrenze – je kleiner, desto besser für Makro")

        return sb.toString()
    }

    private fun describe(sb: StringBuilder, cm: CameraManager, id: String, title: String) {
        val c = try {
            cm.getCameraCharacteristics(id)
        } catch (e: Exception) {
            sb.appendLine()
            sb.appendLine("── $title: nicht lesbar (${e.message})")
            return
        }

        sb.appendLine()
        sb.appendLine("━━━━━━━━━━━━━━━━━━━━━━━━━━━━")
        val facing = when (c.get(CameraCharacteristics.LENS_FACING)) {
            CameraCharacteristics.LENS_FACING_BACK -> "Rückseite"
            CameraCharacteristics.LENS_FACING_FRONT -> "Front"
            CameraCharacteristics.LENS_FACING_EXTERNAL -> "Extern"
            else -> "?"
        }
        sb.appendLine("$title · $facing")

        val level = when (c.get(CameraCharacteristics.INFO_SUPPORTED_HARDWARE_LEVEL)) {
            CameraCharacteristics.INFO_SUPPORTED_HARDWARE_LEVEL_LEGACY -> "LEGACY (sehr eingeschränkt)"
            CameraCharacteristics.INFO_SUPPORTED_HARDWARE_LEVEL_LIMITED -> "LIMITED (eingeschränkt)"
            CameraCharacteristics.INFO_SUPPORTED_HARDWARE_LEVEL_FULL -> "FULL (gut)"
            CameraCharacteristics.INFO_SUPPORTED_HARDWARE_LEVEL_3 -> "LEVEL 3 (sehr gut)"
            CameraCharacteristics.INFO_SUPPORTED_HARDWARE_LEVEL_EXTERNAL -> "EXTERN"
            else -> "?"
        }
        sb.appendLine("Freigabe-Stufe: $level")

        // Sensor und Optik
        val pixels = c.get(CameraCharacteristics.SENSOR_INFO_PIXEL_ARRAY_SIZE)
        if (pixels != null) sb.appendLine("Sensor: ${pixels.width} × ${pixels.height} (${mp(pixels)} MP)")

        if (Build.VERSION.SDK_INT >= 31) {
            val full = c.get(CameraCharacteristics.SENSOR_INFO_PIXEL_ARRAY_SIZE_MAXIMUM_RESOLUTION)
            if (full != null) sb.appendLine("Sensor voll: ${full.width} × ${full.height} (${mp(full)} MP)")
        }

        val focals = c.get(CameraCharacteristics.LENS_INFO_AVAILABLE_FOCAL_LENGTHS)
        val physSize = c.get(CameraCharacteristics.SENSOR_INFO_PHYSICAL_SIZE)
        if (focals != null && focals.isNotEmpty()) {
            val f = focals[0]
            var line = "Brennweite: ${fmt(f)} mm"
            if (physSize != null) {
                val diag = sqrt(physSize.width * physSize.width + physSize.height * physSize.height)
                if (diag > 0f) line += " (≈ ${(f * 43.27f / diag).toInt()} mm KB)"
            }
            sb.appendLine(line)
        }

        val apertures = c.get(CameraCharacteristics.LENS_INFO_AVAILABLE_APERTURES)
        if (apertures != null && apertures.isNotEmpty()) {
            sb.appendLine("Blende: f/${fmt(apertures[0])}")
        }

        val minFocus = c.get(CameraCharacteristics.LENS_INFO_MINIMUM_FOCUS_DISTANCE)
        sb.appendLine(
            when {
                minFocus == null -> "Naheinstellgrenze: ?"
                minFocus == 0f -> "Naheinstellgrenze: Fixfokus"
                else -> "Naheinstellgrenze: ca. ${fmt(100f / minFocus)} cm"
            }
        )

        val ois = c.get(CameraCharacteristics.LENS_INFO_AVAILABLE_OPTICAL_STABILIZATION)
            ?.contains(CameraCharacteristics.LENS_OPTICAL_STABILIZATION_MODE_ON) == true
        sb.appendLine("Optischer Stabi: ${yes(ois)}")

        // Zoom
        val maxDigital = c.get(CameraCharacteristics.SCALER_AVAILABLE_MAX_DIGITAL_ZOOM)
        if (maxDigital != null) sb.appendLine("Digitalzoom max.: ${fmt(maxDigital)}×")
        if (Build.VERSION.SDK_INT >= 30) {
            val zr = c.get(CameraCharacteristics.CONTROL_ZOOM_RATIO_RANGE)
            if (zr != null) sb.appendLine("Zoombereich: ${fmt(zr.lower)}× – ${fmt(zr.upper)}×")
        }

        // Fähigkeiten
        val caps = c.get(CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES)?.toSet() ?: emptySet()
        sb.appendLine("RAW: ${yes(3 in caps)}")
        sb.appendLine("Manueller Sensor: ${yes(1 in caps)}")
        sb.appendLine("Manuelle Nachbearbeitung: ${yes(2 in caps)}")
        sb.appendLine("Burst: ${yes(6 in caps)}")
        sb.appendLine("Volle Auflösung (Ultra-HR): ${yes(16 in caps)}")
        sb.appendLine("Logische Multi-Kamera: ${yes(11 in caps)}")
        val others = caps.filter { it !in setOf(0, 1, 2, 3, 6, 11, 16) }.map { capName(it) }
        if (others.isNotEmpty()) sb.appendLine("Außerdem: ${others.joinToString(", ")}")

        // Ausgabegrößen
        val map = c.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP)
        if (map != null) {
            largest(map.getOutputSizes(ImageFormat.JPEG))?.let {
                sb.appendLine("JPEG max.: ${it.width} × ${it.height} (${mp(it)} MP)")
            }
            largest(map.getOutputSizes(ImageFormat.YUV_420_888))?.let {
                sb.appendLine("Live-Bild max.: ${it.width} × ${it.height} (${mp(it)} MP)")
            }
            val raw = largest(map.getOutputSizes(ImageFormat.RAW_SENSOR))
            sb.appendLine(
                if (raw != null) "RAW max.: ${raw.width} × ${raw.height} (${mp(raw)} MP)"
                else "RAW max.: –"
            )
            val hs = map.highSpeedVideoFpsRanges.maxOfOrNull { it.upper }
            if (hs != null) sb.appendLine("Zeitlupe max.: $hs fps")
        }

        if (Build.VERSION.SDK_INT >= 31) {
            val fullMap = c.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP_MAXIMUM_RESOLUTION)
            if (fullMap != null) {
                largest(fullMap.getOutputSizes(ImageFormat.JPEG))?.let {
                    sb.appendLine("JPEG voll: ${it.width} × ${it.height} (${mp(it)} MP)")
                }
                largest(fullMap.getOutputSizes(ImageFormat.RAW_SENSOR))?.let {
                    sb.appendLine("RAW voll: ${it.width} × ${it.height} (${mp(it)} MP)")
                }
            }
        }

        val phys = c.physicalCameraIds
        if (phys.isNotEmpty()) sb.appendLine("Besteht aus: ${phys.joinToString(", ")}")
    }

    // ───────────────────────── Helfer ─────────────────────────

    private fun largest(sizes: Array<Size>?): Size? =
        sizes?.maxByOrNull { it.width.toLong() * it.height }

    private fun mp(s: Size): String = fmt(s.width.toLong() * s.height / 1_000_000f)

    private fun fmt(v: Float): String {
        val r = Math.round(v * 10f) / 10f
        val s = if (r == r.toInt().toFloat()) r.toInt().toString() else r.toString()
        return s.replace('.', ',')
    }

    private fun yes(b: Boolean) = if (b) "✓ ja" else "✗ nein"

    private fun capName(cap: Int): String = when (cap) {
        4 -> "Private Reprocessing"
        5 -> "Sensorwerte lesen"
        7 -> "YUV-Reprocessing"
        8 -> "Tiefendaten"
        9 -> "High-Speed-Video"
        10 -> "Bewegungsverfolgung"
        12 -> "Monochrom"
        13 -> "Secure Image"
        14 -> "Systemkamera"
        15 -> "Offline-Verarbeitung"
        17 -> "Remosaic-Reprocessing"
        18 -> "10-Bit-HDR"
        19 -> "Stream-Use-Cases"
        20 -> "Farbraum-Profile"
        else -> "Fähigkeit $cap"
    }

    companion object {
        private const val BG = 0xFF111111.toInt()
        private const val FG = 0xFFEDEDED.toInt()
        private const val MUTED = 0xFF9A9A9A.toInt()
    }
}
