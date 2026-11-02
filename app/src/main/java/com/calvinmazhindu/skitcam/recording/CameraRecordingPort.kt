package com.calvinmazhindu.skitcam.recording

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.util.Log
import android.view.Surface
import android.os.Looper
import androidx.camera.video.*
import androidx.core.content.ContextCompat
import java.io.File
import java.util.concurrent.Executor
import java.util.concurrent.atomic.AtomicBoolean

class CameraRecordingPort(private val context: Context, private val events: Executor, private val audio: com.calvinmazhindu.skitcam.audio.AudioMonitor) : RecordingPort {
    val capture: VideoCapture<Recorder> = VideoCapture.Builder(Recorder.Builder()
        .setQualitySelector(QualitySelector.from(Quality.HD, FallbackStrategy.lowerQualityOrHigherThan(Quality.HD)))
        .build()).setMirrorMode(androidx.camera.core.MirrorMode.MIRROR_MODE_OFF).build()
    var beforeStart: ((Int) -> Unit)? = null // Main-thread activity orientation lock.
    private val main = ContextCompat.getMainExecutor(context)
    private var activeStop: (() -> Unit)? = null
    fun stopForBackground() {
        if (Looper.myLooper() == Looper.getMainLooper()) activeStop?.invoke()
        else main.execute { activeStop?.invoke() }
    }

    override fun start(pending: PendingSegment, orientationDegrees: Int,
        listener: (RecorderEvent) -> Unit): RecordingHandle {
        val stopped = AtomicBoolean(false)
        var recording: Recording? = null // Accessed exclusively on main; held strongly until Finalize.
        main.execute {
            try {
                check(ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) {
                    "Microphone permission is required."
                }
                check(ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED) {
                    "Camera permission is required."
                }
                check(!File(pending.path).exists()) { "Refusing to overwrite a segment file." }
                val lock = checkNotNull(beforeStart) { "Camera screen is no longer available." }
                lock(orientationDegrees)
                capture.targetRotation = rotationFor(orientationDegrees)
                audio.begin(pending.id)
                recording = capture.output.prepareRecording(context, FileOutputOptions.Builder(File(pending.path)).build())
                    .withAudioEnabled().start(events) { event ->
                        when (event) {
                            is VideoRecordEvent.Start -> listener(RecorderEvent.Started)
                            is VideoRecordEvent.Status -> {
                                main.execute { audio.sample(pending.id, event.recordingStats.audioStats) }
                                listener(RecorderEvent.Status(
                                event.recordingStats.recordedDurationNanos / 1_000_000,
                                event.recordingStats.audioStats.hasError() ||
                                    event.recordingStats.audioStats.audioState != AudioStats.AUDIO_STATE_ACTIVE))
                            }
                            is VideoRecordEvent.Finalize -> {
                                Log.i("SkitCam", "Segment finalised: error=${event.error}, file=${pending.path}")
                                main.execute { recording = null; activeStop = null; audio.finish(pending.id) }
                                listener(RecorderEvent.Finalized(event.recordingStats.recordedDurationNanos / 1_000_000,
                                    if (event.hasError()) "CameraX finalisation failed (code ${event.error}). File retained, not registered."
                                    else if (event.recordingStats.audioStats.hasError() || event.recordingStats.audioStats.audioState != AudioStats.AUDIO_STATE_ACTIVE)
                                        "Microphone was unavailable at finalisation. File retained, not registered."
                                    else null))
                            }
                        }
                    }
                activeStop = { if (stopped.compareAndSet(false, true)) recording?.stop() }
                if (stopped.get()) recording?.stop()
            } catch (exception: Exception) {
                audio.finish(pending.id)
                events.execute { listener(RecorderEvent.Finalized(0, exception.message ?: "Camera recording failed.")) }
            }
        }
        return RecordingHandle {
            if (stopped.compareAndSet(false, true)) main.execute { recording?.stop() }
        }
    }
    companion object {
        fun rotationFor(degrees: Int): Int = when (degrees) {
            90 -> Surface.ROTATION_270
            180 -> Surface.ROTATION_180
            270 -> Surface.ROTATION_90
            else -> Surface.ROTATION_0
        }
    }
}
