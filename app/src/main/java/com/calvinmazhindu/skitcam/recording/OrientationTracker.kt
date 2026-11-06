package com.calvinmazhindu.skitcam.recording

import android.content.Context
import android.os.SystemClock
import android.os.Handler
import android.os.Looper
import android.view.OrientationEventListener

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

class OrientationTracker(context: Context, private val changed: (Int?) -> Unit) : OrientationEventListener(context) {
    private val filter = OrientationFilter()
    private val handler = Handler(Looper.getMainLooper())
    private var last = ORIENTATION_UNKNOWN
    val rawDegrees get() = last
    private val stable = Runnable { changed(filter.update(last, SystemClock.elapsedRealtime())) }
    override fun onOrientationChanged(orientation: Int) {
        last = orientation
        changed(filter.update(orientation, SystemClock.elapsedRealtime()))
        handler.removeCallbacks(stable)
        handler.postDelayed(stable, 500)
    }
    fun stop() {
        disable()
        handler.removeCallbacks(stable)
        last = ORIENTATION_UNKNOWN
        changed(filter.update(last, SystemClock.elapsedRealtime()))
    }
}
