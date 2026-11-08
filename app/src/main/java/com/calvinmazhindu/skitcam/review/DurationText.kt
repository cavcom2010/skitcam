package com.calvinmazhindu.skitcam.review

import java.util.Locale

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
