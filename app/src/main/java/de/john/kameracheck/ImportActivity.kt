package de.john.kameracheck

import android.app.Activity
import android.content.ClipData
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.os.Bundle
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.widget.LinearLayout
import android.widget.TextView
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.roundToInt

/**
 * Import von Fotos aus der Xiaomi-Kamera (50 MP): Ausschnitt und Zoom wählen, dann rechnet
 * die Hauptansicht (Ausrichten mehrerer Fotos, Entschärfen, Diagnosebild).
 *
 * Kommt von: dem 50-MP-Knopf (neueste Fotos), der Fotoauswahl oder „Teilen“ aus der Galerie.
 */
class ImportActivity : Activity() {

    private lateinit var uris: List<Uri>
    private lateinit var pick: CropPickView
    private lateinit var zoomLabel: TextView
    private var zoom = 4f

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.statusBarColor = BG
        window.navigationBarColor = BG
        uris = readUris(intent).take(MAX_PHOTOS)
        if (uris.isEmpty()) {
            finish()
            return
        }

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(BG)
            fitsSystemWindows = true
        }
        root.addView(TextView(this).apply {
            text = "Ausschnitt wählen"
            setTextColor(FG)
            textSize = 20f
            typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
            setPadding(dp(20f), dp(14f), dp(20f), 0)
        })
        val info = TextView(this).apply {
            setTextColor(MUTED)
            textSize = 13f
            setPadding(dp(20f), dp(4f), dp(20f), dp(10f))
        }
        root.addView(info)

        pick = CropPickView(this)
        root.addView(pick, LinearLayout.LayoutParams(-1, 0, 1f))

        val panel = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20f), dp(10f), dp(20f), dp(14f))
        }
        zoomLabel = TextView(this).apply {
            setTextColor(FG)
            textSize = 20f
            gravity = Gravity.CENTER
        }
        panel.addView(zoomLabel)
        panel.addView(BigSlider(this).apply {
            // 2× … 10×, logarithmisch (unter 2× wird der Ausschnitt zu groß für den Speicher)
            setValue((ln(zoom / 2f) / ln(10f / 2f)))
            onChange = { v -> setZoom(2f * (10f / 2f).pow(v)) }
        }, LinearLayout.LayoutParams(-1, -2))
        panel.addView(TextView(this).apply {
            text = "Rahmen antippen oder ziehen: Ausschnitt verschieben"
            setTextColor(MUTED)
            textSize = 12f
            gravity = Gravity.CENTER
            setPadding(0, dp(4f), 0, dp(10f))
        })
        val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        row.addView(button("Abbrechen", false) { finish() }, LinearLayout.LayoutParams(0, -2, 1f).apply { rightMargin = dp(6f) })
        row.addView(button("Rechnen", true) { go() }, LinearLayout.LayoutParams(0, -2, 1.4f).apply { leftMargin = dp(6f) })
        panel.addView(row, LinearLayout.LayoutParams(-1, -2))
        root.addView(panel)
        setContentView(root)
        setZoom(zoom)

        // Vorschau des ersten Fotos (verkleinert, aufrecht gedreht)
        Thread {
            val dims = uris.mapNotNull { PhotoIo.info(this, it) }
            val first = PhotoIo.preview(this, uris[0], 1600)
            runOnUiThread {
                if (first == null || dims.isEmpty()) {
                    info.text = "Foto lässt sich nicht öffnen"
                    return@runOnUiThread
                }
                pick.bitmap = first
                val d = dims[0]
                val mpx = d.uprightW.toLong() * d.uprightH / 1e6
                val note = if (mpx < 30) " – nur ${"%.0f".format(mpx)} MP: in der Xiaomi-Kamera oben auf 50 MP stellen" else ""
                val same = dims.count { it.uprightW == d.uprightW && it.uprightH == d.uprightH }
                info.text = "${same} Foto${if (same == 1) "" else "s"} · ${d.uprightW} × ${d.uprightH} (${"%.0f".format(mpx)} MP)$note" +
                    if (same > 1) " · werden übereinandergelegt" else ""
            }
        }.start()
    }

    private fun setZoom(z: Float) {
        zoom = z
        zoomLabel.text = "${String.format(java.util.Locale.GERMANY, "%.1f", z).replace(",0", "")}× Ausschnitt"
        pick.zoom = z
    }

    private fun go() {
        val r = pick.cropNorm()
        val out = Intent(this, MainActivity::class.java).apply {
            action = MainActivity.ACTION_IMPORT
            putExtra(MainActivity.EXTRA_RECT, floatArrayOf(r.left, r.top, r.right, r.bottom))
            putExtra(MainActivity.EXTRA_ZOOM, zoom)
            putParcelableArrayListExtra(MainActivity.EXTRA_URIS, ArrayList(uris))
            // Lese-Erlaubnis für die Fotos an die Hauptansicht weiterreichen
            val clip = ClipData.newRawUri("fotos", uris[0])
            for (u in uris.drop(1)) clip.addItem(ClipData.Item(u))
            clipData = clip
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)
        }
        startActivity(out)
        finish()
    }

    @Suppress("DEPRECATION")
    private fun readUris(i: Intent): List<Uri> {
        val out = ArrayList<Uri>()
        i.getParcelableArrayListExtra<Uri>(MainActivity.EXTRA_URIS)?.let { out += it }
        if (i.action == Intent.ACTION_SEND) (i.getParcelableExtra<Uri>(Intent.EXTRA_STREAM))?.let { out += it }
        if (i.action == Intent.ACTION_SEND_MULTIPLE) i.getParcelableArrayListExtra<Uri>(Intent.EXTRA_STREAM)?.let { out += it }
        if (out.isEmpty()) i.clipData?.let { c -> for (k in 0 until c.itemCount) c.getItemAt(k).uri?.let { out += it } }
        if (out.isEmpty()) i.data?.let { out += it }
        return out.distinct()
    }

    private fun ln(v: Float) = kotlin.math.ln(v)

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

    /** Foto mit verschiebbarem Rahmen; Rahmengröße = Foto ÷ Zoom. */
    class CropPickView(ctx: Context) : View(ctx) {
        var bitmap: Bitmap? = null
            set(v) { field = v; invalidate() }
        var zoom = 4f
            set(v) { field = v; invalidate() }
        private var cx = 0.5f
        private var cy = 0.5f
        private val d = ctx.resources.displayMetrics.density
        private val frame = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE; strokeWidth = 2f * d; color = 0xFF5BE38A.toInt()
        }
        private val shade = Paint().apply { color = 0x99000000.toInt() }

        private fun imgRect(): RectF? {
            val b = bitmap ?: return null
            val s = min(width.toFloat() / b.width, height.toFloat() / b.height)
            val w = b.width * s
            val h = b.height * s
            return RectF((width - w) / 2, (height - h) / 2, (width + w) / 2, (height + h) / 2)
        }

        /** Ausschnitt als Anteile des aufrechten Fotos (0..1). */
        fun cropNorm(): RectF {
            val hw = 0.5f / zoom
            val x = cx.coerceIn(hw, 1 - hw)
            val y = cy.coerceIn(hw, 1 - hw)
            return RectF(x - hw, y - hw, x + hw, y + hw)
        }

        override fun onDraw(c: Canvas) {
            val b = bitmap ?: return
            val r = imgRect() ?: return
            c.drawBitmap(b, null, r, null)
            val n = cropNorm()
            val f = RectF(r.left + n.left * r.width(), r.top + n.top * r.height(), r.left + n.right * r.width(), r.top + n.bottom * r.height())
            c.drawRect(r.left, r.top, r.right, f.top, shade)
            c.drawRect(r.left, f.bottom, r.right, r.bottom, shade)
            c.drawRect(r.left, f.top, f.left, f.bottom, shade)
            c.drawRect(f.right, f.top, r.right, f.bottom, shade)
            c.drawRect(f, frame)
        }

        override fun onTouchEvent(e: MotionEvent): Boolean {
            val r = imgRect() ?: return true
            if (e.action == MotionEvent.ACTION_DOWN || e.action == MotionEvent.ACTION_MOVE) {
                cx = ((e.x - r.left) / r.width()).coerceIn(0f, 1f)
                cy = ((e.y - r.top) / r.height()).coerceIn(0f, 1f)
                invalidate()
            }
            return true
        }
    }

    companion object {
        const val MAX_PHOTOS = 8
        private const val BG = 0xFF111111.toInt()
        private const val FG = 0xFFEDEDED.toInt()
        private const val MUTED = 0xFF8A8A8A.toInt()
    }
}

/** Fotos lesen: Größe, Drehung (EXIF), verkleinerte Vorschau, Ausschnitt in voller Auflösung. */
object PhotoIo {
    class Info(val rawW: Int, val rawH: Int, val rotation: Int) {
        val uprightW get() = if (rotation % 180 == 0) rawW else rawH
        val uprightH get() = if (rotation % 180 == 0) rawH else rawW
    }

    fun info(c: Context, u: Uri): Info? = try {
        val o = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        c.contentResolver.openInputStream(u)?.use { BitmapFactory.decodeStream(it, null, o) }
        val rot = c.contentResolver.openInputStream(u)?.use {
            when (android.media.ExifInterface(it).getAttributeInt(android.media.ExifInterface.TAG_ORIENTATION, 1)) {
                android.media.ExifInterface.ORIENTATION_ROTATE_90 -> 90
                android.media.ExifInterface.ORIENTATION_ROTATE_180 -> 180
                android.media.ExifInterface.ORIENTATION_ROTATE_270 -> 270
                else -> 0
            }
        } ?: 0
        if (o.outWidth > 0) Info(o.outWidth, o.outHeight, rot) else null
    } catch (_: Exception) {
        null
    }

    fun preview(c: Context, u: Uri, maxSide: Int): Bitmap? {
        val inf = info(c, u) ?: return null
        var s = 1
        while (max(inf.rawW, inf.rawH) / (s * 2) >= maxSide) s *= 2
        val b = c.contentResolver.openInputStream(u)?.use {
            BitmapFactory.decodeStream(it, null, BitmapFactory.Options().apply { inSampleSize = s })
        } ?: return null
        return rotate(b, inf.rotation)
    }

    /**
     * Ausschnitt (Anteile des aufrechten Fotos) plus Rand in Pixeln, in voller Auflösung, aufrecht.
     * Gibt das Bild und die Lage des eigentlichen Ausschnitts darin (Pixel) zurück.
     */
    fun crop(c: Context, u: Uri, n: FloatArray, marginPx: Int): Pair<Bitmap, android.graphics.Rect>? {
        val inf = info(c, u) ?: return null
        val uw = inf.uprightW
        val uh = inf.uprightH
        // Ausschnitt + Rand im aufrechten Foto (gerade Zahlen für die Farbebenen)
        val il = (n[0] * uw).toInt()
        val it = (n[1] * uh).toInt()
        val ir = (n[2] * uw).toInt()
        val ib = (n[3] * uh).toInt()
        val l = max(0, il - marginPx) / 2 * 2
        val t = max(0, it - marginPx) / 2 * 2
        val r = min(uw, ir + marginPx)
        val b = min(uh, ib + marginPx)
        val w = (r - l) / 2 * 2
        val h = (b - t) / 2 * 2
        // aufrecht → Pixel im gespeicherten (ungedrehten) Foto
        val raw = when (inf.rotation) {
            90 -> android.graphics.Rect(t, inf.rawH - (l + w), t + h, inf.rawH - l)
            180 -> android.graphics.Rect(inf.rawW - (l + w), inf.rawH - (t + h), inf.rawW - l, inf.rawH - t)
            270 -> android.graphics.Rect(inf.rawW - (t + h), l, inf.rawW - t, l + w)
            else -> android.graphics.Rect(l, t, l + w, t + h)
        }
        val dec = c.contentResolver.openInputStream(u)?.use {
            @Suppress("DEPRECATION")
            android.graphics.BitmapRegionDecoder.newInstance(it, false)
        } ?: return null
        val bmp = try {
            dec.decodeRegion(raw, BitmapFactory.Options().apply { inPreferredConfig = Bitmap.Config.ARGB_8888 })
        } finally {
            dec.recycle()
        }
        val up = rotate(bmp, inf.rotation)
        val inner = android.graphics.Rect(il - l, it - t, min(w, ir - l), min(h, ib - t))
        return up to inner
    }

    fun rotate(b: Bitmap, deg: Int): Bitmap {
        if (deg == 0) return b
        val r = Bitmap.createBitmap(b, 0, 0, b.width, b.height, Matrix().apply { postRotate(deg.toFloat()) }, true)
        if (r !== b) b.recycle()
        return r
    }
}
