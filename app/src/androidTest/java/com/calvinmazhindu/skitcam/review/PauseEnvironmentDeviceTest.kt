package com.calvinmazhindu.skitcam.review

import android.Manifest
import android.os.Build
import android.graphics.Bitmap
import android.graphics.Rect
import android.view.Surface
import android.view.accessibility.AccessibilityNodeInfo
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.media3.common.Player
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.calvinmazhindu.skitcam.MainActivity
import com.calvinmazhindu.skitcam.recording.*
import kotlinx.coroutines.runBlocking
import org.junit.*
import org.junit.Assert.*
import org.junit.runner.RunWith
import org.junit.runners.MethodSorters
import java.io.File
import java.util.UUID
import java.util.concurrent.atomic.AtomicReference

@RunWith(AndroidJUnit4::class)
@FixMethodOrder(MethodSorters.NAME_ASCENDING)
class PauseEnvironmentDeviceTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context get() = instrumentation.targetContext
    private lateinit var scenario: ActivityScenario<MainActivity>
    private lateinit var model: RecordingViewModel
    private lateinit var review: SegmentReviewController
    private var originalRotation: String? = null

    private fun shell(command: String): String = instrumentation.uiAutomation.executeShellCommand(command).use {
        android.os.ParcelFileDescriptor.AutoCloseInputStream(it).bufferedReader().readText().trim()
    }
    @Before fun open() {
        check(context.packageName in setOf("com.calvinmazhindu.skitcam.m13validation",
            "com.calvinmazhindu.skitcam.m13reviewvalidation",
            "com.calvinmazhindu.skitcam.m13reviewstablevalidation",
            "com.calvinmazhindu.skitcam.m12cvalidation")) { "Validation package only." }
        for (permission in listOf(Manifest.permission.CAMERA, Manifest.permission.RECORD_AUDIO)) {
            shell("pm grant ${context.packageName} $permission")
        }
        originalRotation = shell("wm user-rotation")
        shell("wm user-rotation lock 0")
        scenario = ActivityScenario.launch(MainActivity::class.java)
        scenario.onActivity { model = it.recordingModel; review = it.reviewController }
        com.calvinmazhindu.skitcam.export.ExportTestSupport.beforeCamera()
        await("camera") { model.cameraReady }
        Assume.assumeTrue("Preserve any existing open session",
            model.snapshots.value.state in setOf(RecordingState.Ready, RecordingState.Completed))
    }
    @After fun close() {
        if (::scenario.isInitialized) { com.calvinmazhindu.skitcam.export.ExportTestSupport.settle(); scenario.close() }
        originalRotation?.let {
            shell("wm user-rotation $it")
        }
    }
    private fun <T> main(block: () -> T): T {
        val value = AtomicReference<T>()
        instrumentation.runOnMainSync { value.set(block()) }
        return value.get()
    }
    private fun await(message: String, condition: () -> Boolean) {
        val deadline = System.nanoTime() + 25_000_000_000L
        while (!condition() && System.nanoTime() < deadline) Thread.sleep(100)
        assertTrue("Timed out: $message; ${if (::model.isInitialized) model.snapshots.value else "no model"}", condition())
    }
    private fun state(state: RecordingState) = await(state.name) { model.snapshots.value.state == state }
    private fun awaitPose(pose: Int) {
        await("stable pose $pose") {
            var matched=false
            scenario.onActivity { matched=it.orientation.pose==pose && CameraOrientationPolicy.matchesDisplay(pose,it.orientation.displayRotation) }
            matched
        }
    }
    private fun begin(pose: Int = 0) {
        awaitPose(pose);scenario.onActivity { model.start(pose) }
        state(RecordingState.Recording)
        await("media samples") { model.snapshots.value.activeDurationMs >= 1200 }
        model.pause(); state(RecordingState.Paused)
    }
    private fun node(predicate: (AccessibilityNodeInfo) -> Boolean): AccessibilityNodeInfo? {
        if (Build.VERSION.SDK_INT >= 33) instrumentation.uiAutomation.clearCache()
        fun find(current: AccessibilityNodeInfo): AccessibilityNodeInfo? {
            if (current.isVisibleToUser && predicate(current)) return current
            for (index in 0 until current.childCount) {
                val child = current.getChild(index) ?: continue
                find(child)?.let { return it }
            }
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
    private fun action(label: String) {
        if (label in setOf("Discard", "Restore")) {
            val order = main {
                model.snapshots.value.session!!.segments.single { it.id == review.selection!!.segmentId }.order
            }
            click { it.contentDescription?.toString() == "More actions for segment $order" }
            click { it.contentDescription?.toString() == "$label segment $order" }
            return
        }
        click { it.text?.toString() == label || it.contentDescription?.toString() == label }
    }
    private fun openFirst() {
        click { it.contentDescription?.startsWith("Review segment 1,") == true }
        await("review ready") { main { review.player?.playbackState == Player.STATE_READY } }
        assertFalse(main { review.player!!.playWhenReady })
    }
    private fun awaitThumbnail(order: Int) {
        await("thumbnail $order decoded") {
            node { it.contentDescription?.startsWith("Review segment $order,") == true &&
                (Build.VERSION.SDK_INT < 30 || it.stateDescription?.toString()?.endsWith("Thumbnail ready") == true) } != null
        }
    }
    private fun play() {
        click { it.contentDescription?.toString() == "Play" }
        await("playing with decoded video") {
            main { review.player?.let { it.isPlaying && it.videoSize.width > 0 && it.currentPosition > 200 } == true }
        }
    }
    private fun assertReviewActionsVisible() {
        await("inline trim track") { node { it.contentDescription?.toString() == "Trim timeline" } != null }
        for (label in listOf("Play", "More actions for segment 1", "Close")) {
            val description = label
            await("$label visible") { node { it.contentDescription?.toString() == description } != null }
            var target = node { it.contentDescription?.toString() == description }!!
            while (!target.isClickable && target.parent != null) target = target.parent
            val bounds = Rect()
            target.getBoundsInScreen(bounds)
            val minimum = (48 * context.resources.displayMetrics.density).toInt()
            assertTrue("$label touch target clipped: $bounds", bounds.height() >= minimum && bounds.width() >= minimum)
        }
    }
    private fun screenshot(name: String) {
        val bitmap = instrumentation.uiAutomation.takeScreenshot() ?: error("No screenshot")
        val file = File(context.filesDir, "validation-m3/${UUID.randomUUID()}-$name.png")
        file.parentFile!!.mkdirs()
        file.outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
        bitmap.recycle()
    }
    private fun verifyResume(pose: Int, landscape: Boolean) {
        awaitPose(pose);model.resume(pose); state(RecordingState.Recording)
        assertNull(main { review.player })
        assertNull(main { review.selection })
        await("audio/video after review") { model.snapshots.value.activeDurationMs >= 1200 }
        model.pause(); state(RecordingState.Paused)
        // The wider M10 landscape preview can show six cards without scrolling.
        // Retain an overflowing fixture so scrolling remains genuinely exercised.
        val expectedCount=if(landscape) 10 else 8
        repeat(expectedCount-2) {
            awaitPose(pose);model.resume(pose); state(RecordingState.Recording)
            await("additional scroll fixture") { model.snapshots.value.activeDurationMs >= 1200 }
            model.pause(); state(RecordingState.Paused)
        }
        val description = "Completed segments, vertical strip"
        await("scrollable strip") { node { it.contentDescription?.toString() == description && it.isScrollable } != null }
        var scrolls = 0
        while (node { it.contentDescription?.startsWith("Review segment $expectedCount,") == true } == null &&
            scrolls++ < expectedCount) {
            assertTrue(node { it.contentDescription?.toString() == description }!!
                .performAction(AccessibilityNodeInfo.ACTION_SCROLL_FORWARD))
            Thread.sleep(300)
        }
        awaitThumbnail(expectedCount)
        screenshot(if (landscape) "landscape-scrolled" else "portrait-scrolled")
        model.stop(); state(RecordingState.Completed)
        val session = model.snapshots.value.session!!
        val store = SessionFileStore(File(context.filesDir, "sessions"))
        session.segments.forEach { assertEquals(it.videoRotationDegrees, store.validate(it.path)) }
        assertEquals(session, store.loadLatest())
    }
    @Test fun a_portraitReviewDecisionsAndRecordingAudio() {
        begin()
        await("vertical strip") { node { it.contentDescription?.toString() == "Completed segments, vertical strip" } != null }
        awaitThumbnail(1)
        val bounds = Rect()
        node { it.contentDescription?.startsWith("Review segment 1,") == true }!!.getBoundsInScreen(bounds)
        assertTrue(bounds.left < 300)
        assertTrue(bounds.width() >= 100 && bounds.height() >= 100)
        screenshot("portrait-strip")
        val first = model.snapshots.value.session!!.segments.single()
        val bytes = File(first.path).length()
        openFirst(); assertReviewActionsVisible(); play()
        assertTrue(model.cameraReady)
        scenario.onActivity { assertTrue(ProcessCameraProvider.getInstance(it).get().isBound(model.port.capture)) }
        action("Discard")
        await("discard saved") { model.snapshots.value.count == 0 }
        assertEquals(0L, model.snapshots.value.durationMs)
        assertEquals(bytes, File(first.path).length())
        action("Restore")
        await("keep restored") { model.snapshots.value.count == 1 }
        screenshot("portrait-review")
        action("Close")
        assertNull(main { review.player })
        openFirst(); play()
        verifyResume(0, false)
        assertEquals(8, model.snapshots.value.count)
    }
    @Suppress("DEPRECATION")
    @Test fun b_landscapeStripAndPlaybackThenResume() {
        shell("wm user-rotation lock 1")
        await("landscape preview") {
            var landscape = false
            scenario.onActivity { landscape = it.windowManager.defaultDisplay.rotation == Surface.ROTATION_90 }
            landscape && model.cameraReady
        }
        begin(270)
        await("vertical shelf in landscape") { node { it.contentDescription?.toString() == "Completed segments, vertical strip" } != null }
        awaitThumbnail(1)
        val bounds = Rect()
        node { it.contentDescription?.startsWith("Review segment 1,") == true }!!.getBoundsInScreen(bounds)
        assertTrue("Shelf remains at the left of the camera", bounds.left < 300)
        screenshot("landscape-strip")
        // Activity recreation during the rotation gives this screen a fresh review controller.
        scenario.onActivity { review = it.reviewController }
        openFirst(); assertReviewActionsVisible(); play(); screenshot("landscape-review")
        verifyResume(270, true)
        assertEquals(10, model.snapshots.value.count)
    }
    @Test fun c_missingCorruptAndThumbnailFallbacks() {
        val source = model.snapshots.value.session!!.segments.first()
        val loader = ThumbnailLoader()
        val frame = runBlocking { loader.load(source) } as ThumbnailResult.Ready
        assertTrue(frame.bitmap.width <= 160 && frame.bitmap.height <= 160)
        assertSame(frame.bitmap, (runBlocking { loader.load(source) } as ThumbnailResult.Ready).bitmap)
        val missing = source.copy(path = File(context.filesDir, "not-present-${UUID.randomUUID()}.mp4").path)
        assertEquals(ThumbnailResult.Unavailable, runBlocking { loader.load(missing) })
        main { review.open("missing-fixture", missing) }
        assertNotNull(main { review.error })
        assertNull(main { review.player })
        main { review.close() }
        val corrupt = File(context.filesDir, "validation-m3/${UUID.randomUUID()}-corrupt.mp4")
        corrupt.parentFile!!.mkdirs(); corrupt.writeText("isolated corrupt media test fixture")
        assertEquals(ThumbnailResult.Unavailable, runBlocking { loader.load(source.copy(path = corrupt.path)) })
        main { review.open("corrupt-fixture", source.copy(path = corrupt.path)) }
        await("playback error handled") { main { review.error != null && review.player == null } }
        assertNotNull(main { review.selection })
        main { review.close() }
        assertEquals(RecordingState.Completed, model.snapshots.value.state)
    }
    @Test fun d_atomicStatusAndRestartFixture() {
        begin()
        openFirst()
        action("Discard")
        await("persisted discard") { model.snapshots.value.count == 0 }
        val discarded = model.snapshots.value.session!!
        assertFalse(SessionFileStore(File(context.filesDir, "sessions")).loadLatest()!!.segments.single().kept)
        scenario.recreate()
        scenario.onActivity { model = it.recordingModel; review = it.reviewController }
        await("restored pause preview") { model.cameraReady }
        assertNull(main { review.selection })
        assertEquals(discarded, model.snapshots.value.session)
        await("discarded thumbnail label") {
            node { it.contentDescription?.toString() == "Review segment 1, Discarded" } != null
        }
        screenshot("discarded-restored")
        model.stop(); state(RecordingState.Completed)
    }
}
