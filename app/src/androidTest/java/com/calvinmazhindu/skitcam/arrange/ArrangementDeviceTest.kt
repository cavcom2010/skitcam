package com.calvinmazhindu.skitcam.arrange

import android.Manifest
import android.graphics.Bitmap
import android.graphics.Rect
import android.os.Build
import android.os.SystemClock
import android.view.InputDevice
import android.view.MotionEvent
import android.view.Surface
import android.view.accessibility.AccessibilityNodeInfo
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.lifecycle.Lifecycle
import androidx.media3.common.Player
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.calvinmazhindu.skitcam.MainActivity
import com.calvinmazhindu.skitcam.recording.*
import com.calvinmazhindu.skitcam.review.SegmentReviewController
import org.junit.*
import org.junit.Assert.*
import org.junit.runner.RunWith
import org.junit.runners.MethodSorters
import java.io.File
import java.util.UUID
import java.util.concurrent.atomic.AtomicReference

@RunWith(AndroidJUnit4::class)
@FixMethodOrder(MethodSorters.NAME_ASCENDING)
class ArrangementDeviceTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context get() = instrumentation.targetContext
    private lateinit var scenario: ActivityScenario<MainActivity>
    private lateinit var model: RecordingViewModel
    private lateinit var editor: ArrangementViewModel
    private lateinit var review: SegmentReviewController
    private var originalRotation: String? = null
    private var owned: String? = null
    private var leavePaused = false
    private fun shell(command: String) = instrumentation.uiAutomation.executeShellCommand(command).use {
        android.os.ParcelFileDescriptor.AutoCloseInputStream(it).bufferedReader().readText().trim()
    }
    private fun <T> main(block: () -> T): T {
        val result = AtomicReference<T>()
        instrumentation.runOnMainSync { result.set(block()) }
        return result.get()
    }
    @Before fun open() {
        for (permission in listOf(Manifest.permission.CAMERA, Manifest.permission.RECORD_AUDIO)) {
            shell("pm grant ${context.packageName} $permission")
        }
        originalRotation = shell("wm user-rotation")
        shell("wm user-rotation lock 0")
        scenario = ActivityScenario.launch(MainActivity::class.java)
        bindModels()
        com.calvinmazhindu.skitcam.export.ExportTestSupport.beforeCamera()
        await("camera ready") { model.cameraReady }
        Assume.assumeTrue("Preserve any existing open session",
            model.snapshots.value.state in setOf(RecordingState.Ready, RecordingState.Completed))
    }
    private fun bindModels() = scenario.onActivity {
        model = it.recordingModel; editor = it.arrangementModel; review = it.reviewController
    }
    @After fun close() {
        try {
            if (::model.isInitialized && owned != null && model.snapshots.value.session?.id == owned && !leavePaused) {
                main { review.close(); editor.cancel() }
                if (model.snapshots.value.state == RecordingState.Recording) model.stop()
                await("test recording settled") { model.snapshots.value.state !in setOf(RecordingState.Starting, RecordingState.Finalising, RecordingState.Stopping) }
                if (model.snapshots.value.state in setOf(RecordingState.Paused, RecordingState.Error) && !model.snapshots.value.unresolved) {
                    model.stop(); state(RecordingState.Completed)
                }
            }
        } finally {
            if (::scenario.isInitialized) { com.calvinmazhindu.skitcam.export.ExportTestSupport.settle(); scenario.close() }
            originalRotation?.let { shell("wm user-rotation $it") }
        }
    }
    private fun await(message: String, condition: () -> Boolean) {
        val end = System.nanoTime() + 25_000_000_000L
        while (!condition() && System.nanoTime() < end) Thread.sleep(100)
        assertTrue("Timed out: $message; ${if (::model.isInitialized) model.snapshots.value else "no model"}", condition())
    }
    private fun state(state: RecordingState) = await(state.name) { model.snapshots.value.state == state }
    private fun record(count: Int, pose: Int = 0) {
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
        fun find(n: AccessibilityNodeInfo): AccessibilityNodeInfo? {
            if (n.isVisibleToUser && predicate(n)) return n
            for (i in 0 until n.childCount) n.getChild(i)?.let { find(it)?.let { result -> return result } }
            return null
        }
        return instrumentation.uiAutomation.rootInActiveWindow?.let(::find)
    }
    private fun click(predicate: (AccessibilityNodeInfo) -> Boolean) {
        await("visible control") { node(predicate) != null }
        var n = node(predicate)!!
        while (!n.isClickable && n.parent != null) n = n.parent
        assertTrue(n.performAction(AccessibilityNodeInfo.ACTION_CLICK))
    }
    private fun text(value: String) = click { it.text?.toString() == value }
    private fun description(value: String) = click { it.contentDescription?.toString() == value }
    private fun bounds(description: String): Rect {
        await(description) { node { it.contentDescription?.toString() == description } != null }
        return Rect().apply { node { it.contentDescription?.toString() == description }!!.getBoundsInScreen(this) }
    }
    private fun arrange() {
        text("Arrange"); await("draft open") { main { editor.draft != null } }
        assertTrue(model.cameraReady)
        scenario.onActivity { assertTrue(ProcessCameraProvider.getInstance(it).get().isBound(model.port.capture)) }
    }
    private fun drag(from: Rect, toX: Float, toY: Float, holdAtEnd: Long = 0) {
        val down = SystemClock.uptimeMillis()
        fun event(action: Int, x: Float, y: Float) {
            val input = MotionEvent.obtain(down, SystemClock.uptimeMillis(), action, x, y, 0)
            input.source = InputDevice.SOURCE_TOUCHSCREEN
            assertTrue(instrumentation.uiAutomation.injectInputEvent(input, true))
            input.recycle()
        }
        val x = from.exactCenterX(); val y = from.exactCenterY()
        event(MotionEvent.ACTION_DOWN, x, y)
        try {
            Thread.sleep(700)
            for (step in 1..24) {
                event(MotionEvent.ACTION_MOVE, x + (toX - x) * step / 24, y + (toY - y) * step / 24)
                Thread.sleep(20)
            }
            if (holdAtEnd > 0) Thread.sleep(holdAtEnd)
        } finally { event(MotionEvent.ACTION_UP, toX, toY) }
        await("drag ended") { main { editor.draggingId == null } }
    }
    private fun screenshot(name: String) {
        val image = instrumentation.uiAutomation.takeScreenshot() ?: error("No screenshot")
        val file = File(context.filesDir, "validation-m4/${UUID.randomUUID()}-$name.png")
        file.parentFile!!.mkdirs()
        file.outputStream().use { image.compress(Bitmap.CompressFormat.PNG, 100, it) }
        image.recycle()
    }
    private fun metadata() = File(context.filesDir, "sessions/${owned!!}/session.json").readText()
    private fun finishAndVerify(pose: Int, arranged: List<String>, history: List<Segment>) {
        model.resume(pose); state(RecordingState.Recording)
        assertNull(main { review.player }); assertNull(main { editor.draft })
        await("audio/video after arranging") { model.snapshots.value.activeDurationMs >= 1200 }
        model.stop(); state(RecordingState.Completed)
        val session = model.snapshots.value.session!!
        assertEquals(history, session.segments.dropLast(1))
        assertEquals(arranged, session.playbackOrder.dropLast(1))
        assertEquals(session.segments.last().id, session.playbackOrder.last())
        val store = SessionFileStore(File(context.filesDir, "sessions"))
        session.segments.forEach { assertEquals(it.videoRotationDegrees, store.validate(it.path)) }
        assertEquals(session, store.loadLatest())
    }
    @Test fun a_touchDragCancelPreviewDoneAndRecord() {
        record(4)
        val original = model.snapshots.value.session!!
        val originalText = metadata()
        arrange()
        val first = bounds("Drag segment 1"); val third = bounds("Drag segment 3")
        drag(first, third.exactCenterX(), third.exactCenterY() + 30)
        val moved = main { editor.draft!!.ids }
        assertNotEquals(original.playbackOrder, moved)
        assertTrue(moved.indexOf(original.segments[0].id) >= 1)
        assertEquals(originalText, metadata())
        screenshot("portrait-drag")
        text("Cancel"); await("cancelled") { main { editor.draft == null } }
        assertEquals(originalText, metadata())
        arrange()
        assertEquals(original.playbackOrder, main { editor.draft!!.ids })
        drag(bounds("Drag segment 1"), bounds("Drag segment 3").exactCenterX(), bounds("Drag segment 3").exactCenterY() + 30)
        val proposed = main { editor.draft!!.ids }
        description("Preview segment 1")
        await("player ready") { main { review.player?.playbackState == Player.STATE_READY } }
        assertFalse(main { review.player!!.playWhenReady })
        description("Play")
        await("playback") { main { review.player?.let { it.isPlaying && it.currentPosition > 200 } == true } }
        assertNull(node { it.text?.toString() == "Discard" })
        screenshot("arrange-preview")
        text("Back to Arrange")
        assertEquals(proposed, main { editor.draft!!.ids })
        text("Done"); await("saved") { main { editor.draft == null } }
        assertEquals(proposed, model.snapshots.value.session!!.playbackOrder)
        assertEquals(original.segments, model.snapshots.value.session!!.segments)
        finishAndVerify(0, proposed, original.segments)
    }
    @Suppress("DEPRECATION")
    @Test fun b_landscapeEdgeAutoScrollAndRecord() {
        shell("wm user-rotation lock 1")
        await("landscape") {
            var rotated = false
            scenario.onActivity { rotated = it.windowManager.defaultDisplay.rotation == Surface.ROTATION_90 }
            rotated && model.cameraReady
        }
        record(8, 270)
        bindModels()
        val original = model.snapshots.value.session!!
        arrange(); screenshot("landscape-list")
        val from = bounds("Drag segment 1")
        val viewport = bounds("Arrangement list")
        drag(from, from.exactCenterX(), viewport.bottom - 12f, 1800)
        val proposed = main { editor.draft!!.ids }
        assertTrue("Drag must move beyond the initial viewport", proposed.indexOf(original.segments[0].id) >= 4)
        assertNotNull(node { it.contentDescription?.toString() == "Drag segment 8" })
        screenshot("landscape-auto-scroll")
        text("Done"); await("saved") { main { editor.draft == null } }
        finishAndVerify(270, proposed, original.segments)
    }
    @Test fun c_backgroundRecreationAndKeepDiscardInteraction() {
        record(3)
        val original = model.snapshots.value.session!!
        val originalText = metadata()
        arrange(); description("Move segment 1 down")
        val draft = main { editor.draft!! }
        scenario.moveToState(Lifecycle.State.CREATED)
        scenario.moveToState(Lifecycle.State.RESUMED)
        await("camera after background") { model.cameraReady }
        assertEquals(draft, main { editor.draft })
        scenario.recreate(); bindModels()
        await("camera after recreation") { model.cameraReady }
        assertEquals(draft, main { editor.draft })
        text("Cancel"); await("cancelled") { main { editor.draft == null } }
        assertEquals(originalText, metadata())
        arrange(); description("Move segment 1 down")
        val arranged = main { editor.draft!!.ids }
        text("Done"); await("saved") { main { editor.draft == null } }
        click { it.contentDescription?.startsWith("Review segment 1,") == true }
        text("Discard"); await("discard") { model.snapshots.value.count == 2 }
        text("Close")
        assertEquals(arranged - original.segments[0].id, model.snapshots.value.session!!.playbackOrder)
        click { it.contentDescription?.startsWith("Review segment 1,") == true }
        text("Keep"); await("restored") { model.snapshots.value.count == 3 }
        text("Close")
        assertEquals((arranged - original.segments[0].id) + original.segments[0].id,
            model.snapshots.value.session!!.playbackOrder)
        model.stop(); state(RecordingState.Completed)
    }
    @Test fun d_savedOrderRestartFixtureAndStaleGuard() {
        record(3)
        val session = model.snapshots.value.session!!
        arrange(); description("Move segment 3 up"); description("Move segment 3 up")
        val proposed = main { editor.draft!!.ids }
        text("Done"); await("saved") { main { editor.draft == null } }
        assertEquals(proposed, model.snapshots.value.session!!.playbackOrder)
        arrange(); description("Move segment 1 up")
        val result = AtomicReference<SegmentEditResult>()
        model.setSegmentKept(session.id, session.segments[1].id, false) { result.set(it) }
        await("external metadata change") { result.get() != null }
        assertTrue(result.get().success)
        await("stale draft message") { node { it.text?.toString()?.startsWith("The session changed") == true } != null }
        text("Cancel"); await("cancelled") { main { editor.draft == null } }
        model.setSegmentKept(session.id, session.segments[1].id, true) { result.set(it) }
        await("restore") { model.snapshots.value.count == 3 }
        arrange(); screenshot("restart-fixture")
        text("Cancel"); await("cancelled") { main { editor.draft == null } }
        leavePaused = false // Complete this owned fixture so the following Trim suite can run.
    }
}
