package com.calvinmazhindu.skitcam.export

/** Presentation timestamp and exclusive presentation end are different quantities. */
data class DecodedTiming(
    val firstPtsUs: Long, val lastPtsUs: Long, val buffers: Long,
    val endExclusiveUs: Long? = null
)

object TimingMeasurement {
    fun sourceGapUs(firstSampleUs: Long, clipStartUs: Long): Long {
        require(clipStartUs >= 0)
        return if(firstSampleUs > clipStartUs) Math.subtractExact(firstSampleUs,clipStartUs) else 0
    }

    /** Nearest PCM frame, bounded to two seconds. No guessing for unknown clip durations. */
    fun leadingGapFrames(ptsUs: Long, sampleRate: Int, clipDurationUs: Long): Long {
        require(ptsUs in 0..2_000_000 && sampleRate in 1..192000 && clipDurationUs > ptsUs) {
            "Unsupported leading audio gap; source timing requires attention"
        }
        return Math.addExact(Math.multiplyExact(ptsUs, sampleRate.toLong()), 500_000) / 1_000_000
    }

    /** Exact integer PCM frame count. Unknown encodings must be reported as unknown by the adapter. */
    fun pcmFrames(bytes: Int, channels: Int, bytesPerSample: Int): Long {
        require(bytes >= 0 && channels > 0 && bytesPerSample > 0)
        val frameBytes = Math.multiplyExact(channels, bytesPerSample)
        require(bytes % frameBytes == 0) { "Partial PCM frame" }
        return (bytes / frameBytes).toLong()
    }
    fun endUs(ptsUs: Long, frames: Long, sampleRate: Int): Long {
        require(frames >= 0 && sampleRate > 0)
        return Math.addExact(ptsUs, Math.multiplyExact(frames, 1_000_000L) / sampleRate)
    }
}
