package com.calvinmazhindu.skitcam.recording

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** Warning priority for the status line under the capture controls. */
class RecordingWarningTest {
    private fun warning(state: RecordingState = RecordingState.Ready, message: String? = null,
        batteryAllowsCapture: Boolean = true, batteryWarning: String? = null,
        captureBlocked: String? = null, poseCorrect: Boolean = true, desiredPose: Int? = null,
        matchesPreview: Boolean = true, microphone: Boolean = true, requested: Boolean = false) =
        recordingWarning(state, message, "Finishing clip", RecordingEngine.BATTERY_PAUSED,
            batteryAllowsCapture, batteryWarning, captureBlocked, poseCorrect, desiredPose,
            "Turn the phone upright to record.", desiredPose?.let { "Rotate back to $it." },
            matchesPreview, "Match the preview orientation", microphone, requested,
            "Microphone access is off.", "Audio is required.")

    @Test fun captureBlockExplainsItselfInsteadOfOrientationAdvice() {
        assertEquals("Legacy capture dimensions cannot be restored. RESUME is blocked.",
            warning(captureBlocked = "Legacy capture dimensions cannot be restored. RESUME is blocked.",
                poseCorrect = false, matchesPreview = false))
        assertEquals("Preview only. The camera cannot start video recording.",
            warning(captureBlocked = "Preview only. The camera cannot start video recording.",
                poseCorrect = false, matchesPreview = false))
    }

    @Test fun captureBlockAppliesToPausedSessionsToo() {
        assertEquals("Original capture settings are unavailable.",
            warning(state = RecordingState.Paused, captureBlocked = "Original capture settings are unavailable.",
                desiredPose = 90, poseCorrect = false))
    }

    @Test fun withoutACaptureBlockExistingAdviceIsUnchanged() {
        assertEquals("Turn the phone upright to record.", warning(poseCorrect = false))
        assertEquals("Rotate back to 90.", warning(desiredPose = 90, poseCorrect = false))
        assertEquals("Match the preview orientation", warning(matchesPreview = false))
        assertEquals(RecordingEngine.BATTERY_PAUSED,
            warning(state = RecordingState.Paused, message = RecordingEngine.BATTERY_PAUSED,
                batteryAllowsCapture = false))
        assertEquals("Low battery · 10%", warning(batteryWarning = "Low battery · 10%"))
        assertEquals("Audio is required.", warning(microphone = false))
        assertEquals("Microphone access is off.", warning(microphone = false, requested = true))
    }

    @Test fun errorsAndProgressOutrankCaptureBlocks() {
        assertEquals("Recording interrupted.", warning(state = RecordingState.Error,
            message = "Recording interrupted.", captureBlocked = "Preview only."))
        assertEquals("Finishing clip", warning(state = RecordingState.Finalising, captureBlocked = "Preview only."))
        assertNull(warning())
        assertNull(warning(state = RecordingState.Completed))
    }
}
