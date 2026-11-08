package com.calvinmazhindu.skitcam.ui.video

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.media3.common.C
import androidx.media3.common.Player
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive

data class VideoPlaybackState(
    val playing: Boolean = false,
    val positionMs: Long = 0,
    val durationMs: Long = 0,
    val finished: Boolean = false
)

/** Polls an existing player for presentation state. Never owns or mutates the player. */
@Composable
fun rememberVideoPlaybackState(player: Player?): VideoPlaybackState {
    var state by remember(player) {
        mutableStateOf(if (player == null) VideoPlaybackState() else player.snapshot())
    }
    LaunchedEffect(player) {
        if (player == null) {
            state = VideoPlaybackState()
            return@LaunchedEffect
        }
        while (isActive) {
            state = player.snapshot()
            if (state.finished) break
            delay(100)
        }
    }
    return state
}

private fun Player.snapshot(): VideoPlaybackState {
    val duration = duration.takeIf { it != C.TIME_UNSET && it > 0 } ?: 0
    return VideoPlaybackState(
        playing = isPlaying,
        positionMs = currentPosition.coerceAtLeast(0),
        durationMs = duration,
        finished = playbackState == Player.STATE_ENDED
    )
}
