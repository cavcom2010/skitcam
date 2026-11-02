package com.calvinmazhindu.skitcam.camera

import com.calvinmazhindu.skitcam.recording.*

enum class CameraMode { SkitVideo, Photo }
data class CameraChoice(val mode: CameraMode = CameraMode.SkitVideo, val lens: CameraLens = CameraLens.Rear)

/** Recovery always wins over preferences; no command in this policy stops a session. */
object CameraPolicy {
    fun maySwitch(recovered: Boolean, snapshot: RecordingSnapshot, exporting: Boolean, photoBusy: Boolean): Boolean =
        recovered && !snapshot.openSession && !snapshot.unresolved &&
            snapshot.state in setOf(RecordingState.Ready, RecordingState.Completed) && !exporting && !photoBusy
    fun initial(saved: CameraChoice, snapshot: RecordingSnapshot, needsExport: Boolean): CameraChoice =
        if (snapshot.openSession || snapshot.state == RecordingState.Error || needsExport)
            CameraChoice(CameraMode.SkitVideo, snapshot.session?.lens ?: CameraLens.Rear) else saved
    fun zoom(requested: Float, minimum: Float, maximum: Float): Float =
        if (requested.isFinite() && minimum > 0 && maximum >= minimum) requested.coerceIn(minimum, maximum) else minimum
}
