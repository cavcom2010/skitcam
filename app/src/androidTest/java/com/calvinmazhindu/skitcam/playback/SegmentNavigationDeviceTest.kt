package com.calvinmazhindu.skitcam.playback

import android.Manifest
import android.content.pm.ActivityInfo
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.Rect
import android.media.MediaExtractor
import android.media.MediaFormat
import android.net.Uri
import android.os.Build
import android.view.Surface
import android.view.accessibility.AccessibilityNodeInfo
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.calvinmazhindu.skitcam.MainActivity
import com.calvinmazhindu.skitcam.camera.CameraMode
import com.calvinmazhindu.skitcam.export.ExportJournal
import com.calvinmazhindu.skitcam.export.ExportState
import com.calvinmazhindu.skitcam.export.ExportTestSupport
import com.calvinmazhindu.skitcam.export.sha
import com.calvinmazhindu.skitcam.recording.CameraLens
import com.calvinmazhindu.skitcam.recording.RecordingState
import com.calvinmazhindu.skitcam.recording.Segment
import com.calvinmazhindu.skitcam.recording.SegmentEditResult
import com.calvinmazhindu.skitcam.recording.Session
import com.calvinmazhindu.skitcam.recording.SessionCodec
import com.calvinmazhindu.skitcam.recording.SessionFileStore
import com.calvinmazhindu.skitcam.video.VideoInspector
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.util.concurrent.atomic.AtomicReference
import kotlin.math.abs
import kotlin.math.roundToInt

/** Run captureBaseline first, then upgrade the isolated app in place and run verifyRecoveredSession. */
@RunWith(AndroidJUnit4::class)
@androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
class SegmentNavigationDeviceTest {
    private val instrument = InstrumentationRegistry.getInstrumentation()
    private val context get() = instrument.targetContext
    private val evidence get() = File(context.filesDir, "m142").apply { mkdirs() }
    private lateinit var scenario: ActivityScenario<MainActivity>
    private lateinit var activity: MainActivity
    private val model get() = activity.recordingModel
    private val playback get() = activity.playback
    private val session get() = requireNotNull(model.snapshots.value.session)
    private var rotation: String? = null
    private var fixedRotation: String? = null
    private var font: String? = null

    @Before fun prepare() {
        check(context.packageName in setOf("com.calvinmazhindu.skitcam.m142validation",
            "com.calvinmazhindu.skitcam.m15avalidation", "com.calvinmazhindu.skitcam.m15b2capture"))
        rotation = shell("wm user-rotation")
        fixedRotation = shell("wm fixed-to-user-rotation")
        font = shell("settings get system font_scale")
        for (permission in listOf(Manifest.permission.CAMERA, Manifest.permission.RECORD_AUDIO)) {
            shell("pm grant ${context.packageName} $permission")
        }
        shell("wm user-rotation lock 0")
    }

    @After fun restore() {
        try {
            if (::scenario.isInitialized) scenario.close()
        } finally {
            font?.let {
                shell(if (it == "null") "settings delete system font_scale" else "settings put system font_scale $it")
            }
            rotation?.let { shell("wm user-rotation $it") }
            fixedRotation?.let { shell("wm fixed-to-user-rotation $it") }
        }
    }

    private fun launch(landscape: Boolean = false) {
        if (landscape) {
            shell("wm fixed-to-user-rotation enabled")
            shell("wm user-rotation lock 1")
        }
        scenario = ActivityScenario.launch(MainActivity::class.java)
        scenario.onActivity {
            activity = it
            if (landscape) it.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE
        }
        ExportTestSupport.beforeCamera()
        await("camera and recovered state") {
            model.recovered.value && main { activity.cameraModel.streaming }
        }
        main {
            if (activity.cameraModel.choice.mode != CameraMode.SkitVideo) {
                activity.cameraModel.select(CameraMode.SkitVideo, CameraLens.Rear, true)
            }
        }
        await("video camera") {
            model.cameraReady && main {
                if (landscape) activity.orientation.displayRotation == Surface.ROTATION_90
                else activity.orientation.pose == 0
            }
        }
    }

    @Test fun captureBaseline() {
        launch()
        val resumeId = InstrumentationRegistry.getArguments().getString("baselineSession")
        if (resumeId == null) {
            record(10, 0)
            keep(3, false)
            keep(9, false)
        } else {
            assertEquals("Only reuse the explicitly identified disposable session", resumeId, session.id)
            assertEquals(8, session.retainedCount)
            assertEquals(10, session.segments.size)
        }
        val checkpoint = session
        File(evidence, "session.json").writeText(SessionCodec.encode(checkpoint))
        File(evidence, "sources.sha256").writeText(sourceHashes())
        val summary = bounds { it.text?.toString() == "8 kept · 10 clips" }
        File(evidence, "baseline-layout.txt").writeText(listOf(summary, namedBounds("Play skit from beginning"))
            .joinToString("\n") { it.flattenToString() })
        capture("before-summary")
        startPlayback()
        seekForScreenshot()
        capture("before-playback")
        main { playback.close() }
        // The upgraded app will restore the same paused session and original files.
    }

    @Test fun verifyRecoveredSession() {
        launch()
        val checkpoint = SessionCodec.decode(File(evidence, "session.json").readText())
        assertEquals(checkpoint, session)
        val hashes = File(evidence, "sources.sha256").readText()
        assertEquals(hashes, sourceHashes())
        expectSummary(8, 2, .8f)
        val baseline = File(evidence, "baseline-layout.txt").readLines()
        // The old Text node was inside its 6 dp padding. The new node covers the entire strip.
        val paddingPx = (6 * context.resources.displayMetrics.density).roundToInt()
        val oldStrip = Rect.unflattenFromString(baseline[0])!!.apply { inset(-paddingPx, -paddingPx) }
        assertEquals(oldStrip, namedBounds("8 kept, 2 discarded"))
        assertEquals(Rect.unflattenFromString(baseline[1]), namedBounds("Play skit from beginning"))
        verifyNavigationAndExport(hashes)
    }

    @Test fun verifyFreshSession() {
        launch()
        record(10, 0)
        keep(3, false)
        keep(9, false)
        expectSummary(8, 2, .8f)
        verifyNavigationAndExport(sourceHashes())
    }

    @Test fun cameraCanAppendAfterClosingPlayback() {
        launch()
        record(2, 0)
        val before = session
        val hashes = before.segments.map { sha(File(it.path)) }
        startPlayback()
        tap("Close playback")
        await("playback released, camera can record") {
            model.cameraReady && main { playback.player == null && activity.cameraModel.streaming }
        }
        model.resume(0)
        await("append records after playback closes") {
            model.snapshots.value.state == RecordingState.Recording && model.snapshots.value.activeDurationMs >= 1_600
        }
        model.pause()
        await("append committed") { model.snapshots.value.state == RecordingState.Paused && session.segments.size == 3 }
        val after = session
        assertEquals(before.id, after.id)
        assertEquals(before.segments, after.segments.dropLast(1))
        assertEquals(hashes, after.segments.dropLast(1).map { sha(File(it.path)) })
        val appended = after.segments.last()
        assertFalse(before.segments.any { it.path == appended.path })
        assertEquals(appended.videoRotationDegrees, SessionFileStore(File(context.filesDir, "sessions")).validate(appended.path))
    }

    private fun verifyNavigationAndExport(hashes: String) {
        capture("after-summary")
        keep(3, true)
        expectSummary(9, 1, .9f)
        capture("after-restore")
        keep(3, false)
        expectSummary(8, 2, .8f)

        startPlayback()
        assertEquals(listOf(1, 2, 4, 5, 6, 7, 8, 10), main { playback.plan!!.clips.map { it.order } })
        expectCentered(1)
        expectState(2, "Upcoming")
        val player = main { playback.player!! }
        main { playback.setPlayWhenReady(true) }
        await("automatic transition without replacing or pausing playback") {
            main { playback.currentIndex == 1 && playback.player === player && player.isPlaying }
        }
        main { playback.setPlayWhenReady(false) }
        expectCentered(2)
        expectState(1, "Completed")

        tap("Clip 1")
        expectCentered(1)
        expectState(2, "Upcoming")
        assertFalse(main { player.playWhenReady })
        val rail = namedBounds("Playback segments")
        val startX = rail.left + rail.width() * 8 / 9
        val endX = rail.left + rail.width() / 5
        shell("input swipe $startX ${rail.centerY()} $endX ${rail.centerY()} 500")
        await("user can scroll away from current clip") { node { it.named("Clip 1") } == null }
        instrument.waitForIdleSync()
        assertEquals(0, main { playback.currentIndex })
        assertNull("No recenter without a segment change", node { it.named("Clip 1") })
        tap("Clip 5")
        expectCentered(5)
        seekForScreenshot()
        expectCentered(4)
        expectState(3, "Completed")
        expectState(5, "Upcoming")
        expectFraming(9f / 16f)
        capture("after-playback")
        main {
            playback.toggleLoop()
            player.seekTo(7, 0)
        }
        expectCentered(8)
        main { playback.setPlayWhenReady(true) }
        await("end of final clip") { main { playback.finished } }
        expectCentered(8)
        tap("Replay")
        expectCentered(1)
        assertSame(player, main { playback.player })
        tap("Close playback")

        val saved = session
        val reversed = saved.playbackOrder.reversed()
        edit { model.savePlaybackOrder(saved.id, saved.playbackOrder, reversed, it) }
        startPlayback()
        expectCentered(1)
        assertEquals(reversed, main { playback.plan!!.clips.map { it.segmentId } })
        assertNotNull(node { it.text?.toString() == "Clip 1 of 8 · Segment 10" })
        tap("Close playback")
        main { playback.play(session, reversed[2]) }
        expectCentered(1)
        assertEquals(reversed.drop(2), main { playback.plan!!.clips.map { it.segmentId } })
        tap("Close playback")
        export()
        assertEquals(hashes, sourceHashes())
    }

    @Test fun landscapeLargeText() {
        val id = seedLandscapeSession()
        shell("settings put system font_scale 2.0")
        launch(landscape = true)
        assertEquals(id, session.id)
        await("large font settings applied") { main { activity.resources.configuration.fontScale == 2f } }
        if (!session.segments[1].kept) keep(2, true)
        expectSummary(3, 0, 1f)
        keep(2, false)
        expectSummary(2, 1, 2f / 3)
        capture("landscape-large-summary")
        startPlayback()
        expectCentered(1)
        tap("Clip 2")
        expectCentered(2)
        expectFraming(16f / 9f)
        capture("landscape-large-playback")
        tap("Close playback")
        edit { model.abandon(id, it) }
    }

    private fun seedLandscapeSession(): String {
        val id = "a1420000-0000-4000-8000-000000000142"
        val root = File(context.filesDir, "sessions")
        val store = SessionFileStore(root)
        val existing = store.loadCurrent()
        if (existing?.id == id && existing.state == RecordingState.Paused) return id
        assertTrue("Preserve any open session", existing == null || existing.state == RecordingState.Completed)
        val source = File(evidence, "landscape-input.mp4")
        check(source.isFile) { "Stage a landscape MP4 with AAC in files/m142/landscape-input.mp4." }
        val directory = File(root, id).apply { mkdirs() }
        val clips = (1..3).map { order ->
            val target = source.copyTo(File(directory, "segment_%03d.mp4".format(order)))
            val media = VideoInspector.inspect(target.path)
            Segment(order, target.path, media.trackDurationUs / 1_000, 270, media.rotation, media = media)
        }
        store.save(Session(id, System.currentTimeMillis(), 270, RecordingState.Paused, clips))
        return id
    }

    private fun record(count: Int, pose: Int) {
        assertTrue("Preserve any existing session", model.snapshots.value.state in setOf(RecordingState.Ready, RecordingState.Completed))
        repeat(count) { index ->
            if (index == 0) model.start(pose) else model.resume(pose)
            await("take ${index + 1} recorded") {
                model.snapshots.value.state == RecordingState.Recording && model.snapshots.value.activeDurationMs >= 1_600
            }
            model.pause()
            await("take ${index + 1} saved") {
                model.snapshots.value.state == RecordingState.Paused && session.segments.size == index + 1
            }
        }
    }

    private fun expectSummary(kept: Int, discarded: Int, fraction: Float) {
        val name = "$kept kept, $discarded discarded"
        val rect = namedBounds(name)
        val summary = node { it.named(name) }!!
        assertFalse(summary.isClickable)
        assertFalse(summary.isScrollable)
        val image = checkNotNull(instrument.uiAutomation.takeScreenshot())
        try {
            val split = rect.left + (rect.width() * fraction).toInt()
            val y = rect.top + 4 // Colour only, outside the label.
            if (kept > 0) assertColour(0xFF8FC7A1.toInt(), image.getPixel(
                if (discarded == 0) rect.centerX() else split - 3, y))
            if (discarded > 0) assertColour(0xFFF0C27A.toInt(), image.getPixel(
                if (kept == 0) rect.centerX() else split + 3, y))
        } finally {
            image.recycle()
        }
    }

    private fun assertColour(expected: Int, actual: Int) {
        assertTrue("Expected ${expected.toUInt().toString(16)}, got ${actual.toUInt().toString(16)}",
            abs(Color.red(expected) - Color.red(actual)) <= 3 &&
                abs(Color.green(expected) - Color.green(actual)) <= 3 &&
                abs(Color.blue(expected) - Color.blue(actual)) <= 3)
    }

    private fun keep(order: Int, kept: Boolean) {
        val current = session
        edit { model.setSegmentKept(current.id, current.segments.single { it.order == order }.id, kept, it) }
    }

    private fun edit(block: ((SegmentEditResult) -> Unit) -> Unit) {
        val result = AtomicReference<SegmentEditResult>()
        block(result::set)
        await("saved edit") { result.get() != null }
        assertTrue(result.get().message ?: "Could not save change", result.get().success)
    }

    private fun startPlayback() {
        tap("Play skit from beginning")
        await("playlist starts") {
            main {
                assertNull("Playlist failed", playback.error)
                playback.player?.isPlaying == true
            }
        }
        main { playback.setPlayWhenReady(false) }
    }

    private fun seekForScreenshot() {
        main {
            playback.player!!.seekTo(3, 500)
        }
        await("fourth kept clip presented") {
            main { playback.currentIndex == 3 && playback.player!!.videoSize.width > 0 } &&
                node { it.text?.toString() == "Clip 4 of 8 · Segment 5" } != null
        }
    }

    private fun expectCentered(position: Int) {
        val rail = namedBounds("Playback segments")
        await("clip $position selected and horizontally centred in $rail") {
            val pill = node { it.named("Clip $position") && (it.isSelected || it.isChecked) }
            pill != null && abs(Rect().also(pill::getBoundsInScreen).centerX() - rail.centerX()) <= 2
        }
        expectState(position, "Current clip")
    }

    private fun expectState(position: Int, state: String) {
        await("clip $position is $state") {
            node { it.named("Clip $position") && it.stateDescription?.toString() == state } != null
        }
    }

    private fun expectFraming(aspect: Float) {
        val video = namedBounds("Skit playback")
        val rail = namedBounds("Playback segments")
        assertTrue("Video stays below rail: $video / $rail", video.top >= rail.bottom)
        assertEquals(aspect, video.width().toFloat() / video.height(), .025f)
    }

    private fun sourceHashes() = session.segments.joinToString("\n") { "${it.order} ${sha(File(it.path))}" }

    private fun export() {
        val id = session.id
        model.stop()
        await("verified export", 150_000) { ExportJournal.status.value?.let { it.sessionId == id && !it.active } == true }
        val status = ExportJournal.status.value!!
        assertEquals(status.message, ExportState.Completed, status.state)
        val uri = Uri.parse(requireNotNull(status.uri))
        val extractor = MediaExtractor()
        try {
            extractor.setDataSource(context, uri, null)
            val tracks = (0 until extractor.trackCount).map(extractor::getTrackFormat)
            assertTrue(tracks.any { it.getString(MediaFormat.KEY_MIME)?.startsWith("video/") == true })
            assertTrue(tracks.any { it.getString(MediaFormat.KEY_MIME)?.startsWith("audio/") == true })
        } finally {
            extractor.release()
        }
        File(evidence, "export-$id.txt").writeText("$status\n")
    }

    private fun capture(name: String) {
        instrument.waitForIdleSync()
        val image = checkNotNull(instrument.uiAutomation.takeScreenshot())
        try {
            File(evidence, "$name.png").outputStream().use { image.compress(Bitmap.CompressFormat.PNG, 100, it) }
        } finally {
            image.recycle()
        }
    }

    private fun AccessibilityNodeInfo.named(name: String) = contentDescription?.toString() == name
    private fun node(predicate: (AccessibilityNodeInfo) -> Boolean): AccessibilityNodeInfo? {
        if (Build.VERSION.SDK_INT >= 33) instrument.uiAutomation.clearCache()
        fun search(node: AccessibilityNodeInfo): AccessibilityNodeInfo? {
            if (node.isVisibleToUser && predicate(node)) return node
            for (i in 0 until node.childCount) node.getChild(i)?.let { search(it) }?.let { return it }
            return null
        }
        return instrument.uiAutomation.rootInActiveWindow?.let(::search)
    }

    private fun namedBounds(name: String) = bounds { it.named(name) }
    private fun bounds(predicate: (AccessibilityNodeInfo) -> Boolean): Rect {
        await("visible bounds") { node(predicate) != null }
        return Rect().also(node(predicate)!!::getBoundsInScreen)
    }

    private fun tap(name: String) {
        await("$name available") { node { it.named(name) } != null }
        assertTrue("Click $name", node { it.named(name) }!!.performAction(AccessibilityNodeInfo.ACTION_CLICK))
    }

    private fun await(what: String, timeoutMs: Long = 20_000, ready: () -> Boolean) {
        val deadline = System.nanoTime() + timeoutMs * 1_000_000
        do {
            if (ready()) return
            Thread.sleep(40)
        } while (System.nanoTime() < deadline)
        fail("Timed out: $what (recording state: ${model.snapshots.value.state})")
    }

    private fun shell(command: String) = instrument.uiAutomation.executeShellCommand(command).use {
        android.os.ParcelFileDescriptor.AutoCloseInputStream(it).bufferedReader().readText().trim()
    }

    private fun <T> main(block: () -> T): T {
        val result = AtomicReference<Result<T>>()
        instrument.runOnMainSync { result.set(runCatching(block)) }
        return result.get().getOrThrow()
    }
}
