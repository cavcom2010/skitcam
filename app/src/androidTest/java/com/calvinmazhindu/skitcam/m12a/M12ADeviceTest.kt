package com.calvinmazhindu.skitcam.m12a

import android.Manifest
import android.graphics.Bitmap
import android.os.Build
import android.os.SystemClock
import android.view.Surface
import android.view.accessibility.AccessibilityNodeInfo
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.calvinmazhindu.skitcam.MainActivity
import com.calvinmazhindu.skitcam.export.ExportTestSupport
import com.calvinmazhindu.skitcam.playback.SkitPlaybackController
import com.calvinmazhindu.skitcam.recording.RecordingState
import com.calvinmazhindu.skitcam.recording.SessionFileStore
import com.calvinmazhindu.skitcam.recording.Session
import com.calvinmazhindu.skitcam.trim.TrimRange
import com.calvinmazhindu.skitcam.recording.RecordingViewModel
import com.calvinmazhindu.skitcam.recording.Segment
import com.calvinmazhindu.skitcam.review.SegmentReviewController
import org.junit.After
import org.junit.Assert.*
import org.junit.Assume
import org.junit.Before
import org.junit.FixMethodOrder
import org.junit.Test
import org.junit.runner.RunWith
import org.junit.runners.MethodSorters
import java.io.File
import java.util.UUID
import java.util.concurrent.atomic.AtomicReference

@RunWith(AndroidJUnit4::class)
@FixMethodOrder(MethodSorters.NAME_ASCENDING)
class M12ADeviceTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context get() = instrumentation.targetContext
    private lateinit var scenario: ActivityScenario<MainActivity>
    private lateinit var model: RecordingViewModel
    private lateinit var review: SegmentReviewController
    private lateinit var activity: MainActivity
    private var originalRotation: String? = null
    private var owned: String? = null

    private fun shell(command: String) = instrumentation.uiAutomation.executeShellCommand(command).use {
        android.os.ParcelFileDescriptor.AutoCloseInputStream(it).bufferedReader().readText().trim()
    }
    private fun <T> main(block: () -> T): T {
        val value = AtomicReference<T>()
        instrumentation.runOnMainSync { value.set(block()) }
        return value.get()
    }
    @Before fun open() {
        for (permission in listOf(Manifest.permission.CAMERA, Manifest.permission.RECORD_AUDIO)) {
            shell("pm grant ${context.packageName} $permission")
        }
        originalRotation = shell("wm user-rotation")
        shell("wm user-rotation lock 0")
        scenario = ActivityScenario.launch(MainActivity::class.java)
        scenario.onActivity { activity = it; model = it.recordingModel; review = it.reviewController }
        ExportTestSupport.beforeCamera()
        await("camera ready") { model.cameraReady }
        Assume.assumeTrue("Preserve an existing open session",
            model.snapshots.value.state in setOf(RecordingState.Ready, RecordingState.Completed))
    }
    @After fun close() {
        try {
            if (::model.isInitialized && owned != null && model.snapshots.value.session?.id == owned) {
                main { review.close() }
                if (model.snapshots.value.state == RecordingState.Recording) model.stop()
                await("test recording settled") { model.snapshots.value.state !in
                    setOf(RecordingState.Starting, RecordingState.Finalising, RecordingState.Stopping) }
                if (model.snapshots.value.state in setOf(RecordingState.Paused, RecordingState.Error) &&
                    !model.snapshots.value.unresolved) {
                    model.stop(); state(RecordingState.Completed)
                }
            }
        } finally {
            if (::scenario.isInitialized) { ExportTestSupport.settle(); scenario.close() }
            originalRotation?.let { shell("wm user-rotation $it") }
        }
    }
    private fun await(message: String, timeoutMs: Long = 25_000L, condition: () -> Boolean) {
        val deadline = System.nanoTime() + timeoutMs * 1_000_000L
        while (!condition() && System.nanoTime() < deadline) Thread.sleep(100)
        assertTrue("Timed out: $message; ${if (::model.isInitialized) model.snapshots.value else "no model"}", condition())
    }
    private fun state(value: RecordingState) = await(value.name) { model.snapshots.value.state == value }
    private fun record(count: Int, pose: Int = 0) {
        await("stable pose $pose") { main { activity.orientation.pose == pose } }
        model.start(pose); state(RecordingState.Recording)
        owned = model.snapshots.value.session!!.id
        repeat(count) { index ->
            if (index > 0) { model.resume(pose); state(RecordingState.Recording) }
            await("recorded samples") { model.snapshots.value.activeDurationMs >= 1200 }
            model.pause(); state(RecordingState.Paused)
        }
    }
    private fun node(predicate: (AccessibilityNodeInfo) -> Boolean): AccessibilityNodeInfo? {
        if (Build.VERSION.SDK_INT >= 33) instrumentation.uiAutomation.clearCache()
        fun find(current: AccessibilityNodeInfo): AccessibilityNodeInfo? {
            if (current.isVisibleToUser && predicate(current)) return current
            for (index in 0 until current.childCount) find(current.getChild(index) ?: continue)?.let { return it }
            return null
        }
        return instrumentation.uiAutomation.rootInActiveWindow?.let(::find)
    }
    private fun click(predicate: (AccessibilityNodeInfo) -> Boolean) {
        await("visible control") { node(predicate) != null }
        var current = node(predicate)!!
        while (!current.isClickable && current.parent != null) current = current.parent
        assertTrue(current.performAction(AccessibilityNodeInfo.ACTION_CLICK))
    }
    private fun description(value: String) = click { it.contentDescription?.toString() == value }
    private fun text(value: String) = click { it.text?.toString() == value }
    private fun metadata(session: com.calvinmazhindu.skitcam.recording.Session) =
        File(context.filesDir, "sessions/${session.id}/session.json").readText()
    private fun screenshot(name: String) {
        val bitmap = instrumentation.uiAutomation.takeScreenshot() ?: return
        val file = File(context.filesDir, "validation-m12a/$name.png")
        file.parentFile!!.mkdirs()
        file.outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
        bitmap.recycle()
    }

    @Test fun a_contextReviewAndDirectShelfActions() {
        record(1)
        val session = model.snapshots.value.session!!
        val segment = session.segments.single()
        val bytes = File(segment.path).length()
        val before = metadata(session)
        click { it.contentDescription?.startsWith("Review segment 1,") == true }
        await("kept review actions") {
            node { it.text?.toString() == "Discard" } != null && node { it.text?.toString() == "Keep" } == null
        }
        screenshot("review-kept")
        text("Close")
        assertEquals(before, metadata(session))
        description("More actions for segment 1")
        description("Discard segment 1")
        await("shelf discard") { model.snapshots.value.count == 0 }
        assertEquals(bytes, File(segment.path).length())
        await("discarded shelf label") {
            node { it.contentDescription?.toString() == "Review segment 1, Discarded" } != null
        }
        screenshot("shelf-discarded")
        description("More actions for segment 1")
        description("Restore segment 1")
        await("shelf restore") { model.snapshots.value.count == 1 }
        assertEquals(before, metadata(session))
        click { it.contentDescription?.startsWith("Review segment 1,") == true }
        await("kept review restored") { node { it.text?.toString() == "Discard" } != null }
        text("Close")
        model.stop(); state(RecordingState.Completed)
    }

    @Test fun b_anchorPlaybackAndResumeReleasesPlayer() {
        record(4)
        val session = model.snapshots.value.session!!
        val anchor = session.segments[2]
        val result = main { activity.playback.play(session, anchor.id) }
        assertTrue(result.ready)
        assertEquals(listOf(3, 4), main { activity.playback.plan!!.clips.map { it.order } })
        await("playback ready") {
            main { activity.playback.player?.isPlaying == true }
        }
        await("first rendered video frame") {
            main { activity.playback.player?.let { it.videoSize.width > 0 && it.currentPosition > 200 } == true }
        }
        assertEquals(anchor.id, main { activity.playback.plan!!.clips.first().segmentId })
        assertTrue(main { activity.playback.loop })
        screenshot("playback-portrait")
        main { activity.playback.toggleLoop() }
        assertFalse(main { activity.playback.loop })
        // The paused dock remains available beside the playback surface. RESUME must
        // close/release the player before the next segment starts.
        click { it.text?.toString() == "RESUME" }
        state(RecordingState.Recording)
        assertNull(main { activity.playback.player })
        assertNull(main { activity.playback.plan })
        await("resumed samples") { model.snapshots.value.activeDurationMs >= 1200 }
        scenario.onActivity { assertTrue(ProcessCameraProvider.getInstance(it).get().isBound(model.port.capture)) }
        model.stop(); state(RecordingState.Completed)
    }

    @Test fun c_portraitPlaybackLayout() {
        record(2)
        val session = model.snapshots.value.session!!
        main { activity.playback.play(session, session.segments[1].id) }
        await("portrait playback") { main { activity.playback.player?.isPlaying == true } }
        assertNotNull(node { it.contentDescription?.toString() == "Skit playback" })
        assertEquals(listOf(2), main { activity.playback.plan!!.clips.map { it.order } })
        main { activity.playback.close() }
        model.stop(); state(RecordingState.Completed)
    }
    @Test fun d_landscapePlaybackLayout() {
        shell("wm user-rotation lock 1")
        await("landscape pose 270", 120_000L) {
            var ready = false
            scenario.onActivity {
                activity = it
                @Suppress("DEPRECATION")
                ready = it.orientation.pose == 270 &&
                    it.windowManager.defaultDisplay.rotation == Surface.ROTATION_90 && it.recordingModel.cameraReady
            }
            ready
        }
        record(2, 270)
        val session = model.snapshots.value.session!!
        main { activity.playback.play(session, session.segments[0].id) }
        await("landscape playback") { main { activity.playback.player?.isPlaying == true } }
        assertNotNull(node { it.contentDescription?.toString() == "Skit playback" })
        assertEquals(listOf(1, 2), main { activity.playback.plan!!.clips.map { it.order } })
        screenshot("playback-landscape")
        main { activity.playback.close() }
        model.stop(); state(RecordingState.Completed)
    }

    @Test fun e_twentyClipPlaybackAndResourceRelease() {
        val source = SessionFileStore(File(context.filesDir, "sessions")).loadLatest()
            ?.segments?.firstOrNull()
        Assume.assumeNotNull("A retained test clip is required", source!!)
        val root = File(context.filesDir, "m12a-fixtures/${UUID.randomUUID()}").apply { mkdirs() }
        val duration = source.trim?.sourceDurationMs ?: source.durationMs
        val trimmed = if (duration > 1000) TrimRange(duration, 300, duration - 300) else TrimRange.full(duration)
        val segments = (1..20).map { index ->
            val file = File(root, "clip_$index.mp4")
            File(source.path).copyTo(file, overwrite = true)
            Segment(index, file.path, source.durationMs, 0, source.videoRotationDegrees,
                id = "perf-$index", trim = if (index == 1) trimmed else TrimRange.full(duration))
        }
        val fixture = Session("perf-${UUID.randomUUID()}", 1, 0, RecordingState.Paused, segments)
        val controller = SkitPlaybackController(context)
        val started = SystemClock.elapsedRealtime()
        main { controller.play(fixture, null, loop = false) }
        await("20-clip playback starts", 20_000L) {
            main { controller.player?.let { it.mediaItemCount == 20 && it.isPlaying } == true }
        }
        val startMs = SystemClock.elapsedRealtime() - started
        val firstClip = main { controller.player?.currentMediaItem?.clippingConfiguration }
        assertEquals(300L, firstClip?.startPositionMs)
        assertEquals(duration - 300L, firstClip?.endPositionMs)
        val checkpoints = mutableListOf<Long>()
        var lastIndex = -1
        val deadline = System.nanoTime() + 60_000_000_000L
        while (System.nanoTime() < deadline) {
            val index = main { controller.currentIndex }
            if (index != lastIndex) {
                checkpoints += SystemClock.elapsedRealtime() - started
                lastIndex = index
            }
            if (main { controller.finished }) break
            Thread.sleep(50)
        }
        assertTrue("20-clip playback must finish with Loop OFF", main { controller.finished })
        assertEquals(19, checkpoints.lastIndex)
        val totalMs = SystemClock.elapsedRealtime() - started
        main { controller.close() }
        assertNull(main { controller.player })
        android.util.Log.i("SkitCamM12A", "20 clips startMs=$startMs totalMs=$totalMs transitions=$checkpoints")
        root.deleteRecursively()
    }

    @Test fun f_anchorLoopRepeatsOnlyTheSavedSuffix() {
        val source = SessionFileStore(File(context.filesDir, "sessions")).loadLatest()
            ?.segments?.firstOrNull()
        Assume.assumeNotNull("A retained test clip is required", source!!)
        val root = File(context.filesDir, "m12a-loop/${UUID.randomUUID()}").apply { mkdirs() }
        val duration = source.trim?.sourceDurationMs ?: source.durationMs
        val segments = (1..3).map { index ->
            val file = File(root, "loop_$index.mp4")
            File(source.path).copyTo(file, overwrite = true)
            Segment(index, file.path, source.durationMs, 0, source.videoRotationDegrees,
                id = "loop-$index", trim = TrimRange.full(duration))
        }
        val fixture = Session("loop-${UUID.randomUUID()}", 1, 0, RecordingState.Paused, segments)
        val controller = SkitPlaybackController(context)
        main { controller.play(fixture, "loop-2", loop = true) }
        await("loop starts at anchor") { main { controller.player?.isPlaying == true } }
        assertEquals(listOf(2, 3), main { controller.plan!!.clips.map { it.order } })
        await("second clip", 15_000L) { main { controller.currentIndex == 1 } }
        await("wrap to anchored first clip", 15_000L) {
            main { controller.currentIndex == 0 && controller.currentSegmentId == "loop-2" }
        }
        assertFalse(main { controller.finished })
        assertEquals("loop-2", main { controller.plan!!.anchorId })
        main { controller.close() }
        assertNull(main { controller.player })
        root.deleteRecursively()
    }

}
