package com.calvinmazhindu.skitcam.trim

/** Milliseconds in the source presentation timeline. End is exclusive. */
data class TrimRange(val sourceDurationMs: Long, val trimStartMs: Long, val trimEndMs: Long) {
    val effectiveDurationMs get() = trimEndMs - trimStartMs
    val isTrimmed get() = trimStartMs != 0L || trimEndMs != sourceDurationMs
    fun valid(): Boolean = sourceDurationMs in 1..MAX_MS && trimStartMs >= 0 &&
        trimStartMs < trimEndMs && trimEndMs <= sourceDurationMs &&
        if (sourceDurationMs < 500) trimStartMs == 0L && trimEndMs == sourceDurationMs
        else effectiveDurationMs >= 500
    /** Preserve full-source intent in the draft; never shorten an existing user trim silently. */
    fun withObservedDuration(duration: Long) = if (!isTrimmed) full(duration) else copy(sourceDurationMs = duration)
    fun start(value: Long) = copy(trimStartMs = value.coerceIn(0, trimEndMs - minOf(500, sourceDurationMs)))
    fun end(value: Long) = copy(trimEndMs = value.coerceIn(trimStartMs + minOf(500, sourceDurationMs), sourceDurationMs))
    companion object {
        const val MAX_MS = Long.MAX_VALUE / 1000
        fun full(duration: Long) = TrimRange(duration, 0, duration)
    }
}
data class SourceFacts(val durationMs: Long, val length: Long, val modifiedMs: Long)
/** Native file identity plus the duration observed on an unclipped Media3 timeline. */
data class TrimSource(val facts: SourceFacts, val presentationDurationMs: Long)
fun safeDurationSum(values: Iterable<Long>): Long = values.fold(0L) { sum, value ->
    if (value < 0 || value > Long.MAX_VALUE - sum) Long.MAX_VALUE else sum + value
}
