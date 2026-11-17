package com.calvinmazhindu.skitcam.arrange

import android.Manifest
import android.graphics.Bitmap
import android.graphics.Rect
import android.graphics.Color
import android.media.MediaMetadataRetriever
import android.os.Build
import android.os.SystemClock
import android.view.InputDevice
import android.view.MotionEvent
import android.view.accessibility.AccessibilityNodeInfo
import androidx.media3.common.Player
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.calvinmazhindu.skitcam.MainActivity
import com.calvinmazhindu.skitcam.recording.*
import com.calvinmazhindu.skitcam.trim.TrimRange
import com.calvinmazhindu.skitcam.video.VideoInspector
import com.calvinmazhindu.skitcam.export.ExportJournal
import com.calvinmazhindu.skitcam.export.ExportState
import org.junit.*
import org.junit.Assert.*
import org.junit.runner.RunWith
import java.io.File
import java.security.MessageDigest
import java.util.UUID
import java.util.concurrent.atomic.AtomicReference

/** Each invocation seeds only a new fixture in the separately installed validation package. */
@RunWith(AndroidJUnit4::class)
class InlineReorderDeviceTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context get() = instrumentation.targetContext
    private val args get() = InstrumentationRegistry.getArguments()
    private lateinit var scenario: ActivityScenario<MainActivity>
    private lateinit var model: RecordingViewModel
    private lateinit var editor: InlineReorderViewModel
    private lateinit var fixture: Session
    private lateinit var hashes: Map<String, String>
    private var originalRotation: String? = null
    private fun shell(command: String) = instrumentation.uiAutomation.executeShellCommand(command).use {
        android.os.ParcelFileDescriptor.AutoCloseInputStream(it).bufferedReader().readText().trim()
    }
    private fun hash(file: File): String = MessageDigest.getInstance("SHA-256").let { digest ->
        file.inputStream().use { input ->
            val bytes = ByteArray(65536)
            while (true) { val n = input.read(bytes); if (n < 0) break; digest.update(bytes, 0, n) }
        }
        digest.digest().joinToString("") { "%02x".format(it) }
    }
    private fun <T> main(block: () -> T): T {
        val result = AtomicReference<T>()
        instrumentation.runOnMainSync { result.set(block()) }
        return result.get()
    }
    private fun await(message: String, timeout: Long = 25_000, condition: () -> Boolean) {
        val end = SystemClock.uptimeMillis() + timeout
        while (!condition() && SystemClock.uptimeMillis() < end) Thread.sleep(100)
        assertTrue("Timed out: $message", condition())
    }
    @Before fun open() {
        check(context.packageName.endsWith(".m12finlinevalidation")) { "Validation package only." }
        shell("cmd statusbar collapse")
        originalRotation = shell("wm user-rotation")
        val landscape = args.getString("landscape") == "true"
        shell("wm user-rotation lock ${if (landscape) 1 else 0}")
        for (permission in listOf(Manifest.permission.CAMERA, Manifest.permission.RECORD_AUDIO))
            shell("pm grant ${context.packageName} $permission")
        val count = args.getString("count")?.toInt() ?: 3
        val recovery = args.getString("recover") == "true"
        if (!recovery) {
            val id = UUID.randomUUID().toString()
            val directory = File(context.filesDir, "sessions/$id").apply { mkdirs() }
            val sources = listOf("red", "green", "blue").map { File(context.filesDir, "inline-inputs/$it.mp4") }
            sources.forEach { check(it.isFile) { "Push generated inline-inputs first." } }
            val facts = sources.map { VideoInspector.inspect(it.path) }
            val segments = List(count) { index ->
                val file = File(directory, "segment_%03d.mp4".format(index + 1))
                sources[index % 3].copyTo(file)
                val info = facts[index % 3]
                Segment(index + 1, file.path, 1000, if (landscape) 90 else 0, info.rotation,
                    kept = count == 1 || index % 4 != 1, id = "inline-$index-${UUID.randomUUID()}",
                    trim = TrimRange(1000, 100, 900), media = info)
            }
            fixture = Session(id, System.currentTimeMillis(), if (landscape) 90 else 0,
                RecordingState.Paused, segments)
            SessionFileStore(File(context.filesDir, "sessions")).save(fixture)
        } else {
            fixture = SessionFileStore(File(context.filesDir, "sessions")).loadLatest()!!
        }
        hashes = fixture.segments.associate { it.path to hash(File(it.path)) }
        scenario = ActivityScenario.launch(MainActivity::class.java)
        bind()
        await("recovered") { model.recovered.value && !main { editor.recovering } }
        await("requested screen orientation") {
            var matches = false
            scenario.onActivity { matches = (it.window.decorView.width > it.window.decorView.height) == landscape }
            matches
        }
        args.getString("font")?.toFloat()?.let { expected ->
            await("requested font scale $expected") {
                var scale = 0f
                scenario.onActivity { scale = it.resources.configuration.fontScale }
                kotlin.math.abs(scale - expected) < 0.01f
            }
        }
        await("paused shelf") { node { it.contentDescription?.toString() == "Completed segments, vertical strip" } != null }
        assertEquals(fixture.id, model.snapshots.value.session!!.id)
    }
    private fun bind() = scenario.onActivity { model = it.recordingModel; editor = it.inlineReorderModel }
    @After fun close() {
        try {
            if (::scenario.isInitialized) {
                val debug = File(context.filesDir, "validation-inline/${fixture.id}-nodes.txt")
                debug.parentFile!!.mkdirs()
                val lines = mutableListOf<String>()
                fun dump(n: AccessibilityNodeInfo) {
                    lines += "${n.contentDescription} | ${n.text} | ${n.stateDescription} | visible=${n.isVisibleToUser} | ${n.actionList.map { it.label to it.id }}"
                    for (i in 0 until n.childCount) n.getChild(i)?.let(::dump)
                }
                instrumentation.uiAutomation.rootInActiveWindow?.let { root ->
                    lines += "rootPackage=${root.packageName}"
                    if (root.packageName?.toString() == context.packageName) dump(root)
                    else lines += "External UI interrupted validation; its contents are not captured."
                }
                debug.writeText(main { "draft=${editor.draft} dragging=${editor.draggingId} error=${editor.error}\n" } + lines.joinToString("\n"))
                screenshot("final")
            }
            if (::hashes.isInitialized) hashes.forEach { (path, before) ->
                assertEquals("Original media changed: $path", before, hash(File(path)))
            }
            if (::model.isInitialized) assertEquals(fixture.segments,
                model.snapshots.value.session!!.segments.take(fixture.segments.size))
            if (::scenario.isInitialized) scenario.close()
        } finally { originalRotation?.let { shell("wm user-rotation $it") } }
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
    private fun card(order: Int) = node { it.contentDescription?.startsWith("Review segment $order,") == true }
    private fun bounds(node: AccessibilityNodeInfo): Rect = Rect().apply { node.getBoundsInScreen(this) }
    private fun click(n: AccessibilityNodeInfo) {
        var target = n
        while (!target.isClickable && target.parent != null) target = target.parent
        assertTrue(target.performAction(AccessibilityNodeInfo.ACTION_CLICK))
    }
    private fun text(value: String) {
        await(value) { node { it.text?.toString() == value } != null }
        click(node { it.text?.toString() == value }!!)
    }
    private fun metadata() = File(context.filesDir, "sessions/${fixture.id}/session.json").readText()
    private fun tap(rect: Rect) {
        val down = SystemClock.uptimeMillis()
        for (action in listOf(MotionEvent.ACTION_DOWN, MotionEvent.ACTION_UP)) {
            val event = MotionEvent.obtain(down, SystemClock.uptimeMillis(), action, rect.exactCenterX(), rect.exactCenterY(), 0)
            event.source = InputDevice.SOURCE_TOUCHSCREEN
            assertTrue(instrumentation.uiAutomation.injectInputEvent(event, true))
            event.recycle()
            if (action == MotionEvent.ACTION_DOWN) Thread.sleep(80)
        }
    }
    private fun longPress(order: Int) {
        await("card $order") { card(order) != null }
        val point = bounds(card(order)!!)
        touch(point, point.exactCenterY(), 600, 0)
        await("unlocked") { main { editor.draft != null && editor.draggingId == null } }
    }
    private fun touch(from: Rect, toY: Float, hold: Long = 600, edgeHold: Long = 0,
        duringHold: (() -> Unit)? = null) {
        val down = SystemClock.uptimeMillis()
        val x = from.exactCenterX(); val y = from.exactCenterY()
        fun event(action: Int, py: Float) {
            val e = MotionEvent.obtain(down, SystemClock.uptimeMillis(), action, x, py, 0)
            e.source = InputDevice.SOURCE_TOUCHSCREEN
            assertTrue(instrumentation.uiAutomation.injectInputEvent(e, true)); e.recycle()
        }
        event(MotionEvent.ACTION_DOWN, y)
        try {
            Thread.sleep(hold)
            for (step in 1..20) {
                event(MotionEvent.ACTION_MOVE, y + (toY - y) * step / 20)
                Thread.sleep(20)
            }
            Thread.sleep(edgeHold)
            duringHold?.invoke()
        } finally { event(MotionEvent.ACTION_UP, toY) }
    }
    private fun moveAction(order: Int, label: String) {
        await("accessible $label for segment $order") {
            card(order)?.actionList?.any { it.label?.toString() == label } == true
        }
        val n = card(order)!!
        val action = n.actionList.single { it.label?.toString() == label }
        assertTrue(n.performAction(action.id))
    }
    private fun screenshot(name: String) {
        val image = instrumentation.uiAutomation.takeScreenshot()!!
        val file = File(context.filesDir, "validation-inline/${fixture.id}-$name.png")
        file.parentFile!!.mkdirs()
        file.outputStream().use { image.compress(Bitmap.CompressFormat.PNG, 100, it) }; image.recycle()
    }
    @Test fun tapLongPressCancelDoneAndPlayback() {
        val before = metadata()
        await("first thumbnail") { card(1) != null }
        tap(bounds(card(1)!!))
        await("Review") { scenarioReviewReady() }
        assertNull(main { editor.draft })
        text("Close")
        await("shelf again") { card(1) != null }
        longPress(1)
        assertEquals(before, metadata())
        if (fixture.segments.size > 1) {
            moveAction(1, "Move down")
            await("draft changed") { main { editor.draft!!.dirty } }
            moveAction(1, "Move up")
            await("move up restored order") { main { !editor.draft!!.dirty } }
            moveAction(1, "Move down")
        }
        assertEquals(before, metadata())
        text("Cancel")
        await("cancelled") { main { editor.draft == null } }
        assertEquals(before, metadata())
        longPress(1)
        if (fixture.segments.size > 1) moveAction(1, "Move down")
        val expected = main { editor.draft!!.ids }
        text("Done")
        await("saved") { main { editor.draft == null } }
        assertEquals(expected, model.snapshots.value.session!!.orderedSegmentIds)
        val kept = fixture.segments.filter { it.kept }.map { it.id }.toSet()
        val playback = expected.filter { it in kept }
        assertEquals(playback, model.snapshots.value.session!!.playbackOrder)
        text("Play skit")
        await("whole-skit playback") {
            var ready = false
            scenario.onActivity { ready = it.playback.active && it.playback.player?.playbackState == Player.STATE_READY }
            ready
        }
        scenario.onActivity { assertEquals(playback, it.playback.plan!!.clips.map { clip -> clip.segmentId }) }
        screenshot("done-playback")
    }
    private fun scenarioReviewReady(): Boolean {
        var ready = false
        scenario.onActivity { ready = it.reviewController.player?.playbackState == Player.STATE_READY }
        return ready
    }
    @Test fun dragAutoScrollAndNavigationGuard() {
        Assume.assumeTrue(fixture.segments.size >= 3)
        val before = metadata()
        await("first thumbnail") { card(1) != null }
        val from = bounds(card(1)!!)
        val shelf = bounds(node { it.contentDescription?.toString() == "Completed segments, vertical strip" }!!)
        touch(from, shelf.bottom - 10f, edgeHold = 1800, duringHold = {
            assertTrue("Drag interrupted; active window=${instrumentation.uiAutomation.rootInActiveWindow?.packageName}",
                main { editor.draft != null && editor.draggingId != null })
            screenshot("drag")
        })
        await("drag settled") { main { editor.draggingId == null && editor.draft!!.dirty } }
        val pending = main { editor.draft!!.ids }
        if (fixture.segments.size >= 20)
            assertTrue("Edge auto-scroll must move beyond initial viewport", pending.indexOf(fixture.segments[0].id) >= 4)
        assertEquals(before, metadata())
        text("Play skit")
        text("Keep reordering")
        assertEquals(pending, main { editor.draft!!.ids })
        text("Finish")
        text("Keep reordering")
        assertEquals(RecordingState.Paused, model.snapshots.value.state)
        scenario.recreate(); bind()
        await("draft after recreation") { main { editor.draft?.ids == pending } }
        await("camera after recreation") { model.cameraReady }
        await("decoded thumbnail after recreation") {
            node { it.stateDescription?.toString()?.contains("Thumbnail ready") == true } != null
        }
        screenshot("recreated")
        text("Done")
        await("committed") { main { editor.draft == null } }
        assertEquals(pending, model.snapshots.value.session!!.orderedSegmentIds)
    }
    @Test fun checkpointForProcessDeath() {
        longPress(1)
        if (fixture.segments.size > 1) moveAction(1, "Move down")
        val expected = main { editor.draft!!.ids }
        await("checkpoint") {
            runCatching {
                InlineReorderDraft.decode(File(context.filesDir, "inline-reorder-draft.json").readText()).ids == expected
            }.getOrDefault(false)
        }
        assertEquals(fixture.playbackOrder, model.snapshots.value.session!!.playbackOrder)
        File(context.filesDir, "inline-expected-checkpoint.json").writeText(main { editor.draft!!.encode() })
        screenshot("checkpoint")
    }
    @Test fun recoverAfterProcessDeath() {
        val expected = InlineReorderDraft.decode(File(context.filesDir, "inline-expected-checkpoint.json").readText())
        await("checkpoint recovered") { main { editor.draft?.ids == expected.ids } }
        assertEquals(expected.baseline.playbackOrder, model.snapshots.value.session!!.playbackOrder)
        screenshot("recovered")
        text("Done")
        await("recovered order committed") { main { editor.draft == null } }
        assertEquals(expected.ids, model.snapshots.value.session!!.orderedSegmentIds)
    }
    @Test fun scrollDoesNotUnlock() {
        Assume.assumeTrue(fixture.segments.size >= 20)
        val before = metadata()
        await("first card") { card(1) != null }
        val shelf = bounds(node { it.contentDescription?.toString() == "Completed segments, vertical strip" }!!)
        val from = Rect(shelf.left + 10, shelf.bottom - 70, shelf.right - 10, shelf.bottom - 30)
        touch(from, shelf.top + 20f, hold = 20)
        assertNull(main { editor.draft })
        assertEquals(before, metadata())
        screenshot("normal-scroll")
    }
    @Test fun topEdgeAutoScroll() {
        assertTrue(fixture.segments.size >= 20)
        val before = metadata()
        await("first card") { card(1) != null }
        var shelf = bounds(node { it.contentDescription?.toString() == "Completed segments, vertical strip" }!!)
        touch(bounds(card(1)!!), shelf.bottom - 10f, edgeHold = 1800)
        await("downward drag settled") { main { editor.draggingId == null && editor.draft != null } }
        val downIndex = main { editor.draft!!.ids.indexOf(fixture.segments[0].id) }
        assertTrue("Downward auto-scroll beyond viewport", downIndex >= 4)
        await("selected card at lower insertion position") { card(1) != null }
        shelf = bounds(node { it.contentDescription?.toString() == "Completed segments, vertical strip" }!!)
        touch(bounds(card(1)!!), shelf.top + 10f, edgeHold = 1800, duringHold = { screenshot("top-edge-drag") })
        await("upward drag settled") { main { editor.draggingId == null } }
        assertTrue("Top edge must auto-scroll back beyond the current viewport",
            main { editor.draft!!.ids.indexOf(fixture.segments[0].id) } < downIndex - 3)
        assertEquals(before, metadata())
        text("Cancel")
        await("cancelled both drags") { main { editor.draft == null } }
        assertEquals(before, metadata())
    }
    @Test fun accessibleUnlockAndNavigationSaveDiscard() {
        await("first thumbnail") { card(1) != null }
        moveAction(1, "Unlock reordering")
        await("accessible unlock") { main { editor.draft != null } }
        moveAction(1, "Move down")
        val before = fixture.playbackOrder
        text("Play skit")
        text("Cancel changes")
        await("play after cancel") {
            var active = false
            scenario.onActivity { active = it.playback.active }
            active
        }
        assertNull(main { editor.draft })
        assertEquals(before, model.snapshots.value.session!!.playbackOrder)
        scenario.onActivity { it.playback.close() }
        await("shelf") { card(1) != null }
        longPress(1)
        moveAction(1, "Move down")
        moveAction(1, "Move down")
        val pending = main { editor.draft!!.ids }
        text("Play skit")
        text("Done and continue")
        await("saved and navigated") {
            var active = false
            scenario.onActivity { active = it.playback.active }
            active && main { editor.draft == null }
        }
        assertEquals(pending, model.snapshots.value.session!!.orderedSegmentIds)
    }
    @Test fun reorderedMp4ContainsBlueThenRedAndNoDiscardedGreen() {
        assertEquals(3, fixture.segments.size)
        longPress(1)
        moveAction(1, "Move down")
        await("first move") { main { editor.draft!!.ids.indexOf(fixture.segments[0].id) == 1 } }
        moveAction(1, "Move down")
        val pending = main { editor.draft!!.ids }
        text("Done")
        await("saved") { main { editor.draft == null } }
        text("Finish")
        await("MP4 export", 180_000) {
            ExportJournal.status.value?.let { it.sessionId == fixture.id && !it.active } == true
        }
        val result = ExportJournal.status.value!!
        assertEquals(result.toString(), ExportState.Completed, result.state)
        assertEquals(pending.filter { id -> fixture.segments.any { it.id == id && it.kept } },
            model.snapshots.value.session!!.playbackOrder)
        val file = File(context.filesDir, "validation-inline/${fixture.id}-export.mp4")
        file.parentFile!!.mkdirs()
        context.contentResolver.openInputStream(android.net.Uri.parse(result.uri))!!.use { input ->
            file.outputStream().use { input.copyTo(it) }
        }
        val retriever = MediaMetadataRetriever()
        try {
            retriever.setDataSource(file.path)
            for ((time, expected) in listOf(400_000L to "blue", 1_200_000L to "red")) {
                val image = retriever.getFrameAtTime(time, MediaMetadataRetriever.OPTION_CLOSEST)!!
                val pixel = image.getPixel(image.width / 2, image.height / 2)
                if (expected == "blue") assertTrue("First kept clip must be blue",
                    Color.blue(pixel) > Color.red(pixel) + 100 && Color.blue(pixel) > Color.green(pixel) + 100)
                else assertTrue("Second kept clip must be red",
                    Color.red(pixel) > Color.blue(pixel) + 100 && Color.red(pixel) > Color.green(pixel) + 100)
                image.recycle()
            }
        } finally { retriever.release() }
        screenshot("exported")
    }
    @Test fun resumeAfterReorderRecordsValidNewSegment() {
        longPress(1)
        moveAction(1, "Move down")
        val pending = main { editor.draft!!.ids }
        text("Done")
        await("saved") { main { editor.draft == null } }
        await("Resume enabled") {
            node { it.contentDescription?.toString() == "Resume recording" && it.isEnabled } != null
        }
        click(node { it.contentDescription?.toString() == "Resume recording" }!!)
        await("recording after reorder") { model.snapshots.value.state == RecordingState.Recording }
        await("recorded media") { model.snapshots.value.activeDurationMs >= 800 }
        click(node { it.contentDescription?.toString() == "Pause recording" }!!)
        await("paused after new segment") { model.snapshots.value.state == RecordingState.Paused }
        val saved = model.snapshots.value.session!!
        assertEquals(fixture.segments.size + 1, saved.segments.size)
        assertEquals(pending, saved.orderedSegmentIds.dropLast(1))
        assertEquals(saved.segments.last().id, saved.orderedSegmentIds.last())
        assertEquals(saved, SessionFileStore(File(context.filesDir, "sessions")).loadLatest())
        SessionFileStore(File(context.filesDir, "sessions")).validate(saved.segments.last().path)
        screenshot("resumed-and-paused")
    }
}
