package com.calvinmazhindu.skitcam.playback

import android.Manifest
import android.graphics.Color
import android.media.MediaMetadataRetriever
import android.os.Build
import android.os.Bundle
import android.os.ParcelFileDescriptor
import android.os.SystemClock
import android.view.WindowManager
import android.view.accessibility.AccessibilityNodeInfo
import androidx.lifecycle.Lifecycle
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.ExoTimeoutException
import androidx.media3.exoplayer.analytics.AnalyticsListener
import androidx.media3.ui.PlayerView
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.calvinmazhindu.skitcam.MainActivity
import com.calvinmazhindu.skitcam.R
import com.calvinmazhindu.skitcam.export.ExportSpec
import com.calvinmazhindu.skitcam.export.sha
import com.calvinmazhindu.skitcam.export.transform
import com.calvinmazhindu.skitcam.export.verify
import com.calvinmazhindu.skitcam.recording.RecordingState
import com.calvinmazhindu.skitcam.recording.Segment
import com.calvinmazhindu.skitcam.recording.Session
import com.calvinmazhindu.skitcam.recording.SessionFileStore
import com.calvinmazhindu.skitcam.trim.TrimRange
import com.calvinmazhindu.skitcam.video.VideoInspector
import org.json.JSONArray
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.util.UUID
import java.util.concurrent.atomic.AtomicReference
import kotlin.math.abs

private const val FIELD_EMULATOR_PACKAGE = "com.calvinmazhindu.skitcam.m15b1m15b33emu"

/** Reuses the real activity, controllers and surfaces without opening a production session. */
@RunWith(AndroidJUnit4::class)
@androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
class PlaybackReliabilityDeviceTest {
    private val instrument = InstrumentationRegistry.getInstrumentation()
    private val context get() = instrument.targetContext
    private val data get() = File(context.filesDir, "m15b2").apply { mkdirs() }
    private val store get() = SessionFileStore(File(context.filesDir, "sessions"))
    private lateinit var scenario: ActivityScenario<MainActivity>
    private lateinit var activity: MainActivity
    private lateinit var fixture: Session
    private lateinit var hashes: List<String>
    private val runId = UUID.randomUUID().toString()
    private val traces = mutableListOf<Trace>()
    private var phase = ""
    private var blackSamples = 0
    private var transitionSamples = 0
    private var failureCode: Int? = null
    private val playback get() = activity.playback
    private val review get() = activity.reviewController

    @Before fun openFixture() {
        val packageName = context.packageName
        check(packageName.matches(Regex("""com\.calvinmazhindu\.skitcam\.m15b2validation[0-9]*""")) ||
            packageName == FIELD_EMULATOR_PACKAGE && Build.HARDWARE == "ranchu")
        if (packageName == FIELD_EMULATOR_PACKAGE) shell("pm grant $packageName ${Manifest.permission.CAMERA}")
        val id = "a15b2000-0000-4000-8000-000000000001"
        val inputs = listOf("first", "second", "third").map { File(data, "$it.mp4") }
        hashes = inputs.map(::sha)
        val existing = store.loadLatest()
        fixture = if (existing == null) {
            val root = File(store.rootPath, id)
            check(root.mkdirs()) { "Use a fresh validation package; no fixture files will be overwritten." }
            val segments = inputs.mapIndexed { index, input ->
                val file = input.copyTo(File(root, "segment_%03d.mp4".format(index + 1)))
                val facts = VideoInspector.inspect(file.path)
                val duration = facts.trackDurationUs / 1_000
                Segment(index + 1, file.path, duration, 0, facts.rotation, media = facts,
                    trim = if (index == 2) TrimRange(duration, 1_040, 2_400) else null)
            }
            Session(id, System.currentTimeMillis(), 0, RecordingState.Paused, segments).also(store::save)
        } else {
            check(existing.id == id && existing.state == RecordingState.Paused)
            existing
        }
        verifySources()
        scenario = ActivityScenario.launch(MainActivity::class.java)
        scenario.onActivity {
            activity = it
            it.window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        }
        await("fixture_restore") {
            activity.recordingModel.snapshots.value.session?.let {
                it.id == fixture.id && it.state == RecordingState.Paused
            } == true
        }
    }

    @After fun closeFixture() {
        if (::scenario.isInitialized) scenario.close()
        if (::fixture.isInitialized) verifySources()
    }

    @Test fun firstPlaybackInFreshProcess() {
        assertEquals(0, exercise("cold_start", 1) { startSkit() })
    }

    @Test fun fieldEmulatorPlaybackCloseAndScreenReturn() {
        // Do not send screen-off or dismiss-keyguard actions to a physical device.
        check(context.packageName == FIELD_EMULATOR_PACKAGE && Build.HARDWARE == "ranchu")
        val model = activity.recordingModel
        var cycle = 0
        val failures = exercise("playback_close_resume", 10) {
            cycle++
            if (cycle == 4 || cycle == 8) {
                startSkit()
                try {
                    shell("input keyevent KEYCODE_SLEEP")
                    await("screen_off_releases_playback") { main { playback.player == null && !model.cameraReady } }
                } finally {
                    shell("input keyevent KEYCODE_WAKEUP")
                    shell("wm dismiss-keyguard")
                }
                await("fixture_after_screen_wake") {
                    model.snapshots.value.session?.id == fixture.id && model.cameraReady
                }
            }
            val player = startSkit().player
            resetPlayers()
            await("paused_draft_after_close") {
                val snapshot = model.snapshots.value
                snapshot.state == RecordingState.Paused && snapshot.session?.id == fixture.id &&
                    main { playback.player == null && review.player == null && model.cameraReady }
            }
            val restarted = startSkit().player
            assertNotSame(player, restarted)
            resetPlayers()
            verifySources()
        }
        assertEquals("Inspect the emulator playback-close observations for failed cycles", 0, failures)
    }

    @Test fun playbackStress() {
        val iterations = InstrumentationRegistry.getArguments().getString("m15b2Iterations")?.toInt() ?: 30
        require(iterations in 1..30)
        var failures = 0
        failures += exercise("repeated_entry_exit", iterations) { startSkit() }
        failures += exercise("rapid_segment_navigation", iterations) {
            val trace = startSkit()
            main { playback.setPlayWhenReady(false) }
            for (index in listOf(1, 0, 2, 1)) {
                main { playback.seekToSegment(index) }
                SystemClock.sleep(40)
            }
            awaitFrame("Skit playback", Color.BLUE, sampleTransition = true) {
                playback.currentIndex == 1 && !trace.player.playWhenReady &&
                    playback.currentSegmentId == fixture.segments[1].id && playback.player === trace.player
            }
            await("rail_selection") { node("Clip 2")?.stateDescription?.toString() == "Current clip" }
        }
        failures += exercise("forward_backward_seek", iterations) {
            val trace = startSkit()
            main { playback.setPlayWhenReady(false); playback.seekTo(1_400) }
            awaitFrame("Skit playback", Color.GREEN, sampleTransition = true) {
                !trace.player.playWhenReady && abs(trace.player.currentPosition - 1_400) <= 40
            }
            main { playback.seekTo(200) }
            awaitFrame("Skit playback", Color.RED, sampleTransition = true) {
                !trace.player.playWhenReady && abs(trace.player.currentPosition - 200) <= 40
            }
            main { playback.setPlayWhenReady(true); playback.seekTo(1_300) }
            awaitFrame("Skit playback", Color.GREEN, sampleTransition = true) {
                trace.player.playWhenReady && trace.player.isPlaying
            }
        }
        failures += exercise("immediate_startup_seek", iterations) {
            startupSeek(waitForSurface = false)
        }
        failures += exercise("review_switching", iterations) {
            val first = startReview(0)
            val view = main { videoView() }
            val second = startReview(1)
            assertNotSame(first.player, second.player)
            assertSame(view, main { videoView() })
            startReview(0)
            assertSame(view, main { videoView() })
        }
        failures += exercise("pause_resume", iterations) {
            val trace = startSkit()
            main { playback.setPlayWhenReady(false) }
            await("paused") { main { !trace.player.isPlaying && !trace.player.playWhenReady } }
            val position = main { trace.player.currentPosition }
            SystemClock.sleep(100)
            assertTrue(abs(main { trace.player.currentPosition } - position) <= 40)
            main { playback.setPlayWhenReady(true) }
            await("resumed") { main { trace.player.isPlaying && trace.player.currentPosition > position } }
        }
        failures += exercise("loop_and_replay", iterations) {
            val trace = startSkit()
            main { trace.player.seekTo(2, 0) }
            awaitFrame("Skit playback", Color.CYAN, sampleTransition = true) {
                playback.currentIndex == 2 && trace.player.isPlaying
            }
            awaitFrame("Skit playback", Color.RED, sampleTransition = true) {
                playback.currentIndex == 0 && trace.player.isPlaying
            }
            main { playback.toggleLoop(); trace.player.seekTo(2, 1_000) }
            await("finished") { main { playback.finished } }
            click("Replay")
            awaitFrame("Skit playback", Color.RED) {
                playback.currentIndex == 0 && trace.player.isPlaying && trace.player === playback.player
            }
        }
        failures += exercise("review_to_skit", iterations) {
            startReview(0)
            // Start in the same main-loop turn to maximize overlap pressure.
            startSkit { closeReview() }
        }
        failures += exercise("background_return", iterations) {
            startSkit()
            scenario.moveToState(Lifecycle.State.CREATED)
            assertTrue(main { !playback.active && playback.player == null && review.player == null })
            scenario.moveToState(Lifecycle.State.RESUMED)
            startSkit()
        }
        assertEquals("See m15b2 attempts for individual failures", 0, failures)
    }

    @Test fun startupSeekTimingProbe() {
        val iterations = InstrumentationRegistry.getArguments().getString("m15b2Iterations")?.toInt() ?: 30
        require(iterations in 1..30)
        var failures = 0
        failures += exercise("probe_same_turn", iterations) { startupSeek(waitForSurface = false) }
        failures += exercise("probe_after_surface", iterations) { startupSeek(waitForSurface = true) }
        assertEquals("See m15b2 attempts for individual failures", 0, failures)
    }

    @Test fun trimPreviewAndPrivateExport() {
        startReview(2, Color.CYAN)
        await("trim_ready") { main { activity.trimModel.ready } }
        val range = main { checkNotNull(activity.trimModel.draft) }
        assertEquals(1_040L, range.trimStartMs)
        val trace = traces.single()
        main { review.seekTrim(range, 2_200, 40) }
        awaitFrame("Segment 3 playback", Color.CYAN) {
            review.player === trace.player && !trace.player.playWhenReady &&
                abs((review.sourcePositionMs ?: 0L) - 2_200) <= 40
        }
        main { review.toggleTrimPlayback(range) }
        await("trim_ended") { main { trace.player.playbackState == Player.STATE_ENDED } }
        assertEquals(range.effectiveDurationMs, main { trace.player.duration })
        resetPlayers()
        val output = File(data, "export-$runId.mp4")
        val spec = ExportSpec.from(fixture.copy(state = RecordingState.Completed)) { segment ->
            store.sourceFacts(segment.path).durationMs
        }
        transform(context, spec, output)
        val result = verify(output, fixture.segments.sumOf { it.displayDurationMs })
        val tracks = result.getJSONObject("timing").getJSONArray("tracks")
        assertEquals(2, tracks.length())
        val retriever = MediaMetadataRetriever()
        try {
            retriever.setDataSource(output.path)
            val second = fixture.segments[0].displayDurationMs
            val third = second + fixture.segments[1].displayDurationMs
            for ((position, color) in listOf(200L to Color.RED, second + 200 to Color.BLUE, third + 200 to Color.CYAN)) {
                val frame = checkNotNull(retriever.getFrameAtTime(position * 1_000, MediaMetadataRetriever.OPTION_CLOSEST))
                try {
                    assertTrue("Expected clip colour at $position ms",
                        matchesColor(frame.getPixel(frame.width / 2, frame.height / 2), color))
                } finally { frame.recycle() }
            }
        } finally { retriever.release() }
        instrument.sendStatus(2, Bundle().apply { putString("m15b2Export", result.getJSONObject("timing").toString()) })
    }

    private fun verifySources() {
        assertEquals(hashes, fixture.segments.map { sha(File(it.path)) })
        assertEquals(fixture, store.loadLatest())
    }

    private fun exercise(label: String, iterations: Int, action: () -> Unit): Int {
        var failures = 0
        repeat(iterations) { index ->
            traces.clear()
            phase = "entry"
            blackSamples = 0
            transitionSamples = 0
            failureCode = null
            var failure: String? = null
            var failureState: JSONObject? = null
            try { action() }
            catch (error: AssertionError) {
                failure = phase
                failureState = main { traces.lastOrNull()?.failureState() }
            }
            finally {
                resetPlayers()
                val players = main {
                    if (failure == null && traces.any { it.errorCode != null }) failure = "cleanup"
                    traces.map { it.toJson() }
                }
                val row = JSONObject().put("run", runId).put("scenario", label)
                    .put("attempt", index + 1).put("passed", failure == null)
                    .put("failure_step", failure).put("media3_error_code", failureCode)
                    .put("before_cleanup", failureState)
                    .put("black_samples", blackSamples).put("transition_samples", transitionSamples)
                    .put("players", JSONArray(players))
                File(data, "attempts.jsonl").appendText("$row\n")
            }
            if (failure != null) failures++
        }
        instrument.sendStatus(2, Bundle().apply { putString("m15b2Result", "$label: $failures/$iterations failed") })
        return failures
    }

    private fun startSkit(before: () -> Unit = {}): Trace = openSkit(before = before).also { trace ->
        awaitFrame("Skit playback", Color.RED) {
            trace.player.isPlaying && trace.frames > 0 && playback.currentIndex == 0
        }
    }

    private fun startupSeek(waitForSurface: Boolean) {
        fun seek(trace: Trace) {
            trace.markSeek()
            playback.seekTo(1_200)
        }
        val trace = openSkit { if (!waitForSurface) seek(it) }
        if (waitForSurface) {
            await("surface_before_seek") { main { trace.surfaceObserved } }
            main { seek(trace) }
        }
        awaitFrame("Skit playback", Color.GREEN) {
            trace.player.isPlaying && trace.frames > 0 && playback.currentIndex == 0
        }
    }

    private fun openSkit(before: () -> Unit = {}, after: (Trace) -> Unit = {}): Trace = main {
        before()
        val started = SystemClock.elapsedRealtime()
        assertTrue(playback.play(fixture).ready)
        trace("skit", playback.player, started).also(after)
    }

    private fun startReview(index: Int, colour: Int = listOf(Color.RED, Color.BLUE, Color.CYAN)[index]): Trace {
        val segment = fixture.segments[index]
        val trace = main {
            val previous = traces.lastOrNull { it.player === review.player }
            val started = SystemClock.elapsedRealtime()
            activity.trimModel.open(fixture, segment)
            bracketRelease(previous) { review.open(fixture.id, segment, fullSource = true) }
            trace("review", review.player, started)
        }
        awaitFrame("Segment ${segment.order} playback", colour) {
            trace.frames > 0 && !trace.player.playWhenReady &&
                (segment.trim == null || activity.trimModel.ready)
        }
        return trace
    }

    private fun trace(screen: String, player: ExoPlayer?, started: Long): Trace {
        assertNotNull("Player should be allocated", player)
        return Trace(screen, checkNotNull(player), started).also {
            traces += it
            player.addListener(it)
            player.addAnalyticsListener(it)
        }
    }

    private fun resetPlayers() = main {
        bracketRelease(traces.lastOrNull { it.player === playback.player }, playback::close)
        closeReview()
    }

    private fun closeReview() {
        activity.trimModel.close()
        bracketRelease(traces.lastOrNull { it.player === review.player }, review::close)
    }

    private fun bracketRelease(trace: Trace?, action: () -> Unit) {
        trace?.closeStarted()
        try { action() } finally { trace?.closeReturned() }
    }

    private fun videoView(): PlayerView? = activity.findViewById(R.id.segment_player)

    private fun awaitFrame(label: String, color: Int, sampleTransition: Boolean = false, ready: () -> Boolean) {
        await("rendered_${label.substringBefore(' ').lowercase()}") {
            val frame = main {
                val view = videoView() ?: return@main null
                if (view.contentDescription?.toString() != label || view.width < 1 || view.height < 1) return@main null
                val xy = IntArray(2).also(view::getLocationOnScreen)
                Triple(xy[0] + view.width / 2, xy[1] + view.height / 2, ready())
            } ?: return@await false
            if (!frame.third && !sampleTransition) return@await false
            val image = instrument.uiAutomation.takeScreenshot() ?: return@await false
            val pixel = try {
                if (frame.first !in 0 until image.width || frame.second !in 0 until image.height) return@await false
                image.getPixel(frame.first, frame.second)
            } finally { image.recycle() }
            if (sampleTransition) {
                transitionSamples++
                if (Color.red(pixel) < 35 && Color.green(pixel) < 35 && Color.blue(pixel) < 35) blackSamples++
            }
            frame.third && matchesColor(pixel, color)
        }
    }

    private fun matchesColor(pixel: Int, expected: Int): Boolean =
        abs(Color.red(pixel) - Color.red(expected)) <= 45 &&
            abs(Color.green(pixel) - Color.green(expected)) <= 45 &&
            abs(Color.blue(pixel) - Color.blue(expected)) <= 45

    private fun await(label: String, ready: () -> Boolean) {
        phase = label
        val deadline = SystemClock.elapsedRealtime() + 4_000
        do {
            val error = main {
                traces.lastOrNull { it.errorCode != null }?.errorCode.also { failureCode = it }
                playback.error != null || review.error != null || failureCode != null
            }
            assertFalse("Unexpected playback error (see SkitCamPlayback)", error)
            if (ready()) return
            SystemClock.sleep(25)
        } while (SystemClock.elapsedRealtime() < deadline)
        fail("Timed out: $label")
    }

    private fun click(label: String) {
        await("control_available") { node(label)?.isEnabled == true }
        assertTrue(checkNotNull(node(label)).performAction(AccessibilityNodeInfo.ACTION_CLICK))
    }

    private fun node(label: String): AccessibilityNodeInfo? {
        fun search(node: AccessibilityNodeInfo): AccessibilityNodeInfo? {
            if (node.isVisibleToUser && node.contentDescription?.toString() == label) return node
            return (0 until node.childCount).firstNotNullOfOrNull { node.getChild(it)?.let(::search) }
        }
        if (android.os.Build.VERSION.SDK_INT >= 33) instrument.uiAutomation.clearCache()
        return instrument.uiAutomation.rootInActiveWindow?.let(::search)
    }

    private fun shell(command: String) {
        ParcelFileDescriptor.AutoCloseInputStream(instrument.uiAutomation.executeShellCommand(command)).use {
            it.readBytes()
        }
    }

    private fun <T> main(block: () -> T): T {
        val value = AtomicReference<Result<T>>()
        instrument.runOnMainSync { value.set(runCatching(block)) }
        return value.get().getOrThrow()
    }

    private class Trace(val screen: String, val player: ExoPlayer, private val started: Long) :
        Player.Listener, AnalyticsListener {
        private val initialState = player.playbackState
        private var bufferingMs = if (initialState == Player.STATE_BUFFERING) elapsed() else null
        private var surfaceMs: Long? = null
        val surfaceObserved get() = surfaceMs != null
        private var decoderMs: Long? = null
        private var decoderName: String? = null
        private var firstFrameMs: Long? = null
        private var seekMs: Long? = null
        private var closeStartedMs: Long? = null
        private var closeReturnedMs: Long? = null
        private var decoderReleasedMs: Long? = null
        var frames = 0
            private set
        var errorCode: Int? = null
            private set
        private var errorMs: Long? = null
        private var timeoutOperation: Int? = null
        override fun onPlaybackStateChanged(playbackState: Int) {
            if (playbackState == Player.STATE_BUFFERING && bufferingMs == null) bufferingMs = elapsed()
        }
        override fun onSurfaceSizeChanged(width: Int, height: Int) {
            if (width > 0 && height > 0 && surfaceMs == null) surfaceMs = elapsed()
        }
        override fun onVideoDecoderInitialized(eventTime: AnalyticsListener.EventTime, decoderName: String,
            initializedTimestampMs: Long, initializationDurationMs: Long) {
            if (decoderMs != null) return
            decoderMs = initializedTimestampMs - started
            this.decoderName = safeDecoderName(decoderName)
        }
        override fun onVideoDecoderReleased(eventTime: AnalyticsListener.EventTime, decoderName: String) {
            decoderReleasedMs = eventTime.realtimeMs - started
        }
        override fun onRenderedFirstFrame() {
            frames++
            if (firstFrameMs == null) firstFrameMs = elapsed()
        }
        override fun onPlayerError(error: PlaybackException) {
            if (errorCode == null) {
                errorCode = error.errorCode
                errorMs = elapsed()
                timeoutOperation = (error.cause as? ExoTimeoutException)?.timeoutOperation
            }
        }
        fun markSeek() { seekMs = elapsed() }
        fun closeStarted() { closeStartedMs = elapsed() }
        fun closeReturned() { closeReturnedMs = elapsed() }
        private fun elapsed() = SystemClock.elapsedRealtime() - started
        fun failureState() = JSONObject().put("player_state", player.playbackState)
            .put("position_ms", player.currentPosition).put("surface_observed", surfaceObserved)
            .put("first_frame_observed", frames > 0)

        fun toJson() = JSONObject().put("screen", screen).put("initial_state", initialState)
            .put("buffering_ms", bufferingMs)
            .put("surface_ms", surfaceMs).put("decoder_ms", decoderMs).put("decoder", decoderName)
            .put("first_frame_ms", firstFrameMs).put("seek_ms", seekMs)
            .put("error_code", errorCode).put("error_ms", errorMs).put("timeout_operation", timeoutOperation)
            .put("close_started_ms", closeStartedMs).put("close_returned_ms", closeReturnedMs)
            .put("decoder_released_ms", decoderReleasedMs)
    }
}
