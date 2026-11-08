package com.calvinmazhindu.skitcam.video

import android.Manifest
import android.graphics.Bitmap
import android.graphics.Rect
import android.view.Surface
import android.view.View
import android.view.accessibility.AccessibilityNodeInfo
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.media3.common.Player
import androidx.media3.ui.PlayerView
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.calvinmazhindu.skitcam.MainActivity
import com.calvinmazhindu.skitcam.camera.ViewfinderGeometry
import com.calvinmazhindu.skitcam.recording.*
import com.calvinmazhindu.skitcam.review.SegmentReviewController
import com.calvinmazhindu.skitcam.trim.TrimViewModel
import org.json.JSONArray
import org.json.JSONObject
import org.junit.*
import org.junit.Assert.*
import org.junit.runner.RunWith
import java.io.File
import java.util.concurrent.atomic.AtomicReference
import kotlin.math.roundToInt

@RunWith(AndroidJUnit4::class)
class UnifiedVideoLayoutDeviceTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context get() = instrumentation.targetContext
    private val arguments get() = InstrumentationRegistry.getArguments()
    private val orientation by lazy { arguments.getString("orientation") ?: "portrait" }
    private val label by lazy { arguments.getString("label") ?: "after" }
    private lateinit var scenario: ActivityScenario<MainActivity>
    private lateinit var activity: MainActivity
    private lateinit var model: RecordingViewModel
    private lateinit var review: SegmentReviewController
    private lateinit var trim: TrimViewModel
    private var originalRotation: String? = null
    private var originalFixedRotation: String? = null

    private fun shell(command: String): String = instrumentation.uiAutomation.executeShellCommand(command).use {
        android.os.ParcelFileDescriptor.AutoCloseInputStream(it).bufferedReader().readText().trim()
    }
    @Before fun open() {
        check(context.packageName.endsWith(".m12cvalidation")) { "Use the isolated validation app" }
        for (permission in listOf(Manifest.permission.CAMERA, Manifest.permission.RECORD_AUDIO)) {
            shell("pm grant ${context.packageName} $permission")
        }
        originalRotation = shell("wm user-rotation")
        originalFixedRotation = shell("wm fixed-to-user-rotation")
        shell("wm user-rotation lock 0")
        shell("wm fixed-to-user-rotation enabled")
        scenario = ActivityScenario.launch(MainActivity::class.java)
        scenario.onActivity { activity = it; model = it.recordingModel; review = it.reviewController; trim = it.trimModel }
        com.calvinmazhindu.skitcam.export.ExportTestSupport.beforeCamera()
        await("camera") { model.cameraReady }
        Assume.assumeTrue("Preserve any existing open session",
            model.snapshots.value.state in setOf(RecordingState.Ready, RecordingState.Completed))
    }
    @After fun close() {
        if (::scenario.isInitialized) {
            scenario.onActivity { it.trimModel.close(); it.reviewController.close(); it.playback.close() }
            runCatching { model.stop() }
            com.calvinmazhindu.skitcam.export.ExportTestSupport.settle()
            scenario.close()
        }
        originalRotation?.let { shell("wm user-rotation $it") }
        originalFixedRotation?.let { shell("wm fixed-to-user-rotation $it") }
    }
    private fun <T> main(block: () -> T): T {
        val value = AtomicReference<T>()
        instrumentation.runOnMainSync { value.set(block()) }
        return value.get()
    }
    private fun await(message: String, timeoutMs: Long = 30_000L, condition: () -> Boolean) {
        val deadline = System.nanoTime() + timeoutMs * 1_000_000
        while (!condition() && System.nanoTime() < deadline) Thread.sleep(100)
        assertTrue("Timed out: $message; ${if (::model.isInitialized) model.snapshots.value else "no model"}", condition())
    }
    private fun node(predicate: (AccessibilityNodeInfo) -> Boolean): AccessibilityNodeInfo? {
        if (android.os.Build.VERSION.SDK_INT >= 33) instrumentation.uiAutomation.clearCache()
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
    private fun text(value: String) = click { it.text?.toString() == value }
    private fun description(value: String) = click { it.contentDescription?.toString() == value }
    private fun bounds(predicate: (AccessibilityNodeInfo) -> Boolean): Rect {
        await("visible bounds") { node(predicate) != null }
        var current = node(predicate)!!
        while (!current.isClickable && current.parent != null) current = current.parent
        return Rect().also(current::getBoundsInScreen)
    }
    private fun awaitPose(): Int {
        var matched: Int? = null
        await("stable display pose") {
            scenario.onActivity {
                val candidate = it.orientation.pose
                matched = candidate?.takeIf { value ->
                    CameraOrientationPolicy.matchesDisplay(value, it.orientation.displayRotation)
                }
            }
            matched != null
        }
        return matched!!
    }
    private fun record() {
        if (orientation == "landscape") {
            shell("wm user-rotation lock 1")
            await("landscape display") {
                var landscape = false
                scenario.onActivity { landscape = it.windowManager.defaultDisplay.rotation == Surface.ROTATION_90 }
                landscape && model.cameraReady
            }
        }
        val pose = awaitPose()
        scenario.onActivity { model.start(pose) }
        await("recording") { model.snapshots.value.state == RecordingState.Recording }
        await("samples") { model.snapshots.value.activeDurationMs >= 1_800 }
        model.pause()
        await("paused") { model.snapshots.value.state == RecordingState.Paused }
        scenario.onActivity { model.resume(pose) }
        await("resumed") { model.snapshots.value.state == RecordingState.Recording }
        await("more samples") { model.snapshots.value.activeDurationMs >= 1_600 }
        model.pause()
        await("paused again") { model.snapshots.value.state == RecordingState.Paused }
    }
    private fun playerBounds(contentDescription: String): Rect {
        var result = Rect()
        scenario.onActivity { activity ->
            fun find(view: View): PlayerView? {
                if (view is PlayerView && view.contentDescription?.toString() == contentDescription) return view
                if (view is android.view.ViewGroup) {
                    for (index in 0 until view.childCount) find(view.getChildAt(index))?.let { return it }
                }
                return null
            }
            val player = checkNotNull(find(activity.window.decorView)) { "Missing player $contentDescription" }
            val location = IntArray(2)
            player.getLocationOnScreen(location)
            result = Rect(location[0], location[1], location[0] + player.width, location[1] + player.height)
        }
        return result
    }
    private fun viewport(): Pair<Rect, Float> {
        var result = Rect() to 1f
        scenario.onActivity { activity ->
            val decor = activity.window.decorView
            val decorBounds = Rect()
            decor.getGlobalVisibleRect(decorBounds)
            val insets = ViewCompat.getRootWindowInsets(decor)!!
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout())
            result = Rect(decorBounds.left + bars.left, decorBounds.top + bars.top,
                decorBounds.right - bars.right, decorBounds.bottom - bars.bottom) to
                activity.resources.configuration.fontScale
        }
        return result
    }
    private fun aspectOf(order: Int): Float {
        val segment = model.snapshots.value.session!!.segments.first { it.order == order }
        return segment.media?.takeIf { it.valid() }?.let {
            ViewfinderGeometry.aspect(it.width, it.height, it.rotation)
        } ?: (if (orientation == "landscape") 16f / 9f else 9f / 16f)
    }
    private fun fitRect(container: Rect, aspect: Float): Rect {
        val fitted = ViewfinderGeometry.fit(container.width().toFloat(), container.height().toFloat(), aspect)
            ?: return Rect(container)
        val left = container.left + ((container.width() - fitted.width) / 2f).roundToInt()
        val top = container.top + ((container.height() - fitted.height) / 2f).roundToInt()
        return Rect(left, top, left + fitted.width.roundToInt(), top + fitted.height.roundToInt())
    }
    private fun screenshot(name: String) {
        val bitmap = instrumentation.uiAutomation.takeScreenshot() ?: error("No screenshot")
        val file = File(context.filesDir, "validation-m12d/$name.png")
        file.parentFile!!.mkdirs()
        file.outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
        bitmap.recycle()
    }
    private fun screenReport(screen: String, contentDescription: String, order: Int,
        viewport: Rect, fontScale: Float): JSONObject {
        val player = playerBounds(contentDescription)
        val aspect = aspectOf(order)
        val rendered = fitRect(player, aspect)
        assertTrue("$screen player inside viewport", viewport.contains(player))
        assertTrue("$screen rendered video inside player", player.contains(rendered))
        assertTrue("$screen aspect ${rendered.width()}/${rendered.height()}",
            kotlin.math.abs(rendered.width().toDouble() / rendered.height() - aspect) < 0.02)
        val coverage = rendered.width().toDouble() * rendered.height() / (viewport.width() * viewport.height())
        val minimum = when {
            fontScale > 1.45f -> if (screen == "trim") 0.22 else 0.28
            screen == "review" -> if (orientation == "portrait") 0.55 else 0.60
            screen == "trim" -> 0.30
            else -> 0.35
        }
        assertTrue("$screen coverage $coverage < $minimum", coverage >= minimum)
        val density = context.resources.displayMetrics.density
        return JSONObject()
            .put("screen", screen)
            .put("orientation", orientation)
            .put("fontScale", fontScale)
            .put("viewportPx", JSONObject().put("bounds", viewport.flattenToString())
                .put("width", viewport.width()).put("height", viewport.height()))
            .put("playerPx", JSONObject().put("bounds", player.flattenToString())
                .put("width", player.width()).put("height", player.height()))
            .put("renderedVideoPx", JSONObject().put("bounds", rendered.flattenToString())
                .put("width", rendered.width()).put("height", rendered.height()))
            .put("renderedVideoDp", JSONObject().put("width", rendered.width() / density)
                .put("height", rendered.height() / density))
            .put("videoAspect", aspect)
            .put("videoAreaCoverage", coverage)
    }
    private fun scrollableNode(): AccessibilityNodeInfo? {
        fun find(current: AccessibilityNodeInfo): AccessibilityNodeInfo? {
            if (current.isVisibleToUser && current.isScrollable) return current
            for (index in 0 until current.childCount) {
                val child = current.getChild(index) ?: continue
                find(child)?.let { return it }
            }
            return null
        }
        return instrumentation.uiAutomation.rootInActiveWindow?.let(::find)
    }
    private fun assertControl(label: String, viewport: Rect, byDescription: Boolean = false,
        scrollable: Boolean = false) {
        val minimum = (48 * context.resources.displayMetrics.density).roundToInt()
        var rect = Rect()
        for (attempt in 0 until if (scrollable) 8 else 1) {
            val found = node { candidate ->
                if (byDescription) candidate.contentDescription?.toString() == label
                else candidate.text?.toString() == label
            }
            if (found != null) {
                var target: AccessibilityNodeInfo = found
                while (!target.isClickable && target.parent != null) target = target.parent
                rect = Rect().also(target::getBoundsInScreen)
                if (viewport.contains(rect) && rect.width() >= minimum && rect.height() >= minimum) return
            }
            if (!scrollable) break
            val scroll = scrollableNode() ?: break
            if (!scroll.performAction(AccessibilityNodeInfo.ACTION_SCROLL_FORWARD)) break
            Thread.sleep(150)
        }
        assertTrue("$label outside viewport: $rect", rect.width() > 0 && viewport.contains(rect))
        assertTrue("$label too small: $rect", rect.width() >= minimum && rect.height() >= minimum)
    }
    private fun assertSemanticBounds(label: String, viewport: Rect, scrollable: Boolean = false) {
        val minimum = (48 * context.resources.displayMetrics.density).roundToInt()
        var rect = Rect()
        for (attempt in 0 until if (scrollable) 8 else 1) {
            val found = node { it.contentDescription?.toString() == label }
            if (found != null) {
                rect = Rect().also(found::getBoundsInScreen)
                if (viewport.contains(rect) && rect.width() >= minimum && rect.height() >= minimum) return
            }
            if (!scrollable) break
            val scroll = scrollableNode() ?: break
            if (!scroll.performAction(AccessibilityNodeInfo.ACTION_SCROLL_FORWARD)) break
            Thread.sleep(150)
        }
        assertTrue("$label outside viewport: $rect", rect.width() > 0 && viewport.contains(rect))
        assertTrue("$label too small: $rect", rect.width() >= minimum && rect.height() >= minimum)
    }
    @Test fun allThreeScreensShareAspectFitAndAccessibleControls() {
        record()
        val (viewport, fontScale) = viewport()
        val reports = JSONArray()
        val stem = "$label-$orientation"

        // Review
        click { it.contentDescription?.startsWith("Review segment 1,") == true }
        await("review player") { main { review.player?.playbackState == Player.STATE_READY } }
        click { it.contentDescription?.toString() == "Play" }
        await("review playing") { main { review.player?.let { it.isPlaying && it.currentPosition > 300 } == true } }
        click { it.contentDescription?.toString() == "Pause" }
        val first = node { it.contentDescription?.startsWith("Review segment 1,") == true }
        val second = node { it.contentDescription?.startsWith("Review segment 2,") == true }
        assertTrue("first thumbnail selected: ${first?.stateDescription}",
            first?.stateDescription?.toString()?.contains("Selected") == true)
        assertTrue("second thumbnail not selected: ${second?.stateDescription}",
            second?.stateDescription?.toString()?.contains("Selected") != true)
        for (control in listOf("Trim", "Discard", "Play from here", "Close")) assertControl(control, viewport)
        reports.put(screenReport("review", "Segment 1 playback", 1, viewport, fontScale))
        screenshot("$stem-review")
        text("Close")
        await("review closed") { main { review.selection == null } }

        // Trim
        click { it.contentDescription?.startsWith("Review segment 1,") == true }
        text("Trim")
        await("trim ready") { main { trim.ready } }
        await("trim player") { main { review.player?.playbackState == Player.STATE_READY } }
        assertControl("Cancel", viewport)
        assertControl("Save", viewport)
        for (control in listOf("Reset Trim", "Precision controls")) assertControl(control, viewport, scrollable = true)
        assertSemanticBounds("Trim timeline", viewport, scrollable = true)
        assertSemanticBounds("Trim start handle", viewport, scrollable = true)
        assertSemanticBounds("Trim end handle", viewport, scrollable = true)
        assertControl("Play", viewport, byDescription = true)
        reports.put(screenReport("trim", "Trim video preview", 1, viewport, fontScale))
        screenshot("$stem-trim")
        text("Cancel")
        await("trim closed") { main { trim.segment == null } }

        // Whole-skit playback from the selected segment
        click { it.contentDescription?.startsWith("Review segment 1,") == true }
        await("review before playback") { main { review.player?.playbackState == Player.STATE_READY } }
        text("Play from here")
        await("skit playback") { main { activity.playback.player?.isPlaying == true } }
        await("playback frame") {
            main { activity.playback.player?.let { it.isPlaying && it.currentPosition > 200 } == true }
        }
        assertTrue(node { it.contentDescription?.toString() == "Skit playback" } != null)
        assertControl("Close playback", viewport, byDescription = true)
        reports.put(screenReport("play", "Skit playback", 1, viewport, fontScale))
        screenshot("$stem-play")
        description("Close playback")
        await("playback closed") { main { !activity.playback.active && activity.playback.player == null } }

        val directory = File(context.filesDir, "validation-m12d").apply { mkdirs() }
        File(directory, "unified-video-layout-$orientation.json").writeText(reports.toString(2))
    }
    private fun pressBack() {
        instrumentation.runOnMainSync { activity.onBackPressedDispatcher.onBackPressed() }
    }
    @Test fun backNavigationClosesReviewTrimAndPlayback() {
        record()
        click { it.contentDescription?.startsWith("Review segment 1,") == true }
        await("review open") { main { review.selection != null } }
        pressBack()
        await("review closed by back") { main { review.selection == null && review.player == null } }

        click { it.contentDescription?.startsWith("Review segment 1,") == true }
        text("Trim")
        await("trim ready") { main { trim.ready } }
        pressBack()
        await("trim closed by back") { main { trim.segment == null && review.player == null } }
        await("paused controls after trim") { node { it.text?.toString() == "RESUME" } != null }

        click { it.contentDescription?.startsWith("Review segment 1,") == true }
        await("review before play") { main { review.player?.playbackState == Player.STATE_READY } }
        text("Play from here")
        await("playback open") { main { activity.playback.active && activity.playback.player != null } }
        pressBack()
        await("playback closed by back") {
            main { !activity.playback.active && activity.playback.player == null }
        }
        await("paused controls after playback") { node { it.text?.toString() == "RESUME" } != null }

        model.stop()
        await("completed") { model.snapshots.value.state == RecordingState.Completed }
    }

}
