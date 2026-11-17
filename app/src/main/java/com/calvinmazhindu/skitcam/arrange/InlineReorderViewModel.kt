package com.calvinmazhindu.skitcam.arrange

import android.app.Application
import android.os.Handler
import android.os.Looper
import android.util.AtomicFile
import androidx.compose.runtime.*
import androidx.lifecycle.AndroidViewModel
import com.calvinmazhindu.skitcam.recording.*
import java.io.File
import java.util.concurrent.Executors

/** Serialized recovery checkpoints are separate from authoritative session.json. */
class InlineReorderViewModel(application: Application) : AndroidViewModel(application) {
    var draft by mutableStateOf<InlineReorderDraft?>(null); private set
    var draggingId by mutableStateOf<String?>(null); private set
    var saving by mutableStateOf(false); private set
    var recovering by mutableStateOf(true); private set
    var error by mutableStateOf<String?>(null); private set
    var notice by mutableStateOf<String?>(null); private set
    private var dragOrigin: InlineReorderDraft? = null
    private var generation = 0
    private var restoreStarted = false
    private val worker = Executors.newSingleThreadExecutor()
    private val main = Handler(Looper.getMainLooper())
    private val checkpoint = AtomicFile(File(application.filesDir, "inline-reorder-draft.json"))

    fun restore(session: Session?) {
        if (restoreStarted) return
        restoreStarted = true
        val token = generation
        worker.execute {
            val result = runCatching {
                if (!checkpoint.baseFile.exists() && !File(checkpoint.baseFile.path + ".bak").exists()) null
                else checkpoint.openRead().bufferedReader().use { InlineReorderDraft.decode(it.readText()) }
            }
            main.post {
                if (generation == token) {
                    val pending = result.getOrNull()
                    if (pending != null && session != null && pending.matches(session) &&
                        session.state == RecordingState.Paused && session.pending == null) {
                        draft = pending
                        notice = "Reorder draft recovered · Done to save"
                    } else if (pending != null || result.isFailure) {
                        error = "An outdated reorder draft was not applied. Saved order and original files are unchanged."
                    }
                    recovering = false
                }
            }
        }
    }
    fun open(session: Session) {
        if (saving || recovering || draft != null || session.state != RecordingState.Paused ||
            session.pending != null || session.segments.isEmpty()) return
        generation++
        draft = InlineReorderDraft(session)
        error = null
        notice = "Reorder unlocked"
        checkpoint()
    }
    fun validate(session: Session?) {
        val pending = draft ?: return
        if (session == null || !pending.matches(session)) {
            cancelDrag()
            error = "The session changed. This draft has not been applied. Cancel and unlock reordering again."
        }
    }
    fun move(id: String, destination: Int) {
        if (saving) return
        draft = draft?.move(id, destination)
        if (draggingId == null) checkpoint()
    }
    fun beginDrag(id: String) {
        if (saving || draggingId != null || draft?.ids?.contains(id) != true) return
        dragOrigin = draft
        draggingId = id
    }
    fun endDrag() {
        draggingId = null; dragOrigin = null
        checkpoint()
    }
    fun cancelDrag() {
        if (draggingId == null) return
        draft = dragOrigin
        endDrag()
    }
    fun cancel() {
        if (saving) return
        generation++
        draft = null; draggingId = null; dragOrigin = null
        error = null; notice = null
        worker.execute { checkpoint.delete() }
    }
    fun done(model: RecordingViewModel, finished: () -> Unit = {}) {
        val pending = draft ?: return
        if (saving || draggingId != null) return
        val token = generation
        saving = true; error = null
        model.saveShelfOrder(pending.baseline, pending.ids) { result ->
            if (token == generation) {
                saving = false
                if (result.success) { cancel(); finished() } else error = result.message
            }
        }
    }
    private fun checkpoint() {
        val pending = draft ?: return
        val token = generation
        worker.execute {
            val result = runCatching {
                val output = checkpoint.startWrite()
                try {
                    output.write(pending.encode().toByteArray(Charsets.UTF_8))
                    checkpoint.finishWrite(output)
                } catch (e: Exception) { checkpoint.failWrite(output); throw e }
            }
            if (result.isFailure) main.post {
                if (generation == token) error = "Draft recovery could not be saved. Keep SkitCam open and select Done or Cancel."
            }
        }
    }
    override fun onCleared() { worker.shutdown() }
}
