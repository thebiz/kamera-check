package de.john.kameracheck

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Typeface
import android.view.GestureDetector
import android.view.MotionEvent
import android.view.ScaleGestureDetector
import android.view.View
import kotlin.math.abs
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * Vergleich: links der Linie das Normalbild, rechts das Ergebnis – stufenlos
 * zwischen Mehrbild (echtes Detail) und KI-Version gemischt. Zwei Finger zoomen, ein Finger verschiebt,
 * an der Linie ziehen verschiebt den Vergleich, Doppeltippen zoomt 3×.
 */
class CompareView(ctx: Context) : View(ctx) {

    private var normal: Bitmap? = null
    private var real: Bitmap? = null
    private var ki: Bitmap? = null

    var mix = 1f
        set(v) {
            field = v.coerceIn(0f, 1f)
            invalidate()
        }

    private var divider = 0.5f
    private var baseScale = 1f
    private var zoom = 1f
    private var tx = 0f
    private var ty = 0f
    private var draggingDivider = false

    private val d = resources.displayMetrics.density
    private val m = Matrix()
    private val imgPaint = Paint(Paint.FILTER_BITMAP_FLAG)
    private val kiPaint = Paint(Paint.FILTER_BITMAP_FLAG)
    private val linePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        strokeWidth = 2f * d
    }
    private val handlePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.WHITE }
    private val arrowPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0xFF111111.toInt()
        textSize = 16f * d
        textAlign = Paint.Align.CENTER
        typeface = Typeface.DEFAULT_BOLD
    }
    private val labelPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        textSize = 13f * d
        typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
    }
    private val labelBg = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xAA000000.toInt() }

    fun setImages(n: Bitmap, r: Bitmap, k: Bitmap) {
        normal = n
        real = r
        ki = k
        fit()
        invalidate()
    }

    /** Rechte Seite austauschen (z. B. mit Klarheit), Zoom und Ausschnitt bleiben */
    fun setRight(r: Bitmap, k: Bitmap) {
        real = r
        ki = k
        invalidate()
    }

    override fun onSizeChanged(w: Int, h: Int, ow: Int, oh: Int) = fit()

    private fun fit() {
        val b = ki ?: return
        if (width == 0 || height == 0) return
        baseScale = min(width.toFloat() / b.width, height.toFloat() / b.height)
        zoom = 1f
        tx = (width - b.width * baseScale) / 2f
        ty = (height - b.height * baseScale) / 2f
    }

    private fun clampPan() {
        val b = ki ?: return
        val s = baseScale * zoom
        val iw = b.width * s
        val ih = b.height * s
        tx = if (iw <= width) (width - iw) / 2f else tx.coerceIn(width - iw, 0f)
        ty = if (ih <= height) (height - ih) / 2f else ty.coerceIn(height - ih, 0f)
    }

    private fun zoomTo(newZoom: Float, fx: Float, fy: Float) {
        val nz = newZoom.coerceIn(1f, 10f)
        val f = nz / zoom
        tx = fx - (fx - tx) * f
        ty = fy - (fy - ty) * f
        zoom = nz
        clampPan()
        invalidate()
    }

    override fun onDraw(c: Canvas) {
        val n = normal ?: return
        val re = real ?: return
        val k = ki ?: return
        val s = baseScale * zoom
        m.setScale(s, s)
        m.postTranslate(tx, ty)
        val dx = divider * width

        c.save()
        c.clipRect(0f, 0f, dx, height.toFloat())
        c.drawBitmap(n, m, imgPaint)
        c.restore()

        c.save()
        c.clipRect(dx, 0f, width.toFloat(), height.toFloat())
        if (mix < 1f) c.drawBitmap(re, m, imgPaint)
        kiPaint.alpha = (mix * 255).toInt()
        c.drawBitmap(k, m, kiPaint)
        c.restore()

        // Trennlinie mit Griff
        c.drawLine(dx, 0f, dx, height.toFloat(), linePaint)
        val cy = height / 2f
        c.drawCircle(dx, cy, 18f * d, handlePaint)
        c.drawText("‹ ›", dx, cy + 5.5f * d, arrowPaint)

        // Beschriftung
        label(c, "Normal", 12f * d, true)
        val right = when {
            mix >= 0.995f -> "KI"
            mix <= 0.005f -> "Echt"
            else -> "Echt + KI ${(mix * 100).roundToInt()} %"
        }
        label(c, right, width - 12f * d, false)
    }

    private fun label(c: Canvas, t: String, x: Float, left: Boolean) {
        val tw = labelPaint.measureText(t)
        val pad = 8f * d
        val top = 12f * d
        val r = if (left) RectF(x, top, x + tw + 2 * pad, top + 26f * d)
        else RectF(x - tw - 2 * pad, top, x, top + 26f * d)
        c.drawRoundRect(r, 13f * d, 13f * d, labelBg)
        c.drawText(t, r.left + pad, r.top + 18f * d, labelPaint)
    }

    // ───────────────────────── Gesten ─────────────────────────

    private val scaleDetector = ScaleGestureDetector(ctx, object : ScaleGestureDetector.SimpleOnScaleGestureListener() {
        override fun onScale(det: ScaleGestureDetector): Boolean {
            zoomTo(zoom * det.scaleFactor, det.focusX, det.focusY)
            return true
        }
    })

    private val gestures = GestureDetector(ctx, object : GestureDetector.SimpleOnGestureListener() {
        override fun onDown(e: MotionEvent): Boolean {
            draggingDivider = abs(e.x - divider * width) < 36f * d
            return true
        }

        override fun onScroll(e1: MotionEvent?, e2: MotionEvent, distX: Float, distY: Float): Boolean {
            if (draggingDivider) {
                divider = (e2.x / width).coerceIn(0f, 1f)
            } else {
                tx -= distX
                ty -= distY
                clampPan()
            }
            invalidate()
            return true
        }

        override fun onDoubleTap(e: MotionEvent): Boolean {
            zoomTo(if (zoom > 1.5f) 1f else 3f, e.x, e.y)
            return true
        }
    })

    @SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(e: MotionEvent): Boolean {
        scaleDetector.onTouchEvent(e)
        if (!scaleDetector.isInProgress && e.pointerCount == 1) gestures.onTouchEvent(e)
        else draggingDivider = false
        return true
    }

    /** Mischt Mehrbild und KI im gewählten Anteil zu einem neuen Bild. */
    fun renderMix(): Bitmap {
        val n = real!!
        val k = ki!!
        val out = Bitmap.createBitmap(k.width, k.height, Bitmap.Config.ARGB_8888)
        val c = Canvas(out)
        c.drawBitmap(n, 0f, 0f, null)
        val p = Paint().apply { alpha = (mix * 255).toInt() }
        c.drawBitmap(k, 0f, 0f, p)
        return out
    }
}
