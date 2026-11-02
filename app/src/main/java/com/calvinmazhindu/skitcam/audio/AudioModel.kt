package com.calvinmazhindu.skitcam.audio

/** Device identity is valid only for this process/device connection. Never a saved preference. */
enum class InputKind { BuiltIn, Usb, Wired, Bluetooth, Other }
data class AudioInput(val id: Int, val kind: InputKind, val name: String)
enum class CaptureHealth { Idle, Active, Muted, Silenced, Failed, Unavailable }
data class AudioObservation(val sessionId: Int, val device: AudioInput)
data class AudioSnapshot(
    val inputs: List<AudioInput> = emptyList(),
    val observations: List<AudioObservation> = emptyList(),
    val permission: Boolean = false,
    val systemMuted: Boolean = false,
    val health: CaptureHealth = CaptureHealth.Idle,
    val peak: Double? = null,
    val sampledAt: Long? = null,
    val warning: String? = null,
    val discoveryAvailable: Boolean = true
) {
    val title: String get() = when {
        !permission -> "Microphone permission needed"
        systemMuted -> "Microphone muted by Android"
        health == CaptureHealth.Silenced -> "Microphone silenced by Android"
        health == CaptureHealth.Muted -> "Recording audio muted"
        health == CaptureHealth.Failed -> "Microphone capture failed"
        health == CaptureHealth.Unavailable -> "Recording audio unavailable"
        health == CaptureHealth.Active -> "Recording audio · input unverified"
        else -> "Microphone · Android automatic"
    }
    fun visiblePeak(now: Long): Double? = if(permission && !systemMuted && health == CaptureHealth.Active &&
        sampledAt?.let { now - it in 0..2000 } == true) peak else null
}

/** Stateless rules shared by the real adapter and deterministic tests. */
object AudioPolicy {
    fun peak(value: Double): Double? = value.takeIf { it.isFinite() }?.coerceIn(0.0, 1.0)
    fun removedExternal(before: List<AudioInput>, after: List<AudioInput>): Boolean =
        before.any { it.kind in setOf(InputKind.Usb, InputKind.Wired, InputKind.Bluetooth) && after.none { other -> other.id == it.id } }
    fun routeChanged(before: List<AudioObservation>, after: List<AudioObservation>): Boolean =
        before.any { old -> after.any { it.sessionId == old.sessionId && it.device.id != old.device.id } }
    fun captureFailure(health: CaptureHealth) = health in setOf(CaptureHealth.Muted, CaptureHealth.Silenced, CaptureHealth.Failed, CaptureHealth.Unavailable)
}
