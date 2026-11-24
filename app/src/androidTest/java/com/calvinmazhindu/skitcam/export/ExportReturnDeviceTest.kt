package com.calvinmazhindu.skitcam.export

import android.Manifest
import android.net.Uri
import android.os.SystemClock
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.calvinmazhindu.skitcam.MainActivity
import com.calvinmazhindu.skitcam.recording.*
import org.junit.Assert.*
import org.junit.Assume
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/**
 * Camera return after a normal automatic export, without gallery/share UI automation.
 * The heavier end-to-end UI test remains in ExportDeviceTest.
 */
@RunWith(AndroidJUnit4::class)
class ExportReturnDeviceTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context = instrumentation.targetContext
    private fun shell(command: String) = instrumentation.uiAutomation.executeShellCommand(command).use {
        android.os.ParcelFileDescriptor.AutoCloseInputStream(it).bufferedReader().readText().trim()
    }
    private fun await(message: String, limitMs: Long = 180_000, condition: () -> Boolean) {
        val end = SystemClock.elapsedRealtime() + limitMs
        while (!condition() && SystemClock.elapsedRealtime() < end) Thread.sleep(100)
        assertTrue(message, condition())
    }

    @Test fun automaticExportReturnsToLiveCamera() {
        check(context.packageName == "com.calvinmazhindu.skitcam.m15b31a.m12bvalidation")
        for (permission in listOf(Manifest.permission.CAMERA, Manifest.permission.RECORD_AUDIO,
            Manifest.permission.POST_NOTIFICATIONS)) shell("pm grant ${context.packageName} $permission")
        Assume.assumeFalse("Do not interrupt an active export", ExportJournal.busy)
        val rotation = shell("wm user-rotation")
        val scenario = ActivityScenario.launch(MainActivity::class.java)
        try {
            lateinit var model: RecordingViewModel
            lateinit var activity: MainActivity
            scenario.onActivity { activity = it;model = it.recordingModel }
            Assume.assumeTrue("Preserve any open session",
                model.snapshots.value.state in setOf(RecordingState.Ready, RecordingState.Completed) &&
                    !model.snapshots.value.openSession)
            instrumentation.runOnMainSync { ExportJournal.status.value = null }
            await("camera ready") { model.cameraReady && activity.orientation.pose == 0 }

            model.start(0)
            await("recording") { model.snapshots.value.state == RecordingState.Recording }
            await("samples") { model.snapshots.value.activeDurationMs >= 1200 }
            model.stop();model.stop()
            await("finalised") { model.snapshots.value.state == RecordingState.Completed }
            val session = model.snapshots.value.session!!
            val hashes = session.segments.map { sha(File(it.path)) }

            await("export terminal", 180_000) {
                ExportJournal.status.value?.let { it.sessionId == session.id && !it.active } == true
            }
            val status = ExportJournal.status.value!!
            assertEquals(status.toString(), ExportState.Completed, status.state)
            val uri = Uri.parse(status.uri)
            context.contentResolver.openInputStream(uri)!!.use { assertTrue(it.read() >= 0) }
            assertEquals("Originals must be unchanged after automatic export",
                hashes, session.segments.map { sha(File(it.path)) })
            val rows = fieldPublicationRows(context, checkNotNull(ExportJournal.read(context, session.id)))
            assertEquals("One owned Gallery row", 1, rows.size)
            assertFalse(rows.single().second)

            await("live camera with the saved video exposed") {
                var returned = false
                scenario.onActivity {
                    returned = model.cameraReady && it.cameraModel.streaming &&
                        it.cameraModel.latestUri == status.uri
                }
                returned
            }
            scenario.onActivity {
                assertTrue(it.cameraModel.streaming)
                assertEquals(status.uri, it.cameraModel.latestUri)
            }
        } finally {
            scenario.close()
            assertEquals("Export test must not change global rotation", rotation, shell("wm user-rotation"))
        }
    }
}
