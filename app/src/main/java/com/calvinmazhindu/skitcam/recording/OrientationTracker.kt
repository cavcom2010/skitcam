package com.calvinmazhindu.skitcam.recording

import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorManager
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.view.OrientationEventListener
import android.view.OrientationEventListener.ORIENTATION_UNKNOWN

/** Pure pose filtering is shared by the sensor adapter and JVM tests. */
class OrientationFilter {
    private var candidate: Int? = null
    private var since = 0L
    fun update(degrees: Int, nowMs: Long): Int? {
        val nearest = if (degrees < 0) null else ((degrees + 45) / 90 * 90) % 360
        val distance = nearest?.let { minOf(kotlin.math.abs(degrees - it), 360 - kotlin.math.abs(degrees - it)) }
        val next = if (distance != null && distance <= 30) nearest else null
        if (candidate != next) { candidate = next; since = nowMs }
        return next?.takeIf { nowMs - since >= 500 }
    }
}

/** One fresh platform listener per foreground entry. Main thread only. */
class OrientationTracker(private val context: Context, private val changed: (Int?) -> Unit) {
    private val filter = OrientationFilter()
    private val handler = Handler(Looper.getMainLooper())
    private val sensorAvailable = context.getSystemService(SensorManager::class.java)
        .getDefaultSensor(Sensor.TYPE_ACCELEROMETER) != null
    private var listener: OrientationEventListener? = null
    private var last = ORIENTATION_UNKNOWN
    val rawDegrees get() = last
    private val stable = Runnable { changed(filter.update(last, SystemClock.elapsedRealtime())) }

    fun canDetectOrientation() = sensorAvailable

    fun enable() {
        if (listener != null || !sensorAvailable) return
        // Android retains the last reported angle on disable(). A new listener
        // reports the first valid reading even if the phone has not moved.
        val next = object : OrientationEventListener(context) {
            override fun onOrientationChanged(orientation: Int) {
                if (listener !== this) return
                last = orientation
                changed(filter.update(orientation, SystemClock.elapsedRealtime()))
                handler.removeCallbacks(stable)
                handler.postDelayed(stable, 500)
            }
        }
        listener = next
        next.enable()
    }

    fun stop() {
        val previous = listener
        listener = null
        previous?.disable()
        handler.removeCallbacks(stable)
        last = ORIENTATION_UNKNOWN
        changed(filter.update(last, SystemClock.elapsedRealtime()))
    }
}
