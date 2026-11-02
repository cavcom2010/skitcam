package com.calvinmazhindu.skitcam.audio

import android.Manifest
import android.content.Context
import android.content.BroadcastReceiver
import android.content.Intent
import android.content.IntentFilter
import androidx.core.content.ContextCompat
import android.content.pm.PackageManager
import android.media.*
import android.os.*
import androidx.camera.video.AudioStats
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow

/** Observes Android and CameraX. Never opens a microphone or changes audio routing. Main thread only. */
class AudioMonitor(private val context: Context, private val onInputChange: (String) -> Unit) {
    private val manager = context.getSystemService(AudioManager::class.java)
    private val main = Handler(Looper.getMainLooper())
    private val mutable = MutableStateFlow(AudioSnapshot())
    val state = mutable.asStateFlow()
    private var foreground = false
    private var registered = false
    private var muteRegistered = false
    private val legacyMutePoll = object : Runnable {
        override fun run() {
            refreshPermission()
            if(foreground || attempt != null) main.postDelayed(this, 1000)
        }
    }
    private val muteChanges = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) { refreshPermission() }
    }
    private var recordingRegistered = false
    private var attempt: String? = null
    private var inputChangeReported = false
    private val devices = object : AudioDeviceCallback() {
        override fun onAudioDevicesAdded(addedDevices: Array<out AudioDeviceInfo>) = refreshDevices()
        override fun onAudioDevicesRemoved(removedDevices: Array<out AudioDeviceInfo>) = refreshDevices()
    }
    private var configurations: AudioManager.AudioRecordingCallback? = null
    fun foreground(value: Boolean) {
        foreground = value
        reconcile()
        if(value) refreshDevices()
    }
    fun begin(id: String) {
        attempt = id; inputChangeReported = false
        mutable.value = mutable.value.copy(health = CaptureHealth.Unavailable, peak = null, sampledAt = null,
            observations = emptyList(), warning = null)
        reconcile();refreshDevices()
        try { observe(manager.activeRecordingConfigurations) } catch(_: RuntimeException) { }
    }
    fun sample(id: String, stats: AudioStats) {
        if(attempt != id) return
        refreshPermission()
        val health = when(stats.audioState) {
            AudioStats.AUDIO_STATE_ACTIVE -> CaptureHealth.Active
            AudioStats.AUDIO_STATE_MUTED -> CaptureHealth.Muted
            AudioStats.AUDIO_STATE_SOURCE_SILENCED -> CaptureHealth.Silenced
            AudioStats.AUDIO_STATE_SOURCE_ERROR, AudioStats.AUDIO_STATE_ENCODER_ERROR -> CaptureHealth.Failed
            else -> CaptureHealth.Unavailable
        }
        mutable.value = mutable.value.copy(health = health, peak = AudioPolicy.peak(stats.audioAmplitude), sampledAt = SystemClock.elapsedRealtime())
    }
    fun finish(id: String) {
        if(attempt != id) return
        attempt = null
        mutable.value = mutable.value.copy(health = CaptureHealth.Idle, peak = null, sampledAt = null, observations = emptyList())
        reconcile()
    }
    private fun reconcile() {
        if(Build.VERSION.SDK_INT < 28) {
            main.removeCallbacks(legacyMutePoll)
            if(foreground || attempt != null) main.postDelayed(legacyMutePoll, 1000)
        }
        if(foreground || attempt != null) {
            if(Build.VERSION.SDK_INT >= 28 && !muteRegistered) try {
                ContextCompat.registerReceiver(context, muteChanges, IntentFilter(AudioManager.ACTION_MICROPHONE_MUTE_CHANGED), ContextCompat.RECEIVER_NOT_EXPORTED)
                muteRegistered = true
            } catch(_: RuntimeException) { }
            if(!registered) try { manager.registerAudioDeviceCallback(devices, main); registered = true }
                catch(_: RuntimeException) { mutable.value = mutable.value.copy(discoveryAvailable = false) }
            if(attempt != null && !recordingRegistered) try {
                val id = attempt
                val callback = object : AudioManager.AudioRecordingCallback() {
                    override fun onRecordingConfigChanged(configs: MutableList<AudioRecordingConfiguration>) {
                        if(attempt == id && id != null) observe(configs)
                    }
                }
                manager.registerAudioRecordingCallback(callback, main)
                configurations = callback;recordingRegistered = true
            } catch(_: RuntimeException) { }
        } else if(registered) {
            manager.unregisterAudioDeviceCallback(devices);registered = false
        }
        if(!foreground && attempt == null && muteRegistered) {
            context.unregisterReceiver(muteChanges);muteRegistered = false
        }
        if(attempt == null && recordingRegistered) {
            configurations?.let { manager.unregisterAudioRecordingCallback(it) }
            configurations = null;recordingRegistered = false
        }
    }
    private fun refreshPermission() {
        mutable.value = mutable.value.copy(permission = context.checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED,
            systemMuted = try { manager.isMicrophoneMute } catch(_: RuntimeException) { false })
    }
    private fun refreshDevices() {
        refreshPermission()
        try {
            val inputs = manager.getDevices(AudioManager.GET_DEVICES_INPUTS).filter { it.isSource }.map(::input).distinctBy { it.id }
            if(AudioPolicy.removedExternal(mutable.value.inputs, inputs)) warn("External audio input disconnected. Check the microphone before RESUME.")
            mutable.value = mutable.value.copy(inputs = inputs, discoveryAvailable = true)
        } catch(_: RuntimeException) { mutable.value = mutable.value.copy(discoveryAvailable = false) }
    }
    private fun observe(configs: List<AudioRecordingConfiguration>) {
        // No public CameraX capture-session ID: even a unique CAMCORDER configuration is NOT proof of ownership.
        val observed = configs.filter { it.clientAudioSource == MediaRecorder.AudioSource.CAMCORDER }.mapNotNull { config ->
            try { config.audioDevice?.let { AudioObservation(config.clientAudioSessionId, input(it)) } } catch(_: RuntimeException) { null }
        }
        if(AudioPolicy.routeChanged(mutable.value.observations, observed)) warn("Android reported an audio route change. Check the microphone before RESUME.")
        mutable.value = mutable.value.copy(observations = observed)
    }
    private fun warn(message: String) {
        mutable.value = mutable.value.copy(warning = message)
        if(attempt != null && !inputChangeReported) { inputChangeReported = true; onInputChange(message) }
    }
    companion object {
        fun input(device: AudioDeviceInfo): AudioInput {
            val kind = when(device.type) {
                AudioDeviceInfo.TYPE_BUILTIN_MIC -> InputKind.BuiltIn
                AudioDeviceInfo.TYPE_USB_DEVICE, AudioDeviceInfo.TYPE_USB_ACCESSORY, AudioDeviceInfo.TYPE_USB_HEADSET -> InputKind.Usb
                AudioDeviceInfo.TYPE_WIRED_HEADSET, AudioDeviceInfo.TYPE_LINE_ANALOG, AudioDeviceInfo.TYPE_LINE_DIGITAL -> InputKind.Wired
                AudioDeviceInfo.TYPE_BLUETOOTH_SCO -> InputKind.Bluetooth
                else -> if(Build.VERSION.SDK_INT >= 31 && device.type == AudioDeviceInfo.TYPE_BLE_HEADSET) InputKind.Bluetooth else InputKind.Other
            }
            return AudioInput(device.id, kind, device.productName.toString().ifBlank { "${kind.name} input" })
        }
    }
}
