package com.calvinmazhindu.skitcam.recording

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
        private fun runtime(application: Application) = synchronized(this) {
            shared ?: RecordingRuntime(application).also { shared = it }
        }
    }
    // One engine per process: finishing/reopening an activity cannot bypass an unresolved recording.
    private val runtime = runtime(application)
    val snapshots get() = runtime.snapshots
    val port get() = runtime.port
    val cameraAvailability get() = runtime.cameraAvailability
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
    fun background() = runtime.background()
    fun cameraError() = runtime.cameraError()
    override fun onCleared() { runtime.background() }
}

private class RecordingRuntime(private val application: Application) {
    private val worker = Executors.newSingleThreadExecutor()
    private val main = Handler(Looper.getMainLooper())
    private val state = MutableStateFlow(RecordingSnapshot())
    val snapshots = state.asStateFlow()
    val port = CameraRecordingPort(application, worker)
    private val camera = MutableStateFlow(false)
    val cameraAvailability = camera.asStateFlow()
    var cameraReady: Boolean
        get() = camera.value
        set(value) { camera.value = value }
    private val engine = RecordingEngine(port, SessionFileStore(File(application.filesDir, "sessions")),
        Watchdog { callback ->
            val runnable = Runnable { worker.execute(callback) }
            main.postDelayed(runnable, 30_000)
            Cancellation { main.removeCallbacks(runnable) }
        }, availability = {
            when {
                !cameraReady -> "Wait for the rear-camera preview."
                !hasPermission(Manifest.permission.CAMERA) -> "Camera permission is required."
                !hasPermission(Manifest.permission.RECORD_AUDIO) -> "Microphone permission is required."
                else -> null
            }
        }, changed = { state.value = it })
    init { worker.execute { engine.restore() } }
    private fun hasPermission(permission: String) = ContextCompat.checkSelfPermission(
        application, permission) == PackageManager.PERMISSION_GRANTED
    fun start(orientation: Int?) = worker.execute { engine.start(orientation) }
    fun resume(orientation: Int?) = worker.execute { engine.resume(orientation) }
    fun pause() = worker.execute { engine.pause() }
    fun stop() = worker.execute { engine.stop() }
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
