package de.john.kameracheck

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.view.MotionEvent
import android.view.View

/**
 * Großer, griffiger Regler: Die ganze Fläche reagiert – antippen springt hin,
 * Ziehen verschiebt. Wert 0..1.
 */
class BigSlider(ctx: Context) : View(ctx) {

    var value = 0f
        private set
    var onChange: ((Float) -> Unit)? = null

    private val d = resources.displayMetrics.density
    private val track = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0xFF4A4A4A.toInt()
        strokeWidth = 4f * d
        strokeCap = Paint.Cap.ROUND
    }
    private val fill = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0xFFEDEDED.toInt()
        strokeWidth = 4f * d
        strokeCap = Paint.Cap.ROUND
    }
    private val knob = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xFFEDEDED.toInt() }
    private val knobRing = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0x33EDEDED }
    private var dragging = false

    private val pad get() = 16f * d

    fun setValue(v: Float) {
        value = v.coerceIn(0f, 1f)
        invalidate()
    }

    override fun onMeasure(wSpec: Int, hSpec: Int) {
        setMeasuredDimension(getDefaultSize(suggestedMinimumWidth, wSpec), resolveSize((48 * d).toInt(), hSpec))
    }

    override fun onDraw(c: Canvas) {
        val cy = height / 2f
        val x0 = pad
        val x1 = width - pad
        val x = x0 + (x1 - x0) * value
        c.drawLine(x0, cy, x1, cy, track)
        c.drawLine(x0, cy, x, cy, fill)
        if (dragging) c.drawCircle(x, cy, 20f * d, knobRing)
        c.drawCircle(x, cy, (if (dragging) 13f else 11f) * d, knob)
    }

    @SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(e: MotionEvent): Boolean {
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                parent?.requestDisallowInterceptTouchEvent(true)
                dragging = true
                update(e.x)
            }
            MotionEvent.ACTION_MOVE -> update(e.x)
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                dragging = false
                invalidate()
            }
        }
        return true
    }

    private fun update(x: Float) {
        val v = ((x - pad) / (width - 2 * pad)).coerceIn(0f, 1f)
        if (v != value) {
            value = v
            onChange?.invoke(v)
        }
        invalidate()
    }
}
