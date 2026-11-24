package com.calvinmazhindu.skitcam.export

import com.calvinmazhindu.skitcam.video.CaptureProfile
import com.calvinmazhindu.skitcam.video.VideoSettings
import com.calvinmazhindu.skitcam.trim.TrimRange
import org.junit.Assert.*
import org.junit.Test

class ExportCandidateTest {
    private val profile = CaptureProfile("0", VideoSettings(), 1920, 1080)
    private fun spec(vararg clips: ExportClip) = ExportSpec("session", 90, clips.toList(), profile)

    @Test fun fingerprintIsStableForTheSameOrderedSpecification() {
        val value = spec(ExportClip("a", "/a.mp4", TrimRange(10000, 100, 9000)),
            ExportClip("b", "/b.mp4", TrimRange.full(5000)))
        assertEquals(ExportCandidate.specFingerprint(value), ExportCandidate.specFingerprint(value.copy()))
    }

    @Test fun fingerprintChangesWithOrderTrimDurationOrientationOrProfile() {
        val base = spec(ExportClip("a", "/a.mp4", TrimRange(10000, 100, 9000)),
            ExportClip("b", "/b.mp4", TrimRange.full(5000)))
        val expected = ExportCandidate.specFingerprint(base)
        assertNotEquals(expected, ExportCandidate.specFingerprint(base.copy(clips = base.clips.reversed())))
        assertNotEquals(expected, ExportCandidate.specFingerprint(base.copy(clips = listOf(
            base.clips[0].copy(range = base.clips[0].range.copy(trimEndMs = 8000)), base.clips[1]))))
        assertNotEquals(expected, ExportCandidate.specFingerprint(base.copy(clips = listOf(
            base.clips[0].copy(range = base.clips[0].range.copy(sourceDurationMs = 11000)), base.clips[1]))))
        assertNotEquals(expected, ExportCandidate.specFingerprint(base.copy(orientation = 0)))
        assertNotEquals(expected, ExportCandidate.specFingerprint(base.copy(captureProfile = profile.copy(width = 1280))))
        assertNotEquals(expected, ExportCandidate.specFingerprint(base.copy(sessionId = "other")))
    }

    @Test fun reverificationIsTheOnlyNewStateTransition() {
        assertTrue(exportTransitionAllowed(ExportState.Preparing, ExportState.Verifying))
        assertTrue(exportTransitionAllowed(ExportState.Failed, ExportState.Preparing))
        assertFalse(exportTransitionAllowed(ExportState.Preparing, ExportState.Completed))
        assertFalse(exportTransitionAllowed(ExportState.Completed, ExportState.Verifying))
        assertFalse(exportTransitionAllowed(ExportState.Verifying, ExportState.Exporting))
    }
}
