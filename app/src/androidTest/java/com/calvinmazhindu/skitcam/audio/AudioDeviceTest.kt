package com.calvinmazhindu.skitcam.audio

import android.Manifest
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.calvinmazhindu.skitcam.MainActivity
import com.calvinmazhindu.skitcam.camera.CameraMode
import com.calvinmazhindu.skitcam.recording.*
import com.calvinmazhindu.skitcam.export.*
import org.junit.*
import org.junit.Assert.*
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class AudioDeviceTest {
    private val instrumentation=InstrumentationRegistry.getInstrumentation()
    private fun shell(command: String)=instrumentation.uiAutomation.executeShellCommand(command).use {
        android.os.ParcelFileDescriptor.AutoCloseInputStream(it).bufferedReader().readText().trim()
    }
    private fun await(message: String, condition: () -> Boolean) {
        val deadline=System.nanoTime()+180_000_000_000L
        while(!condition() && System.nanoTime()<deadline) Thread.sleep(100)
        assertTrue(message,condition())
    }
    @Test fun builtinDiscoveryLevelPauseResumeAndExport() {
        val pkg=instrumentation.targetContext.packageName
        for(p in listOf(Manifest.permission.CAMERA,Manifest.permission.RECORD_AUDIO,Manifest.permission.POST_NOTIFICATIONS)) shell("pm grant $pkg $p")
        val rotation=shell("wm user-rotation")
        val scenario=ActivityScenario.launch(MainActivity::class.java)
        lateinit var model: RecordingViewModel
        lateinit var activity: MainActivity
        var owned: String?=null
        try {
            scenario.onActivity { activity=it;model=it.recordingModel }
            await("session recovery") { model.recovered.value }
            Assume.assumeTrue("Preserve user recording",!ExportJournal.busy && model.snapshots.value.state in setOf(RecordingState.Ready,RecordingState.Completed))
            ExportTestSupport.beforeCamera()
            lateinit var camera: com.calvinmazhindu.skitcam.camera.CameraModel
            scenario.onActivity { camera=it.cameraModel }
            await("camera stream") { camera.streaming }
            scenario.onActivity { camera.select(CameraMode.SkitVideo,CameraLens.Rear,true) }
            await("video camera") { model.cameraReady && camera.streaming && !camera.switching && camera.choice.mode==CameraMode.SkitVideo && activity.orientation.pose==0 }
            await("built-in microphone discovery") { model.audio.state.value.inputs.any { it.kind==InputKind.BuiltIn } }
            model.start(0);await("recording") { model.snapshots.value.state==RecordingState.Recording }
            owned=model.snapshots.value.session!!.id
            await("CameraX audio sample") { model.audio.state.value.health==CaptureHealth.Active && model.audio.state.value.peak!=null }
            val sample=model.audio.state.value
            assertTrue(sample.title.contains("unverified"));assertTrue(sample.permission)
            assertTrue(sample.peak!! in 0.0..1.0)
            val diagnostic=java.io.File(instrumentation.targetContext.filesDir,"validation-m7/audio-$owned.txt")
            diagnostic.parentFile!!.mkdirs()
            diagnostic.writeText("session=$owned\ninputs=${sample.inputs}\nobservations=${sample.observations}\nhealth=${sample.health}\npeak=${sample.peak}\n")
            await("samples") { model.snapshots.value.activeDurationMs>=1500 };model.pause()
            await("paused") { model.snapshots.value.state==RecordingState.Paused }
            await("meter released") { model.audio.state.value.health==CaptureHealth.Idle && model.audio.state.value.peak==null }
            scenario.recreate();scenario.onActivity { activity=it;model=it.recordingModel }
            await("camera after recreation") { model.cameraReady && activity.orientation.pose==0 }
            model.resume(0);await("resumed") { model.snapshots.value.state==RecordingState.Recording }
            await("resumed audio") { model.audio.state.value.health==CaptureHealth.Active && model.snapshots.value.activeDurationMs>=1500 }
            model.stop();await("recording complete") { model.snapshots.value.state==RecordingState.Completed }
            await("export settled") { ExportJournal.status.value?.let { it.sessionId==owned && !it.active }==true }
            assertEquals(ExportState.Completed,ExportJournal.status.value!!.state)
            assertEquals(2,model.snapshots.value.count)
        } finally {
            if(owned!=null && model.snapshots.value.session?.id==owned && model.snapshots.value.state in setOf(RecordingState.Recording,RecordingState.Paused)) model.stop()
            ExportTestSupport.settle();scenario.close();assertEquals("Audio test must not change global rotation",rotation,shell("wm user-rotation"))
        }
    }
}
