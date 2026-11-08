package com.calvinmazhindu.skitcam.ui.video

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.Spacer
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.calvinmazhindu.skitcam.camera.CameraIcon
import com.calvinmazhindu.skitcam.camera.CameraSymbol
import com.calvinmazhindu.skitcam.ui.theme.SkitCamColors

@Composable
fun VideoTransportControls(
    playing: Boolean,
    positionMs: Long,
    durationMs: Long,
    enabled: Boolean,
    onPlayPause: () -> Unit,
    onSeek: (Long) -> Unit,
    modifier: Modifier = Modifier,
    showProgress: Boolean = true,
    overlay: Boolean = false,
    seekContentDescription: String = "Seek video playback"
) {
    Row(
        modifier.fillMaxWidth()
            .then(if (overlay) Modifier.background(Color.Black.copy(alpha = 0.62f)) else Modifier)
            .padding(horizontal = 8.dp, vertical = 2.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        IconButton(
            onClick = onPlayPause,
            enabled = enabled,
            modifier = Modifier.size(52.dp).semantics {
                contentDescription = if (playing) "Pause" else "Play"
            }
        ) {
            CameraSymbol(if (playing) CameraIcon.Pause else CameraIcon.Play, Modifier.size(30.dp),
                if (enabled) Color.White else SkitCamColors.TextMuted)
        }
        if (showProgress) {
            VideoProgressBar(
                positionMs = positionMs,
                durationMs = durationMs,
                enabled = enabled,
                onSeek = onSeek,
                contentDescription = seekContentDescription,
                modifier = Modifier.weight(1f)
            )
        } else {
            Spacer(Modifier.weight(1f))
        }
        Text(
            "${formatClockTime(positionMs)} / ${formatClockDuration(durationMs)}",
            style = MaterialTheme.typography.labelSmall,
            color = SkitCamColors.TextPrimary,
            maxLines = 1
        )
    }
}
