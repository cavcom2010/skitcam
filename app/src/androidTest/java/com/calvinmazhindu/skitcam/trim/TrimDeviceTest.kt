package com.calvinmazhindu.skitcam.trim

import android.Manifest
import android.graphics.Bitmap
import android.graphics.Rect
import android.os.SystemClock
import android.view.InputDevice
import android.view.MotionEvent
import android.view.Surface
import android.view.accessibility.AccessibilityNodeInfo
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
class TrimDeviceTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context get() = instrumentation.targetContext
    private lateinit var scenario: ActivityScenario<MainActivity>
    private lateinit var model: RecordingViewModel
    private lateinit var trim: TrimViewModel
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
        trim = it.trimModel; model = it.recordingModel; review = it.reviewController
    }
    @After fun close() {
        try {
            if (::model.isInitialized && owned != null && model.snapshots.value.session?.id == owned && !leavePaused) {
                main { trim.close(); review.close() }
                if (model.snapshots.value.state == RecordingState.Recording) model.stop()
                await("test recording settled") {
                    model.snapshots.value.state !in setOf(RecordingState.Starting, RecordingState.Finalising, RecordingState.Stopping)
                }
                if (model.snapshots.value.state in setOf(RecordingState.Paused, RecordingState.Error) && !model.snapshots.value.unresolved) {
                    model.stop(); state(RecordingState.Completed)
                }
            }
        } finally {
            if (::scenario.isInitialized) { com.calvinmazhindu.skitcam.export.ExportTestSupport.settle(); scenario.close() }
            originalRotation?.let { shell("wm user-rotation $it") }
        }
    }
    private fun await(message: String, timeoutMs: Long = 25_000L, condition: () -> Boolean) {
        val end = System.nanoTime() + timeoutMs * 1_000_000
        while (!condition() && System.nanoTime() < end) Thread.sleep(100)
        assertTrue("Timed out: $message; ${if (::model.isInitialized) model.snapshots.value else "no model"}; " +
            "trim=${if (::trim.isInitialized) main { "ready=${trim.ready}, draft=${trim.draft}, dirty=${trim.dirty}, error=${trim.error}, saving=${trim.saving}" } else "unbound"}",
            condition())
    }
    private fun state(state: RecordingState) = await(state.name) { model.snapshots.value.state == state }
    private fun awaitPose(pose: Int) {
        await("stable pose $pose") {
            var matched = false
            scenario.onActivity {
                matched = it.orientation.pose == pose &&
                    com.calvinmazhindu.skitcam.recording.CameraOrientationPolicy.matchesDisplay(pose, it.orientation.displayRotation)
            }
            matched
        }
    }
    private fun record(count: Int, pose: Int = 0) {
        awaitPose(pose); model.start(pose); state(RecordingState.Recording)
        owned = model.snapshots.value.session!!.id
        repeat(count) { index ->
            if (index > 0) { awaitPose(pose); model.resume(pose); state(RecordingState.Recording) }
            await("recorded samples") { model.snapshots.value.activeDurationMs >= 3_000 }
            model.pause(); state(RecordingState.Paused)
        }
    }
    private fun node(predicate: (AccessibilityNodeInfo) -> Boolean): AccessibilityNodeInfo? {
        if (android.os.Build.VERSION.SDK_INT >= 33) instrumentation.uiAutomation.clearCache()
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
    }
    private fun screenshot(name: String) {
        val image = instrumentation.uiAutomation.takeScreenshot() ?: error("No screenshot")
        val file = File(context.filesDir, "validation-m12e/${UUID.randomUUID()}-$name.png")
        file.parentFile!!.mkdirs()
        file.outputStream().use { image.compress(Bitmap.CompressFormat.PNG, 100, it) }
        image.recycle()
    }
    private fun metadata() = File(context.filesDir, "sessions/${owned!!}/session.json").readText()
    private fun openTrim(number: Int) {
        click { it.contentDescription?.startsWith("Review segment $number,") == true }
        await("review selection") { main { review.selection != null } }
        await("inline trim ready", 60_000L) { main { trim.ready } }
        await("clipped player ready", 60_000L) {
            main { review.player?.playbackState in setOf(Player.STATE_READY, Player.STATE_ENDED) }
        }
        await("trim track visible", 60_000L) { node { it.contentDescription?.toString() == "Trim timeline" } != null }
    }
    private fun dragBoundary(start: Boolean, fraction: Float) {
        val rect = bounds("Trim timeline")
        val from = Rect(rect)
        val x = if (start) rect.left + 5 else rect.right - 5
        from.set(x - 1, rect.centerY() - 1, x + 1, rect.centerY() + 1)
        drag(from, rect.left + rect.width() * fraction, rect.exactCenterY())
        await("trim settled") { main { !trim.saving } }
    }
    private fun digest(path: String) = java.security.MessageDigest.getInstance("SHA-256").digest(File(path).readBytes()).toList()
    @Test fun a_inlineDragSaveResetDirtyCloseAndSourcePreservation() {
        record(2)
        val original = model.snapshots.value.session!!
        val hashes = original.segments.associate { it.id to digest(it.path) }
        openTrim(1)
        dragBoundary(true, 0.2f); dragBoundary(false, 0.8f)
        val chosen = main { trim.draft!! }
        assertTrue("End handle did not move: $chosen", chosen.trimEndMs < chosen.sourceDurationMs)
        assertTrue("Drag did not trim: $chosen", chosen.isTrimmed)
        assertTrue("Invalid draft $chosen", chosen.valid())
        assertTrue("Trim must be dirty before save", main { trim.dirty })
        assertNull("Play from here hidden while trim is dirty", node { it.text?.toString() == "Play from here" })
        description("Play")
        await("playing clipped interval") { main { review.player?.isPlaying == true } }
        await("stops at clip end") { main { review.player?.playbackState == Player.STATE_ENDED } }
        main { assertTrue(review.player!!.currentPosition <= chosen.effectiveDurationMs + 100) }
        screenshot("portrait-inline-trim")
        text("Save trim")
        await("trim saved") { main { !trim.dirty && !trim.saving } }
        assertEquals(chosen, model.snapshots.value.session!!.segments[0].trim)
        assertNotNull("Play from here returns after save", node { it.text?.toString() == "Play from here" })
        text("Close")
        await("review closed after save") { main { review.selection == null } }
        openTrim(1)
        assertEquals(chosen, main { trim.draft })
        main { trim.change(trim.draft!!.start(0)); trim.settled() }
        text("Close")
        await("discard prompt") { node { it.text?.toString() == "Discard unsaved trim?" } != null }
        text("Keep editing")
        await("still reviewing") { main { review.selection != null } }
        text("Close")
        await("discard prompt again") { node { it.text?.toString() == "Discard unsaved trim?" } != null }
        text("Discard")
        await("review closed without saving") { main { review.selection == null && trim.segment == null } }
        assertEquals(chosen, model.snapshots.value.session!!.segments[0].trim)

        openTrim(1)
        text("Reset trim")
        assertFalse(main { trim.draft!!.isTrimmed })
        text("Save trim")
        await("reset saved") { main { !trim.dirty && !trim.saving } }
        val full = model.snapshots.value.session!!.segments[0].trim!!
        assertFalse(full.isTrimmed)
        assertEquals(full.sourceDurationMs, full.trimEndMs)
        text("Close")
        await("review closed") { main { review.selection == null } }
        assertEquals(original.segments.map { it.id }, model.snapshots.value.session!!.segments.map { it.id })
        original.segments.forEach { assertEquals(hashes[it.id], digest(it.path)) }
        model.resume(0); state(RecordingState.Recording)
        await("new audio samples") { model.snapshots.value.activeDurationMs >= 1_500 }
        model.stop(); state(RecordingState.Completed)
    }
    @Suppress("DEPRECATION")
    @Test fun b_landscapeInlineTrimSave() {
        shell("wm user-rotation lock 1")
        await("landscape") {
            var yes = false
            scenario.onActivity {
                yes = it.windowManager.defaultDisplay.rotation == Surface.ROTATION_90
            }
            yes && model.cameraReady
        }
        record(1, 270); bindModels(); openTrim(1)
        val full = main { trim.draft!! }
        main { trim.change(full.start(300)); trim.settled() }
        val draft = main { trim.draft }
        screenshot("landscape-inline-trim")
        text("Save trim")
        await("landscape trim saved") { main { !trim.dirty && !trim.saving } }
        assertEquals(draft, model.snapshots.value.session!!.segments.single().trim)
        text("Close")
        await("closed landscape review") { main { review.selection == null } }
        model.resume(270); state(RecordingState.Recording)
        await("landscape new audio") { model.snapshots.value.activeDurationMs >= 1_500 }
        model.stop(); state(RecordingState.Completed)
    }
    @Test fun c_dirtyCloseKeepEditingAndMissingSourceFallback() {
        record(2)
        val before = metadata()
        openTrim(1)
        val firstId = model.snapshots.value.session!!.segments[0].id
        main { trim.change(trim.draft!!.start(200)); trim.settled() }
        click { it.contentDescription?.startsWith("Review segment 2,") == true }
        await("switch prompt") { node { it.text?.toString() == "Discard unsaved trim?" } != null }
        text("Keep editing")
        await("selection retained") { main { review.selection?.segmentId == firstId } }
        text("Close")
        await("dirty prompt") { node { it.text?.toString() == "Discard unsaved trim?" } != null }
        text("Keep editing")
        assertEquals(before, metadata())
        text("Close"); text("Discard")
        await("closed") { main { review.selection == null && trim.segment == null } }
        assertEquals(before, metadata())
        model.stop(); state(RecordingState.Completed)
    }
    @Test fun z_restartFixture() {
        record(1); openTrim(1)
        main { trim.change(trim.draft!!.start(300)); trim.settled() }
        text("Save trim")
        await("saved") { main { !trim.dirty } }
        text("Close")
        await("closed") { main { review.selection == null } }
        leavePaused = true
    }
}
