package com.calvinmazhindu.skitcam.recording

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.BatteryManager
import androidx.core.content.ContextCompat

internal data class BatteryReading(val percentage: Double? = null, val charging: Boolean = false) {
    val knownPercentage get() = percentage?.takeIf { it in 0.0..100.0 }

    companion object {
        fun from(level: Int, scale: Int, charging: Boolean) =
            BatteryReading(if (scale > 0 && level in 0..scale) level * 100.0 / scale else null, charging)
    }
}

internal data class RecordingBatteryPolicy(
    val warningAt: Int = 10,
    val pauseAt: Int = 7,
    val criticalAt: Int = 5
) {
    init { require(criticalAt in 0..pauseAt && pauseAt < warningAt && warningAt <= 100) }

    fun allowsCapture(reading: BatteryReading) =
        reading.knownPercentage?.let { it >= warningAt } == true

    fun shouldPause(reading: BatteryReading) =
        reading.knownPercentage?.let { it <= pauseAt } == true

    fun warning(reading: BatteryReading, state: RecordingState): String? {
        val level = reading.knownPercentage
        return when {
            state in setOf(RecordingState.Finalising, RecordingState.Stopping, RecordingState.Error) -> null
            level == null -> "Battery level unavailable. Waiting for a reading."
            level <= criticalAt && state != RecordingState.Recording ->
                "Battery critically low. Recording is temporarily unavailable. Your existing clips remain accessible."
            level < warningAt && state != RecordingState.Recording ->
                "Low battery. Charge to at least ${warningAt}% to record."
            level <= warningAt -> "Low battery · ${level.toInt()}%"
            else -> null
        }
    }
}

/** Only registered while the camera activity is visible. */
internal class RecordingBatteryMonitor(private val context: Context, private val changed: (BatteryReading) -> Unit) {
    private val receiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) = update(intent)
    }

    fun start() {
        val sticky = ContextCompat.registerReceiver(context, receiver, IntentFilter(Intent.ACTION_BATTERY_CHANGED),
            ContextCompat.RECEIVER_NOT_EXPORTED)
        update(sticky)
    }

    fun stop() = context.unregisterReceiver(receiver)

    private fun update(intent: Intent?) {
        val status = intent?.getIntExtra(BatteryManager.EXTRA_STATUS, -1)
        changed(BatteryReading.from(intent?.getIntExtra(BatteryManager.EXTRA_LEVEL, -1) ?: -1,
            intent?.getIntExtra(BatteryManager.EXTRA_SCALE, -1) ?: -1,
            status == BatteryManager.BATTERY_STATUS_CHARGING || status == BatteryManager.BATTERY_STATUS_FULL))
    }
}
