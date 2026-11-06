package com.calvinmazhindu.skitcam.playback

import android.content.Context
import android.net.Uri
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.SeekParameters
import com.calvinmazhindu.skitcam.recording.Session
import java.io.File

/**
 * Activity-owned continuous skit playback. It never modifies session metadata or source files,
 * and it releases its single ExoPlayer before recording is allowed to resume.
 */
@androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
class SkitPlaybackController(context: Context) {
    private val application = context.applicationContext
    var active by mutableStateOf(false)
        private set
    var plan by mutableStateOf<SkitPlaybackPlan?>(null)
        private set
    var player by mutableStateOf<ExoPlayer?>(null)
        private set
    var loop by mutableStateOf(true)
        private set
    var currentIndex by mutableIntStateOf(0)
        private set
    var currentSegmentId by mutableStateOf<String?>(null)
        private set
    var finished by mutableStateOf(false)
        private set
    var error by mutableStateOf<String?>(null)
        private set
    val positionMs: Long get() = player?.currentPosition?.coerceAtLeast(0) ?: 0
    val durationMs: Long get() = player?.duration?.takeIf { it > 0 } ?: 0

    fun play(session: Session, anchorId: String? = null, loop: Boolean = true): PlaybackPlanResult {
        close()
        this.loop = loop
        val result = SkitPlaybackPlanFactory.from(session, anchorId)
        val next = result.plan
        if (next == null) {
            active = true
            error = result.error
            return result
        }
        plan = next
        currentIndex = 0
        currentSegmentId = next.clips.first().segmentId
        finished = false
        val missing = next.clips.firstOrNull { !readable(it.path) }
        if (missing != null) {
            active = true
            error = "Segment ${missing.order} is missing or unreadable. Playback was not started. The skit and original files were not changed."
            return result
        }
        val current = ExoPlayer.Builder(application).build()
        player = current
        current.setAudioAttributes(AudioAttributes.Builder()
            .setUsage(C.USAGE_MEDIA)
            .setContentType(C.AUDIO_CONTENT_TYPE_MOVIE)
            .build(), true)
        current.setSeekParameters(SeekParameters.EXACT)
        current.repeatMode = if (loop) Player.REPEAT_MODE_ALL else Player.REPEAT_MODE_OFF
        current.addListener(object : Player.Listener {
            override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) {
                val currentPlan = plan ?: return
                val id = mediaItem?.mediaId ?: return
                val index = currentPlan.clips.indexOfFirst { it.segmentId == id }
                if (index >= 0) {
                    currentIndex = index
                    currentSegmentId = id
                }
                finished = false
            }

            override fun onPlaybackStateChanged(playbackState: Int) {
                if (playbackState == Player.STATE_ENDED) finished = true
            }

            override fun onPlayerError(playbackException: PlaybackException) {
                if (player !== current) return
                android.util.Log.w("SkitCamPlayback", "Skit playlist playback failed", playbackException)
                releasePlayer()
                finished = true
                error = "Playback stopped because a clip could not be played. The skit and original files were not changed."
            }
        })
        active = true
        current.setMediaItems(next.clips.map(::mediaItem), 0, 0)
        current.prepare()
        current.playWhenReady = true
        return result
    }

    fun toggleLoop() {
        loop = !loop
        player?.repeatMode = if (loop) Player.REPEAT_MODE_ALL else Player.REPEAT_MODE_OFF
        if (loop && finished && player != null) {
            finished = false
            player?.seekTo(0, 0)
            player?.play()
        }
    }

    fun replay() {
        val current = player ?: return
        finished = false
        current.seekTo(0, 0)
        current.play()
    }

    fun setPlayWhenReady(play: Boolean) { player?.playWhenReady = play }

    fun seekTo(positionMs: Long) { player?.seekTo(positionMs.coerceAtLeast(0)) }

    /** Stops and releases playback but leaves the error/notice state visible for the UI. */
    fun stopWithNotice(message: String) {
        if (error != null) return
        releasePlayer()
        finished = true
        error = message
    }

    /** A confirmed session no longer matches the playlist; never keep stale source references. */
    fun validate(session: Session?) {
        val currentPlan = plan ?: return
        if (!active) return
        if (session == null || session.id != currentPlan.sessionId) {
            stopWithNotice("The recording session changed. Playback stopped.")
            return
        }
        val result = SkitPlaybackPlanFactory.from(session, currentPlan.anchorId)
        val replacement = result.plan
        if (replacement == null) {
            stopWithNotice(result.error ?: "The skit changed. Playback stopped.")
        } else if (replacement != currentPlan) {
            stopWithNotice("The skit changed. Playback stopped. Start it again to use the new edit.")
        }
    }

    fun close() {
        active = false
        plan = null
        currentIndex = 0
        currentSegmentId = null
        finished = false
        error = null
        releasePlayer()
    }

    private fun mediaItem(clip: PlaybackClip): MediaItem {
        val builder = MediaItem.Builder()
            .setUri(Uri.fromFile(File(clip.path)))
            .setMediaId(clip.segmentId)
        clip.range?.let {
            builder.setClippingConfiguration(MediaItem.ClippingConfiguration.Builder()
                .setStartPositionMs(it.trimStartMs)
                .setEndPositionMs(it.trimEndMs)
                .build())
        }
        return builder.build()
    }

    private fun readable(path: String) = File(path).let { it.isFile && it.canRead() && it.length() > 0 }

    private fun releasePlayer() {
        val previous = player
        player = null
        previous?.release()
    }
}
