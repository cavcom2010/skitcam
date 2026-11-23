package com.calvinmazhindu.skitcam.playback

import android.content.Intent
import android.net.Uri
import android.os.SystemClock
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.calvinmazhindu.skitcam.export.FinishedPlaybackActivity
import com.calvinmazhindu.skitcam.recording.RecordingState
import com.calvinmazhindu.skitcam.recording.Segment
import com.calvinmazhindu.skitcam.recording.Session
import com.calvinmazhindu.skitcam.review.SegmentReviewController
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.util.concurrent.atomic.AtomicReference

/** No camera, real recording or shared storage required; also a candidate for emulator smoke tests. */
@RunWith(AndroidJUnit4::class)
class PlaybackFailureDeviceTest {
    @Test fun corruptFixtureStopsBothPlayersWithoutChangingTheSource() {
        val instrument = InstrumentationRegistry.getInstrumentation()
        val context = instrument.targetContext
        check(context.packageName == "com.calvinmazhindu.skitcam.m15avalidation")
        val fixture = File.createTempFile("private-diagnostic-fixture-", ".mp4", context.cacheDir)
        val contents = "Not a playable MP4".encodeToByteArray()
        val clip = Segment(1, fixture.path, 1000, 0, 0, id = "private-diagnostic-segment")
        val session = Session("private-diagnostic-session", 1, 0, RecordingState.Paused, listOf(clip))
        val skit = SkitPlaybackController(context)
        val review = SegmentReviewController(context)

        fun <T> onMain(read: () -> T): T {
            val value = AtomicReference<Result<T>>()
            instrument.runOnMainSync { value.set(runCatching(read)) }
            return value.get().getOrThrow()
        }
        fun awaitFailure(check: () -> Boolean) {
            val deadline = SystemClock.elapsedRealtime() + 10_000
            while (!onMain(check) && SystemClock.elapsedRealtime() < deadline) Thread.sleep(30)
            assertTrue("Playback error should be visible and its player released", onMain(check))
        }

        try {
            fixture.writeBytes(contents)
            assertTrue(onMain { skit.play(session).ready })
            awaitFailure { skit.active && skit.finished && skit.error != null && skit.player == null }
            assertArrayEquals(contents, fixture.readBytes())

            onMain { review.open(session.id, clip) }
            awaitFailure { review.selection?.segmentId == clip.id && review.error != null && review.player == null }
            assertArrayEquals(contents, fixture.readBytes())
        } finally {
            onMain { skit.close(); review.close() }
            fixture.delete()
        }
    }

    @Test fun unavailableSavedVideoReportsAFailure() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        check(context.packageName == "com.calvinmazhindu.skitcam.m15avalidation")
        val uri = Uri.parse("content://${context.packageName}.absent/private-diagnostic-gallery-id")
        val intent = Intent(context, FinishedPlaybackActivity::class.java).setData(uri)
        ActivityScenario.launch<FinishedPlaybackActivity>(intent).use { scenario ->
            var failed = false
            val deadline = SystemClock.elapsedRealtime() + 10_000
            do {
                scenario.onActivity { failed = it.currentPlayer?.playerError != null }
                if (!failed) Thread.sleep(30)
            } while (!failed && SystemClock.elapsedRealtime() < deadline)
            assertTrue("Unavailable saved video must produce a player error", failed)
        }
    }
}
