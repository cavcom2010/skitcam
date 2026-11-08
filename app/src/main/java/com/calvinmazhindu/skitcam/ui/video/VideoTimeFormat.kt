package com.calvinmazhindu.skitcam.ui.video

import java.util.Locale

/** Short, human clip length ("4.4s", "1:01"). */
internal fun formatClipDuration(durationMs: Long): String {
    val total = durationMs.coerceAtLeast(0)
    val hours = total / 3_600_000
    val minutes = total / 60_000 % 60
    val seconds = total / 1_000 % 60
    val tenths = total % 1_000 / 100
    return when {
        hours > 0 -> String.format(Locale.US, "%d:%02d:%02d", hours, minutes, seconds)
        minutes > 0 -> String.format(Locale.US, "%d:%02d", minutes, seconds)
        seconds > 0 -> if (tenths == 0L) "${seconds}s" else String.format(Locale.US, "%d.%ds", seconds, tenths)
        tenths > 0 -> String.format(Locale.US, "0.%ds", tenths)
        else -> "0s"
    }
}

/** Clock-style transport time ("00:03", "1:02:03"). */
internal fun formatClockTime(durationMs: Long): String {
    val total = durationMs.coerceAtLeast(0)
    val hours = total / 3_600_000
    val minutes = total / 60_000 % 60
    val seconds = total / 1_000 % 60
    return if (hours > 0) String.format(Locale.US, "%d:%02d:%02d", hours, minutes, seconds)
    else String.format(Locale.US, "%02d:%02d", minutes, seconds)
}

/** Total duration rounded up so a 1.9s clip reads 00:02 rather than 00:01. */
internal fun formatClockDuration(durationMs: Long): String =
    formatClockTime(if (durationMs > 0) durationMs + 999 else 0)

/** Transport time with tenths so short clips visibly advance. */
internal fun formatTransportTime(durationMs: Long): String {
    val total = durationMs.coerceAtLeast(0)
    val hours = total / 3_600_000
    val minutes = total / 60_000 % 60
    val seconds = total / 1_000 % 60
    val tenths = total / 100 % 10
    return if (hours > 0) String.format(Locale.US, "%d:%02d:%02d", hours, minutes, seconds)
    else String.format(Locale.US, "%02d:%02d.%d", minutes, seconds, tenths)
}

/** Frame-editing time with milliseconds ("00:03.200"). */
internal fun formatPreciseTime(durationMs: Long): String {
    val total = durationMs.coerceAtLeast(0)
    return String.format(Locale.US, "%02d:%02d.%03d", total / 60_000, total / 1_000 % 60, total % 1_000)
}
