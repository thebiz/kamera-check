package de.john.kameracheck

import android.content.Context
import android.graphics.Canvas
import android.graphics.DashPathEffect
import android.graphics.Paint
import android.graphics.RectF
import android.view.View

/** Zeichnet den Fokus-Rahmen über der Vorschau. Nimmt selbst keine Berührungen an. */
class FocusView(ctx: Context) : View(ctx) {

    enum class State { SEARCHING, SHARP, UNSHARP, LOST }

    var shown = false
        private set
    var fx = 0f
        private set
    var fy = 0f
        private set
    var state = State.SEARCHING
        set(v) {
            if (field != v) {
                field = v
                invalidate()
            }
        }
    var dragging = false
        set(v) {
            field = v
            invalidate()
        }
    var macro = false
        set(v) {
            field = v
            invalidate()
        }

    private val d = resources.displayMetrics.density
    val size = 76f * d

    private val line = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 2.5f * d
        strokeCap = Paint.Cap.ROUND
    }
    private val shadow = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 4.5f * d
        strokeCap = Paint.Cap.ROUND
        color = 0x66000000
    }
    private val dot = Paint(Paint.ANTI_ALIAS_FLAG)
    private val text = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textSize = 12f * d
        textAlign = Paint.Align.CENTER
        typeface = android.graphics.Typeface.create("sans-serif-medium", android.graphics.Typeface.NORMAL)
    }
    private val dash = DashPathEffect(floatArrayOf(6f * d, 6f * d), 0f)

    init {
        isClickable = false
        isFocusable = false
    }

    fun show(x: Float, y: Float) {
        fx = x
        fy = y
        shown = true
        invalidate()
    }

    fun hide() {
        shown = false
        invalidate()
    }

    fun contains(x: Float, y: Float) = shown && kotlin.math.abs(x - fx) < size * 0.75f && kotlin.math.abs(y - fy) < size * 0.75f

    override fun onDraw(c: Canvas) {
        if (!shown) return
        val col = when (state) {
            State.SHARP -> 0xFF5BE38A.toInt()
            State.UNSHARP -> 0xFFFFB443.toInt()
            State.LOST -> 0xFF9A9A9A.toInt()
            State.SEARCHING -> 0xFFFFFFFF.toInt()
        }
        line.color = col
        dot.color = col
        text.color = col
        line.pathEffect = if (state == State.LOST) dash else null

        val s = if (dragging) size * 1.15f else size
        val r = RectF(fx - s / 2, fy - s / 2, fx + s / 2, fy + s / 2)
        val k = s * 0.28f
        // Eckwinkel
        for (p in listOf(shadow, line)) {
            c.drawLine(r.left, r.top, r.left + k, r.top, p); c.drawLine(r.left, r.top, r.left, r.top + k, p)
            c.drawLine(r.right, r.top, r.right - k, r.top, p); c.drawLine(r.right, r.top, r.right, r.top + k, p)
            c.drawLine(r.left, r.bottom, r.left + k, r.bottom, p); c.drawLine(r.left, r.bottom, r.left, r.bottom - k, p)
            c.drawLine(r.right, r.bottom, r.right - k, r.bottom, p); c.drawLine(r.right, r.bottom, r.right, r.bottom - k, p)
        }
        c.drawCircle(fx, fy, 2.5f * d, dot)

        val label = when {
            state == State.LOST -> "Ziel verloren"
            dragging -> "verschieben"
            macro -> "Makro"
            state == State.SHARP -> "scharf"
            else -> ""
        }
        if (label.isNotEmpty()) c.drawText(label, fx, r.bottom + 18f * d, text)
    }
}
