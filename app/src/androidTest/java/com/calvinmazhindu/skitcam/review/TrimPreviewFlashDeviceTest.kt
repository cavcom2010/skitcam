package com.calvinmazhindu.skitcam.review

import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.Rect
import android.os.SystemClock
import android.util.Log
import android.view.InputDevice
import android.view.MotionEvent
import android.view.View
import android.view.ViewTreeObserver
import android.view.accessibility.AccessibilityNodeInfo
import androidx.media3.common.C
import androidx.media3.common.Player
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
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.util.UUID
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference
import kotlin.concurrent.thread
import kotlin.math.abs

/**
 * Run only in a disposable validation installation with tools/trim_flash_fixtures.sh
 * outputs staged in files/trim-flash-inputs. Run each method in a fresh instrumentation
 * invocation so the recording runtime restores that method's fixture. No MediaStore publication.
 */
@RunWith(AndroidJUnit4::class)
@androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
class TrimPreviewFlashDeviceTest {
    private val instrument = InstrumentationRegistry.getInstrumentation()
    private val context get() = instrument.targetContext
    private val store get() = SessionFileStore(File(context.filesDir, "sessions"))
    private lateinit var scenario: ActivityScenario<MainActivity>
    private lateinit var activity: MainActivity
    private lateinit var fixture: Session
    private val trim get() = activity.trimModel
    private val player get() = activity.reviewController.player!!
    private val playerView get() = activity.findViewById<PlayerView>(R.id.segment_player)
    private val shutter get() = playerView.findViewById<View>(androidx.media3.ui.R.id.exo_shutter)

    private fun <T> main(block: () -> T): T {
        val result = AtomicReference<Result<T>>()
        instrument.runOnMainSync { result.set(runCatching(block)) }
        return result.get().getOrThrow()
    }

    @Before fun open() {
        check(context.packageName in setOf("com.calvinmazhindu.skitcam.trimflash",
            "com.calvinmazhindu.skitcam.trimflashbaseline",
            "com.calvinmazhindu.skitcam.reviewcheck")) { "Trim flash validation package only." }
        val id = UUID.randomUUID().toString()
        val root = File(context.filesDir, "sessions/$id").apply { mkdirs() }
        val segments = listOf("first", "second").mapIndexed { index, name ->
            val source = File(context.filesDir, "trim-flash-inputs/$name.mp4")
            check(source.isFile) { "Stage the generated trim flash fixtures first." }
            val file = source.copyTo(File(root, "segment_%03d.mp4".format(index + 1)))
            val media = VideoInspector.inspect(file.path)
            Segment(index + 1, file.path, 4000, 0, media.rotation, media = media,
                trim = if (index == 0) TrimRange.full(4000) else TrimRange(4000, 1040, 3400))
        }
        fixture = Session(id, System.currentTimeMillis(), 0, RecordingState.Paused, segments)
        store.save(fixture)
        scenario = ActivityScenario.launch(MainActivity::class.java)
        scenario.onActivity { activity = it }
        await("paused fixture") {
            val snapshot = activity.recordingModel.snapshots.value
            snapshot.session?.id == id && snapshot.state == RecordingState.Paused
        }
    }

    @After fun close() {
        if (::scenario.isInitialized) scenario.close()
        // Fixtures are synthetic; a leftover paused legacy session would block the next
        // launch's capture profile, so remove this test's session and outputs afterward.
        // The staged inputs stay so both methods can run in one invocation.
        if (::fixture.isInitialized) {
            File(context.filesDir, "sessions/${fixture.id}").deleteRecursively()
            File(context.filesDir, "trim-flash-export-${fixture.id}.mp4").delete()
        }
        File(context.filesDir, "trim-flash-taps.txt").delete()
        File(context.filesDir, "trim-flash-export.txt").delete()
        File(context.filesDir, "trim-flash-after-drag.png").delete()
        File(context.filesDir, "trim-flash-after-switch.png").delete()
    }

    @Test fun repeatedTapsKeepPresentedFrameAndDoNotReprepare() {
        openSegment(1)
        expectColour("initial Review", Color.RED)
        assertFalse(main { activity.reviewController.showOpeningPoster })
        val preview = bounds("Segment 1 playback")
        Log.i("TrimFlashValidation", "Preview bounds: $preview; sample=${preview.centerX()},${preview.centerY()}")
        val initialEnd = main { player.currentMediaItem!!.clippingConfiguration.endPositionMs }
        seek(1400)
        expectColour("playhead in green", Color.GREEN)
        var resets = 0
        var coveredDraws = 0
        var draws = 0
        val listener = object : Player.Listener {
            override fun onTimelineChanged(timeline: androidx.media3.common.Timeline, reason: Int) {
                if (reason == Player.TIMELINE_CHANGE_REASON_PLAYLIST_CHANGED) resets++
            }
        }
        val drawListener = ViewTreeObserver.OnDrawListener {
            draws++
            if (shutter.isShown) coveredDraws++
        }
        main {
            player.addListener(listener)
            playerView.viewTreeObserver.addOnDrawListener(drawListener)
        }
        val tapping = AtomicBoolean(true)
        var samples = 0
        var offColourSamples = 0
        val sampler = thread(name = "Review screen sampler") {
            while (tapping.get()) {
                val image = instrument.uiAutomation.takeScreenshot() ?: continue
                try {
                    samples++
                    if (!matches(image.getPixel(preview.centerX(), preview.centerY()), Color.GREEN)) offColourSamples++
                } finally { image.recycle() }
            }
        }
        try {
            for (edge in listOf("start", "end")) repeat(5) {
                val old = main { trim.draft!! }
                tap("Trim $edge handle")
                await("one-frame $edge step") { main {
                    val next = trim.draft!!
                    if (edge == "start") next.trimStartMs == old.trimStartMs + 40
                    else next.trimEndMs == old.trimEndMs - 40
                } }
                expectColour("$edge step retains the presented frame", Color.GREEN)
            }
        } finally {
            tapping.set(false)
            sampler.join()
            main {
                player.removeListener(listener)
                playerView.viewTreeObserver.removeOnDrawListener(drawListener)
            }
        }
        val report = "Taps: $resets playlist resets, $coveredDraws/$draws draws with shutter, " +
            "$offColourSamples/$samples composited samples not green"
        Log.i("TrimFlashValidation", report)
        File(context.filesDir, "trim-flash-taps.txt").writeText(report)
        assertTrue("Observed draws", draws > 0)
        assertEquals("Full-source Review needs no clip replacement", C.TIME_END_OF_SOURCE, initialEnd)
        assertEquals("No media replacement for an in-range paused playhead", 0, resets)
        assertEquals("Never draw the shutter over the displayed preview during a tap", 0, coveredDraws)
        assertTrue("Observed composited output", samples > 0)
        assertEquals("No flash or unexpected frame in captured output", 0, offColourSamples)
        expectPosition(1400)
    }

    @Test fun dragPlaybackSaveResetSwitchAndExport() {
        val hashes = fixture.segments.map { sha(File(it.path)) }
        openSegment(1)
        val initialView = main { playerView }
        tap("Trim start handle")
        await("start advances one frame") { main { trim.draft!!.trimStartMs == 40L } }
        expectPosition(40)
        expectColour("inward start remains visible without Play", Color.RED)

        drag("Trim start handle", 1280)
        val range = main { trim.draft!! }
        assertTrue("Drag start into the green interval: $range", range.trimStartMs in 1040..1520)
        expectPosition(range.trimStartMs)
        expectColour("drag settles at the new first frame", Color.GREEN)

        // Scrub far enough from the end grip's 48dp hit region to hit the track.
        seek(3200)
        expectColour("scrub forward", Color.YELLOW)
        drag("Trim end handle", 2760)
        val edited = main { trim.draft!! }
        assertTrue("Drag end into the blue interval: $edited", edited.trimEndMs in 2520..2960)
        expectPosition(edited.trimEndMs - 40)
        expectColour("drag settles at the new last frame", Color.BLUE, "after-drag")

        click("Jump to segment start")
        expectPosition(edited.trimStartMs)
        expectColour("jump to clipped start", Color.GREEN)
        click("Play")
        await("bounded playback with selected audio") { main {
            player.isPlaying && player.currentTracks.isTypeSelected(C.TRACK_TYPE_AUDIO) &&
                player.duration == edited.effectiveDurationMs
        } }
        await("playback ends at the retained boundary") { main { player.playbackState == Player.STATE_ENDED } }
        // The end clock can advance by part of the last audio buffer; the clip duration is exact.
        assertTrue("End clock within one frame of the boundary",
            abs(main { player.currentPosition } - edited.effectiveDurationMs) <= 40)
        expectColour("playback never reaches yellow", Color.BLUE)

        click("Save trim")
        await("trim saved") { main { !trim.dirty && !trim.saving } }
        val saved = store.loadCurrent()!!
        assertEquals(edited, saved.segments[0].trim)
        assertEquals(hashes, saved.segments.map { sha(File(it.path)) })

        openSegment(2)
        expectColour("different segment, previously saved trim", Color.MAGENTA, "after-switch")
        assertSame("Switching clips must not recreate the preview view", initialView, main { playerView })
        assertFalse(main { activity.reviewController.showOpeningPoster })
        expectPosition(1040)
        openSegment(1)
        expectColour("reopening starts at the saved boundary", Color.GREEN)
        assertSame(initialView, main { playerView })
        expectPosition(edited.trimStartMs)
        click("Play")
        await("saved clip playing") { main { player.isPlaying && player.duration == edited.effectiveDurationMs } }
        click("Pause")
        await("paused within saved clip") { main { !player.playWhenReady } }
        val beforeReset = main { player.currentPosition + player.currentMediaItem!!.clippingConfiguration.startPositionMs }
        click("Reset trim")
        await("full draft after Reset") { main { !trim.draft!!.isTrimmed } }
        expectPosition(beforeReset)
        expectColour("Reset preserves the current frame", Color.GREEN)
        seek(3200)
        expectPosition(3200)
        expectColour("Reset exposes the trimmed-out video", Color.YELLOW)
        click("Save trim")
        await("reset saved") { main { !trim.dirty && !trim.saving } }
        assertEquals(TrimRange.full(4000), store.loadCurrent()!!.segments[0].trim)
        assertEquals(hashes, fixture.segments.map { sha(File(it.path)) })

        click("More actions for segment 2")
        click("Play from segment 2")
        await("whole-skit surface still plays the saved clip with audio") {
            node("Skit playback") != null && main {
                activity.playback.player?.let {
                    it.isPlaying && it.currentTracks.isTypeSelected(C.TRACK_TYPE_AUDIO) &&
                        it.currentMediaItem?.clippingConfiguration?.startPositionMs == 1040L
                } == true
            }
        }
        click("Close playback")

        // Render the previously saved range into private test storage, with no gallery publication.
        val output = File(context.filesDir, "trim-flash-export-${fixture.id}.mp4")
        val spec = ExportSpec.from(saved.copy(state = RecordingState.Completed, segments = saved.segments.take(1),
            playbackOrder = listOf(saved.segments.first().id), shelfOrder = null)) {
            it.trim!!.sourceDurationMs
        }
        transform(context, spec, output)
        verify(output, edited.effectiveDurationMs)
        val reader = android.media.MediaMetadataRetriever()
        try {
            reader.setDataSource(output.path)
            for ((position, colour) in listOf(0L to Color.GREEN,
                (edited.effectiveDurationMs - 70) * 1000 to Color.BLUE)) {
                val frame = reader.getFrameAtTime(position, android.media.MediaMetadataRetriever.OPTION_CLOSEST)!!
                try {
                    assertTrue("Export boundary at $position μs",
                        matches(frame.getPixel(frame.width / 2, frame.height / 2), colour))
                } finally { frame.recycle() }
            }
        } finally { reader.release() }
        val report = "Private test export: ${output.name}; range=$edited"
        Log.i("TrimFlashValidation", report)
        File(context.filesDir, "trim-flash-export.txt").writeText(report)
    }

    private fun openSegment(number: Int) {
        val card = bounds("Review segment $number")
        // Tap the card body, away from its independent overflow button.
        tapAt(card.left + card.width() / 3f, card.bottom - card.height() / 5f)
        try {
            await("ready trim and first frame of segment $number") {
                node("Segment $number playback") != null && main {
                    trim.ready && trim.segment?.order == number && player.playbackState == Player.STATE_READY &&
                        playerView != null && !shutter.isShown
                }
            }
        } catch (failure: AssertionError) {
            val state = main {
                val review = activity.reviewController
                "selection=${review.selection}, ready=${trim.ready}, dirty=${trim.dirty}, " +
                    "trim=${trim.segment?.order}, errors=${trim.error}/${review.error}, " +
                    "player=${review.player?.playbackState}@${review.sourcePositionMs}, " +
                    "description=${playerView?.contentDescription}, shutter=${playerView?.let { shutter.isShown }}"
            }
            throw AssertionError("$failure; $state", failure)
        }
    }

    private fun expectPosition(position: Long) = await("source playhead at $position ms") { main {
        val offset = player.currentMediaItem!!.clippingConfiguration.startPositionMs
        abs(player.currentPosition + offset - position) <= 40
    } }

    private fun expectColour(label: String, colour: Int, screenshot: String? = null) {
        val view = main { playerView }
        val rect = main {
            val origin = IntArray(2).also(view::getLocationOnScreen)
            Rect(origin[0], origin[1], origin[0] + view.width, origin[1] + view.height)
        }
        var pixel = 0
        try {
            await(label) {
                val image: Bitmap = instrument.uiAutomation.takeScreenshot() ?: return@await false
                try {
                    pixel = image.getPixel(rect.centerX(), rect.centerY())
                    val matched = matches(pixel, colour)
                    if (matched && screenshot != null) {
                        File(context.filesDir, "trim-flash-$screenshot.png").outputStream().use {
                            image.compress(Bitmap.CompressFormat.PNG, 100, it)
                        }
                    }
                    matched
                } finally { image.recycle() }
            }
        } catch (failure: AssertionError) {
            val state = main { "at ${activity.reviewController.sourcePositionMs} ms; state=${player.playbackState}, " +
                "bounds=$rect, shutter=${shutter.isShown}, range=${trim.draft}" }
            throw AssertionError("$failure; pixel=${Integer.toHexString(pixel)}; $state", failure)
        }
    }

    private fun matches(pixel: Int, expected: Int) = abs(Color.red(pixel) - Color.red(expected)) <= 45 &&
        abs(Color.green(pixel) - Color.green(expected)) <= 45 && abs(Color.blue(pixel) - Color.blue(expected)) <= 45

    private fun seek(ms: Long) {
        val track = bounds("Trim timeline")
        tapAt(track.left + track.width() * ms / 4000f, track.exactCenterY())
    }

    private fun tap(label: String) = bounds(label).let { tapAt(it.exactCenterX(), it.exactCenterY()) }
    private fun tapAt(x: Float, y: Float) {
        val down = SystemClock.uptimeMillis()
        input(down, MotionEvent.ACTION_DOWN, x, y)
        Thread.sleep(50)
        input(down, MotionEvent.ACTION_UP, x, y)
    }

    private fun drag(handle: String, ms: Long) {
        val from = bounds(handle)
        val track = bounds("Trim timeline")
        val x = from.exactCenterX()
        val y = from.exactCenterY()
        val end = track.left + track.width() * ms / 4000f
        val down = SystemClock.uptimeMillis()
        val revision = main { trim.revision }
        input(down, MotionEvent.ACTION_DOWN, x, y)
        for (step in 1..12) {
            input(down, MotionEvent.ACTION_MOVE, x + (end - x) * step / 12, y)
            Thread.sleep(16)
        }
        // Android may batch the last MOVE; let Compose consume it before releasing the grip.
        instrument.waitForIdleSync()
        Thread.sleep(100)
        input(down, MotionEvent.ACTION_UP, end, y)
        await("drag settles") { main { trim.revision > revision } }
    }

    private fun input(down: Long, action: Int, x: Float, y: Float) {
        val event = MotionEvent.obtain(down, SystemClock.uptimeMillis(), action, x, y, 0)
        event.source = InputDevice.SOURCE_TOUCHSCREEN
        try { assertTrue(instrument.uiAutomation.injectInputEvent(event, true)) }
        finally { event.recycle() }
    }

    private fun node(label: String): AccessibilityNodeInfo? {
        if (android.os.Build.VERSION.SDK_INT >= 33) instrument.uiAutomation.clearCache()
        fun find(n: AccessibilityNodeInfo): AccessibilityNodeInfo? {
            val description = n.contentDescription?.toString()
            if (n.isVisibleToUser && (description == label || n.text?.toString() == label ||
                    label.startsWith("Review segment ") && description?.startsWith("$label,") == true)) return n
            return (0 until n.childCount).firstNotNullOfOrNull { n.getChild(it)?.let(::find) }
        }
        return instrument.uiAutomation.rootInActiveWindow?.let(::find)
    }

    private fun bounds(label: String): Rect {
        await(label) { node(label) != null }
        return Rect().apply { node(label)!!.getBoundsInScreen(this) }
    }

    private fun click(label: String) {
        await("$label enabled") { node(label)?.isEnabled == true }
        tap(label)
    }

    private fun await(label: String, predicate: () -> Boolean) {
        val deadline = SystemClock.elapsedRealtime() + 15_000
        while (SystemClock.elapsedRealtime() < deadline) {
            if (predicate()) return
            Thread.sleep(50)
        }
        fail("Timed out: $label")
    }
}
