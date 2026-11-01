package com.calvinmazhindu.skitcam.recording

import android.Manifest
import android.content.pm.ActivityInfo
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.lifecycle.Lifecycle
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.calvinmazhindu.skitcam.MainActivity
import org.junit.*
import org.junit.Assert.*
import org.junit.runner.RunWith
import org.junit.runners.MethodSorters
import java.io.File
import java.util.UUID

/** Uses real CameraX/audio/files. Deliberately retains every test session and MP4. */
@RunWith(AndroidJUnit4::class)
@FixMethodOrder(MethodSorters.NAME_ASCENDING)
class RecordingDeviceTest {
    private lateinit var scenario: ActivityScenario<MainActivity>
    private lateinit var model: RecordingViewModel
    private var originalRotation: String? = null
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context get() = instrumentation.targetContext
    private fun shell(command: String) = instrumentation.uiAutomation.executeShellCommand(command).use {
        android.os.ParcelFileDescriptor.AutoCloseInputStream(it).bufferedReader().readText().trim()
    }

    @Before fun openCamera() {
        for (permission in listOf(Manifest.permission.CAMERA, Manifest.permission.RECORD_AUDIO)) {
            instrumentation.uiAutomation.executeShellCommand("pm grant ${context.packageName} $permission")
                .use { android.os.ParcelFileDescriptor.AutoCloseInputStream(it).readBytes() }
        }
        originalRotation = shell("wm user-rotation")
        shell("wm user-rotation lock 0")
        scenario = ActivityScenario.launch(MainActivity::class.java)
        scenario.onActivity {
            it.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_PORTRAIT
            model = it.recordingModel
        }
        com.calvinmazhindu.skitcam.export.ExportTestSupport.beforeCamera()
        await("camera ready") { model.cameraReady }
        Assume.assumeTrue("Do not modify an existing open user session",
            model.snapshots.value.state in setOf(RecordingState.Ready, RecordingState.Completed))
    }
    @After fun closeCamera() {
        try { if (::scenario.isInitialized) { com.calvinmazhindu.skitcam.export.ExportTestSupport.settle(); scenario.close() } }
        finally { originalRotation?.let { shell("wm user-rotation $it") } }
    }
    private fun await(description: String, predicate: () -> Boolean) {
        val deadline = System.nanoTime() + 25_000_000_000L
        while (!predicate() && System.nanoTime() < deadline) Thread.sleep(100)
        assertTrue("Timed out: $description; ${if (::model.isInitialized) model.snapshots.value else "no model"}", predicate())
    }
    private fun state(state: RecordingState) = await(state.name) { model.snapshots.value.state == state }
    private fun start() {
        // Exercise the engine with a known pose; sensor filtering is independently tested.
        scenario.onActivity { model.start(0) }
        state(RecordingState.Recording)
        await("one second of audio/video") { model.snapshots.value.activeDurationMs >= 1200 }
    }
    private fun pause() { model.pause(); state(RecordingState.Paused) }
    private fun verifyFiles(session: Session) {
        val store = SessionFileStore(File(context.filesDir, "sessions"))
        session.segments.forEach {
            assertTrue(File(it.path).length() > 0)
            assertEquals(it.videoRotationDegrees, store.validate(it.path))
            assertTrue(it.durationMs > 0)
            assertEquals(session.orientationDegrees, it.orientationDegrees)
        }
        assertEquals(session, store.loadLatest())
    }
    @Test fun a_twoSegmentsStopWhileRecording() {
        start(); pause()
        assertEquals(1, model.snapshots.value.count)
        model.resume(180)
        Thread.sleep(300)
        assertEquals(RecordingState.Paused, model.snapshots.value.state)
        assertEquals(1, model.snapshots.value.count)
        model.resume(0); state(RecordingState.Recording)
        await("second segment duration") { model.snapshots.value.activeDurationMs >= 1200 }
        model.stop(); state(RecordingState.Completed)
        val session = model.snapshots.value.session!!
        assertEquals(listOf(1, 2), session.segments.map { it.order })
        verifyFiles(session)
    }
    @Test fun b_stopWhilePausedDoesNotCreateExtraFile() {
        start(); pause()
        val before = model.snapshots.value.session!!
        model.stop(); state(RecordingState.Completed)
        assertEquals(before.segments, model.snapshots.value.session!!.segments)
        assertEquals(1, File(context.filesDir, "sessions/${before.id}").listFiles()!!.count { it.extension == "mp4" })
        verifyFiles(model.snapshots.value.session!!)
    }
    @Test fun c_backgroundFinalizesAndExplicitResumeWorks() {
        start()
        scenario.moveToState(Lifecycle.State.CREATED)
        state(RecordingState.Paused)
        assertEquals(1, model.snapshots.value.count)
        scenario.moveToState(Lifecycle.State.RESUMED)
        await("preview recovered") { model.cameraReady }
        model.resume(0); state(RecordingState.Recording)
        await("resumed media") { model.snapshots.value.activeDurationMs >= 1200 }
        model.stop(); state(RecordingState.Completed)
        assertEquals(2, model.snapshots.value.count)
        verifyFiles(model.snapshots.value.session!!)
    }
    @Test fun d_sourceLossRetainsEarlierConfirmedSegment() {
        start(); pause()
        val first = model.snapshots.value.session!!.segments.single()
        model.resume(0); state(RecordingState.Recording)
        await("data before camera loss") { model.snapshots.value.activeDurationMs >= 1200 }
        scenario.onActivity {
            ProcessCameraProvider.getInstance(it).get().unbind(model.port.capture)
        }
        state(RecordingState.Error)
        val session = model.snapshots.value.session!!
        assertEquals(listOf(first), session.segments)
        assertTrue(File(first.path).length() > 0)
        assertNotNull(session.pending)
        model.stop(); state(RecordingState.Completed)
        verifyFiles(model.snapshots.value.session!!)
    }
    @Test fun e_metadataRoundTripAndInvalidFile() {
        val root = File(context.filesDir, "storage-tests/${UUID.randomUUID()}")
        val store = SessionFileStore(root)
        val session = Session(UUID.randomUUID().toString(), 42L, 270, RecordingState.Paused,
            listOf(Segment(1, File(root, "retained.mp4").path, 1200L, 270, 0)))
        store.save(session)
        assertEquals(session, store.loadLatest())
        store.save(session.copy(state = RecordingState.Completed))
        assertEquals(RecordingState.Completed, store.loadLatest()!!.state)
        val empty = File(root, "empty.mp4").apply { createNewFile() }
        try { store.validate(empty.path); fail("Empty media must not be registered") }
        catch (_: IllegalStateException) { /* Expected. */ }
    }
    @Test fun f_pausedSessionSurvivesActivityRecreation() {
        start(); pause()
        val saved = model.snapshots.value.session!!
        scenario.recreate()
        scenario.onActivity { model = it.recordingModel }
        await("preview after recreation") { model.cameraReady }
        state(RecordingState.Paused)
        assertEquals(saved, model.snapshots.value.session)
        verifyFiles(saved)
        model.stop(); state(RecordingState.Completed)
    }
}
