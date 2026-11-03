package com.calvinmazhindu.skitcam.video

import android.Manifest
import android.content.pm.ActivityInfo
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.calvinmazhindu.skitcam.MainActivity
import com.calvinmazhindu.skitcam.camera.*
import com.calvinmazhindu.skitcam.recording.*
import com.calvinmazhindu.skitcam.export.*
import com.calvinmazhindu.skitcam.trim.*
import org.json.JSONObject
import org.junit.*
import org.junit.Assert.*
import org.junit.runner.RunWith
import org.junit.runners.MethodSorters
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/** All recordings/publications belong to the isolated M9 application; no user data is cleared. */
@RunWith(AndroidJUnit4::class)
@FixMethodOrder(MethodSorters.NAME_ASCENDING)
class VideoDeviceTest {
    private val inst=InstrumentationRegistry.getInstrumentation()
    private val context get()=inst.targetContext
    private lateinit var scenario: ActivityScenario<MainActivity>
    private lateinit var activity: MainActivity
    private var originalRotation: String?=null
    private val camera get()=activity.cameraModel
    private val model get()=activity.recordingModel
    private val video get()=camera.video
    private fun shell(command: String)=inst.uiAutomation.executeShellCommand(command).use {
        android.os.ParcelFileDescriptor.AutoCloseInputStream(it).bufferedReader().readText().trim()
    }
    private fun await(label: String,seconds: Int=50,block: ()->Boolean) {
        val end=System.nanoTime()+TimeUnit.SECONDS.toNanos(seconds.toLong())
        while(!block() && System.nanoTime()<end) Thread.sleep(100)
        assertTrue("$label: ${video.message}; ${camera.message}; ${model.snapshots.value}; ${ExportJournal.status.value}",block())
    }
    @Before fun open() {
        check((context.packageName.endsWith(".m9validation") || context.packageName.endsWith(".m10validation")))
        for(p in listOf(Manifest.permission.CAMERA,Manifest.permission.RECORD_AUDIO,Manifest.permission.POST_NOTIFICATIONS)) shell("pm grant ${context.packageName} $p")
        originalRotation=shell("wm user-rotation");shell("wm user-rotation lock 0")
        scenario=ActivityScenario.launch(MainActivity::class.java)
        scenario.onActivity { activity=it;it.requestedOrientation=ActivityInfo.SCREEN_ORIENTATION_PORTRAIT }
        await("recovery") { camera.ready && model.recovered.value }
        if(model.snapshots.value.state==RecordingState.Error && !model.snapshots.value.unresolved) {
            model.stop();await("close settled validation failure") { model.snapshots.value.state==RecordingState.Completed }
        }
        ExportTestSupport.settle()
        inst.runOnMainSync { ExportJournal.status.value?.takeIf { !it.active }?.let { camera.acknowledge(it.sessionId) } }
        await("stream") { camera.streaming }
        inst.runOnMainSync { camera.select(CameraMode.SkitVideo,CameraLens.Rear,true) }
        await("ready rear video") { camera.streaming && !camera.switching && camera.choice==CameraChoice() && model.cameraReady }
    }
    @After fun close() { try { if(::scenario.isInitialized) scenario.close() } finally { originalRotation?.let { shell("wm user-rotation $it") } } }
    private fun choose(value: VideoSettings) {
        inst.runOnMainSync {
            val changed=value!=video.settings
            video.choose(value)
            if(changed && video.pending) {
                // Reproduce an old Preview STREAMING callback before Compose rebinds.
                // The previous binding must not commit/cancel this newly requested profile.
                video.streamed()
                Thread.sleep(100)
                assertFalse("START must close synchronously before a rebind",model.cameraReady)
                val before=model.snapshots.value
                model.start(0)
                assertEquals(before,model.snapshots.value)
            }
        }
        await("profile $value") { !video.pending && model.cameraReady && video.settings==value }
    }
    private fun record(pause: Boolean=true,pose: Int=0,durationMs: Long=2200) {
        val session=model.snapshots.value
        if(session.state==RecordingState.Paused) model.resume(pose) else inst.runOnMainSync { model.start(pose) }
        await("recording") { model.snapshots.value.state==RecordingState.Recording }
        await("samples") { model.snapshots.value.activeDurationMs>=durationMs }
        if(pause) {
            model.pause();await("paused") { model.snapshots.value.state==RecordingState.Paused }
            val segment=model.snapshots.value.session!!.segments.last()
            assertTrue(segment.media!!.valid())
            assertTrue(model.snapshots.value.session!!.captureProfile!!.accepts(segment.media))
        } else model.stop()
    }
    private fun finish(label: String) {
        model.stop()
        await("completed") { model.snapshots.value.state==RecordingState.Completed }
        await("published",180) { ExportJournal.status.value?.active==false }
        val status=ExportJournal.status.value!!
        assertEquals(status.toString(),ExportState.Completed,status.state)
        val dir=File(context.filesDir,"validation-m9").apply { mkdirs() }
        File(dir,"$label.json").writeText(JSONObject().put("capabilities",video.diagnostic()).put("session",JSONObject(SessionCodec.encode(model.snapshots.value.session!!)))
            .put("export",ExportJournal.read(context,status.sessionId)).toString(2))
        context.contentResolver.openInputStream(android.net.Uri.parse(status.uri!!))!!.use { input -> File(dir,"$label.mp4").outputStream().use { input.copyTo(it) } }
        inst.runOnMainSync { camera.acknowledge(status.sessionId) }
        await("camera after export") { model.cameraReady }
    }
    @Test fun a_rearSupportedQualityAudioAndOutputGeometry() {
        val dir=File(context.filesDir,"validation-m9").apply { mkdirs() }
        File(dir,"rear-capabilities.json").writeText(video.diagnostic().toString(2))
        assertEquals("FHD",video.settings.quality)
        assertTrue("FHD sixty must be configuration-reported",60 in video.fpsOptions)
        val available=video.qualities.toList()
        for(quality in available) {
            choose(VideoSettings(quality))
            record();finish("rear-$quality-auto")
        }
    }
    @Test fun b_profileFrozenTrimArrangeAndResume() {
        choose(VideoSettings("FHD",30,true,"DAYLIGHT"))
        await("white balance confirmed") { video.whiteBalanceStatus.contains("confirmed") }
        record()
        val first=model.snapshots.value.session!!
        inst.runOnMainSync { video.choose(VideoSettings("HD")) }
        assertEquals(first.captureProfile!!.settings,video.settings)
        assertEquals(first,model.snapshots.value.session)
        scenario.recreate();scenario.onActivity { activity=it }
        await("recreated locked profile") { model.cameraReady }
        assertEquals(first.captureProfile,model.port.profile)
        record()
        val session=model.snapshots.value.session!!
        val segment=session.segments.first()
        val source=TrimSource(SessionFileStore(File(context.filesDir,"sessions")).sourceFacts(segment.path),segment.trim!!.sourceDurationMs)
        val latch=CountDownLatch(1)
        var result: SegmentEditResult?=null
        model.saveTrim(session.id,segment,source,TrimRange(source.presentationDurationMs,100,source.presentationDurationMs-100)) { result=it;latch.countDown() }
        assertTrue(latch.await(15,TimeUnit.SECONDS));assertTrue(result.toString(),result!!.success)
        val order=CountDownLatch(1)
        model.savePlaybackOrder(session.id,session.playbackOrder,session.playbackOrder.reversed()) { result=it;order.countDown() }
        assertTrue(order.await(15,TimeUnit.SECONDS));assertTrue(result.toString(),result.success)
        // STOP while paused creates no third file. Re-encoding must retain 1080p and chosen order.
        assertEquals(2,model.snapshots.value.session!!.segments.size)
        finish("rear-fhd30-eis-daylight-arranged-trimmed")
        choose(VideoSettings())
    }
    @Test fun c_frontCapabilitiesFixedFocusAndThirtyTarget() {
        inst.runOnMainSync { camera.select(CameraMode.SkitVideo,CameraLens.Front,true) }
        await("front") { camera.streaming && !camera.switching && camera.choice.lens==CameraLens.Front && video.cameraId=="1" && model.cameraReady }
        assertFalse(video.autofocusSupported)
        assertFalse(60 in video.fpsOptions)
        File(context.filesDir,"validation-m9/front-capabilities.json").writeText(video.diagnostic().toString(2))
        for(quality in video.qualities.toList()) {
            choose(VideoSettings(quality));record();finish("front-$quality-auto")
        }
        choose(VideoSettings("FHD",30))
        inst.runOnMainSync { video.toggleExposureLock() }
        await("AE lock result") { video.exposureLock=="Exposure locked" }
        inst.runOnMainSync { video.toggleExposureLock() }
        record(false);finish("front-fhd30")
        inst.runOnMainSync { camera.select(CameraMode.SkitVideo,CameraLens.Rear,true) }
        await("rear") { model.cameraReady && camera.choice.lens==CameraLens.Rear }
    }
    @Test fun d_preferencesAppliedBeforeCommitAndRecreation() {
        choose(VideoSettings("HD",30))
        assertEquals(VideoSettings("HD",30),VideoJson.settings(JSONObject(context.getSharedPreferences("video_preferences",0).getString("camera_${video.cameraId}",null)!!)))
        scenario.recreate();scenario.onActivity { activity=it }
        await("preference restoration") { model.cameraReady && video.settings==VideoSettings("HD",30) }
        inst.runOnMainSync { video.toggleExposureLock() }
        await("AE locked") { video.exposureLock=="Exposure locked" }
        scenario.moveToState(androidx.lifecycle.Lifecycle.State.CREATED)
        scenario.moveToState(androidx.lifecycle.Lifecycle.State.RESUMED)
        await("auto after background") { model.cameraReady && video.exposureLock=="Auto exposure" }
        choose(VideoSettings())
    }
    @Test fun e_rearFhdSixtySegmentedAndExported() {
        choose(VideoSettings("FHD",60))
        assertFalse(video.settings.stabilization)
        record();record()
        assertTrue(model.snapshots.value.session!!.segments.all { it.media!!.averageFps!! in 55.0..65.0 })
        finish("rear-fhd60-two-segments")
        choose(VideoSettings())
    }

    @Test fun f_longerLandscapeSixtyAndFocusFeedback() {
        shell("wm user-rotation lock 1")
        scenario.onActivity { it.requestedOrientation=ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED }
        scenario.recreate()
        await("landscape binding") {
            scenario.onActivity { activity=it }
            @Suppress("DEPRECATION") val rotation=activity.windowManager.defaultDisplay.rotation
            model.cameraReady && rotation==android.view.Surface.ROTATION_90 && activity.resources.configuration.orientation==android.content.res.Configuration.ORIENTATION_LANDSCAPE
        }
        choose(VideoSettings("FHD",60))
        inst.runOnMainSync { camera.toggleFocusHold() }
        await("focus result") { video.focusHold || camera.message?.contains("focus",true)==true }
        File(context.filesDir,"validation-m9/focus-result.json").writeText(video.diagnostic().put("feedback",camera.message).toString(2))
        record(pause=false,pose=270,durationMs=15000)
        finish("rear-fhd60-landscape-15s")
        choose(VideoSettings())
        scenario.onActivity { it.requestedOrientation=ActivityInfo.SCREEN_ORIENTATION_PORTRAIT }
    }

    /** Diagnostic only: never changes a stored session/profile or claims a supported UI option. */
    @Test fun z_diagnosticRearFhdSixty() {
        val report=JSONObject().put("requestedFps",60).put("durationMs",4000)
        val directory=File(context.filesDir,"validation-m9").apply { mkdirs() }
        val file=File(directory,"probe-fhd60-${System.currentTimeMillis()}.mp4")
        val finalised=CountDownLatch(1)
        var failure: String?=null
        var recording: androidx.camera.video.Recording?=null
        try {
            inst.runOnMainSync {
                val provider=androidx.camera.lifecycle.ProcessCameraProvider.getInstance(context).get()
                val view=androidx.camera.view.PreviewView(activity)
                activity.setContentView(view)
                provider.unbindAll()
                val preview=androidx.camera.core.Preview.Builder().build().also { it.surfaceProvider=view.surfaceProvider }
                val capture=androidx.camera.video.VideoCapture.Builder(androidx.camera.video.Recorder.Builder()
                    .setQualitySelector(androidx.camera.video.QualitySelector.from(androidx.camera.video.Quality.FHD)).build())
                    .setTargetFrameRate(android.util.Range(60,60)).setDynamicRange(androidx.camera.core.DynamicRange.SDR).build()
                provider.bindToLifecycle(activity,androidx.camera.core.CameraSelector.DEFAULT_BACK_CAMERA,preview,capture)
                recording=capture.output.prepareRecording(context,androidx.camera.video.FileOutputOptions.Builder(file).build())
                    .withAudioEnabled().start(androidx.core.content.ContextCompat.getMainExecutor(context)) { event ->
                        if(event is androidx.camera.video.VideoRecordEvent.Finalize) {
                            if(event.hasError()) failure="CameraX ${event.error}: ${event.cause}"
                            finalised.countDown()
                        }
                    }
            }
            Thread.sleep(4000)
            inst.runOnMainSync { recording?.stop() }
            check(finalised.await(20,TimeUnit.SECONDS)) { "Diagnostic did not finalise" }
            if(failure==null) report.put("observed",VideoJson.media(VideoInspector.inspect(file.path))) else report.put("rejected",failure)
        } catch(e: Exception) { report.put("rejected",e.toString()) }
        finally { inst.runOnMainSync { recording?.close() };File(directory,"probe-fhd60.json").writeText(report.toString(2)) }
    }

}
