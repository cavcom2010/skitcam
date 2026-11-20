package com.calvinmazhindu.skitcam.review

import com.calvinmazhindu.skitcam.trim.TrimRange
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
import androidx.media3.common.VideoSize
import androidx.media3.exoplayer.ExoPlayer
import com.calvinmazhindu.skitcam.camera.ViewfinderGeometry
import com.calvinmazhindu.skitcam.recording.RecordingViewModel
import com.calvinmazhindu.skitcam.recording.Segment
import com.calvinmazhindu.skitcam.recording.SegmentEditResult
import java.io.File

data class ReviewSelection(val sessionId: String, val segmentId: String)

/** Activity-owned UI resources, deliberately separate from the durable recording runtime. Main thread only. */
@androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
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
    var pendingSegmentId by mutableStateOf<String?>(null)
        private set
    var playbackLabel by mutableStateOf("Ready to play")
        private set
    var videoAspect by mutableStateOf<Float?>(null)
        private set
    /** Only cold Review entry needs a loading still; switches reuse the active surface. */
    var showOpeningPoster by mutableStateOf(false)
        private set
    private var generation = 0
    // Player positions are relative to the item actually prepared, not the trim draft.
    private var preparedRange: TrimRange? = null
    private var sourceFile: File? = null
    val sourcePositionMs: Long?
        get() = player?.let { it.currentPosition + (preparedRange?.trimStartMs ?: 0L) }

    fun open(sessionId: String, segment: Segment, fullSource: Boolean = false) {
        val opening = selection == null
        close()
        selection = ReviewSelection(sessionId, segment.id)
        videoAspect = null
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
                    android.util.Log.w("SkitCamPlayback", "Segment playback failed", exception)
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
                override fun onVideoSizeChanged(videoSize: VideoSize) {
                    if (player === current) videoAspect = aspect(videoSize)
                }
                override fun onRenderedFirstFrame() {
                    if (player === current) showOpeningPoster = false
                }
            })
            sourceFile = file
            showOpeningPoster = opening
            preparedRange = if (fullSource) null else segment.trim?.takeIf { it.valid() && it.isTrimmed }
            current.setMediaItem(item(file, preparedRange))
            current.setSeekParameters(androidx.media3.exoplayer.SeekParameters.EXACT)
            current.playWhenReady = false
            current.prepare()
        } catch (_: Exception) {
            error = "Cannot open this segment. The original file has not been changed."
            releasePlayer()
        }
    }
    private fun item(file: File, range: TrimRange?): MediaItem {
        val builder = MediaItem.Builder().setUri(Uri.fromFile(file))
        range?.let { builder.setClippingConfiguration(MediaItem.ClippingConfiguration.Builder()
            .setStartPositionMs(it.trimStartMs).setEndPositionMs(it.trimEndMs).build()) }
        return builder.build()
    }
    /** Keep the frame under the playhead if it is still part of the edited range. */
    fun previewTrim(range: TrimRange, anchorMs: Long?, frameStepMs: Long) {
        val position = sourcePositionMs ?: return
        val target = anchorMs ?: position
        if (target == position && position >= range.trimStartMs && position < range.trimEndMs &&
            preparedRange?.let { position >= it.trimEndMs } != true) return
        seekTrim(range, target, frameStepMs)
    }

    fun seekTrim(range: TrimRange, sourceMs: Long, frameStepMs: Long) {
        val current = player ?: return
        // Seeking to the exclusive end can end the player without rendering a frame.
        val lastFrame = (range.trimEndMs - frameStepMs).coerceAtLeast(range.trimStartMs)
        val target = sourceMs.coerceIn(range.trimStartMs, lastFrame)
        if (current.playbackState == Player.STATE_ENDED) current.pause()
        val prepared = preparedRange
        if (prepared != null && (target < prepared.trimStartMs || target >= prepared.trimEndMs)) {
            // Reset can expose frames outside the previously played clip.
            prepareAt(current, if (current.playWhenReady) range.takeIf { it.isTrimmed } else null, target)
        } else if (target != sourcePositionMs) {
            current.seekTo(target - (prepared?.trimStartMs ?: 0L))
        }
    }

    fun toggleTrimPlayback(range: TrimRange) {
        val current = player ?: return
        val ended = current.playbackState == Player.STATE_ENDED
        if (current.playWhenReady && !ended) {
            current.pause()
            return
        }
        val position = sourcePositionMs ?: range.trimStartMs
        val target = if (ended || position >= range.trimEndMs) range.trimStartMs
            else position.coerceAtLeast(range.trimStartMs)
        val clip = range.takeIf { it.isTrimmed }
        // Enforce the exact boundaries in Media3 before playback can advance.
        if (clip != preparedRange) prepareAt(current, clip, target)
        else if (ended || target != position) current.seekTo(target - (clip?.trimStartMs ?: 0L))
        current.play()
    }

    private fun prepareAt(current: ExoPlayer, range: TrimRange?, sourceMs: Long) {
        val file = sourceFile ?: return
        preparedRange = range
        current.setMediaItem(item(file, range), sourceMs - (range?.trimStartMs ?: 0L))
        current.prepare()
    }
    fun decide(model: RecordingViewModel, kept: Boolean) {
        val selected = selection ?: return
        decideById(model, selected.sessionId, selected.segmentId, kept)
    }

    /** Shared atomic decision path used by both review and direct shelf actions. */
    fun decideById(model: RecordingViewModel, sessionId: String, segmentId: String, kept: Boolean,
        finished: (SegmentEditResult) -> Unit = {}) {
        if (saving) return
        val token = generation
        saving = true
        pendingSegmentId = segmentId
        feedback = null
        model.setSegmentKept(sessionId, segmentId, kept) { result ->
            if (generation == token) {
                saving = false
                pendingSegmentId = null
                feedback = result.message ?: if (kept) "Kept" else "Discarded · original file retained"
                finished(result)
            }
        }
    }
    @Suppress("DEPRECATION")
    private fun aspect(size: VideoSize): Float? {
        if (size.width <= 0 || size.height <= 0) return null
        val raw = size.width.toFloat() * size.pixelWidthHeightRatio / size.height
        val rotation = size.unappliedRotationDegrees
        return if (rotation % 180 == 0) raw else 1f / raw
    }
    private fun releasePlayer() {
        val previous = player
        player = null // Ignore callbacks caused by releasing this instance.
        showOpeningPoster = false
        preparedRange = null
        sourceFile = null
        previous?.release() // Also abandons ExoPlayer's audio focus before CameraX starts.
    }
    fun close() {
        generation++
        selection = null
        releasePlayer()
        error = null
        feedback = null
        saving = false
        pendingSegmentId = null
        playbackLabel = "Ready to play"
        videoAspect = null
    }
}
