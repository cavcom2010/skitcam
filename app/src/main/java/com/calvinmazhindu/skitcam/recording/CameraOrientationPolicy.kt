package com.calvinmazhindu.skitcam.recording

/** Activity policy only. Never changes sensor data, capture profiles or saved media. */
object CameraOrientationPolicy {
    enum class Request { FullSensor, Portrait, Landscape, ReversePortrait, ReverseLandscape }

    fun request(snapshot: RecordingSnapshot, recovered: Boolean, finishing: Boolean,
        pendingStart: Int? = null, naturalPortrait: Boolean = true): Request? {
        if(!recovered) return null
        val locked = snapshot.session?.takeIf { snapshot.openSession || finishing }?.orientationDegrees ?: pendingStart
        return locked?.let { lock(it,naturalPortrait) } ?: Request.FullSensor
    }

    fun lock(degrees: Int, naturalPortrait: Boolean): Request {
        require(degrees in setOf(0,90,180,270))
        return if(naturalPortrait) when(degrees) {
            90 -> Request.ReverseLandscape
            180 -> Request.ReversePortrait
            270 -> Request.Landscape
            else -> Request.Portrait
        } else when(degrees) {
            90 -> Request.Portrait
            180 -> Request.ReverseLandscape
            270 -> Request.ReversePortrait
            else -> Request.Landscape
        }
    }

    fun matchesDisplay(pose: Int?, rotation: Int): Boolean =
        pose in setOf(0,90,180,270) && (360-pose!!)%360/90==rotation

    fun cameraMayBind(request: Request?, lockedPose: Int?, displayRotation: Int): Boolean =
        request!=null && (request==Request.FullSensor || matchesDisplay(lockedPose,displayRotation))
}
