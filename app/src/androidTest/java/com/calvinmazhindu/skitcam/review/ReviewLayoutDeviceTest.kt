package com.calvinmazhindu.skitcam.review

import android.Manifest
import android.graphics.Bitmap
import android.graphics.Rect
import android.view.Surface
import android.view.View
import android.view.accessibility.AccessibilityNodeInfo
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.media3.common.Player
import androidx.media3.common.VideoSize
import androidx.media3.ui.PlayerView
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.calvinmazhindu.skitcam.MainActivity
import com.calvinmazhindu.skitcam.camera.ViewfinderGeometry
import com.calvinmazhindu.skitcam.recording.*
import org.json.JSONObject
import org.junit.*
import org.junit.Assert.*
import org.junit.runner.RunWith
import java.io.File
import java.util.concurrent.atomic.AtomicReference
import kotlin.math.roundToInt

@RunWith(AndroidJUnit4::class)
class ReviewLayoutDeviceTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context get() = instrumentation.targetContext
    private val arguments get() = InstrumentationRegistry.getArguments()
    private val orientation by lazy { arguments.getString("orientation") ?: "portrait" }
    private val label by lazy { arguments.getString("label") ?: "run" }
    private val baseline by lazy { arguments.getString("baseline") == "true" }
    private lateinit var scenario: ActivityScenario<MainActivity>
    private lateinit var activity: MainActivity
    private lateinit var model: RecordingViewModel
    private lateinit var review: SegmentReviewController
    private var originalRotation: String? = null
    private var originalFixedRotation: String? = null

    private fun shell(command: String): String = instrumentation.uiAutomation.executeShellCommand(command).use {
        android.os.ParcelFileDescriptor.AutoCloseInputStream(it).bufferedReader().readText().trim()
    }
    @Before fun open() {
        check(context.packageName.endsWith(".m12cvalidation")) { "Use the isolated m12c validation app" }
        for (permission in listOf(Manifest.permission.CAMERA, Manifest.permission.RECORD_AUDIO, Manifest.permission.POST_NOTIFICATIONS)) {
            shell("pm grant ${context.packageName} $permission")
        }
        originalRotation = shell("wm user-rotation")
        originalFixedRotation = shell("wm fixed-to-user-rotation")
        shell("wm user-rotation lock 0")
        if (orientation == "landscape") shell("wm fixed-to-user-rotation enabled")
        scenario = ActivityScenario.launch(MainActivity::class.java)
        scenario.onActivity { activity = it; model = it.recordingModel; review = it.reviewController }
        com.calvinmazhindu.skitcam.export.ExportTestSupport.beforeCamera()
        await("camera") { model.cameraReady }
        Assume.assumeTrue("Preserve any existing open session",
            model.snapshots.value.state in setOf(RecordingState.Ready, RecordingState.Completed))
    }
    @After fun close() {
        if (::scenario.isInitialized) { com.calvinmazhindu.skitcam.export.ExportTestSupport.settle(); scenario.close() }
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
    private fun state(state: RecordingState) = await(state.name, 30_000L) { model.snapshots.value.state == state }
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
    private fun begin() {
        if (orientation == "landscape") {
            shell("wm user-rotation lock 1")
            await("landscape display") {
                var landscape = false
                scenario.onActivity { landscape = it.windowManager.defaultDisplay.rotation == Surface.ROTATION_90 }
                landscape && model.cameraReady
            }
        }
        val startPose = awaitPose()
        scenario.onActivity { model.start(startPose) }
        state(RecordingState.Recording)
        await("media samples", 30_000L) { model.snapshots.value.activeDurationMs >= 1_800 }
        model.pause()
        state(RecordingState.Paused)
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
    private fun bounds(predicate: (AccessibilityNodeInfo) -> Boolean): Rect {
        await("visible bounds") { node(predicate) != null }
        var current = node(predicate)!!
        while (!current.isClickable && current.parent != null) current = current.parent
        return Rect().also(current::getBoundsInScreen)
    }
    private fun playerViewBounds(): Rect {
        var result = Rect()
        scenario.onActivity { activity ->
            fun find(view: View): PlayerView? {
                if (view is PlayerView) return view
                if (view is android.view.ViewGroup) {
                    for (index in 0 until view.childCount) find(view.getChildAt(index))?.let { return it }
                }
                return null
            }
            val player = checkNotNull(find(activity.window.decorView)) { "PlayerView not attached" }
            val location = IntArray(2)
            player.getLocationOnScreen(location)
            result = Rect(location[0], location[1], location[0] + player.width, location[1] + player.height)
        }
        return result
    }
    private fun playerAspect(): Float? {
        val segment = model.snapshots.value.session?.segments?.firstOrNull() ?: return null
        segment.media?.takeIf { it.valid() }?.let {
            return ViewfinderGeometry.aspect(it.width, it.height, it.rotation)
        }
        return main { review.player?.videoSize?.let(::aspectOf) }
    }
    @Suppress("DEPRECATION")
    private fun aspectOf(size: VideoSize): Float? {
        if (size.width <= 0 || size.height <= 0) return null
        val raw = size.width.toFloat() * size.pixelWidthHeightRatio / size.height
        return if (size.unappliedRotationDegrees % 180 == 0) raw else 1f / raw
    }
    private fun fitRect(container: Rect, aspect: Float?): Rect {
        if (aspect == null) return Rect(container)
        val fitted = ViewfinderGeometry.fit(container.width().toFloat(), container.height().toFloat(), aspect)
            ?: return Rect(container)
        val left = container.left + ((container.width() - fitted.width) / 2f).roundToInt()
        val top = container.top + ((container.height() - fitted.height) / 2f).roundToInt()
        return Rect(left, top, left + fitted.width.roundToInt(), top + fitted.height.roundToInt())
    }
    private fun screenshot(name: String) {
        val bitmap = instrumentation.uiAutomation.takeScreenshot() ?: error("No screenshot")
        val file = File(context.filesDir, "validation-m12c-review/$name.png")
        file.parentFile!!.mkdirs()
        file.outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
        bitmap.recycle()
    }
    @Test fun reviewLayoutEvidence() {
        begin()
        await("thumbnail") {
            node { it.contentDescription?.startsWith("Review segment 1,") == true } != null
        }
        click { it.contentDescription?.startsWith("Review segment 1,") == true }
        await("review player ready", 60_000L) {
            main {
                activity.trimModel.ready &&
                    review.player?.playbackState in setOf(Player.STATE_READY, Player.STATE_ENDED)
            }
        }
        click { it.contentDescription?.toString() == "Play" }
        await("pause icon while playing") {
            node { it.contentDescription?.toString() == "Pause" } != null
        }
        await("decoded frame") { main { review.player?.let { it.isPlaying && it.videoSize.width > 0 && it.currentPosition > 400 } == true } }
        fun timeText(): String? = node { it.text?.toString()?.contains(" / ") == true }?.text?.toString()
        val timeBefore = timeText()
        Thread.sleep(600)
        val timeAfter = timeText()
        assertNotEquals("Playback time must advance while playing", timeBefore, timeAfter)
        click { it.contentDescription?.toString() == "Pause" }
        await("play icon while paused") {
            main { review.player?.isPlaying == false } && node { it.contentDescription?.toString() == "Play" } != null
        }
        val jump = node { it.contentDescription?.toString() == "Jump to segment start" }
        assertNotNull("Missing jump-to-start", jump)
        val jumpBounds = Rect().also(jump!!::getBoundsInScreen)
        assertTrue("Jump-to-start target clipped: $jumpBounds",
            jumpBounds.width() >= (48 * context.resources.displayMetrics.density).toInt() &&
                jumpBounds.height() >= (48 * context.resources.displayMetrics.density).toInt())
        click { it.contentDescription?.toString() == "Jump to segment start" }
        await("jumped to segment start") { main { (review.player?.currentPosition ?: Long.MAX_VALUE) <= 100 } }
        click { it.contentDescription?.toString() == "Play" }
        await("decoded frame after jump") { main { review.player?.let { it.isPlaying && it.currentPosition > 300 } == true } }
        click { it.contentDescription?.toString() == "Pause" }
        await("paused with decoded video") { main { review.player?.isPlaying == false } }
        Thread.sleep(300)

        val player = playerViewBounds()
        val aspect = playerAspect()
        assertNotNull("Recorded video aspect must be known", aspect)
        val rendered = fitRect(player, aspect)

        var viewport = Rect()
        var fontScale = 1f
        var actualOrientation = orientation
        scenario.onActivity { activity ->
            val decor = activity.window.decorView
            val decorBounds = Rect()
            decor.getGlobalVisibleRect(decorBounds)
            val insets = ViewCompat.getRootWindowInsets(decor)!!
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout())
            viewport = Rect(decorBounds.left + bars.left, decorBounds.top + bars.top,
                decorBounds.right - bars.right, decorBounds.bottom - bars.bottom)
            fontScale = activity.resources.configuration.fontScale
            actualOrientation = if (activity.resources.configuration.orientation ==
                android.content.res.Configuration.ORIENTATION_LANDSCAPE) "landscape" else "portrait"
        }
        assertTrue("Player must be inside the viewport: $player vs $viewport", viewport.contains(player))
        assertTrue("Rendered video must be inside the player: $rendered vs $player", player.contains(rendered))

        await("inline trim ready") { node { it.contentDescription?.toString() == "Trim timeline" } != null }
        val controls = JSONObject()
        for (track in listOf("Trim timeline", "Trim start handle", "Trim end handle")) {
            val item = node { it.contentDescription?.toString() == track }
            assertNotNull("Missing $track", item)
            val rect = Rect().also(item!!::getBoundsInScreen)
            assertTrue("$track outside viewport: $rect vs $viewport", viewport.contains(rect))
            controls.put(track, JSONObject().put("bounds", rect.flattenToString()))
        }
        val expectedLabels = listOf("Discard", "Play from here", "Close")
        val rects = mutableMapOf<String, Rect>()
        for (control in expectedLabels) {
            var current = node { it.text?.toString() == control }
            assertNotNull("Missing $control", current)
            while (!current!!.isClickable && current.parent != null) current = current.parent
            val rect = Rect().also(current::getBoundsInScreen)
            controls.put(control, JSONObject().put("bounds", rect.flattenToString()))
            assertTrue("$control outside viewport: $rect vs $viewport", viewport.contains(rect))
            val minimum = (48 * context.resources.displayMetrics.density).roundToInt()
            if (!baseline) assertTrue("$control target too small: $rect",
                rect.width() >= minimum && rect.height() >= minimum)
            rects[control] = rect
        }
        for (first in expectedLabels) for (second in expectedLabels) if (first < second) {
            assertFalse("$first overlaps $second", Rect.intersects(rects.getValue(first), rects.getValue(second)))
        }

        val viewportArea = viewport.width().toLong() * viewport.height()
        val videoArea = rendered.width().toLong() * rendered.height()
        val coverage = if (viewportArea > 0) videoArea.toDouble() / viewportArea else 0.0
        val density = context.resources.displayMetrics.density
        val report = JSONObject()
            .put("label", label)
            .put("orientation", actualOrientation)
            .put("fontScale", fontScale)
            .put("viewportPx", JSONObject().put("bounds", viewport.flattenToString())
                .put("width", viewport.width()).put("height", viewport.height()))
            .put("viewportDp", JSONObject()
                .put("width", viewport.width() / density).put("height", viewport.height() / density))
            .put("playerViewPx", JSONObject().put("bounds", player.flattenToString())
                .put("width", player.width()).put("height", player.height()))
            .put("renderedVideoPx", JSONObject().put("bounds", rendered.flattenToString())
                .put("width", rendered.width()).put("height", rendered.height()))
            .put("renderedVideoDp", JSONObject()
                .put("width", rendered.width() / density).put("height", rendered.height() / density))
            .put("videoAspect", aspect)
            .put("videoAreaCoverage", coverage)
            .put("controls", controls)

        val directory = File(context.filesDir, "validation-m12c-review")
        directory.mkdirs()
        val stem = "$label-$actualOrientation"
        File(directory, "$stem.json").writeText(report.toString(2))
        screenshot(stem)

        if (!baseline) {
            val minimumCoverage = when {
                actualOrientation == "portrait" -> if (fontScale > 1.45f) 0.45 else 0.55
                else -> if (fontScale > 1.45f) 0.50 else 0.60
            }
            assertTrue("Video must use the review viewport: ${"%.3f".format(coverage)} < $minimumCoverage",
                coverage >= minimumCoverage)
        }

        main { review.close() }
        model.stop()
        state(RecordingState.Completed)
        com.calvinmazhindu.skitcam.export.ExportTestSupport.settle()
    }
}
