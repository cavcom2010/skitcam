package com.calvinmazhindu.skitcam.playback

import androidx.media3.common.Player
import org.junit.Assert.*
import org.junit.Test

class PlaybackObservationTest {
    @Test fun preparingAndPausedReadyAreDistinctFromPlayback() {
        val observation = PlaybackObservation()
        assertEquals("preparation", observation.phase(10, false))
        observation.stateChanged(Player.STATE_BUFFERING)
        assertEquals("preparation", observation.phase(20, true))
        observation.stateChanged(Player.STATE_READY)
        assertEquals("unknown", observation.phase(30, false))
        observation.playing()
        observation.renderedFrame()
        assertEquals("playback", observation.phase(40, true))
        assertEquals("unknown", observation.phase(50, false))
        assertTrue(observation.firstFrameObserved)
    }

    @Test fun seekingIsBoundedAndDoesNotBecomeAStaleDiagnosis() {
        val observation = PlaybackObservation()
        observation.stateChanged(Player.STATE_READY)
        observation.playing()
        observation.seek(100)
        assertEquals("seeking", observation.phase(150, true))
        assertEquals("unknown", observation.phase(2_101, true))
        observation.stateChanged(Player.STATE_BUFFERING)
        observation.stateChanged(Player.STATE_READY)
        assertEquals("playback", observation.phase(2_120, true))
        observation.seek(2_130)
        observation.renderedFrame()
        assertEquals("unknown", observation.phase(2_150, false))
    }

    @Test fun automaticTransitionsPreservePlaybackButPlaylistReplacementStartsPreparation() {
        val observation = PlaybackObservation()
        observation.stateChanged(Player.STATE_READY)
        observation.playing()
        observation.renderedFrame()
        observation.transition(Player.MEDIA_ITEM_TRANSITION_REASON_AUTO)
        observation.stateChanged(Player.STATE_BUFFERING)
        assertEquals("automatic", observation.transition)
        assertFalse(observation.firstFrameObserved)
        assertEquals("playback", observation.phase(50, true))
        observation.seek(100)
        observation.transition(Player.MEDIA_ITEM_TRANSITION_REASON_PLAYLIST_CHANGED)
        assertEquals("playlist_changed", observation.transition)
        assertEquals("preparation", observation.phase(110, false))
    }

    @Test fun retryAndInitialResumePositionStillCountAsPreparation() {
        val observation = PlaybackObservation()
        observation.seek(30)
        observation.stateChanged(Player.STATE_BUFFERING)
        assertEquals("preparation", observation.phase(50, true))
        observation.stateChanged(Player.STATE_READY)
        observation.playing()
        observation.renderedFrame()
        observation.stateChanged(Player.STATE_IDLE)
        observation.stateChanged(Player.STATE_BUFFERING)
        assertEquals("preparation", observation.phase(200, true))
        assertFalse(observation.firstFrameObserved)
    }

    @Test fun missingOrUntrustedCodecDetailsAreOmitted() {
        assertNull(safeDecoderName(null))
        assertNull(safePlaybackMime(null))
        assertEquals("c2.mtk.avc.decoder", safeDecoderName("c2.mtk.avc.decoder"))
        assertEquals("video/avc", safePlaybackMime("video/avc"))
        assertNull(safeDecoderName("file:///private/source.mp4"))
        assertNull(safeDecoderName("decoder\nprivate"))
        assertNull(safePlaybackMime("video/private-source"))
    }
}
