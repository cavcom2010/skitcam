package com.calvinmazhindu.skitcam.playback

import com.calvinmazhindu.skitcam.recording.RecordingState
import com.calvinmazhindu.skitcam.recording.Segment
import com.calvinmazhindu.skitcam.recording.Session
import com.calvinmazhindu.skitcam.trim.TrimRange
import com.calvinmazhindu.skitcam.trim.safeDurationSum

/** A clipped item in the saved playback order. No file is written to build this plan. */
data class PlaybackClip(val segmentId: String, val order: Int, val path: String,
    val range: TrimRange?, val durationMs: Long)

/**
 * Media3 playlist suffix. The anchor is the first item and is also the loop point.
 * Ordinary seeking never changes the anchor; only an explicit new Play action can.
 */
data class SkitPlaybackPlan(val sessionId: String, val anchorId: String, val clips: List<PlaybackClip>) {
    val anchorOrder: Int get() = clips.first().order
    val totalDurationMs: Long get() = safeDurationSum(clips.map { it.durationMs })
}

data class PlaybackPlanResult(val plan: SkitPlaybackPlan? = null, val error: String? = null) {
    val ready get() = plan != null
}

object SkitPlaybackPlanFactory {
    fun from(session: Session, anchorId: String? = null): PlaybackPlanResult {
        if (session.state != RecordingState.Paused) {
            return PlaybackPlanResult(error = "Playback is only available while recording is paused.")
        }
        if (!session.validPlaybackOrder()) {
            return PlaybackPlanResult(error = "Saved playback order is invalid. Playback was not started.")
        }
        val kept = runCatching { session.playbackSegments }.getOrElse {
            return PlaybackPlanResult(error = "Saved playback order is unavailable. Playback was not started.")
        }
        if (kept.isEmpty()) return PlaybackPlanResult(error = "No kept clips to play.")
        val anchor = anchorId ?: kept.first().id
        val index = kept.indexOfFirst { it.id == anchor }
        if (index < 0) {
            val all = session.segments.find { it.id == anchor }
            return PlaybackPlanResult(error = if (all != null && !all.kept)
                "The starting clip was discarded. Choose another clip." else
                "The starting clip is no longer available. Choose another clip.")
        }
        val clips = ArrayList<PlaybackClip>(kept.size - index)
        for (segment in kept.drop(index)) {
            val clip = clip(segment) ?: return PlaybackPlanResult(error = invalidTrimMessage(segment))
            clips += clip
        }
        return PlaybackPlanResult(plan = SkitPlaybackPlan(session.id, anchor, clips))
    }

    private fun clip(segment: Segment): PlaybackClip? {
        if (segment.invalidTrimJson != null) return null
        val range = segment.trim
        if (range != null && !range.valid()) return null
        return PlaybackClip(segment.id, segment.order, segment.path, range, segment.displayDurationMs)
    }

    private fun invalidTrimMessage(segment: Segment) =
        "Segment ${segment.order} has an invalid saved trim. Open Trim to repair it; playback was not started."
}
