package com.calvinmazhindu.skitcam.trim

import android.Manifest
import android.view.accessibility.AccessibilityNodeInfo
import androidx.media3.common.Player
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.calvinmazhindu.skitcam.MainActivity
import com.calvinmazhindu.skitcam.recording.*
import com.calvinmazhindu.skitcam.review.SegmentReviewController
import org.json.JSONObject
import org.junit.*
import org.junit.Assert.*
import org.junit.runner.RunWith
import java.io.File

@RunWith(AndroidJUnit4::class)
class TrimDurationProbeDeviceTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context get() = instrumentation.targetContext
    private lateinit var scenario: ActivityScenario<MainActivity>
    private lateinit var model: RecordingViewModel
    private lateinit var trim: TrimViewModel
    private lateinit var review: SegmentReviewController
    private var originalRotation: String? = null

    private fun shell(command: String): String = instrumentation.uiAutomation.executeShellCommand(command).use {
        android.os.ParcelFileDescriptor.AutoCloseInputStream(it).bufferedReader().readText().trim()
    }
    @Before fun open() {
        for (permission in listOf(Manifest.permission.CAMERA, Manifest.permission.RECORD_AUDIO)) {
            shell("pm grant ${context.packageName} $permission")
        }
        originalRotation = shell("wm user-rotation")
        shell("wm user-rotation lock 0")
        scenario = ActivityScenario.launch(MainActivity::class.java)
        scenario.onActivity { model = it.recordingModel; trim = it.trimModel; review = it.reviewController }
        com.calvinmazhindu.skitcam.export.ExportTestSupport.beforeCamera()
        await("camera") { model.cameraReady }
        Assume.assumeTrue("Preserve any existing open session",
            model.snapshots.value.state in setOf(RecordingState.Ready, RecordingState.Completed))
    }
    @After fun close() {
        if (::scenario.isInitialized) {
            scenario.onActivity { it.trimModel.close(); it.reviewController.close() }
            runCatching { model.stop() }
            com.calvinmazhindu.skitcam.export.ExportTestSupport.settle()
            scenario.close()
        }
        originalRotation?.let { shell("wm user-rotation $it") }
    }
    private fun await(message: String, timeoutMs: Long = 30_000L, condition: () -> Boolean) {
        val deadline = System.nanoTime() + timeoutMs * 1_000_000
        while (!condition() && System.nanoTime() < deadline) Thread.sleep(100)
        assertTrue("Timed out: $message; ${if (::model.isInitialized) model.snapshots.value else "no model"}", condition())
    }
    private fun <T> main(block: () -> T): T {
        val value = java.util.concurrent.atomic.AtomicReference<T>()
        instrumentation.runOnMainSync { value.set(block()) }
        return value.get()
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
    @Test fun probeDurationSources() {
        scenario.onActivity { model.start(0) }
        await("recording") { model.snapshots.value.state == RecordingState.Recording }
        await("samples") { model.snapshots.value.activeDurationMs >= 1_800 }
        model.pause()
        await("paused") { model.snapshots.value.state == RecordingState.Paused }
        repeat(2) {
            scenario.onActivity { model.resume(0) }
            await("resumed") { model.snapshots.value.state == RecordingState.Recording }
            await("more samples") { model.snapshots.value.activeDurationMs >= 1_800 }
            model.pause()
            await("paused again") { model.snapshots.value.state == RecordingState.Paused }
        }
        val reports = org.json.JSONArray()
        for (order in 1..3) {
            click { it.contentDescription?.startsWith("Review segment $order,") == true }
            await("trim ready $order") { trim.ready }
            await("player ready $order trim=${main { trim.error }} review=${main { review.error }}") {
                main { review.player?.playbackState == Player.STATE_READY }
            }
            var report = JSONObject()
            scenario.onActivity {
                val segment = model.snapshots.value.session!!.segments[order - 1]
                val playerDuration = review.player?.duration ?: -1L
                val media = segment.media
                report = JSONObject()
                    .put("segmentOrder", segment.order)
                    .put("segmentDurationMs", segment.durationMs)
                    .put("trimSourceDurationMs", segment.trim?.sourceDurationMs ?: JSONObject.NULL)
                    .put("retrieverDurationMs", trim.source?.facts?.durationMs ?: JSONObject.NULL)
                    .put("playerPresentationDurationMs", trim.source?.presentationDurationMs ?: JSONObject.NULL)
                    .put("currentPlayerDurationMs", playerDuration)
                    .put("mediaTrackDurationUs", media?.trackDurationUs ?: JSONObject.NULL)
                    .put("mediaWidth", media?.width ?: JSONObject.NULL)
                    .put("mediaHeight", media?.height ?: JSONObject.NULL)
                    .put("mediaRotation", media?.rotation ?: JSONObject.NULL)
                    .put("notice", trim.notice ?: JSONObject.NULL)
                    .put("deltaRetrieverVsPlayer",
                        if (trim.source != null) kotlin.math.abs(trim.source!!.facts.durationMs - trim.source!!.presentationDurationMs) else JSONObject.NULL)
                    .put("deltaRetrieverVsTrack",
                        if (media != null && trim.source != null)
                            kotlin.math.abs(trim.source!!.facts.durationMs - media.trackDurationUs / 1000)
                        else JSONObject.NULL)
            }
            reports.put(report)
            click { it.text?.toString() == "Close" }
            await("review closed $order") { trim.segment == null && review.selection == null }
        }
        val directory = File(context.filesDir, "validation-m12d").apply { mkdirs() }
        File(directory, "trim-duration-probe.json").writeText(reports.toString(2))
        android.util.Log.i("SkitCamM12D", "trim duration probes=$reports")
        model.stop()
        await("completed") { model.snapshots.value.state == RecordingState.Completed }
    }
}
