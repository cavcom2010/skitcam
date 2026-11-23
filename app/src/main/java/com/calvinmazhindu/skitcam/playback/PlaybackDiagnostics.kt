package com.calvinmazhindu.skitcam.playback

import android.media.MediaCodec
import android.os.Build
import android.os.SystemClock
import android.util.Log
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaLibraryInfo
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlaybackException
import androidx.media3.exoplayer.mediacodec.MediaCodecDecoderException
import androidx.media3.exoplayer.mediacodec.MediaCodecRenderer.DecoderInitializationException
import org.json.JSONObject

internal enum class PlaybackScreen(val label: String) { SKIT("skit"), REVIEW("review"), SAVED("saved") }

/** Read-only per-player observations. No media items, files or throwables are retained. */
internal class PlaybackObservation {
    var state: Int = Player.STATE_IDLE
        private set
    var transition: String? = null
        private set
    var firstFrameObserved = false
        private set
    var surfaceAvailable: Boolean? = null
    private var preparing = true
    private var hasPlayed = false
    private var soughtAtMs: Long? = null
    private var seekBuffered = false

    fun transition(reason: Int) {
        transition = when (reason) {
            Player.MEDIA_ITEM_TRANSITION_REASON_PLAYLIST_CHANGED -> "playlist_changed"
            Player.MEDIA_ITEM_TRANSITION_REASON_AUTO -> "automatic"
            Player.MEDIA_ITEM_TRANSITION_REASON_SEEK -> "seek"
            Player.MEDIA_ITEM_TRANSITION_REASON_REPEAT -> "repeat"
            else -> "unknown"
        }
        firstFrameObserved = false
        if (reason == Player.MEDIA_ITEM_TRANSITION_REASON_PLAYLIST_CHANGED) beginPreparation()
    }

    fun seek(atMs: Long) {
        soughtAtMs = atMs
        seekBuffered = false
    }

    fun stateChanged(state: Int) {
        if (this.state == Player.STATE_IDLE && state == Player.STATE_BUFFERING) beginPreparation()
        this.state = state
        if (state == Player.STATE_BUFFERING && soughtAtMs != null) seekBuffered = true
        if (state == Player.STATE_READY) {
            preparing = false
            if (seekBuffered) clearSeek()
        }
    }

    fun playing() {
        hasPlayed = true
        preparing = false
        clearSeek()
    }

    fun renderedFrame() {
        firstFrameObserved = true
        preparing = false
        clearSeek()
    }

    fun phase(nowMs: Long, playWhenReady: Boolean): String {
        val seek = soughtAtMs
        return when {
            preparing -> "preparation"
            seek != null -> if (nowMs - seek in 0L..2_000L) "seeking" else "unknown"
            hasPlayed && playWhenReady && (state == Player.STATE_READY || state == Player.STATE_BUFFERING) -> "playback"
            else -> "unknown"
        }
    }

    private fun beginPreparation() {
        preparing = true
        hasPlayed = false
        firstFrameObserved = false
        clearSeek()
    }

    private fun clearSeek() {
        soughtAtMs = null
        seekBuffered = false
    }
}

/** Report only allowlisted fields to local Logcat, never an exception message or stack trace. */
@androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
internal class PlaybackDiagnostics(private val screen: PlaybackScreen) : Player.Listener {
    private val observation = PlaybackObservation()

    override fun onPlaybackStateChanged(playbackState: Int) = observation.stateChanged(playbackState)
    override fun onIsPlayingChanged(isPlaying: Boolean) {
        if (isPlaying) observation.playing()
    }
    override fun onRenderedFirstFrame() = observation.renderedFrame()
    override fun onSurfaceSizeChanged(width: Int, height: Int) {
        observation.surfaceAvailable = width > 0 && height > 0
    }
    override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) = observation.transition(reason)

    override fun onPositionDiscontinuity(oldPosition: Player.PositionInfo, newPosition: Player.PositionInfo, reason: Int) {
        if (reason == Player.DISCONTINUITY_REASON_SEEK || reason == Player.DISCONTINUITY_REASON_SEEK_ADJUSTMENT) {
            observation.seek(SystemClock.elapsedRealtime())
        }
    }

    fun report(player: Player?, failure: Throwable) {
        val error = failure as? PlaybackException
        val rendererFailure = failure as? ExoPlaybackException
        val causes = generateSequence(failure) { it.cause }.take(8).toList()
        val decoder = causes.filterIsInstance<MediaCodecDecoderException>().firstOrNull()
        val initialization = causes.filterIsInstance<DecoderInitializationException>().firstOrNull()
        val platformCodecError = causes.filterIsInstance<MediaCodec.CodecException>().firstOrNull()
        val codec = decoder?.codecInfo ?: initialization?.codecInfo
        val clipCount = player?.mediaItemCount ?: 0
        val clipIndex = player?.currentMediaItemIndex?.takeIf { it in 0 until clipCount }
        val playWhenReady = player?.playWhenReady ?: false
        val event = JSONObject()
            .put("event", "playback_failure")
            .put("screen", screen.label)
            .put("sdk", Build.VERSION.SDK_INT)
            .put("android_release", Build.VERSION.RELEASE?.take(32))
            .put("device_model", Build.MODEL?.take(80))
            .put("media3", MediaLibraryInfo.VERSION)
            .put("error_code", error?.errorCode)
            .put("error_name", error?.errorCodeName)
            .put("phase", observation.phase(SystemClock.elapsedRealtime(), playWhenReady))
            .put("last_player_state", stateLabel(observation.state))
            .put("play_when_ready", playWhenReady)
            .put("clip_number", clipIndex?.plus(1))
            .put("clip_count", clipCount)
            .put("position_ms", player?.currentPosition?.takeIf { clipIndex != null && it >= 0 })
            .put("last_transition", observation.transition)
            .put("surface_available", observation.surfaceAvailable)
            .put("first_frame_observed", observation.firstFrameObserved)
            .put("decoder_name", safeDecoderName(codec?.name))
            .put("sample_mime_type", safePlaybackMime(codec?.mimeType ?: initialization?.mimeType
                ?: rendererFailure?.rendererFormat?.sampleMimeType))
            .put("hardware_decoder", codec?.hardwareAccelerated)
            .put("codec_error_code", platformCodecError?.errorCode ?: decoder?.errorCode)
        Log.w("SkitCamPlayback", event.toString())
    }
}

private fun stateLabel(state: Int): String = when (state) {
    Player.STATE_IDLE -> "idle"
    Player.STATE_BUFFERING -> "buffering"
    Player.STATE_READY -> "ready"
    Player.STATE_ENDED -> "ended"
    else -> "unknown"
}

private val decoderName = Regex("[a-zA-Z0-9_.-]{1,96}")
internal fun safeDecoderName(name: String?) = name?.takeIf(decoderName::matches)

private val playbackMimeTypes = setOf(
    "video/avc", "video/hevc", "video/av01", "video/x-vnd.on2.vp8", "video/x-vnd.on2.vp9",
    "video/mp4v-es", "audio/mp4a-latm", "audio/opus", "audio/vorbis", "audio/mpeg", "audio/raw"
)
internal fun safePlaybackMime(mime: String?) = mime?.takeIf { it in playbackMimeTypes }
