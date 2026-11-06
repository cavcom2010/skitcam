package com.calvinmazhindu.skitcam.playback

import com.calvinmazhindu.skitcam.recording.RecordingState
import com.calvinmazhindu.skitcam.recording.Segment
import com.calvinmazhindu.skitcam.recording.Session
import com.calvinmazhindu.skitcam.trim.TrimRange
import org.junit.Assert.*
import org.junit.Test

class SkitPlaybackPlanTest {
    private fun segment(number: Int, kept: Boolean = true, trim: TrimRange? = TrimRange.full(1000),
        invalidTrim: String? = null) = Segment(
        order = number,
        path = "/clips/clip_$number.mp4",
        durationMs = 1000,
        orientationDegrees = 0,
        videoRotationDegrees = 90,
        kept = kept,
        id = "id$number",
        trim = trim,
        invalidTrimJson = invalidTrim)

    private fun session(order: List<Int>, kept: Set<Int> = order.toSet(),
        trims: Map<Int, TrimRange?> = emptyMap(), state: RecordingState = RecordingState.Paused): Session {
        val segments = (1..5).map { number ->
            val trim = if (trims.containsKey(number)) trims[number] else TrimRange.full(1000)
            segment(number, number in kept, trim)
        }
        return Session("session", 1, 0, state, segments, playbackOrder = order.map { "id$it" })
    }

    @Test fun anchorThreePlaysOnlyThreeThroughFive() {
        val result = SkitPlaybackPlanFactory.from(session(listOf(1, 2, 3, 4, 5)), "id3")
        assertTrue(result.ready)
        assertEquals(listOf(3, 4, 5), result.plan!!.clips.map { it.order })
        assertEquals(3, result.plan!!.anchorOrder)
    }

    @Test fun discardedMiddleIsSkippedWithoutChangingSavedOrder() {
        val result = SkitPlaybackPlanFactory.from(
            session(listOf(1, 2, 3, 5), kept = setOf(1, 2, 3, 5)), "id3")
        assertEquals(listOf(3, 5), result.plan!!.clips.map { it.order })
    }

    @Test fun customPlaybackOrderIsNeverReplacedByChronology() {
        val result = SkitPlaybackPlanFactory.from(session(listOf(5, 1, 3, 2, 4)), "id3")
        assertEquals(listOf(3, 2, 4), result.plan!!.clips.map { it.order })
    }

    @Test fun noAnchorStartsAtFirstKeptItemInSavedOrder() {
        val result = SkitPlaybackPlanFactory.from(session(listOf(5, 1, 3, 2, 4)))
        assertEquals(listOf(5, 1, 3, 2, 4), result.plan!!.clips.map { it.order })
        assertEquals(5, result.plan!!.anchorOrder)
    }

    @Test fun savedTrimIsPreservedAndStartsAtItsInPoint() {
        val trim = TrimRange(2000, 300, 1800)
        val result = SkitPlaybackPlanFactory.from(session(listOf(1, 2, 3, 4, 5),
            trims = mapOf(3 to trim)), "id3")
        val clip = result.plan!!.clips.first()
        assertEquals(3, clip.order)
        assertEquals(trim, clip.range)
        assertEquals(1500L, clip.durationMs)
        assertEquals(3500L, result.plan!!.totalDurationMs)
    }

    @Test fun singleKeptClipIsAValidAnchor() {
        val result = SkitPlaybackPlanFactory.from(session(listOf(2), kept = setOf(2)), "id2")
        assertEquals(listOf(2), result.plan!!.clips.map { it.order })
    }

    @Test fun allDiscardedCannotPlay() {
        val result = SkitPlaybackPlanFactory.from(session(emptyList(), kept = emptySet()), null)
        assertEquals("No kept clips to play.", result.error)
        assertNull(result.plan)
    }

    @Test fun discardedAnchorExplainsWhyItCannotPlay() {
        val result = SkitPlaybackPlanFactory.from(
            session(listOf(1, 2, 3, 5), kept = setOf(1, 2, 3, 5)), "id4")
        assertTrue(result.error!!.contains("discarded"))
        assertNull(result.plan)
    }

    @Test fun missingAnchorIsRejected() {
        val result = SkitPlaybackPlanFactory.from(session(listOf(1, 2, 3)), "missing")
        assertTrue(result.error!!.contains("no longer available"))
        assertNull(result.plan)
    }

    @Test fun invalidTrimIsNotSilentlyIgnored() {
        val result = SkitPlaybackPlanFactory.from(session(listOf(1, 2, 3),
            trims = mapOf(2 to TrimRange(1000, 800, 100))), "id1")
        assertTrue(result.error!!.contains("invalid saved trim"))
        assertNull(result.plan)
    }

    @Test fun invalidTrimJsonIsNotSilentlyIgnored() {
        val item = session(listOf(1, 2)).segments[0].copy(invalidTrimJson = "{}")
        val source = session(listOf(1, 2)).copy(segments = listOf(item, session(listOf(1, 2)).segments[1]))
        val result = SkitPlaybackPlanFactory.from(source, "id1")
        assertTrue(result.error!!.contains("invalid saved trim"))
        assertNull(result.plan)
    }

    @Test fun legacyMissingTrimMeansFullSource() {
        val result = SkitPlaybackPlanFactory.from(session(listOf(1, 2), trims = mapOf(1 to null)), "id1")
        assertNull(result.plan!!.clips.first().range)
        assertEquals(1000L, result.plan!!.clips.first().durationMs)
    }

    @Test fun onlyPausedSessionsPlay() {
        val result = SkitPlaybackPlanFactory.from(session(listOf(1), state = RecordingState.Completed))
        assertTrue(result.error!!.contains("paused"))
        assertNull(result.plan)
    }

    @Test fun invalidSavedOrderIsRejected() {
        val source = session(listOf(1, 2)).copy(playbackOrder = listOf("id1"))
        val result = SkitPlaybackPlanFactory.from(source, "id1")
        assertTrue(result.error!!.contains("invalid"))
        assertNull(result.plan)
    }
}
