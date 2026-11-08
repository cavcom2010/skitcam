package com.calvinmazhindu.skitcam.ui.video

import android.view.LayoutInflater
import android.widget.FrameLayout
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.media3.common.Player
import androidx.media3.ui.PlayerView
import com.calvinmazhindu.skitcam.R
import com.calvinmazhindu.skitcam.camera.ViewfinderGeometry
import com.calvinmazhindu.skitcam.recording.Segment
import com.calvinmazhindu.skitcam.ui.theme.SkitCamColors

/**
 * Aspect-fitted Media3 surface. The caller owns the player and all playback policy;
 * this composable only presents frames and an optional overlay inside the exact
 * rendered video bounds.
 */
@androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
@Composable
fun VideoSurface(
    player: Player?,
    aspect: Float?,
    contentDescription: String,
    modifier: Modifier = Modifier,
    fallbackAspect: Float = 9f / 16f,
    overlay: @Composable BoxScope.() -> Unit = {}
) {
    BoxWithConstraints(modifier) {
        val safeAspect = aspect?.takeIf { it.isFinite() && it > 0f } ?: fallbackAspect
        val fitted = ViewfinderGeometry.fit(maxWidth.value, maxHeight.value, safeAspect)
        if (fitted != null) Box(
            Modifier.size(fitted.width.dp, fitted.height.dp)
                .align(Alignment.Center)
                .background(SkitCamColors.Ink)
        ) {
            if (player != null) {
                AndroidView(
                    factory = { context ->
                        (LayoutInflater.from(context)
                            .inflate(R.layout.segment_player, FrameLayout(context), false) as PlayerView).apply {
                            setShowNextButton(false)
                            setShowPreviousButton(false)
                            setShowFastForwardButton(false)
                            setShowRewindButton(false)
                            useController = false
                            this.contentDescription = contentDescription
                            this.player = player
                        }
                    },
                    update = { it.player = player },
                    onRelease = { it.player = null },
                    modifier = Modifier.fillMaxSize()
                )
            }
            overlay()
        }
    }
}

/** Recorded display aspect, preferring inspected media facts over a runtime report. */
fun segmentVideoAspect(segment: Segment, reportedAspect: Float?, fallback: Float): Float =
    segment.media?.takeIf { it.valid() }?.let {
        ViewfinderGeometry.aspect(it.width, it.height, it.rotation)
    } ?: reportedAspect ?: fallback
