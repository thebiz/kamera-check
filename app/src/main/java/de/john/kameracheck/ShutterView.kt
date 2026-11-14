package de.john.kameracheck

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.view.View

/**
 * Auslöser mit Fortschrittsring:
 * IDLE = weißer Knopf, CAPTURING = Ring füllt sich (Bilder der Serie),
 * PROCESSING = kleiner Knopf, umlaufender Bogen (es wird gerechnet – Handy darf bewegt werden).
 */
class ShutterView(ctx: Context) : View(ctx) {

    enum class Mode { IDLE, CAPTURING, PROCESSING }

    var mode = Mode.IDLE
        set(v) {
            field = v
            if (v != Mode.CAPTURING) progress = 0f
            invalidate()
        }
    var progress = 0f
        set(v) {
            field = v.coerceIn(0f, 1f)
            invalidate()
        }

    private val d = resources.displayMetrics.density
    private val track = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 4f * d
        color = 0xFFEDEDED.toInt()
    }
    private val arc = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 5f * d
        strokeCap = Paint.Cap.ROUND
        color = 0xFF5BE38A.toInt()
    }
    private val fill = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xFFEDEDED.toInt() }
    private var spin = 0f

    override fun onDraw(c: Canvas) {
        val cx = width / 2f
        val cy = height / 2f
        val r = minOf(cx, cy) - 4f * d
        val oval = RectF(cx - r, cy - r, cx + r, cy + r)

        when (mode) {
            Mode.IDLE -> {
                track.alpha = 255
                c.drawCircle(cx, cy, r, track)
                fill.alpha = 255
                c.drawCircle(cx, cy, r - 9f * d, fill)
            }
            Mode.CAPTURING -> {
                track.alpha = 90
                c.drawCircle(cx, cy, r, track)
                c.drawArc(oval, -90f, 360f * progress, false, arc)
                fill.alpha = 255
                c.drawCircle(cx, cy, r - 14f * d, fill)
            }
            Mode.PROCESSING -> {
                track.alpha = 60
                c.drawCircle(cx, cy, r, track)
                arc.alpha = 255
                c.drawArc(oval, spin, 80f, false, arc)
                fill.alpha = 110
                c.drawCircle(cx, cy, r - 20f * d, fill)
                spin = (spin + 6f) % 360f
                postInvalidateOnAnimation()
            }
        }
    }
}
