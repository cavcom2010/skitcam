package com.calvinmazhindu.skitcam.video

/** Immutable applied settings, distinct from preferences and observed file properties. */
data class VideoSettings(val quality: String = "FHD", val fps: Int? = null,
    val stabilization: Boolean = false, val whiteBalance: String = "AUTO") {
    fun valid() = quality in listOf("SD","HD","FHD","UHD") && fps in listOf(null,24,25,30,60) &&
        whiteBalance in WHITE_BALANCES && (!stabilization || (quality in listOf("SD","HD","FHD") && (fps ?: 30)<=30))
    companion object { val WHITE_BALANCES = listOf("AUTO","DAYLIGHT","CLOUDY","INCANDESCENT","FLUORESCENT") }
}

data class CaptureProfile(val cameraId: String, val settings: VideoSettings,
    val width: Int, val height: Int, val legacy: Boolean = false) {
    fun valid() = cameraId.isNotBlank() && settings.valid() && width in 1..8192 && height in 1..8192
    fun accepts(media: SegmentMediaFacts) = valid() && media.valid() &&
        maxOf(width,height)==maxOf(media.width,media.height) && minOf(width,height)==minOf(media.width,media.height)
    fun sameConfiguration(other: CaptureProfile) = cameraId==other.cameraId && settings==other.settings &&
        maxOf(width,height)==maxOf(other.width,other.height) && minOf(width,height)==minOf(other.width,other.height)
}

data class SegmentMediaFacts(val width: Int, val height: Int, val rotation: Int,
    val videoMime: String, val audioMime: String, val audioRate: Int, val audioChannels: Int,
    val videoFrames: Long, val firstPtsUs: Long, val lastPtsUs: Long,
    val minimumIntervalUs: Long?, val maximumIntervalUs: Long?, val reordered: Boolean,
    val videoBytes: Long, val trackDurationUs: Long) {
    fun valid() = width in 1..8192 && height in 1..8192 && rotation in listOf(0,90,180,270) &&
        videoMime=="video/avc" && audioMime=="audio/mp4a-latm" && audioRate>0 && audioChannels in 1..8 &&
        videoFrames>0 && firstPtsUs>=0 && lastPtsUs>=firstPtsUs && videoBytes>0 && trackDurationUs>0 &&
        (minimumIntervalUs==null || minimumIntervalUs>0) && (maximumIntervalUs==null || maximumIntervalUs>0) &&
        (minimumIntervalUs==null || maximumIntervalUs==null || maximumIntervalUs>=minimumIntervalUs)
    val averageFps: Double? get() = if(videoFrames>1 && lastPtsUs>firstPtsUs)
        (videoFrames-1).toDouble()*1_000_000/(lastPtsUs-firstPtsUs) else null
    val averageVideoBitrate: Long get() = java.math.BigInteger.valueOf(videoBytes).multiply(java.math.BigInteger.valueOf(8_000_000))
        .divide(java.math.BigInteger.valueOf(trackDurationUs.coerceAtLeast(1))).min(java.math.BigInteger.valueOf(Long.MAX_VALUE)).toLong()
    val variableCadence get() = reordered || minimumIntervalUs==null || maximumIntervalUs==null || maximumIntervalUs-minimumIntervalUs>2000
}

object VideoProfilePolicy {
    fun fallback(available: List<String>): String? = listOf("FHD","HD","SD","UHD").firstOrNull { it in available }
    fun canChange(state: com.calvinmazhindu.skitcam.recording.RecordingSnapshot, pending: Boolean, exporting: Boolean) =
        !state.openSession && !state.unresolved && !pending && !exporting && state.state in setOf(
            com.calvinmazhindu.skitcam.recording.RecordingState.Ready,com.calvinmazhindu.skitcam.recording.RecordingState.Completed)
    fun reserveBytes(durationMs: Long, width: Int, height: Int, fps: Int?): Long {
        require(durationMs>=0 && width in 1..8192 && height in 1..8192)
        val rate = when { maxOf(width,height)>1920 -> 50_000_000L; maxOf(width,height)>1280 -> 20_000_000L; else -> 10_000_000L }
        val scaled=Math.multiplyExact(rate,maxOf(30,fps ?: 30).toLong())/30+128_000
        // Two output copies, 50% headroom, then the existing 100 MiB reserve.
        return Math.addExact(Math.multiplyExact(durationMs,Math.multiplyExact(scaled,3))/8000,100L*1024*1024)
    }
}
