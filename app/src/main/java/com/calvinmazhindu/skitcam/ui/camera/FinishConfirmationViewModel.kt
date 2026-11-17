package com.calvinmazhindu.skitcam.ui.camera

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.ViewModel
import com.calvinmazhindu.skitcam.recording.RecordingSnapshot
import com.calvinmazhindu.skitcam.recording.RecordingState

/** Presentation-only gate. Retained across recreation, never auto-confirmed after process death. */
class FinishConfirmationViewModel : ViewModel() {
    var sessionId by mutableStateOf<String?>(null)
        private set
    private var submittedId by mutableStateOf<String?>(null)

    // A corrupt session can fail recovery before an ID exists. Preserve the engine's
    // existing error-acknowledgement path; it closes the error without exporting.
    private fun key(snapshot: RecordingSnapshot): String? = snapshot.session?.id ?:
        if (snapshot.state == RecordingState.Error && !snapshot.unresolved) "unreadable-session-error" else null

    fun eligible(snapshot: RecordingSnapshot, blocked: Boolean): Boolean =
        !blocked && key(snapshot) != null && key(snapshot) != submittedId &&
            (snapshot.state == RecordingState.Recording ||
                snapshot.state in setOf(RecordingState.Paused, RecordingState.Error) && !snapshot.unresolved)

    fun request(snapshot: RecordingSnapshot, blocked: Boolean) {
        if (sessionId == null && eligible(snapshot, blocked)) sessionId = key(snapshot)
    }

    fun cancel() { sessionId = null }

    fun validate(snapshot: RecordingSnapshot) {
        if (key(snapshot) != sessionId ||
            snapshot.state in setOf(RecordingState.Ready, RecordingState.Completed)) cancel()
    }

    /** Consume synchronously on the UI thread before enqueuing the existing stop command. */
    fun consume(snapshot: RecordingSnapshot, blocked: Boolean): Boolean {
        if (sessionId == null || sessionId != key(snapshot) || !eligible(snapshot, blocked)) return false
        submittedId = sessionId
        sessionId = null
        return true
    }
}
