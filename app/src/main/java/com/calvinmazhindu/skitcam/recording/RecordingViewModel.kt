package com.calvinmazhindu.skitcam.recording

import com.calvinmazhindu.skitcam.trim.*
import com.calvinmazhindu.skitcam.export.*
import android.Manifest
import android.app.Application
import android.content.pm.PackageManager
import android.os.Handler
import android.os.Looper
import androidx.core.content.ContextCompat
import androidx.lifecycle.AndroidViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.io.File
import java.util.concurrent.Executors

class RecordingViewModel(application: Application) : AndroidViewModel(application) {
    companion object {
        private var shared: RecordingRuntime? = null
        fun cameraReleasedForExport(): Boolean = shared?.cameraReady != true
        private fun runtime(application: Application) = synchronized(this) {
            shared ?: RecordingRuntime(application).also { shared = it }
        }
    }
    // One engine per process: finishing/reopening an activity cannot bypass an unresolved recording.
    private val runtime = runtime(application)
    val snapshots get() = runtime.snapshots
    val audio get() = runtime.audio
    val port get() = runtime.port
    val cameraAvailability get() = runtime.cameraAvailability
    val recovered get() = runtime.recovered
    val commandPending get() = runtime.commandPending
    var boundLens: CameraLens
        get() = runtime.boundLens
        set(value) { runtime.boundLens = value }
    var cameraReady: Boolean
        get() = runtime.cameraReady
        set(value) { runtime.cameraReady = value }
    fun start(orientation: Int?) = runtime.start(orientation)
    fun resume(orientation: Int?) = runtime.resume(orientation)
    fun pause() = runtime.pause()
    fun stop() = runtime.stop()
    fun setSegmentKept(sessionId: String, segmentId: String, kept: Boolean, finished: (SegmentEditResult) -> Unit) =
        runtime.setSegmentKept(sessionId, segmentId, kept, finished)
    fun savePlaybackOrder(sessionId: String, expected: List<String>, order: List<String>, finished: (SegmentEditResult) -> Unit) =
        runtime.savePlaybackOrder(sessionId, expected, order, finished)
    fun saveTrim(sessionId: String, segment: Segment, source: TrimSource, range: TrimRange, finished: (SegmentEditResult) -> Unit) =
        runtime.saveTrim(sessionId, segment, source, range, finished)
    fun background() = runtime.background()
    fun cameraError() = runtime.cameraError()
    override fun onCleared() { runtime.background() }
}

private class RecordingRuntime(private val application: Application) {
    private val worker = Executors.newSingleThreadExecutor()
    private val main = Handler(Looper.getMainLooper())
    private val state = MutableStateFlow(RecordingSnapshot())
    val recovered = MutableStateFlow(false)
    val commandPending = MutableStateFlow(false)
    @Volatile var boundLens = CameraLens.Rear
    val snapshots = state.asStateFlow()
    val audio = com.calvinmazhindu.skitcam.audio.AudioMonitor(application, ::audioChanged)
    val port = CameraRecordingPort(application, worker, audio)
    private fun audioChanged(reason: String) { worker.execute { engine.pauseForAudioChange(reason) } }
    private val camera = MutableStateFlow(false)
    val cameraAvailability = camera.asStateFlow()
    var cameraReady: Boolean
        get() = camera.value
        set(value) { camera.value = value }
    private val engine: RecordingEngine = RecordingEngine(port, SessionFileStore(File(application.filesDir, "sessions")),
        Watchdog { callback ->
            val runnable = Runnable { worker.execute(callback) }
            main.postDelayed(runnable, 30_000)
            Cancellation { main.removeCallbacks(runnable) }
        }, availability = {
            when {
                ExportJournal.busy -> "Wait for video finishing to complete."
                !recovered.value -> "Wait for session recovery."
                !cameraReady -> "Wait for the camera preview."
                engineLensMismatch() -> "The original session camera is not ready."
                !hasPermission(Manifest.permission.CAMERA) -> "Camera permission is required."
                !hasPermission(Manifest.permission.RECORD_AUDIO) -> "Microphone permission is required."
                else -> null
            }
        }, changed = {
            state.value = it
            if(it.state in setOf(RecordingState.Completed,RecordingState.Error)) it.session?.let { session ->
                try { ExportJournal.finalised(application,session) } catch(_: Exception) { }
            }
        })
    init { worker.execute {
        engine.restore()
        engine.snapshot.session?.let { try { ExportJournal.restore(application,it) } catch(_: Exception) {} }
        recovered.value = true
    } }
    private fun hasPermission(permission: String) = ContextCompat.checkSelfPermission(
        application, permission) == PackageManager.PERMISSION_GRANTED
    private fun engineLensMismatch() = engine.snapshot.openSession && engine.snapshot.session?.lens != boundLens
    fun start(orientation: Int?) {
        if(!cameraReady || commandPending.value) return
        commandPending.value = true
        worker.execute { try { engine.start(orientation, boundLens) } finally { commandPending.value = false } }
    }
    fun resume(orientation: Int?) = worker.execute { engine.resume(orientation) }
    fun pause() = worker.execute { engine.pause() }
    fun stop() = worker.execute {
        val session=engine.snapshot.session
        if(session!=null && engine.snapshot.state in setOf(RecordingState.Paused,RecordingState.Recording) && !ExportJournal.busy) {
            try {
                ExportJournal.request(application,session)
                ExportJournal.busy=true
                main.post {
                    try { ExportService.launch(application,session.id) }
                    catch(e: Exception) { ExportJournal.busy=false;ExportJournal.update(application,session.id,ExportState.Failed,e.message) }
                }
            } catch(_: Exception) { ExportJournal.busy=false }
        }
        engine.stop()
    }
    fun setSegmentKept(sessionId: String, segmentId: String, kept: Boolean, finished: (SegmentEditResult) -> Unit) {
        worker.execute {
            val result = engine.setSegmentKept(sessionId, segmentId, kept)
            main.post { finished(result) }
        }
    }
    fun savePlaybackOrder(sessionId: String, expected: List<String>, order: List<String>, finished: (SegmentEditResult) -> Unit) {
        val baseline = expected.toList()
        val proposed = order.toList()
        worker.execute {
            val result = engine.savePlaybackOrder(sessionId, baseline, proposed)
            main.post { finished(result) }
        }
    }
    fun saveTrim(sessionId: String, segment: Segment, source: TrimSource, range: TrimRange, finished: (SegmentEditResult) -> Unit) {
        worker.execute {
            val result = engine.saveTrim(sessionId, segment.id, segment, source, range)
            main.post { finished(result) }
        }
    }
    fun background() {
        worker.execute { engine.background() }
        // onPause must stop the source synchronously, before CameraX sees ON_STOP.
        port.stopForBackground()
    }
    fun cameraError() {
        cameraReady = false
        worker.execute { engine.interruption("Camera interrupted. Confirmed segments are retained.") }
    }
}
