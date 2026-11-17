package com.calvinmazhindu.skitcam.ui

import android.Manifest
import android.graphics.Bitmap
import android.graphics.Rect
import android.os.SystemClock
import android.view.InputDevice
import android.view.MotionEvent
import android.view.View
import android.view.accessibility.AccessibilityNodeInfo
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.media3.common.Player
import androidx.media3.ui.PlayerView
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.runner.lifecycle.ActivityLifecycleMonitorRegistry
import androidx.test.runner.lifecycle.Stage
import com.calvinmazhindu.skitcam.MainActivity
import com.calvinmazhindu.skitcam.export.*
import com.calvinmazhindu.skitcam.gallery.SavedMediaActivity
import com.calvinmazhindu.skitcam.recording.*
import com.calvinmazhindu.skitcam.trim.TrimRange
import com.calvinmazhindu.skitcam.video.VideoInspector
import org.junit.*
import org.junit.Assert.*
import org.junit.runner.RunWith
import java.io.File
import java.util.UUID
import java.util.concurrent.atomic.AtomicReference
import kotlin.math.abs

/** Run each method in a separate instrumentation invocation: fixtures never touch production.
 * Software landscape tests exercise layout, not physical sensor/rotation correctness.
 */
@RunWith(AndroidJUnit4::class)
class M13UiDeviceTest {
    private val inst = InstrumentationRegistry.getInstrumentation()
    private val context get() = inst.targetContext
    private val args get() = InstrumentationRegistry.getArguments()
    private lateinit var scenario: ActivityScenario<MainActivity>
    private lateinit var activity: MainActivity
    private val model get() = activity.recordingModel
    private lateinit var fixture: Session
    private var rotation: String? = null
    private var fixedRotation: String? = null
    private val landscape get() = args.getString("landscape") == "true"
    private val label get() = "${if (landscape) "landscape" else "portrait"}-${args.getString("font") ?: "normal"}"

    private fun shell(command: String) = inst.uiAutomation.executeShellCommand(command).use {
        android.os.ParcelFileDescriptor.AutoCloseInputStream(it).bufferedReader().readText().trim()
    }
    private fun <T> main(action: () -> T): T {
        val result = AtomicReference<T>()
        inst.runOnMainSync { result.set(action()) }
        return result.get()
    }
    private fun await(message: String, timeout: Long = 30_000, condition: () -> Boolean) {
        val deadline = SystemClock.uptimeMillis() + timeout
        while (SystemClock.uptimeMillis() < deadline) {
            if (condition()) return
            Thread.sleep(100)
        }
        fail("Timed out: $message")
    }
    @Before fun open() {
        check(context.packageName in setOf("com.calvinmazhindu.skitcam.m13validation",
            "com.calvinmazhindu.skitcam.m13reviewvalidation",
            "com.calvinmazhindu.skitcam.m13reviewstablevalidation")) { "M13 validation only." }
        rotation = shell("wm user-rotation")
        fixedRotation = shell("wm fixed-to-user-rotation")
        if (landscape && args.getString("idle") == "true") shell("wm fixed-to-user-rotation enabled")
        shell("wm user-rotation lock ${if (landscape) 1 else 0}")
        for (permission in listOf(Manifest.permission.CAMERA, Manifest.permission.RECORD_AUDIO,
            Manifest.permission.POST_NOTIFICATIONS)) shell("pm grant ${context.packageName} $permission")
        context.getSharedPreferences("camera_preferences", 0).edit().putString("mode", "SkitVideo").commit()
        val id = UUID.randomUUID().toString()
        val root = File(context.filesDir, "sessions/$id").apply { mkdirs() }
        val idle = args.getString("idle") == "true"
        val segments = if (idle) emptyList() else listOf("red", "green", "blue").mapIndexed { index, colour ->
            val source = File(context.filesDir, "inline-inputs/$colour.mp4")
            check(source.isFile) { "Push generated fixtures into validation package first." }
            val file = File(root, "segment_%03d.mp4".format(index + 1))
            source.copyTo(file)
            val facts = VideoInspector.inspect(file.path)
            Segment(index + 1, file.path, 1000, if (landscape) 90 else 0, facts.rotation,
                kept = index != 1, trim = TrimRange(1000, 100, 900), media = facts)
        }
        fixture = Session(id, System.currentTimeMillis(), if (landscape) 90 else 0,
            if (idle) RecordingState.Completed else RecordingState.Paused, segments)
        SessionFileStore(File(context.filesDir, "sessions")).save(fixture)
        scenario = ActivityScenario.launch(MainActivity::class.java)
        scenario.onActivity { activity = it }
        await("recovery and preview") { model.recovered.value && model.cameraReady }
        await("actual layout orientation") { main { (activity.window.decorView.width > activity.window.decorView.height) == landscape } }
        args.getString("font")?.toFloat()?.let { font ->
            assertEquals(font, main { activity.resources.configuration.fontScale }, .01f)
        }
    }
    @After fun close() {
        try {
            if (::scenario.isInitialized) {
                main { activity.finishConfirmation.cancel() }
                scenario.close()
            }
        } finally {
            rotation?.let { shell("wm user-rotation $it") }
            fixedRotation?.let { shell("wm fixed-to-user-rotation $it") }
        }
    }
    private fun node(label: String): AccessibilityNodeInfo? {
        if (android.os.Build.VERSION.SDK_INT >= 33) inst.uiAutomation.clearCache()
        fun find(n: AccessibilityNodeInfo): AccessibilityNodeInfo? {
            if (n.isVisibleToUser && (n.text?.toString() == label || n.contentDescription?.toString() == label ||
                    label.startsWith("Review segment ") && n.contentDescription?.startsWith("$label,") == true)) return n
            for (i in 0 until n.childCount) n.getChild(i)?.let { find(it)?.let { found -> return found } }
            return null
        }
        return inst.uiAutomation.rootInActiveWindow?.let(::find)
    }
    private fun target(label: String): AccessibilityNodeInfo {
        await(label) { node(label) != null }
        var n = node(label)!!
        while (!n.isClickable && n.parent != null) n = n.parent
        return n
    }
    private fun click(label: String) {
        await("$label enabled") { node(label)?.let { n ->
            var button = n
            while (!button.isClickable && button.parent != null) button = button.parent
            button.isEnabled && button.isClickable
        } == true }
        assertTrue(label, target(label).performAction(AccessibilityNodeInfo.ACTION_CLICK))
    }
    private fun bounds(label: String): Rect = Rect().apply { target(label).getBoundsInScreen(this) }
    private fun assertTarget(label: String): Rect {
        await("foreground safe insets") { main {
            ActivityLifecycleMonitorRegistry.getInstance().getActivitiesInStage(Stage.RESUMED)
                .lastOrNull()?.window?.decorView?.let { ViewCompat.getRootWindowInsets(it) } != null
        } }
        val minimum = 48 * context.resources.displayMetrics.density - 2
        // Dropdowns scale in: visible semantics can precede the final 48dp bounds.
        // Wait for the actual target size rather than measuring an animation frame.
        await("$label full touch target") {
            node(label)?.let {
                var button = it
                while (!button.isClickable && button.parent != null) button = button.parent
                val size = Rect().apply { button.getBoundsInScreen(this) }
                size.width() >= minimum && size.height() >= minimum
            } == true
        }
        val r = bounds(label)
        assertTrue("$label target clipped: $r", r.width() >= minimum && r.height() >= minimum)
        val safe = main {
            val foreground = ActivityLifecycleMonitorRegistry.getInstance().getActivitiesInStage(Stage.RESUMED).last()
            val view = foreground.window.decorView
            val insets = ViewCompat.getRootWindowInsets(view)!!.getInsets(
                WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout())
            Rect(insets.left, insets.top, view.width - insets.right, view.height - insets.bottom)
        }
        assertTrue("$label outside safe bounds: $r / $safe", safe.contains(r))
        return r
    }
    private fun reachable(label: String) {
        fun scroll(n: AccessibilityNodeInfo): AccessibilityNodeInfo? {
            if (n.isScrollable && n.isVisibleToUser) return n
            for (i in 0 until n.childCount) n.getChild(i)?.let { scroll(it)?.let { found -> return found } }
            return null
        }
        repeat(8) {
            if (node(label) != null) return
            inst.uiAutomation.rootInActiveWindow?.let(::scroll)?.performAction(AccessibilityNodeInfo.ACTION_SCROLL_FORWARD)
            Thread.sleep(150)
        }
        assertNotNull(label, node(label))
    }
    private fun shot(name: String) {
        await("validation foreground for screenshot") {
            inst.uiAutomation.rootInActiveWindow?.packageName?.toString() == context.packageName
        }
        inst.waitForIdleSync()
        val bitmap = inst.uiAutomation.takeScreenshot()!!
        val file = File(context.filesDir, "validation-m13/$label-$name.png")
        file.parentFile!!.mkdirs()
        file.outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
        bitmap.recycle()
    }
    private fun metadata() = File(context.filesDir, "sessions/${fixture.id}/session.json").readText()

    @Test fun idleLayout() {
        assertFalse(model.snapshots.value.openSession)
        assertTarget("Switch to front camera"); assertTarget("Start recording")
        assertTarget("Open saved media gallery"); assertTarget("Photo")
        shot("idle-video")
        click("Photo")
        await("photo mode") { node("Take photo") != null }
        assertTarget("Take photo"); assertTarget("Video")
        shot("idle-photo")
        click("Video")
        await("video mode") { node("Start recording") != null && model.cameraReady }
    }

    @Test fun captureAndFinishFlow() {
        assertFalse("Use -e idle true", model.snapshots.value.openSession)
        val lens = assertTarget("Switch to front camera")
        val record = assertTarget("Start recording")
        assertTarget("Open saved media gallery"); assertTarget("Photo")
        if (!landscape) assertEquals("Shutter centred", main { activity.window.decorView.width / 2f }, record.exactCenterX(), 3f)
        shot("idle-video")
        click("Photo")
        await("photo mode") { node("Take photo") != null }
        shot("idle-photo")
        click("Video")
        await("video ready") { model.cameraReady && node("Start recording") != null }
        // Inject a known pose; this is a real CameraX recording, not a physical-rotation test.
        fun previewBounds(): Rect? = main {
            fun find(v: View): androidx.camera.view.PreviewView? {
                if (v is androidx.camera.view.PreviewView) return v
                if (v is android.view.ViewGroup) for (i in 0 until v.childCount) find(v.getChildAt(i))?.let { return it }
                return null
            }
            find(activity.window.decorView)?.let { Rect().also(it::getGlobalVisibleRect) }
        }
        val idlePreview = checkNotNull(previewBounds()) { "Preview not bound" }
        model.start(if (landscape) 90 else 0)
        // START must not move the capture frame: no warning row may enlarge the header
        // and the shutter must keep its footprint.
        val startSamples = mutableListOf<Rect>()
        val deadline = SystemClock.uptimeMillis() + 4_000
        while (SystemClock.uptimeMillis() < deadline &&
            model.snapshots.value.state != RecordingState.Recording) {
            previewBounds()?.let { startSamples += it }
            assertNull("Audio startup must not read as a failure", node("Recording audio unavailable"))
            Thread.sleep(20)
        }
        await("recording samples") { model.snapshots.value.state == RecordingState.Recording &&
            model.snapshots.value.activeDurationMs >= 1500 }
        previewBounds()?.let { startSamples += it }
        assertTrue("Expected samples during START", startSamples.isNotEmpty())
        startSamples.forEach { assertEquals("Preview must not move on START", idlePreview, it) }
        val session = model.snapshots.value.session!!
        val finish = assertTarget("Finish skit")
        val pause = assertTarget("Pause recording")
        assertNull(node("Switch to front camera"))
        assertNull(node("Switch to rear camera"))
        if (!landscape) assertEquals(lens, finish)
        assertFalse(Rect.intersects(pause, finish))
        shot("recording")
        click("Finish skit")
        assertTarget("Finish and export"); assertTarget("Continue recording")
        assertEquals(RecordingState.Recording, model.snapshots.value.state)
        val duration = model.snapshots.value.activeDurationMs
        Thread.sleep(900)
        assertTrue("Dialog must not stop capture", model.snapshots.value.activeDurationMs > duration)
        assertTrue("No journal before confirmation", ExportJournal.status.value?.sessionId != session.id)
        shot("finish-active")
        click("Continue recording")
        assertEquals(RecordingState.Recording, model.snapshots.value.state)
        assertEquals(session.pending?.id, model.snapshots.value.session?.pending?.id)
        assertEquals(0, model.snapshots.value.session!!.segments.size)
        click("Finish skit")
        target("Continue recording")
        shell("input keyevent 4")
        await("Back dismisses only dialog") { main { activity.finishConfirmation.sessionId == null } }
        assertEquals(RecordingState.Recording, model.snapshots.value.state)
        click("Finish skit")
        val confirm = target("Finish and export")
        assertTrue(confirm.performAction(AccessibilityNodeInfo.ACTION_CLICK))
        confirm.performAction(AccessibilityNodeInfo.ACTION_CLICK)
        await("export", 180_000) { ExportJournal.status.value?.let { it.sessionId == session.id && !it.active } == true }
        assertEquals(ExportState.Completed, ExportJournal.status.value?.state)
        assertEquals(1, model.snapshots.value.session!!.segments.size)
        val result = VideoInspector.inspect(model.snapshots.value.session!!.segments.single().path)
        assertTrue(result.valid())
        await("return to camera") { node("Start recording") != null && model.cameraReady }
        shot("returned-camera")
    }

    @Test fun pausedAndReorderDialogs() {
        val before = metadata()
        val finish = assertTarget("Finish skit")
        val resume = assertTarget("Resume recording")
        assertTarget("Play skit from beginning")
        if (landscape) assertNotNull("Compact rail preserves the full time/count announcement",
            node("Segment count. 2 kept. Skit duration 00:01."))
        assertNull(node("Switch to front camera"))
        assertFalse(Rect.intersects(finish, resume))
        shot("paused")
        click("Finish skit")
        reachable("Finish and export"); assertTarget("Finish and export")
        reachable("Keep editing"); assertTarget("Keep editing")
        val gate = main { activity.finishConfirmation }
        scenario.recreate()
        scenario.onActivity { activity = it }
        await("confirmation after recreation") { node("Finish this skit?") != null }
        assertSame(gate, main { activity.finishConfirmation })
        assertEquals(fixture.id, main { activity.finishConfirmation.sessionId })
        assertTrue(ExportJournal.status.value?.sessionId != fixture.id)
        shot("finish-paused")
        click("Keep editing")
        assertEquals(before, metadata())
        assertTrue(ExportJournal.status.value?.sessionId != fixture.id)
        assertEquals(finish, bounds("Finish skit"))
        val card = target("Review segment 1")
        assertTrue(card.performAction(AccessibilityNodeInfo.ACTION_LONG_CLICK))
        await("reorder unlocked") { main { activity.inlineReorderModel.draft != null } }
        val moving = target("Review segment 1")
        val move = moving.actionList.single { it.label?.toString() == "Move down" }
        assertTrue(moving.performAction(move.id))
        click("Play skit from beginning")
        for (action in listOf("Done and continue", "Cancel changes", "Keep reordering")) {
            reachable(action); assertTarget(action)
        }
        shot("reorder-confirmation")
        click("Keep reordering")
        assertNotNull(main { activity.inlineReorderModel.draft })
        assertEquals(before, metadata())
        shot("reorder-unlocked")
        click("Cancel")
        assertEquals(before, metadata())
    }

    @Test fun reviewTrimAndPlayback() {
        val sourceHashes = fixture.segments.associate { it.path to sha(File(it.path)) }
        click("Review segment 1")
        await("trim and player", 60_000) { main { activity.trimModel.ready &&
            activity.reviewController.player?.playbackState == Player.STATE_READY } }
        assertTarget("Close")
        assertNull("No lower discard button", node("Discard segment"))
        assertNull("No lower skit-play button", node("Play from here"))
        assertTarget("More actions for segment 1")
        assertTarget("Play")
        fun playerView(v: View): PlayerView? {
            if (v is PlayerView) return v
            if (v is android.view.ViewGroup) for (i in 0 until v.childCount) playerView(v.getChildAt(i))?.let { return it }
            return null
        }
        main {
            val view = playerView(activity.window.decorView)!!
            val media = fixture.segments.first().media!!
            val aspect = com.calvinmazhindu.skitcam.camera.ViewfinderGeometry.aspect(media.width, media.height, media.rotation)!!
            assertEquals("Aspect fit", aspect, view.width.toFloat() / view.height, .02f)
        }
        shot("review-trim")
        val handle = node("Trim start handle")!!
        assertTrue(handle.performAction(handle.actionList.single { it.label?.toString() == "Move later one frame" }.id))
        await("dirty trim") { main { activity.trimModel.dirty } }
        assertTarget("Save trim"); click("Save trim")
        await("trim saved") { main { !activity.trimModel.saving && !activity.trimModel.dirty } }
        val saved = model.snapshots.value.session!!.segments.first().trim
        assertNotEquals(fixture.segments.first().trim, saved)
        click("Reset trim")
        await("reset draft") { main { activity.trimModel.dirty } }
        click("Close")
        reachable("Keep editing"); click("Keep editing")
        assertNotNull(main { activity.reviewController.selection })
        click("Close")
        reachable("Discard"); click("Discard")
        assertEquals(saved, model.snapshots.value.session!!.segments.first().trim)
        click("Review segment 1")
        await("review ready") { main { activity.trimModel.ready } }
        click("More actions for segment 1")
        assertTarget("Discard segment 1")
        click("Discard segment 1")
        await("discarded") { !model.snapshots.value.session!!.segments.first().kept }
        click("More actions for segment 1")
        assertTarget("Restore segment 1"); click("Restore segment 1")
        await("restored") { model.snapshots.value.session!!.segments.first().kept }
        click("More actions for segment 1")
        assertTarget("Play from segment 1")
        click("Play from segment 1")
        await("skit playing") { main { activity.playback.player?.isPlaying == true } }
        assertTarget("Pause"); assertTarget("Close playback"); assertTarget("Turn loop off")
        shot("play-skit")
        click("Close playback")
        sourceHashes.forEach { (path, hash) -> assertEquals(hash, sha(File(path))) }
    }

    @Test fun reviewTrimControlsNeverMovePreview() {
        val sourceHashes = fixture.segments.associate { it.path to sha(File(it.path)) }
        click("Review segment 1")
        await("trim ready", 60_000) { main { activity.trimModel.ready &&
            activity.reviewController.player?.playbackState == Player.STATE_READY } }
        click("Reset trim")
        click("Save trim")
        await("full range saved") { main { !activity.trimModel.saving && !activity.trimModel.dirty } }
        await("clean trim controls disabled") {
            node("Save trim")?.isEnabled == false && node("Reset trim")?.isEnabled == false
        }
        assertNull(node("Discard segment"))
        assertNull(node("Play from here"))
        fun previewBounds(): Rect = main {
            fun find(v: View): PlayerView? {
                if (v is PlayerView) return v
                if (v is android.view.ViewGroup) for (i in 0 until v.childCount) find(v.getChildAt(i))?.let { return it }
                return null
            }
            Rect().also { find(activity.window.decorView)!!.getGlobalVisibleRect(it) }
        }
        val controls = listOf("Jump to segment start", "Play", "Save trim", "Reset trim")
        fun nodeBounds(label: String): Rect {
            await(label) { node(label) != null }
            return Rect().apply { node(label)!!.getBoundsInScreen(this) }
        }
        fun geometry(): Map<String, Rect> = mapOf("preview" to previewBounds()) +
            controls.associateWith(::bounds) +
            listOf("Trim timeline", "Review segment 1").associateWith(::nodeBounds)
        val clean = geometry()
        val observations = linkedMapOf("clean" to clean)
        fun assertStable(stage: String) {
            inst.waitForIdleSync()
            val current = geometry()
            observations[stage] = current
            assertEquals("Preview, controls, timeline and shelf must not jump: $stage", clean, current)
        }
        for (label in controls) {
            val target = assertTarget(label)
            assertTrue("$label must sit above the playhead: $target / ${clean.getValue("Trim timeline")}",
                target.bottom <= clean.getValue("Trim timeline").top)
            assertTrue("$label must share the playback row",
                abs(target.centerY() - clean.getValue("Play").centerY()) <= 2)
        }
        for (first in controls) for (second in controls) if (first < second) {
            assertFalse("$first overlaps $second", Rect.intersects(clean.getValue(first), clean.getValue(second)))
        }
        shot("review-stable-clean")
        fun dragGrip(label: String, toX: Float, midway: (() -> Unit)? = null) {
            val grip = nodeBounds(label)
            val down = SystemClock.uptimeMillis()
            fun event(action: Int, x: Float) {
                val input = MotionEvent.obtain(down, SystemClock.uptimeMillis(), action, x,
                    grip.centerY().toFloat(), 0)
                input.source = InputDevice.SOURCE_TOUCHSCREEN
                assertTrue(inst.uiAutomation.injectInputEvent(input, true))
                input.recycle()
            }
            val fromX = grip.centerX().toFloat()
            event(MotionEvent.ACTION_DOWN, fromX)
            try {
                Thread.sleep(700)
                for (step in 1..24) {
                    event(MotionEvent.ACTION_MOVE, fromX + (toX - fromX) * step / 24)
                    Thread.sleep(20)
                    if (step == 12) midway?.invoke()
                }
            } finally { event(MotionEvent.ACTION_UP, toX) }
        }
        fun tapTimeline(ms: Long) {
            val track = nodeBounds("Trim timeline")
            val span = main { activity.trimModel.draft!!.sourceDurationMs }
            val x = track.left + track.width() * ms.toFloat() / span
            val down = SystemClock.uptimeMillis()
            for (action in listOf(MotionEvent.ACTION_DOWN, MotionEvent.ACTION_UP)) {
                val input = MotionEvent.obtain(down, SystemClock.uptimeMillis(), action,
                    x, track.centerY().toFloat(), 0)
                input.source = InputDevice.SOURCE_TOUCHSCREEN
                assertTrue(inst.uiAutomation.injectInputEvent(input, true))
                input.recycle()
                Thread.sleep(60)
            }
        }
        // Put the playhead at 400ms of the 1000ms source, then drag the start grip
        // to ~8dp beside it. Snapping must land exactly on the playhead while the
        // finger position maps tens of milliseconds away.
        tapTimeline(400)
        fun drawnPlayhead(): Long? {
            val description = node("Trim timeline")?.stateDescription?.toString() ?: return null
            val match = Regex("""Playhead (\d+):(\d+)\.(\d+)""").find(description) ?: return null
            val (minutes, seconds, millis) = match.destructured
            return minutes.toLong() * 60_000 + seconds.toLong() * 1_000 + millis.toLong()
        }
        var stableReadings = 0
        var lastReading = -1L
        await("playhead settles near 400ms") {
            val reading = drawnPlayhead()
            stableReadings = if (reading != null && reading == lastReading) stableReadings + 1 else 0
            lastReading = reading ?: -1L
            stableReadings >= 3 && reading != null && reading in 300L..500L
        }
        val playheadSource = lastReading
        val track = nodeBounds("Trim timeline")
        val span = main { activity.trimModel.draft!!.sourceDurationMs }
        val playX = track.left + track.width() * playheadSource.toFloat() / span
        dragGrip("Trim start handle", playX + 8 * context.resources.displayMetrics.density, midway = {
            // The playhead anchor is a fixed reference while a trim edge moves.
            assertEquals("Playhead must not drift with the trim handle", playheadSource, drawnPlayhead())
        })
        shot("review-stable-after-snap-drag")
        try {
            await("grip snaps to playhead") { main {
                abs(activity.trimModel.draft!!.trimStartMs - playheadSource) <= 20
            } }
        } catch (failure: AssertionError) {
            val details = main {
                val draft = activity.trimModel.draft!!
                "expected≈$playheadSource playX=$playX start=${draft.trimStartMs} end=${draft.trimEndMs} " +
                    "source=${draft.sourceDurationMs} pos=${activity.reviewController.player?.currentPosition} " +
                    "playing=${activity.reviewController.player?.isPlaying}"
            }
            throw AssertionError("snap failed; $details", failure)
        }
        assertTrue("Snapped trim is still a valid edit",
            main { activity.trimModel.draft!!.valid() })
        assertStable("snap-to-playhead")
        shot("review-stable-snapped")
        click("More actions for segment 1")
        assertTarget("Discard segment 1")
        assertNull("Unsaved trim still blocks skit playback", node("Play from segment 1"))
        shell("input keyevent 4")
        await("menu dismissed") { node("Discard segment 1") == null }
        click("Reset trim")
        await("full range restored") { node("Save trim")?.isEnabled == false && node("Reset trim")?.isEnabled == false }
        assertStable("reset")
        val handle = node("Trim start handle")!!
        assertTrue(handle.performAction(handle.actionList.single { it.label?.toString() == "Move later one frame" }.id))
        await("trim-in enables actions") { node("Save trim")?.isEnabled == true && node("Reset trim")?.isEnabled == true }
        assertTarget("Save trim"); assertTarget("Reset trim")
        assertStable("trim-in")
        shot("review-stable-trim-in")
        val track2 = nodeBounds("Trim timeline")
        dragGrip("Trim end handle", track2.left + track2.width() * .7f)
        await("end handle dragged") { main {
            activity.trimModel.draft!!.trimEndMs < activity.trimModel.draft!!.sourceDurationMs - 100
        } }
        assertStable("drag-trim-out")
        shot("review-stable-drag")
        val endHandle = node("Trim end handle")!!
        assertTrue(endHandle.performAction(endHandle.actionList.single { it.label?.toString() == "Move earlier one frame" }.id))
        await("trim-out enables actions") { node("Save trim")?.isEnabled == true }
        assertStable("trim-out")
        shot("review-stable-dirty")
        click("Save trim")
        try {
            await("trim saved") { main { !activity.trimModel.dirty && !activity.trimModel.saving } &&
                node("Save trim")?.isEnabled == false }
        } catch (failure: AssertionError) {
            val details = main {
                "dirty=${activity.trimModel.dirty} saving=${activity.trimModel.saving} " +
                    "error=${activity.trimModel.error} notice=${activity.trimModel.notice} " +
                    "canSave=${activity.trimModel.canSave}"
            }
            throw AssertionError("save stalled; $details", failure)
        }
        assertStable("saved")
        assertTrue("Saved trim can still be reset", target("Reset trim").isEnabled)
        click("Reset trim")
        await("reset saved range is dirty") { node("Save trim")?.isEnabled == true }
        assertStable("reset-saved")
        click("Save trim")
        await("full range saved again") { node("Save trim")?.isEnabled == false && node("Reset trim")?.isEnabled == false }
        assertStable("saved-full")
        File(context.filesDir, "validation-m13/$label-review-stable.txt").writeText(
            observations.entries.joinToString("\n") { (stage, rects) ->
                "$stage=" + rects.entries.joinToString("; ") { (name, rect) -> "$name:${rect.flattenToString()}" }
            } + "\n")
        sourceHashes.forEach { (path, hash) -> assertEquals(hash, sha(File(path))) }
    }

    @Test fun exportAndGalleryPresentation() {
        val states = listOf(ExportState.Preparing to "Preparing video", ExportState.Exporting to "Finishing video",
            ExportState.Verifying to "Checking video", ExportState.Publishing to "Saving to gallery",
            ExportState.Failed to "Could not finish video", ExportState.Cancelled to "Finishing cancelled")
        try {
            for ((state, title) in states) {
                main { ExportJournal.status.value = ExportStatus(fixture.id, state, "Presentation fixture · originals preserved") }
                await(title) { node(title) != null }
                if (state in setOf(ExportState.Preparing, ExportState.Exporting, ExportState.Verifying)) {
                    reachable("Cancel export"); assertTarget("Cancel export")
                }
                if (state == ExportState.Failed || state == ExportState.Cancelled) {
                    reachable("Retry"); assertTarget("Retry")
                }
                shot("export-${state.name.lowercase()}")
            }
        } finally { main { ExportJournal.status.value = null } }
        ActivityScenario.launch(SavedMediaActivity::class.java).use {
            await("gallery") { node("Gallery") != null }
            assertTarget("Refresh gallery")
            shot("gallery")
        }
    }
}
