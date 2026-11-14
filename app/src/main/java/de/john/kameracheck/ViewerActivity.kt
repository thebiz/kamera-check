package de.john.kameracheck

import android.app.Activity
import android.content.ContentValues
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
import android.widget.SeekBar
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
    private lateinit var saveBtn: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.statusBarColor = BG
        window.navigationBarColor = BG

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(BG)
        }

        val stage = FrameLayout(this)
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
        panel.addView(mixLabel)

        val seek = SeekBar(this).apply {
            max = 100
            progress = 100
            setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(s: SeekBar, p: Int, fromUser: Boolean) = setMix(p / 100f)
                override fun onStartTrackingTouch(s: SeekBar) {}
                override fun onStopTrackingTouch(s: SeekBar) {}
            })
        }
        panel.addView(seek, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(4f) })

        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
        }
        row.addView(button("Zurück", false) { finish() }, LinearLayout.LayoutParams(0, -2, 1f).apply { rightMargin = dp(6f) })
        saveBtn = button("Mischung speichern", true) { saveMix() }
        row.addView(saveBtn, LinearLayout.LayoutParams(0, -2, 1.4f).apply { leftMargin = dp(6f) })
        panel.addView(row, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(10f) })

        val hint = TextView(this).apply {
            text = "Linie ziehen: vergleichen · Zwei Finger: zoomen · Doppeltippen: 3×"
            setTextColor(MUTED)
            textSize = 12f
            gravity = Gravity.CENTER
        }
        panel.addView(hint, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(10f) })

        root.addView(panel)
        setContentView(root)
        setMix(1f)

        val normal = BitmapFactory.decodeFile(File(cacheDir, NORMAL).path)
        val ki = BitmapFactory.decodeFile(File(cacheDir, KI).path)
        if (normal == null || ki == null) {
            mixLabel.text = "Noch kein Foto vorhanden"
            saveBtn.visibility = View.GONE
            return
        }
        compare.setImages(normal, ki)
    }

    private fun setMix(v: Float) {
        compare.mix = v
        mixLabel.text = "KI-Anteil: ${(v * 100).roundToInt()} %"
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
                saveToGallery(this, b, "zoom_${stamp}_KI-$pct")
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
        const val KI = "letztes_ki.jpg"
        private const val BG = 0xFF111111.toInt()
        private const val FG = 0xFFEDEDED.toInt()
        private const val MUTED = 0xFF8A8A8A.toInt()

        fun saveToGallery(a: Activity, b: Bitmap, name: String) {
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
        }
    }
}
