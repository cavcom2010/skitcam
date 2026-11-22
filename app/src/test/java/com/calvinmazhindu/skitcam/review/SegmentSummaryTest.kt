package com.calvinmazhindu.skitcam.review

import com.calvinmazhindu.skitcam.recording.RecordingState
import com.calvinmazhindu.skitcam.recording.Segment
import com.calvinmazhindu.skitcam.recording.Session
import com.calvinmazhindu.skitcam.recording.SessionCodec
import org.junit.Assert.assertEquals
import org.junit.Test

class SegmentSummaryTest {
    @Test fun proportionsDoNotRoundSmallRegionsAway() {
        fun check(kept: Int, discarded: Int, green: Float, amber: Float) {
            val summary = SegmentSummary(kept, discarded)
            assertEquals(kept + discarded, summary.total)
            assertEquals(green, summary.keptFraction, .00001f)
            assertEquals(amber, summary.discardedFraction, .00001f)
        }
        check(8, 2, .8f, .2f)
        check(10, 0, 1f, 0f)
        check(0, 10, 0f, 1f)
        check(99, 1, .99f, .01f)
        check(1, 99, .01f, .99f)
        check(0, 0, 0f, 0f)
    }

    @Test fun changesAndRecoveryUseOnlyCommittedSegments() {
        val first = Segment(1, "/first.mp4", 1000, 0, 0)
        val second = Segment(2, "/second.mp4", 1200, 0, 0)
        fun session(vararg segments: Segment) =
            Session("skit", 1, 0, RecordingState.Paused, segments.toList())

        assertEquals(SegmentSummary(1, 0), SegmentSummary.from(session(first)))
        val discarded = session(first, second.copy(kept = false))
        assertEquals(SegmentSummary(1, 1), SegmentSummary.from(discarded))
        val recovered = SessionCodec.decode(SessionCodec.encode(discarded))
        assertEquals(SegmentSummary(1, 1), SegmentSummary.from(recovered))
        assertEquals(SegmentSummary(2, 0), SegmentSummary.from(session(first, second)))
    }
}
