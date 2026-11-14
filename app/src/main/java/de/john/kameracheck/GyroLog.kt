package de.john.kameracheck

import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import kotlin.math.hypot

/**
 * Zeichnet das Gyroskop laufend auf (Ringpuffer). Daraus lässt sich für jedes Bild
 * der Serie berechnen, wie stark die Hand während der Belichtung gezittert hat.
 * Drehung um die x- und y-Achse verschiebt das Bild; die Drehung um die Blickachse
 * (z) wird hier nicht gezählt, die gleicht das kachelweise Ausrichten aus.
 */
class GyroLog : SensorEventListener {

    private val size = 4096
    private val ts = LongArray(size)
    private val omega = FloatArray(size)
    private var n = 0L
    private var sm: SensorManager? = null

    fun start(manager: SensorManager) {
        val gyro = manager.getDefaultSensor(Sensor.TYPE_GYROSCOPE) ?: return
        sm = manager
        manager.registerListener(this, gyro, 5000) // 200 Hz
    }

    fun stop() {
        sm?.unregisterListener(this)
        sm = null
    }

    override fun onSensorChanged(e: SensorEvent) {
        synchronized(this) {
            val i = (n % size).toInt()
            ts[i] = e.timestamp
            omega[i] = hypot(e.values[0], e.values[1])
            n++
        }
    }

    override fun onAccuracyChanged(s: Sensor?, a: Int) {}

    /** Mittlere Drehgeschwindigkeit (rad/s) im Zeitraum [t0, t1] (Nanosekunden, Echtzeituhr). */
    fun meanOmega(t0: Long, t1: Long): Float? {
        synchronized(this) {
            val count = minOf(n, size.toLong()).toInt()
            var sum = 0.0
            var c = 0
            var nearest = Long.MAX_VALUE
            var nearestVal = 0f
            for (k in 0 until count) {
                val i = ((n - 1 - k) % size).toInt()
                val t = ts[i]
                if (t in t0..t1) {
                    sum += omega[i]
                    c++
                }
                val d = kotlin.math.abs(t - (t0 + t1) / 2)
                if (d < nearest) {
                    nearest = d
                    nearestVal = omega[i]
                }
            }
            if (c > 0) return (sum / c).toFloat()
            // kein Messwert im (kurzen) Belichtungsfenster: nächster innerhalb 20 ms
            return if (nearest < 20_000_000L) nearestVal else null
        }
    }
}
