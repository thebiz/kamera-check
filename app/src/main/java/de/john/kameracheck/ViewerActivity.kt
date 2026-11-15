package de.john.kameracheck

import android.app.Activity
import android.content.ContentValues
import android.content.Intent
import android.net.Uri
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.provider.MediaStore
import android.view.Gravity
import android.view.View
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.roundToInt

/** Zeigt das letzte Foto: Normal ↔ KI im Vergleich, KI-Anteil einstellbar. */
class ViewerActivity : Activity() {

    private lateinit var compare: CompareView
    private lateinit var mixLabel: TextView
    private lateinit var seek: BigSlider
    private lateinit var saveBtn: TextView
    private lateinit var clarityLabel: TextView

    // Klarheit (Johns Wunsch nach dem Test in der Galerie): im Hintergrund rechnen, neuester Wert gewinnt
    private val clarityLock = Object()
    @Volatile private var clarityWanted = DEFAULT_CLARITY
    /** Plastizität (Johns Idee): nah knackiger, fern weicher und dunstiger – nach der Tiefenkarte */
    @Volatile private var plasticWanted = 0f
    private lateinit var plasticLabel: TextView
    @Volatile private var depth: Depth? = null
    private var clarityThread: Thread? = null
    private lateinit var stage: FrameLayout
    private var parallax: ParallaxView? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.statusBarColor = BG
        window.navigationBarColor = BG

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(BG)
        }

        stage = FrameLayout(this)
        compare = CompareView(this)
        stage.addView(compare, FrameLayout.LayoutParams(-1, -1))
        root.addView(stage, LinearLayout.LayoutParams(-1, 0, 1f))

        val panel = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20f), dp(10f), dp(20f), dp(14f))
        }

        mixLabel = TextView(this).apply {
            setTextColor(FG)
            textSize = 15f
        }
        // Was die App erkannt und gerechnet hat – bleibt stehen, antippen klappt auf/zu
        val infoText = File(cacheDir, INFO).takeIf { it.exists() }?.readText().orEmpty()
        if (infoText.isNotEmpty()) {
            val info = TextView(this).apply {
                setTextColor(MUTED)
                textSize = 12f
                setLineSpacing(0f, 1.2f)
                text = infoText
                maxLines = 2
                ellipsize = android.text.TextUtils.TruncateAt.END
                setPadding(0, 0, 0, dp(8f))
                setOnClickListener {
                    maxLines = if (maxLines == 2) 20 else 2
                }
            }
            panel.addView(info)
        }
        panel.addView(mixLabel)

        seek = BigSlider(this).apply {
            setValue(startMix / 100f)
            onChange = { v -> setMix(v) }
        }
        panel.addView(seek, LinearLayout.LayoutParams(-1, -2))

        // Weitere Regler hinter einem Knopf, damit das Bild groß bleibt (Johns Wunsch)
        val extra = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            visibility = View.GONE
        }
        clarityWanted = try {
            getSharedPreferences("viewer", MODE_PRIVATE).getFloat("clarity", DEFAULT_CLARITY)
        } catch (_: Throwable) { DEFAULT_CLARITY }
        clarityLabel = TextView(this).apply {
            setTextColor(FG)
            textSize = 15f
            setPadding(0, dp(8f), 0, 0)
        }
        showClarity(clarityWanted, false)
        extra.addView(clarityLabel)
        extra.addView(BigSlider(this).apply {
            setValue(clarityWanted)
            onChange = { v ->
                showClarity(v, true)
                getSharedPreferences("viewer", MODE_PRIVATE).edit().putFloat("clarity", v).apply()
                synchronized(clarityLock) { clarityWanted = v; clarityLock.notifyAll() }
            }
        }, LinearLayout.LayoutParams(-1, -2))

        plasticWanted = try {
            getSharedPreferences("viewer", MODE_PRIVATE).getFloat("plastic", 0f)
        } catch (_: Throwable) { 0f }
        plasticLabel = TextView(this).apply {
            setTextColor(FG)
            textSize = 15f
            setPadding(0, dp(8f), 0, 0)
        }
        showPlastic(plasticWanted, false)
        extra.addView(plasticLabel)
        extra.addView(BigSlider(this).apply {
            setValue(plasticWanted)
            onChange = { v ->
                showPlastic(v, true)
                getSharedPreferences("viewer", MODE_PRIVATE).edit().putFloat("plastic", v).apply()
                synchronized(clarityLock) { plasticWanted = v; clarityLock.notifyAll() }
            }
        }, LinearLayout.LayoutParams(-1, -2))
        // Schnellvergleich an derselben Stelle: Echt ⇄ KI (Johns Wunsch) · daneben: weitere Regler auf/zu
        val quick = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        quick.addView(button("Echt ⇄ KI", false) {
            val v = if (compare.mix >= 0.5f) 0f else 1f
            seek.setValue(v)
            setMix(v)
        }, LinearLayout.LayoutParams(0, -2, 1f).apply { rightMargin = dp(4f) })
        lateinit var toggle: TextView
        toggle = button("Klarheit & Tiefe ▾", false) {
            val open = extra.visibility != View.VISIBLE
            extra.visibility = if (open) View.VISIBLE else View.GONE
            toggle.text = if (open) "Klarheit & Tiefe ▴" else "Klarheit & Tiefe ▾"
        }
        quick.addView(toggle, LinearLayout.LayoutParams(0, -2, 1f).apply { leftMargin = dp(4f) })
        panel.addView(quick, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(8f) })
        panel.addView(extra, LinearLayout.LayoutParams(-1, -2))

        // Johns Wünsche: fertiges Foto direkt in der Galerie öffnen, Diagnosebild direkt teilen
        val links = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        recallPhoto(this)?.let { u ->
            links.addView(button("Galerie öffnen", false) { openInGallery(this, u) }.apply { textSize = 13f },
                LinearLayout.LayoutParams(0, -2, 1f).apply { rightMargin = dp(4f) })
        }
        recallDiag(this)?.let { u ->
            links.addView(button("Diagnose teilen", false) {
                val send = Intent(Intent.ACTION_SEND).apply {
                    type = "image/jpeg"
                    putExtra(Intent.EXTRA_STREAM, u)
                    addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                }
                startActivity(Intent.createChooser(send, "Diagnose teilen"))
            }.apply { textSize = 13f }, LinearLayout.LayoutParams(0, -2, 1f).apply { leftMargin = dp(4f); rightMargin = dp(4f) })
        }
        links.addView(button("3D ansehen", false) { open3d() }.apply { textSize = 13f },
            LinearLayout.LayoutParams(0, -2, 1f).apply { leftMargin = dp(4f) })
        if (links.childCount > 0) panel.addView(links, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(8f) })

        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
        }
        row.addView(button("Zurück", false) { finish() }, LinearLayout.LayoutParams(0, -2, 1f).apply { rightMargin = dp(6f) })
        saveBtn = button("Mischung speichern", true) { saveMix() }
        row.addView(saveBtn, LinearLayout.LayoutParams(0, -2, 1.4f).apply { leftMargin = dp(6f) })
        panel.addView(row, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(10f) })


        root.addView(panel)
        setContentView(root)
        setMix(startMix / 100f)

        val normal = BitmapFactory.decodeFile(File(cacheDir, NORMAL).path)
        val ki = BitmapFactory.decodeFile(File(cacheDir, KI).path)
        val real = BitmapFactory.decodeFile(File(cacheDir, REAL).path) ?: normal
        if (normal == null || ki == null || real == null) {
            mixLabel.text = "Noch kein Foto vorhanden"
            saveBtn.visibility = View.GONE
            return
        }
        compare.setImages(normal, real, ki)
        startClarity(real, ki)
    }

    private fun showClarity(v: Float, busy: Boolean) {
        val pct = (v * 100).roundToInt()
        clarityLabel.text = (if (pct == 0) "Klarheit: aus" else "Klarheit: $pct %") + if (busy) " · rechnet …" else ""
    }

    /** Klarheit für die rechte Seite (Echt und KI) – links bleibt das Normalbild unverändert zum Vergleich. */
    private fun startClarity(real: Bitmap, ki: Bitmap) {
        clarityThread = Thread {
            try {
                runOnUiThread { clarityLabel.text = "Klarheit: bereite vor …" }
                val cr = Clarity(real)
                val ck = Clarity(ki)
                // zwei Puffer im Wechsel: in den einen wird gerechnet, der andere wird angezeigt
                val bufs = Array(2) { arrayOf(
                    Bitmap.createBitmap(cr.w, cr.h, Bitmap.Config.ARGB_8888),
                    Bitmap.createBitmap(ck.w, ck.h, Bitmap.Config.ARGB_8888)) }
                var cur = 0
                var done = -1f
                var doneP = -1f
                var depthSet = false
                while (!Thread.currentThread().isInterrupted) {
                    val (v, pl) = synchronized(clarityLock) {
                        while (clarityWanted == done && plasticWanted == doneP) clarityLock.wait()
                        clarityWanted to plasticWanted
                    }
                    if (pl > 0f && !depthSet) {
                        runOnUiThread { plasticLabel.text = "Plastizität: Tiefe wird geschätzt …" }
                        val d = depth ?: Depth.estimate(this, ki).also { depth = it }
                        cr.setDepth(d)
                        ck.setDepth(d)
                        depthSet = true
                    }
                    val b = bufs[cur]
                    cr.apply(v, b[0], pl)
                    ck.apply(v, b[1], pl)
                    done = v
                    doneP = pl
                    cur = 1 - cur
                    runOnUiThread {
                        compare.setRight(b[0], b[1])
                        showClarity(v, clarityWanted != v)
                        showPlastic(pl, plasticWanted != pl)
                    }
                }
            } catch (_: InterruptedException) {
            } catch (e: Throwable) {
                runOnUiThread { clarityLabel.text = "Klarheit nicht verfügbar (${e.message})" }
            }
        }.also { it.start() }
    }

    private fun showPlastic(v: Float, busy: Boolean) {
        val pct = (v * 100).roundToInt()
        plasticLabel.text = (if (pct == 0) "Plastizität (Tiefe): aus" else "Plastizität (Tiefe): $pct %") + if (busy) " · rechnet …" else ""
    }

    /** 3D-Wackeln: aktuelles Ergebnis (wie rechts eingestellt) nach Tiefe in Schichten bewegen */
    private fun open3d() {
        if (parallax != null) return
        val hint = TextView(this).apply {
            text = "3D wird vorbereitet …"
            setTextColor(FG)
            textSize = 15f
            gravity = Gravity.CENTER
            setBackgroundColor(0xCC000000.toInt())
        }
        val full = findViewById<FrameLayout>(android.R.id.content)
        full.addView(hint, FrameLayout.LayoutParams(-1, -1))
        // Die Ansicht selbst (mit ihren Gesten-Erkennern) muss im Vordergrund entstehen – nur das Rechnen läuft
        // im Hintergrund (Fehler 1.17.2: „Can't create handler inside thread … Looper.prepare()“)
        val pv = ParallaxView(this)
        Thread {
            try {
                val img = compare.renderMix()
                val d = depth ?: Depth.estimate(this, img).also { depth = it }
                pv.setImage(img, d)
                img.recycle()
                runOnUiThread {
                    full.removeView(hint)
                    // bildschirmfüllend, mit Zoom und Regler für die Stärke (Johns Wunsch)
                    val box = FrameLayout(this).apply { setBackgroundColor(BG) }
                    box.addView(pv, FrameLayout.LayoutParams(-1, -1))
                    box.addView(TextView(this).apply {
                        text = "Zwei Finger: zoomen · ein Finger: verschieben · Doppeltippen: 3×"
                        setTextColor(FG)
                        textSize = 12f
                        setPadding(dp(10f), dp(6f), dp(10f), dp(6f))
                        setBackgroundColor(0x99000000.toInt())
                    }, FrameLayout.LayoutParams(-2, -2, Gravity.TOP or Gravity.CENTER_HORIZONTAL).apply { topMargin = dp(40f) })
                    val bottom = LinearLayout(this).apply {
                        orientation = LinearLayout.VERTICAL
                        setPadding(dp(20f), dp(10f), dp(20f), dp(16f))
                        setBackgroundColor(0xAA000000.toInt())
                    }
                    val sLabel = TextView(this).apply { setTextColor(FG); textSize = 14f }
                    val st0 = try { getSharedPreferences("viewer", MODE_PRIVATE).getFloat("strength3d", 0.4f) } catch (_: Throwable) { 0.4f }
                    fun setStrength(v: Float) {
                        pv.strength = 0.005f + v * 0.075f
                        sLabel.text = "3D-Stärke: ${(v * 100).roundToInt()} %"
                    }
                    setStrength(st0)
                    bottom.addView(sLabel)
                    bottom.addView(BigSlider(this).apply {
                        setValue(st0)
                        onChange = { v ->
                            setStrength(v)
                            getSharedPreferences("viewer", MODE_PRIVATE).edit().putFloat("strength3d", v).apply()
                        }
                    }, LinearLayout.LayoutParams(-1, -2))
                    bottom.addView(button("3D schließen", true) { close3d() }, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(8f) })
                    box.addView(bottom, FrameLayout.LayoutParams(-1, -2, Gravity.BOTTOM))
                    full.addView(box, FrameLayout.LayoutParams(-1, -1))
                    parallax = pv
                    pv.tag = box
                }
            } catch (e: Throwable) {
                runOnUiThread {
                    hint.text = "3D nicht möglich (${e.message})"
                    hint.postDelayed({ full.removeView(hint) }, 2500)
                }
            }
        }.start()
    }

    private fun close3d() {
        val pv = parallax ?: return
        findViewById<FrameLayout>(android.R.id.content).removeView(pv.tag as View)
        pv.release()
        parallax = null
    }

    override fun onBackPressed() {
        if (parallax != null) close3d() else super.onBackPressed()
    }

    override fun onDestroy() {
        clarityThread?.interrupt()
        parallax?.release()
        super.onDestroy()
    }

    private fun setMix(v: Float) {
        compare.mix = v
        val pct = (v * 100).roundToInt()
        mixLabel.text = when (pct) {
            0 -> "KI-Anteil: 0 % · nur echt (Mehrbild, Schärfe zurückgeholt)"
            100 -> "KI-Anteil: 100 % · volle KI"
            else -> "KI-Anteil: $pct %"
        }
    }

    private fun dp(v: Float) = (v * resources.displayMetrics.density).roundToInt()

    private fun button(label: String, primary: Boolean, onClick: () -> Unit) = TextView(this).apply {
        text = label
        textSize = 15f
        gravity = Gravity.CENTER
        setPadding(dp(12f), dp(12f), dp(12f), dp(12f))
        setTextColor(if (primary) BG else FG)
        background = GradientDrawable().apply {
            cornerRadius = dp(22f).toFloat()
            if (primary) setColor(FG) else {
                setColor(Color.TRANSPARENT)
                setStroke(dp(1.5f), 0xFF555555.toInt())
            }
        }
        setOnClickListener { onClick() }
    }

    private fun saveMix() {
        saveBtn.isEnabled = false
        saveBtn.text = "Speichere …"
        val pct = (compare.mix * 100).roundToInt()
        Thread {
            val ok = try {
                val b = compare.renderMix()
                val stamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.GERMANY).format(Date())
                val cl = (clarityWanted * 100).roundToInt()
                rememberPhoto(this, saveToGallery(this, b, "zoom_${stamp}_KI-$pct" + if (cl > 0) "_Klarheit-$cl" else ""))
                b.recycle()
                true
            } catch (_: Throwable) {
                false
            }
            runOnUiThread {
                saveBtn.isEnabled = true
                saveBtn.text = if (ok) "Gespeichert ✓" else "Fehler beim Speichern"
                saveBtn.postDelayed({ saveBtn.text = "Mischung speichern" }, 2000)
            }
        }.start()
    }

    companion object {
        const val NORMAL = "letztes_normal.jpg"
        const val REAL = "letztes_mehrbild.jpg"
        const val KI = "letztes_ki.jpg"
        const val DEFAULT_MIX = 30
        /** Klarheit beim ersten Öffnen: sanft (danach merkt sich der Viewer Johns Einstellung) */
        const val DEFAULT_CLARITY = 0.2f
        /** KI-Anteil, mit dem die Ansicht startet (0, wenn Schrift erkannt wurde – dort erfindet die KI) */
        @Volatile var startMix = DEFAULT_MIX
        const val INFO = "letztes_info.txt"
        private const val BG = 0xFF111111.toInt()
        private const val FG = 0xFFEDEDED.toInt()
        private const val MUTED = 0xFF8A8A8A.toInt()

        /** Zuletzt gespeichertes Diagnose-/Vergleichsbild – zum direkten Teilen aus der Ansicht */
        @Volatile var lastDiag: Uri? = null

        /** Zuletzt gespeichertes fertiges Foto (für „In Galerie öffnen“), auch über einen Neustart hinaus */
        fun rememberPhoto(a: Activity, uri: Uri) {
            a.getSharedPreferences("viewer", Activity.MODE_PRIVATE).edit().putString("lastPhoto", uri.toString()).apply()
        }

        fun recallPhoto(a: Activity): Uri? =
            a.getSharedPreferences("viewer", Activity.MODE_PRIVATE).getString("lastPhoto", null)?.let { Uri.parse(it) }

        /** Foto in der Galerie-App öffnen (dort teilen, bearbeiten, löschen) */
        fun openInGallery(a: Activity, uri: Uri) {
            val view = Intent(Intent.ACTION_VIEW).apply {
                setDataAndType(uri, "image/jpeg")
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            try {
                a.startActivity(view)
            } catch (_: Throwable) {
                android.widget.Toast.makeText(a, "Keine Galerie-App gefunden", android.widget.Toast.LENGTH_SHORT).show()
            }
        }

        /** Merkt sich das Diagnosebild auch über einen Neustart der App hinaus */
        fun rememberDiag(a: Activity, uri: Uri) {
            lastDiag = uri
            a.getSharedPreferences("viewer", Activity.MODE_PRIVATE).edit().putString("lastDiag", uri.toString()).apply()
        }

        private fun recallDiag(a: Activity): Uri? = lastDiag
            ?: a.getSharedPreferences("viewer", Activity.MODE_PRIVATE).getString("lastDiag", null)?.let { Uri.parse(it) }

        fun saveToGallery(a: Activity, b: Bitmap, name: String): Uri {
            val values = ContentValues().apply {
                put(MediaStore.Images.Media.DISPLAY_NAME, "$name.jpg")
                put(MediaStore.Images.Media.MIME_TYPE, "image/jpeg")
                put(MediaStore.Images.Media.RELATIVE_PATH, "Pictures/Kamera-Check")
                put(MediaStore.Images.Media.IS_PENDING, 1)
            }
            val r = a.contentResolver
            val uri = r.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values)
                ?: throw IllegalStateException("Galerie nicht erreichbar")
            r.openOutputStream(uri)!!.use { b.compress(Bitmap.CompressFormat.JPEG, 95, it) }
            values.clear()
            values.put(MediaStore.Images.Media.IS_PENDING, 0)
            r.update(uri, values, null, null)
            return uri
        }
    }
}
