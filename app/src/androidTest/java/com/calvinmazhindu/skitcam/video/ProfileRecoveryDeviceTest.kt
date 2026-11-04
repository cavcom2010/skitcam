package com.calvinmazhindu.skitcam.video

import android.Manifest
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.calvinmazhindu.skitcam.MainActivity
import com.calvinmazhindu.skitcam.camera.*
import com.calvinmazhindu.skitcam.recording.*
import com.calvinmazhindu.skitcam.export.*
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/** Run each method in a separate instrumentation process, force-stopping only .m9validation between them. */
@RunWith(AndroidJUnit4::class)
class ProfileRecoveryDeviceTest {
    private val inst=InstrumentationRegistry.getInstrumentation()
    private val context get()=inst.targetContext
    private fun await(message: String, block: ()->Boolean) {
        val end=System.nanoTime()+180_000_000_000L
        while(!block() && System.nanoTime()<end) Thread.sleep(100)
        assertTrue(message+": "+ExportJournal.status.value,block())
    }
    @Test fun a_savePausedProfileCheckpoint() {
        check((context.packageName.endsWith(".m9validation") || (context.packageName.endsWith(".m10validation") || (context.packageName.endsWith(".framingvalidation") || context.packageName.endsWith(".m11validation")))))
        for(p in listOf(Manifest.permission.CAMERA,Manifest.permission.RECORD_AUDIO)) inst.uiAutomation.executeShellCommand("pm grant ${context.packageName} $p").close()
        val scenario=ActivityScenario.launch(MainActivity::class.java)
        try {
            lateinit var activity: MainActivity
            scenario.onActivity { activity=it }
            val model=activity.recordingModel;val camera=activity.cameraModel
            await("recovery") { model.recovered.value && camera.ready }
            if(model.snapshots.value.openSession) {
                assertTrue("Only a settled isolated test session may be completed",model.snapshots.value.state in setOf(RecordingState.Paused,RecordingState.Error) && !model.snapshots.value.unresolved)
                model.stop();await("completed prior test fixture") { model.snapshots.value.state==RecordingState.Completed }
            }
            ExportTestSupport.settle()
            inst.runOnMainSync { ExportJournal.status.value?.let { camera.acknowledge(it.sessionId) } }
            await("stream") { camera.streaming }
            inst.runOnMainSync { camera.select(CameraMode.SkitVideo,CameraLens.Rear,true) }
            await("rear video") { model.cameraReady && camera.streaming && camera.choice==CameraChoice() && !camera.switching && camera.video.canEdit() }
            inst.runOnMainSync { camera.video.choose(VideoSettings("FHD",60)) }
            await("sixty ready") { model.cameraReady && !camera.video.pending && camera.video.settings==VideoSettings("FHD",60) }
            model.start(0);await("recording") { model.snapshots.value.state==RecordingState.Recording }
            await("samples") { model.snapshots.value.activeDurationMs>=2500 }
            model.pause();await("paused") { model.snapshots.value.state==RecordingState.Paused }
            val session=model.snapshots.value.session!!
            val metadata=File(context.filesDir,"sessions/${session.id}/session.json").readText()
            val directory=File(context.filesDir,"validation-m9").apply { check(isDirectory || mkdirs()) }
            File(directory,"recovery-checkpoint.json").writeText(JSONObject().put("sessionId",session.id).put("metadata",metadata).toString(2))
            // Opposing idle preferences must never override a recovered session.
            assertTrue(context.getSharedPreferences("video_preferences",0).edit().putString("camera_0",VideoJson.settings(VideoSettings("HD",30)).toString()).commit())
            assertTrue(context.getSharedPreferences("camera_preferences",0).edit().putString("mode",CameraMode.Photo.name).commit())
        } finally { scenario.close() }
    }
    @Test fun b_recoverExactProfileBeforePreferencesAndResume() {
        check((context.packageName.endsWith(".m9validation") || (context.packageName.endsWith(".m10validation") || (context.packageName.endsWith(".framingvalidation") || context.packageName.endsWith(".m11validation")))))
        val checkpoint=JSONObject(File(context.filesDir,"validation-m9/recovery-checkpoint.json").readText())
        val expected=SessionCodec.decode(checkpoint.getString("metadata"))
        val scenario=ActivityScenario.launch(MainActivity::class.java)
        try {
            lateinit var activity: MainActivity
            scenario.onActivity { activity=it }
            val model=activity.recordingModel;val camera=activity.cameraModel
            await("restored session and binding") { model.recovered.value && model.cameraReady }
            assertEquals(expected,model.snapshots.value.session)
            assertEquals(expected.captureProfile,model.port.profile)
            assertEquals(CameraChoice(),camera.choice)
            assertEquals(VideoSettings("FHD",60),camera.video.settings)
            assertEquals(checkpoint.getString("metadata"),File(context.filesDir,"sessions/${expected.id}/session.json").readText())
            model.resume(0);await("resume") { model.snapshots.value.state==RecordingState.Recording }
            await("new samples") { model.snapshots.value.activeDurationMs>=2500 }
            model.stop();await("finalisation") { model.snapshots.value.state==RecordingState.Completed }
            await("publication") { ExportJournal.status.value?.active==false }
            assertEquals(ExportState.Completed,ExportJournal.status.value!!.state)
            assertEquals(2,model.snapshots.value.session!!.segments.size)
            val completed=model.snapshots.value.session!!
            assertEquals(expected.captureProfile,completed.captureProfile)
            assertTrue(completed.segments.all { it.media!!.valid() && it.media.averageFps!=null })
            File(context.filesDir,"validation-m9/recovery-result.json").writeText(JSONObject()
                .put("session",JSONObject(SessionCodec.encode(completed))).put("cameraResult",camera.video.diagnostic()).toString(2))
            inst.runOnMainSync { camera.acknowledge(expected.id) }
            await("camera returned") { model.cameraReady }
            inst.runOnMainSync { camera.video.choose(VideoSettings()) }
            await("default restored") { model.cameraReady && camera.video.settings==VideoSettings() && !camera.video.pending }
        } finally { scenario.close() }
    }
}
