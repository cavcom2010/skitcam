package com.calvinmazhindu.skitcam.arrange

import androidx.compose.runtime.*
import androidx.lifecycle.ViewModel
import com.calvinmazhindu.skitcam.recording.RecordingViewModel
import com.calvinmazhindu.skitcam.recording.Session

class ArrangementViewModel : ViewModel() {
    var draft by mutableStateOf<ArrangementDraft?>(null)
        private set
    var draggingId by mutableStateOf<String?>(null)
        private set
    var saving by mutableStateOf(false)
        private set
    var error by mutableStateOf<String?>(null)
        private set
    private var dragOrigin: ArrangementDraft? = null
    private var generation = 0

    fun open(session: Session) {
        if (saving || session.retainedCount < 2) return
        generation++
        draft = ArrangementDraft.from(session)
        error = null
    }
    fun move(id: String, destination: Int) {
        if (!saving) draft = draft?.move(id, destination)
    }
    fun beginDrag(id: String) {
        if (saving || draggingId != null || draft?.ids?.contains(id) != true) return
        dragOrigin = draft
        draggingId = id
    }
    fun endDrag() { draggingId = null; dragOrigin = null }
    fun cancelDrag() {
        if (draggingId != null) draft = dragOrigin
        endDrag()
    }
    fun cancel() { if (!saving) invalidate() }
    fun invalidate() {
        generation++
        endDrag()
        draft = null
        saving = false
        error = null
    }
    fun done(model: RecordingViewModel) {
        val pending = draft ?: return
        if (saving || draggingId != null) return
        val token = generation
        saving = true
        error = null
        model.savePlaybackOrder(pending.sessionId, pending.baseline, pending.ids) { result ->
            if (generation == token) {
                saving = false
                if (result.success) invalidate() else error = result.message
            }
        }
    }
}
