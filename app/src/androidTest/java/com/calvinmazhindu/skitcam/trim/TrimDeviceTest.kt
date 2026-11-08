package com.calvinmazhindu.skitcam.trim
import com.calvinmazhindu.skitcam.arrange.ArrangementViewModel

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
class TrimDeviceTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context get() = instrumentation.targetContext
    private lateinit var scenario: ActivityScenario<MainActivity>
    private lateinit var model: RecordingViewModel
    private lateinit var editor: ArrangementViewModel
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
        trim = it.trimModel; model = it.recordingModel; editor = it.arrangementModel; review = it.reviewController
    }
    @After fun close() {
        try {
            if (::model.isInitialized && owned != null && model.snapshots.value.session?.id == owned && !leavePaused) {
                main { trim.close(); review.close(); editor.cancel() }
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
        assertTrue("Timed out: $message; ${if (::model.isInitialized) model.snapshots.value else "no model"}; editor=${if (::trim.isInitialized) main { "ready=${trim.ready}, draft=${trim.draft}, error=${trim.error}, player=${review.player?.playbackState}, playback=${review.error}" } else "unbound"}", condition())
    }
    private fun state(state: RecordingState) = await(state.name) { model.snapshots.value.state == state }
    private fun awaitPose(pose: Int) {
        await("stable pose $pose") {
            var matched=false
            scenario.onActivity { matched=it.orientation.pose==pose && com.calvinmazhindu.skitcam.recording.CameraOrientationPolicy.matchesDisplay(pose,it.orientation.displayRotation) }
            matched
        }
    }
    private fun record(count: Int, pose: Int = 0) {
        awaitPose(pose);model.start(pose); state(RecordingState.Recording)
        owned = model.snapshots.value.session!!.id
        repeat(count) { index ->
            if (index > 0) { awaitPose(pose);model.resume(pose); state(RecordingState.Recording) }
            await("recorded samples") { model.snapshots.value.activeDurationMs >= 3000 }
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
        val file = File(context.filesDir, "validation-m5/${UUID.randomUUID()}-$name.png")
        file.parentFile!!.mkdirs()
        file.outputStream().use { image.compress(Bitmap.CompressFormat.PNG, 100, it) }
        image.recycle()
    }
    private fun metadata() = File(context.filesDir, "sessions/${owned!!}/session.json").readText()
    private fun finishAndVerify(pose: Int, arranged: List<String>, history: List<Segment>) {
        awaitPose(pose);model.resume(pose); state(RecordingState.Recording)
        assertNull(main { review.player }); assertNull(main { editor.draft })
        await("audio/video after arranging") { model.snapshots.value.activeDurationMs >= 3000 }
        model.stop(); state(RecordingState.Completed)
        val session = model.snapshots.value.session!!
        assertEquals(history, session.segments.dropLast(1))
        assertEquals(arranged, session.playbackOrder.dropLast(1))
        assertEquals(session.segments.last().id, session.playbackOrder.last())
        val store = SessionFileStore(File(context.filesDir, "sessions"))
        session.segments.forEach { assertEquals(it.videoRotationDegrees, store.validate(it.path)) }
        assertEquals(session, store.loadLatest())
    }
    private fun openTrim(number: Int) {
        click { it.contentDescription?.startsWith("Review segment $number,") == true }
        text("Trim")
        await("trim ready") { main { trim.ready } }
        await("clipped player ready; error=${main { trim.error }}; review=${main { review.error }}") { main { review.player?.playbackState == Player.STATE_READY } }
    }
    private fun dragBoundary(start: Boolean, fraction: Float) {
        val rect = bounds("Trim timeline")
        val from = Rect(rect)
        val x = if(start) rect.left + 5 else rect.right - 5
        from.set(x-1,rect.centerY()-1,x+1,rect.centerY()+1)
        drag(from,rect.left + rect.width()*fraction,rect.exactCenterY())
    }
    private fun digest(path: String) = java.security.MessageDigest.getInstance("SHA-256").digest(File(path).readBytes()).toList()
    @Test fun a_dragPreviewSaveCancelResetAndRecording() {
        record(2)
        val original = model.snapshots.value.session!!
        val hashes = original.segments.associate { it.id to digest(it.path) }
        openTrim(1)
        dragBoundary(true,0.2f); dragBoundary(false,0.8f)
        val chosen = main { trim.draft!! }
        assertTrue("End handle did not move: $chosen", chosen.trimEndMs < chosen.sourceDurationMs)
        assertTrue("Drag did not trim: $chosen; error=${main { trim.error }}",chosen.isTrimmed); assertTrue("Invalid draft $chosen",chosen.valid())
        description("Play")
        await("playing clipped interval") { main { review.player?.isPlaying == true } }
        await("stops at clip end") { main { review.player?.playbackState == Player.STATE_ENDED } }
        main { assertTrue(review.player!!.currentPosition <= chosen.effectiveDurationMs + 100) }
        screenshot("portrait-trim")
        text("Save"); await("saved") { main { trim.segment == null } }
        assertEquals(chosen,model.snapshots.value.session!!.segments[0].trim)
        openTrim(1); assertEquals(chosen,main { trim.draft })
        main { trim.change(chosen.start(0)); trim.settled() };text("Cancel")
        await("cancel") { main { trim.segment == null } }
        assertEquals(chosen,model.snapshots.value.session!!.segments[0].trim)
        openTrim(1);text("Reset Trim"); assertFalse(main { trim.draft!!.isTrimmed });text("Cancel")
        await("cancel reset") { main { trim.segment == null } }
        openTrim(2); main { trim.change(trim.draft!!.start(200));trim.settled() };text("Save")
        await("saved second") { main { trim.segment == null } }
        text("Arrange");await("arrange") { main { editor.draft != null } }
        description("Move segment 2 up");text("Done");await("arranged") { main { editor.draft == null } }
        val session=model.snapshots.value.session!!
        val result=AtomicReference<SegmentEditResult>()
        model.setSegmentKept(session.id,session.segments[0].id,false){result.set(it)}
        await("discard") { model.snapshots.value.count==1 }
        model.setSegmentKept(session.id,session.segments[0].id,true){result.set(it)}
        await("restore") { model.snapshots.value.count==2 }
        assertEquals(chosen,model.snapshots.value.session!!.segments[0].trim)
        model.resume(0);state(RecordingState.Recording)
        assertNull(main { review.player });await("new audio samples") { model.snapshots.value.activeDurationMs>=1500 }
        model.stop();state(RecordingState.Completed)
        val store=SessionFileStore(File(context.filesDir,"sessions"))
        model.snapshots.value.session!!.segments.forEach { store.validate(it.path) }
        original.segments.forEach { assertEquals(hashes[it.id],digest(it.path)) }
    }
    @Suppress("DEPRECATION")
    @Test fun b_landscapeDraftRecreationAndReset() {
        shell("wm user-rotation lock 1")
        await("landscape") { var yes=false;scenario.onActivity { yes=it.windowManager.defaultDisplay.rotation==Surface.ROTATION_90 };yes&&model.cameraReady }
        record(1,270);bindModels();openTrim(1)
        val full=main { trim.draft!! }
        main { trim.change(full.start(300));trim.settled() }
        val draft=main { trim.draft }
        scenario.moveToState(Lifecycle.State.CREATED);scenario.moveToState(Lifecycle.State.RESUMED)
        await("ready after background") { main { trim.ready } };assertEquals(draft,main { trim.draft })
        scenario.recreate();bindModels();await("ready after recreate") { main { trim.ready } }
        assertEquals(draft,main { trim.draft })
        await("timeline after recreation") { node { it.contentDescription?.toString()=="Trim timeline" }!=null }
        text("Zoom in");await("zoomed timeline pan") { node { it.contentDescription?.toString()=="Timeline position" }!=null }
        text("Show end");text("Show start");text("Zoom out")
        instrumentation.waitForIdleSync();Thread.sleep(300);screenshot("landscape-trim")
        // Controls may be below the viewport in landscape; scroll the editor content.
        repeat(4) { if(node { it.text?.toString()=="Reset Trim" }==null) node { it.isScrollable }?.performAction(AccessibilityNodeInfo.ACTION_SCROLL_FORWARD) }
        text("Reset Trim");text("Save");await("saved reset") { main { trim.segment==null } }
        assertEquals(full,model.snapshots.value.session!!.segments.single().trim)
        model.resume(270);state(RecordingState.Recording);await("landscape new audio") { model.snapshots.value.activeDurationMs>=1500 };model.stop();state(RecordingState.Completed)
    }
    @Test fun c_missingCorruptAndInterruptedAtomicWrite() {
        record(1)
        val before=model.snapshots.value
        val root=File(context.filesDir,"validation-m5/atomic-${UUID.randomUUID()}")
        val store=SessionFileStore(root)
        val session=Session("test",1,0,RecordingState.Paused)
        store.save(session)
        val atomic=android.util.AtomicFile(File(root,"test/session.json"))
        atomic.startWrite().use { it.write("{truncated".toByteArray()) } // Simulate death before finishWrite.
        assertEquals(session,store.loadLatest())
        val corrupt=File(root,"corrupt.mp4").apply { writeText("not an MP4") }
        for(path in listOf(File(root,"missing.mp4").path,corrupt.path)) {
            val item=Segment(1,path,1000,0,90)
            main { trim.open(before.session!!.copy(segments=listOf(item), playbackOrder=listOf(item.id)),item) }
            await("source error") { main { trim.error!=null } }
            assertFalse(main { trim.canSave });text("Cancel")
            await("closed unavailable") { main { trim.segment==null } }
        }
        assertEquals(before,model.snapshots.value)
    }
    @Test fun z_restartFixture() {
        record(1);openTrim(1);main { trim.change(trim.draft!!.start(300));trim.settled() };text("Save")
        await("saved") { main { trim.segment==null } }
        leavePaused=true
    }
}
