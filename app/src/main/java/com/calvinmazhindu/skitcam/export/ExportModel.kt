package com.calvinmazhindu.skitcam.export

import com.calvinmazhindu.skitcam.recording.*
import com.calvinmazhindu.skitcam.trim.TrimRange

enum class ExportState { Preparing, Exporting, Verifying, Publishing, Completed, Failed, Cancelled, Empty }
data class ExportClip(val id: String, val path: String, val range: TrimRange)
data class ExportSpec(val sessionId: String, val orientation: Int, val clips: List<ExportClip>) {
    val durationMs: Long get() = clips.fold(0L) { sum, item -> Math.addExact(sum,item.range.effectiveDurationMs) }
    companion object {
        fun from(session: Session, duration: (Segment) -> Long): ExportSpec {
            require(session.state == RecordingState.Completed && session.pending == null && session.error == null) { "Recording did not finalise successfully. Original files are retained." }
            require(session.retainedCount <= RecordingEngine.MAX_SEGMENTS) { "Too many retained segments." }
            require(session.validPlaybackOrder()) { "Invalid saved playback order." }
            val clips = session.playbackSegments.map { segment ->
                require(segment.orientationDegrees == session.orientationDegrees) { "Segment ${segment.order}: orientation differs." }
                require(segment.invalidTrimJson == null) { "Segment ${segment.order}: invalid trim metadata." }
                val observed = duration(segment)
                require(observed in 1..TrimRange.MAX_MS) { "Segment ${segment.order}: duration unavailable." }
                val saved = segment.trim
                require(saved == null || !saved.isTrimmed || kotlin.math.abs(saved.sourceDurationMs - observed) <= 1L) {
                    "Segment ${segment.order}: source duration changed; review the saved trim."
                }
                val range = if (saved == null || !saved.isTrimmed) TrimRange.full(observed) else saved
                require(range.valid() && range.trimEndMs <= observed) { "Segment ${segment.order}: trim is outside the source." }
                ExportClip(segment.id,segment.path,range)
            }
            return ExportSpec(session.id,session.orientationDegrees,clips).also { it.durationMs }
        }
    }
}
data class ExportStatus(val sessionId: String, val state: ExportState, val message: String? = null, val uri: String? = null) {
    val active get() = state in setOf(ExportState.Preparing,ExportState.Exporting,ExportState.Verifying,ExportState.Publishing)
}

fun exportTransitionAllowed(from: ExportState,to: ExportState): Boolean = from==to || when(from) {
    ExportState.Preparing -> to in setOf(ExportState.Exporting,ExportState.Publishing,ExportState.Failed,ExportState.Cancelled,ExportState.Empty)
    ExportState.Exporting -> to in setOf(ExportState.Verifying,ExportState.Failed,ExportState.Cancelled)
    ExportState.Verifying -> to in setOf(ExportState.Publishing,ExportState.Failed,ExportState.Cancelled)
    ExportState.Publishing -> to in setOf(ExportState.Completed,ExportState.Failed)
    ExportState.Failed,ExportState.Cancelled -> to==ExportState.Preparing
    else -> false
}

/** AAC decoder priming may precede time zero; only subsequent ordering is constrained. */
class PresentationOrder {
    var first: Long? = null; private set
    var last: Long? = null; private set
    fun accept(timeUs: Long) {
        require(last == null || timeUs >= last!!) { "Decoded presentation timestamps moved backwards." }
        if(first == null) first=timeUs
        last=timeUs
    }
}
