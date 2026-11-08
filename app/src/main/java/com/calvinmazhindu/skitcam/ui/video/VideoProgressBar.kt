package com.calvinmazhindu.skitcam.ui.video

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import com.calvinmazhindu.skitcam.ui.theme.SkitCamColors

@Composable
fun VideoProgressBar(
    positionMs: Long,
    durationMs: Long,
    enabled: Boolean,
    onSeek: (Long) -> Unit,
    contentDescription: String,
    modifier: Modifier = Modifier
) {
    val maximum = durationMs.coerceAtLeast(1L).toFloat()
    var dragging by remember { mutableStateOf(false) }
    var draft by remember { mutableFloatStateOf(positionMs.coerceIn(0L, durationMs).toFloat()) }
    LaunchedEffect(positionMs, durationMs, dragging) {
        if (!dragging) draft = positionMs.coerceIn(0L, durationMs).toFloat()
    }
    Slider(
        value = draft.coerceIn(0f, maximum),
        onValueChange = { dragging = true; draft = it },
        onValueChangeFinished = {
            dragging = false
            onSeek(draft.toLong().coerceIn(0L, durationMs))
        },
        valueRange = 0f..maximum,
        enabled = enabled && durationMs > 0,
        colors = SliderDefaults.colors(
            thumbColor = SkitCamColors.Warm,
            activeTrackColor = SkitCamColors.Warm,
            inactiveTrackColor = SkitCamColors.TextMuted.copy(alpha = 0.45f),
            disabledThumbColor = SkitCamColors.TextMuted,
            disabledActiveTrackColor = SkitCamColors.TextMuted.copy(alpha = 0.35f),
            disabledInactiveTrackColor = SkitCamColors.TextMuted.copy(alpha = 0.2f)
        ),
        modifier = modifier.fillMaxWidth().semantics { this.contentDescription = contentDescription }
    )
}
