package com.calvinmazhindu.skitcam.review

import android.content.Context
import android.net.Uri
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import com.calvinmazhindu.skitcam.recording.RecordingViewModel
import com.calvinmazhindu.skitcam.recording.Segment
import java.io.File

data class ReviewSelection(val sessionId: String, val segmentId: String)

/** Activity-owned UI resources, deliberately separate from the durable recording runtime. Main thread only. */
class SegmentReviewController(context: Context) {
    private val application = context.applicationContext
    var selection by mutableStateOf<ReviewSelection?>(null)
        private set
    var player by mutableStateOf<ExoPlayer?>(null)
        private set
    var error by mutableStateOf<String?>(null)
        private set
    var feedback by mutableStateOf<String?>(null)
        private set
    var saving by mutableStateOf(false)
        private set
    var playbackLabel by mutableStateOf("Ready to play")
        private set
    private var generation = 0

    fun open(sessionId: String, segment: Segment) {
        close()
        selection = ReviewSelection(sessionId, segment.id)
        val file = File(segment.path)
        if (!file.isFile || !file.canRead() || file.length() == 0L) {
            error = "Video unavailable. The original file is missing or unreadable."
            return
        }
        try {
            val current = ExoPlayer.Builder(application).build()
            player = current
            current.setAudioAttributes(AudioAttributes.Builder().setUsage(C.USAGE_MEDIA)
                .setContentType(C.AUDIO_CONTENT_TYPE_MOVIE).build(), true)
            current.addListener(object : Player.Listener {
                override fun onPlayerError(exception: PlaybackException) {
                    if (player === current) {
                        error = "Cannot play this segment. Its file and Keep/Discard status have not been changed."
                        releasePlayer()
                    }
                }
                override fun onPlaybackStateChanged(state: Int) {
                    if (player === current) playbackLabel = when (state) {
                        Player.STATE_BUFFERING -> "Loading video"
                        Player.STATE_ENDED -> "Finished"
                        else -> if (current.isPlaying) "Playing" else "Ready to play"
                    }
                }
                override fun onIsPlayingChanged(isPlaying: Boolean) {
                    if (player === current) playbackLabel = if (isPlaying) "Playing" else "Playback paused"
                }
            })
            current.setMediaItem(MediaItem.fromUri(Uri.fromFile(file)))
            current.playWhenReady = false
            current.prepare()
        } catch (_: Exception) {
            error = "Cannot open this segment. The original file has not been changed."
            releasePlayer()
        }
    }
    fun decide(model: RecordingViewModel, kept: Boolean) {
        val selected = selection ?: return
        if (saving) return
        val token = generation
        saving = true
        feedback = null
        model.setSegmentKept(selected.sessionId, selected.segmentId, kept) { result ->
            if (generation == token && selection == selected) {
                saving = false
                feedback = result.message ?: if (kept) "Kept" else "Discarded · original file retained"
            }
        }
    }
    private fun releasePlayer() {
        val previous = player
        player = null // Ignore callbacks caused by releasing this instance.
        previous?.release() // Also abandons ExoPlayer's audio focus before CameraX starts.
    }
    fun close() {
        generation++
        selection = null
        releasePlayer()
        error = null
        feedback = null
        saving = false
        playbackLabel = "Ready to play"
    }
}
