package de.john.kameracheck

import android.content.Context
import android.graphics.Canvas
import android.graphics.DashPathEffect
import android.graphics.Paint
import android.graphics.RectF
import android.view.View

/**
 * Zielrahmen für die Kalibrierung: zeigt, wo das Testmuster im Bild liegen soll,
 * und dunkelt den Rest leicht ab. Nimmt keine Berührungen an.
 */
class CalibOverlay(ctx: Context) : View(ctx) {

    /** Rahmenmitte als Anteil der Vorschau (0..1); null = ausgeblendet */
    var center: Pair<Float, Float>? = null
        set(v) {
            field = v
            invalidate()
        }

    /** Rahmenbreite als Anteil der Vorschaubreite; Höhe = Breite · 3/4 (iPad quer) */
    val boxWidth = 0.34f

    var label = ""
        set(v) {
            field = v
            invalidate()
        }

    private val d = resources.displayMetrics.density
    private val shade = Paint().apply { color = 0x66000000 }
    private val frame = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 3f * d
        color = 0xFF5BE38A.toInt()
        pathEffect = DashPathEffect(floatArrayOf(10f * d, 6f * d), 0f)
    }
    private val text = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0xFF5BE38A.toInt()
        textSize = 13f * d
        textAlign = Paint.Align.CENTER
        typeface = android.graphics.Typeface.create("sans-serif-medium", android.graphics.Typeface.NORMAL)
    }

    init {
        isClickable = false
        isFocusable = false
    }

    /** Rahmen in Vorschau-Pixeln. */
    fun boxRect(): RectF? {
        val c = center ?: return null
        val bw = width * boxWidth
        val bh = bw * 0.75f
        val cx = c.first * width
        val cy = c.second * height
        return RectF(cx - bw / 2, cy - bh / 2, cx + bw / 2, cy + bh / 2)
    }

    override fun onDraw(cv: Canvas) {
        val r = boxRect() ?: return
        // außen abdunkeln
        cv.drawRect(0f, 0f, width.toFloat(), r.top, shade)
        cv.drawRect(0f, r.bottom, width.toFloat(), height.toFloat(), shade)
        cv.drawRect(0f, r.top, r.left, r.bottom, shade)
        cv.drawRect(r.right, r.top, width.toFloat(), r.bottom, shade)
        cv.drawRect(r, frame)
        if (label.isNotEmpty()) {
            val y = if (r.bottom + 24f * d < height) r.bottom + 20f * d else r.top - 10f * d
            cv.drawText(label, r.centerX(), y, text)
        }
    }
}
