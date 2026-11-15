package de.john.kameracheck

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Matrix
import android.graphics.Paint
import android.view.GestureDetector
import android.view.MotionEvent
import android.view.ScaleGestureDetector
import android.view.View
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sin

/**
 * 3D-Wackeln wie die früheren Facebook-3D-Fotos (Johns Idee): Das Bild wird nach der Tiefenkarte in Schichten
 * zerlegt; nahe Schichten wandern bei Bewegung stärker als ferne. Die Schichten sind kumulativ (jede enthält alles,
 * was mindestens so nah ist) und weich ausgeblendet – so entstehen beim Verschieben keine Löcher, nur leichtes Dehnen.
 * Läuft von selbst sanft im Kreis. Zwei Finger zoomen, ein Finger verschiebt (gezoomt), Doppeltippen 3×/zurück.
 */
class ParallaxView(ctx: Context) : View(ctx) {

    private var layers: List<Pair<Bitmap, Float>> = emptyList()   // Bild + Tiefe der Schicht (0 fern … 1 nah)
    private var bw = 1
    private var bh = 1
    private val paint = Paint(Paint.FILTER_BITMAP_FLAG)
    private val m = Matrix()
    private var t0 = System.nanoTime()
    private var zoom = 1f
    private var panX = 0f
    private var panY = 0f

    /** Stärke der Bewegung (Anteil der Bildbreite für ganz vorn gegen ganz hinten) */
    var strength = 0.035f

    fun setImage(src: Bitmap, depth: Depth) {
        val long = max(src.width, src.height)
        val sc = min(1f, MAX_SIDE.toFloat() / long)
        bw = max(1, (src.width * sc).roundToInt())
        bh = max(1, (src.height * sc).roundToInt())
        val base = Bitmap.createScaledBitmap(src, bw, bh, true)
        val px = IntArray(bw * bh).also { base.getPixels(it, 0, bw, 0, 0, bw, bh) }
        val near = depth.upscale(bw, bh)
        val list = ArrayList<Pair<Bitmap, Float>>()
        list += base to 0f
        val soft = 1f / LAYERS
        for (i in 1 until LAYERS) {
            val th = i.toFloat() / LAYERS
            val lp = IntArray(px.size)
            for (k in px.indices) {
                val a = ((near[k] - th) / soft + 0.5f).coerceIn(0f, 1f)
                lp[k] = (px[k] and 0x00FFFFFF) or ((a * 255).toInt() shl 24)
            }
            list += Bitmap.createBitmap(lp, bw, bh, Bitmap.Config.ARGB_8888) to th
        }
        layers = list
        t0 = System.nanoTime()
        postInvalidate()   // darf aus dem Rechen-Thread kommen
    }

    fun release() {
        for ((b, _) in layers) b.recycle()
        layers = emptyList()
    }

    override fun onDraw(c: Canvas) {
        if (layers.isEmpty()) return
        val fit = min(width.toFloat() / bw, height.toFloat() / bh) * 1.06f   // leicht größer: Ränder bleiben verdeckt
        val s = fit * zoom
        clampPan(s)
        val ox = (width - bw * s) / 2f + panX
        val oy = (height - bh * s) / 2f + panY
        val tt = (System.nanoTime() - t0) / 1e9f * 1.6f
        val dx = cos(tt)
        val dy = 0.5f * sin(tt)
        val amp = strength * bw * s
        for ((b, depth) in layers) {
            val k = (depth - 0.5f) * amp       // Mitte bleibt stehen, nah wandert mit, fern gegen
            m.setScale(s, s)
            m.postTranslate(ox + dx * k, oy + dy * k)
            c.drawBitmap(b, m, paint)
        }
        postInvalidateOnAnimation()
    }

    private fun clampPan(s: Float) {
        val mx = max(0f, (bw * s - width) / 2f)
        val my = max(0f, (bh * s - height) / 2f)
        panX = panX.coerceIn(-mx, mx)
        panY = panY.coerceIn(-my, my)
    }

    private fun zoomTo(nz: Float, fx: Float, fy: Float) {
        val z = nz.coerceIn(1f, 8f)
        val f = z / zoom
        // Punkt unter den Fingern bleibt stehen
        panX = (panX - (fx - width / 2f)) * f + (fx - width / 2f)
        panY = (panY - (fy - height / 2f)) * f + (fy - height / 2f)
        zoom = z
    }

    private val scaler = ScaleGestureDetector(ctx, object : ScaleGestureDetector.SimpleOnScaleGestureListener() {
        override fun onScale(d: ScaleGestureDetector): Boolean {
            zoomTo(zoom * d.scaleFactor, d.focusX, d.focusY)
            return true
        }
    })

    private val gestures = GestureDetector(ctx, object : GestureDetector.SimpleOnGestureListener() {
        override fun onDown(e: MotionEvent) = true
        override fun onScroll(e1: MotionEvent?, e2: MotionEvent, distX: Float, distY: Float): Boolean {
            panX -= distX
            panY -= distY
            return true
        }
        override fun onDoubleTap(e: MotionEvent): Boolean {
            if (zoom > 1.5f) { zoom = 1f; panX = 0f; panY = 0f } else zoomTo(3f, e.x, e.y)
            return true
        }
    })

    @SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(e: MotionEvent): Boolean {
        scaler.onTouchEvent(e)
        if (!scaler.isInProgress && e.pointerCount == 1) gestures.onTouchEvent(e)
        return true
    }

    companion object {
        const val LAYERS = 8
        const val MAX_SIDE = 1600
    }
}
