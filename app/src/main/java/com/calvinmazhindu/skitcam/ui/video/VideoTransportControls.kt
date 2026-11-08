package com.calvinmazhindu.skitcam.ui.video

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalDensity
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
    onJumpToStart: (() -> Unit)? = null,
    modifier: Modifier = Modifier,
    showProgress: Boolean = true,
    overlay: Boolean = false,
    seekContentDescription: String = "Seek video playback",
    progressContent: (@Composable (Modifier) -> Unit)? = null
) {
    BoxWithConstraints(
        modifier.fillMaxWidth()
            .then(if (overlay) Modifier.background(Color.Black.copy(alpha = 0.62f)) else Modifier)
            .padding(horizontal = 8.dp, vertical = 2.dp)
    ) {
        val stacked = maxWidth < 320.dp || LocalDensity.current.fontScale > 1.5f
        @Composable
        fun buttons() {
            if (onJumpToStart != null) {
                IconButton(
                    onClick = onJumpToStart,
                    enabled = enabled,
                    modifier = Modifier.size(48.dp).semantics {
                        contentDescription = "Jump to segment start"
                    }
                ) {
                    CameraSymbol(CameraIcon.Start, Modifier.size(26.dp),
                        if (enabled) Color.White else SkitCamColors.TextMuted)
                }
            }
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
        }
        @Composable
        fun timeLabel() {
            Text(
                "${formatTransportTime(positionMs)} / ${formatTransportTime(durationMs)}",
                style = MaterialTheme.typography.labelSmall,
                color = SkitCamColors.TextPrimary,
                maxLines = 1,
                modifier = Modifier.semantics {
                    contentDescription =
                        "Playback time ${formatTransportTime(positionMs)} of ${formatTransportTime(durationMs)}"
                }
            )
        }
        @Composable
        fun progress(slot: Modifier) {
            if (progressContent != null) {
                progressContent(slot)
            } else {
                VideoProgressBar(
                    positionMs = positionMs,
                    durationMs = durationMs,
                    enabled = enabled,
                    onSeek = onSeek,
                    contentDescription = seekContentDescription,
                    modifier = slot
                )
            }
        }
        if (stacked) {
            Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    buttons()
                    Spacer(Modifier.weight(1f))
                    timeLabel()
                }
                if (showProgress) progress(Modifier.fillMaxWidth())
            }
        } else {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                buttons()
                if (showProgress) progress(Modifier.weight(1f)) else Spacer(Modifier.weight(1f))
                timeLabel()
            }
        }
    }
}
