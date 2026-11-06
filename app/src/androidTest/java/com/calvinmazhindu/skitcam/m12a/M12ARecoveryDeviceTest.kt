package com.calvinmazhindu.skitcam.m12a

import android.Manifest
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.calvinmazhindu.skitcam.MainActivity
import com.calvinmazhindu.skitcam.export.ExportTestSupport
import com.calvinmazhindu.skitcam.recording.RecordingState
import com.calvinmazhindu.skitcam.recording.RecordingViewModel
import com.calvinmazhindu.skitcam.recording.SegmentEditResult
import com.calvinmazhindu.skitcam.recording.SessionCodec
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.FixMethodOrder
import org.junit.Test
import org.junit.runner.RunWith
import org.junit.runners.MethodSorters
import java.io.File
import java.util.concurrent.atomic.AtomicReference

@RunWith(AndroidJUnit4::class)
@FixMethodOrder(MethodSorters.NAME_ASCENDING)
class M12ARecoveryDeviceTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context get() = instrumentation.targetContext
    private lateinit var scenario: ActivityScenario<MainActivity>
    private lateinit var model: RecordingViewModel
    private var originalRotation: String? = null
    private val checkpoint get() = File(context.filesDir, "m12a-recovery.json")

    private fun shell(command: String) = instrumentation.uiAutomation.executeShellCommand(command).use {
        android.os.ParcelFileDescriptor.AutoCloseInputStream(it).bufferedReader().readText().trim()
    }
    private fun <T> main(block: () -> T): T {
        val value = AtomicReference<T>()
        instrumentation.runOnMainSync { value.set(block()) }
        return value.get()
    }
    @Before fun open() {
        for (permission in listOf(Manifest.permission.CAMERA, Manifest.permission.RECORD_AUDIO)) {
            shell("pm grant ${context.packageName} $permission")
        }
        originalRotation = shell("wm user-rotation")
        shell("wm user-rotation lock 0")
        scenario = ActivityScenario.launch(MainActivity::class.java)
        scenario.onActivity { model = it.recordingModel }
        ExportTestSupport.beforeCamera()
        await("camera ready") { model.cameraReady }
    }
    @After fun close() {
        if (::scenario.isInitialized) { ExportTestSupport.settle(); scenario.close() }
        originalRotation?.let { shell("wm user-rotation $it") }
    }
    private fun await(message: String, condition: () -> Boolean) {
        val deadline = System.nanoTime() + 30_000_000_000L
        while (!condition() && System.nanoTime() < deadline) Thread.sleep(100)
        assertTrue("Timed out: $message; ${if (::model.isInitialized) model.snapshots.value else "no model"}", condition())
    }
    private fun state(value: RecordingState) = await(value.name) { model.snapshots.value.state == value }
    private fun record(count: Int) {
        await("stable pose") { cameraPoseZero() }
        model.start(0); state(RecordingState.Recording)
        repeat(count) { index ->
            if (index > 0) { model.resume(0); state(RecordingState.Recording) }
            await("recorded samples") { model.snapshots.value.activeDurationMs >= 1200 }
            model.pause(); state(RecordingState.Paused)
        }
    }
    private fun cameraPoseZero(): Boolean {
        var zero = false
        scenario.onActivity { zero = it.orientation.pose == 0 }
        return zero
    }
    private fun edit(block: ((SegmentEditResult) -> Unit) -> Unit) {
        val result = AtomicReference<SegmentEditResult>()
        block { result.set(it) }
        await("metadata edit") { result.get() != null }
        assertTrue(result.get().message ?: "metadata edit failed", result.get().success)
    }

    @Test fun a_checkpointPausedSession() {
        assertEquals(RecordingState.Ready, model.snapshots.value.state)
        record(3)
        val original = model.snapshots.value.session!!
        edit { done -> model.setSegmentKept(original.id, original.segments[1].id, false, done) }
        val afterDiscard = model.snapshots.value.session!!
        assertEquals(2, afterDiscard.retainedCount)
        val reordered = afterDiscard.playbackOrder.reversed()
        edit { done -> model.savePlaybackOrder(afterDiscard.id, afterDiscard.playbackOrder, reordered, done) }
        val saved = model.snapshots.value.session!!
        assertEquals(reordered, saved.playbackOrder)
        assertFalse(saved.segments[1].kept)
        checkpoint.writeText(SessionCodec.encode(saved))
        // Leave the session Paused. The host force-stops the validation process after this test.
    }

    @Test fun b_restoreAfterProcessDeath() {
        assertTrue("checkpoint missing", checkpoint.exists())
        await("recovered paused session") { model.snapshots.value.state == RecordingState.Paused }
        val expected = SessionCodec.decode(checkpoint.readText())
        assertEquals(expected, model.snapshots.value.session)
        assertEquals(expected.playbackOrder, model.snapshots.value.session!!.playbackOrder)
        assertFalse(model.snapshots.value.session!!.segments[1].kept)
        assertTrue(model.snapshots.value.message!!.contains("Recovered paused session"))
        model.stop(); state(RecordingState.Completed)
    }
}
