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

    /** Aufnahme läuft: Fortschritt 0..1 und Text („12/32“) direkt am Motiv – Johns Wunsch; < 0 = keine Aufnahme */
    var capture = -1f
        private set
    private var captureText = ""

    fun setCapture(done: Int, total: Int) {
        capture = if (total > 0) done.toFloat() / total else -1f
        captureText = "$done/$total"
        invalidate()
    }

    fun clearCapture() {
        capture = -1f
        invalidate()
    }

    private val d = resources.displayMetrics.density
    /** Rahmengröße – mit zwei Fingern im Rahmen veränderbar (Johns Wunsch 1.19) */
    var size = 76f * d
        set(v) { field = v.coerceIn(MIN_DP * d, MAX_DP * d); invalidate() }
    /** Vorschau über 10× digital vergrößert: dieser View wird mitskaliert, gezeichnet wird in normaler Größe */
    var viewScale = 1f
        set(v) { field = v; invalidate() }
    /** Selbstauslöser: verbleibende Sekunden groß in der Bildmitte (0 = aus) */
    var countdown = 0
        set(v) { field = v; invalidate() }
    private val bigText = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textAlign = Paint.Align.CENTER
        color = 0xEEFFFFFF.toInt()
        setShadowLayer(8f, 0f, 0f, 0xAA000000.toInt())
    }

    /** Rahmengröße in den (unskalierten) Koordinaten der Vorschau – für Messung und Treffer */
    val localSize get() = size / viewScale
    val minSize get() = MIN_DP * d
    val maxSize get() = MAX_DP * d

    /** Zoom über das Kamera-Maximum hinaus: Anteil der Vorschau, der aufgenommen wird (1 = alles) */
    var cropFrac = 1f
        set(v) { field = v; invalidate() }
    var cropLabel = ""
    private val cropPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        color = 0xDDFFFFFF.toInt()
    }

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

    fun contains(x: Float, y: Float) = shown && kotlin.math.abs(x - fx) < localSize * 0.75f && kotlin.math.abs(y - fy) < localSize * 0.75f

    private val barBg = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 4f * d
        strokeCap = Paint.Cap.ROUND
        color = 0x88000000.toInt()
    }
    private val barFg = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 4f * d
        strokeCap = Paint.Cap.ROUND
        color = 0xFFFFFFFF.toInt()
    }

    override fun onDraw(c: Canvas) {
        if (cropFrac < 0.999f) {
            // Ausschnitt, der bei Zoom über 10× wirklich aufgenommen wird
            cropPaint.strokeWidth = 1.5f * d
            cropPaint.pathEffect = dash
            val w2 = width * cropFrac / 2f
            val h2 = height * cropFrac / 2f
            c.drawRect(width / 2f - w2, height / 2f - h2, width / 2f + w2, height / 2f + h2, cropPaint)
            text.color = 0xDDFFFFFF.toInt()
            c.drawText(cropLabel, width / 2f, height / 2f - h2 - 8f * d, text)
        }
        val px = if (shown) fx else width / 2f
        val py = if (shown) fy else height / 2f
        c.save()
        c.scale(1f / viewScale, 1f / viewScale, px, py)
        drawMarks(c)
        c.restore()
        if (countdown > 0) {
            c.save()
            c.scale(1f / viewScale, 1f / viewScale, width / 2f, height / 2f)
            bigText.textSize = 96f * d
            c.drawText(countdown.toString(), width / 2f, height / 2f + 34f * d, bigText)
            c.restore()
        }
    }

    private fun drawMarks(c: Canvas) {
        if (capture >= 0f) {
            // Fortschritt am Motiv: Balken unter dem Rahmen (bzw. Bildmitte, wenn kein Rahmen da ist)
            val cx = if (shown) fx else width / 2f
            val cy = if (shown) fy else height / 2f
            val y = cy + size / 2 + 14f * d
            val half = size / 2
            c.drawLine(cx - half, y, cx + half, y, barBg)
            c.drawLine(cx - half, y, cx - half + 2 * half * capture.coerceIn(0f, 1f), y, barFg)
            text.color = 0xFFFFFFFF.toInt()
            c.drawText(captureText, cx, y + 18f * d, text)
            if (!shown) return
        }
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
        if (label.isNotEmpty() && capture < 0f) c.drawText(label, fx, r.bottom + 18f * d, text)
    }

    companion object {
        const val MIN_DP = 40f
        const val MAX_DP = 220f
    }
}
