package com.calvinmazhindu.skitcam.trim

import androidx.compose.runtime.*
import androidx.lifecycle.ViewModel
import com.calvinmazhindu.skitcam.recording.*

class TrimViewModel : ViewModel() {
    var segment by mutableStateOf<Segment?>(null); private set
    var sessionId: String? = null; private set
    var draft by mutableStateOf<TrimRange?>(null); private set
    var source: TrimSource? = null; private set
    var ready by mutableStateOf(false); private set
    var saving by mutableStateOf(false); private set
    var error by mutableStateOf<String?>(null); private set
    var notice by mutableStateOf<String?>(null); private set
    var revision by mutableIntStateOf(0); private set
    private var generation = 0
    var repairConfirmed by mutableStateOf(false); private set
    val canSave get() = ready && !saving && draft?.valid() == true && (segment?.invalidTrimJson == null || repairConfirmed)
    fun open(session: Session, item: Segment) {
        if (saving || session.state != RecordingState.Paused) return
        close(); sessionId = session.id; segment = item; draft = item.trim
    }
    fun resolve(source: SourceFacts, duration: Long) {
        check(duration in 1..TrimRange.MAX_MS) { "Playable duration unavailable." }
        this.source = TrimSource(source, duration)
        val previous = draft
        draft = previous?.withObservedDuration(duration) ?: TrimRange.full(duration)
        notice = when {
            segment?.invalidTrimJson != null -> "Saved trim is invalid. Reset and Save to replace it."
            source.durationMs != duration || previous?.sourceDurationMs?.let { it != duration } == true ->
                "Source duration differs from metadata. Check the range before saving."
            segment?.trim == null -> "Full source selected. Save verifies this legacy segment's duration."
            else -> null
        }
        ready = true; error = null; revision++
    }
    fun suspendPlayback() { ready = false }
    fun fail(message: String) { error = message; ready = false }
    fun change(value: TrimRange) { if (!saving && ready && value.valid()) { draft = value; error = null } }
    fun settled() { revision++ }
    fun reset() { repairConfirmed = true; draft?.let { change(TrimRange.full(it.sourceDurationMs)); settled() } }
    fun close() {
        generation++; segment = null; sessionId = null; draft = null; source = null
        ready = false; saving = false; error = null; notice = null; repairConfirmed = false
    }
    fun save(model: RecordingViewModel, finished: () -> Unit) {
        val item = segment ?: return; val range = draft ?: return; val verified = source ?: return
        if (!canSave) return
        val token = generation; saving = true
        model.saveTrim(sessionId!!, item, verified, range) { result ->
            if (token == generation) {
                saving = false
                if (result.success) { close(); finished() } else error = result.message
            }
        }
    }
}
