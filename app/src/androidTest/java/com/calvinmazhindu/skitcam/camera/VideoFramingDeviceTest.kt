package com.calvinmazhindu.skitcam.camera

import android.Manifest
import android.content.pm.ActivityInfo
import android.graphics.Bitmap
import android.view.View
import android.view.ViewGroup
import androidx.camera.view.PreviewView
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.calvinmazhindu.skitcam.MainActivity
import com.calvinmazhindu.skitcam.export.*
import com.calvinmazhindu.skitcam.recording.*
import org.json.JSONObject
import org.junit.*
import org.junit.Assert.*
import org.junit.runner.RunWith
import java.io.File

/** Real media in a hidden, separate app. Never touches production sessions/preferences. */
@RunWith(AndroidJUnit4::class)
class VideoFramingDeviceTest {
    private val inst=InstrumentationRegistry.getInstrumentation()
    private val context get()=inst.targetContext
    private lateinit var scenario: ActivityScenario<MainActivity>
    private lateinit var activity: MainActivity
    private val camera get()=activity.cameraModel
    private val model get()=activity.recordingModel
    private var variant=""
    private val baseline get()=InstrumentationRegistry.getArguments().getString("framingBaseline")=="true"
    private val folder get()=File(context.filesDir,"validation-framing/${InstrumentationRegistry.getArguments().getString("framingRun") ?: if(baseline) "before" else "after"}").apply { mkdirs() }
    private var rotation: String?=null
    private fun shell(command: String)=inst.uiAutomation.executeShellCommand(command).use {
        android.os.ParcelFileDescriptor.AutoCloseInputStream(it).bufferedReader().readText().trim()
    }
    private fun await(label: String,seconds: Int=45,condition: ()->Boolean) {
        val end=System.nanoTime()+seconds*1_000_000_000L
        while(!condition() && System.nanoTime()<end) {
            Thread.sleep(100)
            scenario.onActivity { activity=it }
        }
        assertTrue("$label: ${camera.message} ${camera.video.message} ${model.snapshots.value}",condition())
    }
    @Before fun open() {
        check(context.packageName.endsWith(".framingvalidation"))
        for(p in listOf(Manifest.permission.CAMERA,Manifest.permission.RECORD_AUDIO,Manifest.permission.POST_NOTIFICATIONS)) shell("pm grant ${context.packageName} $p")
        rotation=shell("wm user-rotation")
        scenario=ActivityScenario.launch(MainActivity::class.java)
        scenario.onActivity { activity=it }
        await("streaming") { camera.streaming && model.cameraReady }
    }
    @After fun close() {
        if(::scenario.isInitialized) scenario.close()
        rotation?.let { shell("wm user-rotation $it") }
    }
    private fun preview(view: View=activity.window.decorView): PreviewView? {
        if(view is PreviewView) return view
        if(view is ViewGroup) for(i in 0 until view.childCount) preview(view.getChildAt(i))?.let { return it }
        return null
    }
    private fun choose(mode: CameraMode,lens: CameraLens) {
        inst.runOnMainSync { camera.select(mode,lens,true) }
        await("$mode $lens") { camera.choice==CameraChoice(mode,lens) && camera.streaming && !camera.switching && (mode==CameraMode.Photo || model.cameraReady) }
        Thread.sleep(700)
    }
    private fun snapshot(name: String): JSONObject {
        Thread.sleep(700)
        lateinit var report: JSONObject
        inst.runOnMainSync {
            val view=checkNotNull(preview())
            report=camera.framing.snapshot(view).put("videoSettings",camera.video.diagnostic())
            view.bitmap?.let { bitmap -> File(folder,"$name-preview.png").outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG,100,it) };bitmap.recycle() }
        }
        inst.uiAutomation.takeScreenshot()?.let { bitmap -> File(folder,"$name-screen.png").outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG,100,it) };bitmap.recycle() }
        File(folder,"$name.json").writeText(report.toString(2))
        return report
    }
    private fun exercise(lens: CameraLens,landscape: Boolean) {
        val name="${lens.name.lowercase()}-${if(landscape) "landscape" else "portrait"}$variant"
        shell("wm user-rotation lock ${if(landscape) 1 else 0}")
        scenario.onActivity { it.requestedOrientation=if(landscape) ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE else ActivityInfo.SCREEN_ORIENTATION_PORTRAIT }
        await("orientation") { activity.resources.configuration.orientation==if(landscape) 2 else 1 }
        await("orientation camera ready") { camera.streaming && !camera.switching && model.cameraReady }
        choose(CameraMode.Photo,lens)
        snapshot("$name-photo")
        choose(CameraMode.SkitVideo,lens)
        val video=snapshot("$name-video")
        if(!baseline) {
            assertEquals("FIT_CENTER",video.getString("scaleType"))
            assertEquals(1.0,video.getDouble("zoomRatio"),.001)
            val info=video.getJSONObject("capture")
            val rotated=info.getInt("rotationDegrees")%180!=0
            val expected=if(rotated) info.getDouble("height")/info.getDouble("width") else info.getDouble("width")/info.getDouble("height")
            assertEquals("view must fit the recording aspect",expected,video.getDouble("viewWidth")/video.getDouble("viewHeight"),.005)
            val previewInfo=video.getJSONObject("preview")
            assertEquals("preview stream must match the capture aspect",info.getDouble("width")/info.getDouble("height"),
                previewInfo.getDouble("width")/previewInfo.getDouble("height"),.015)
        } else {
            inst.runOnMainSync { preview()!!.scaleType=PreviewView.ScaleType.FIT_CENTER }
            snapshot("$name-video-fit-probe")
            inst.runOnMainSync { preview()!!.scaleType=PreviewView.ScaleType.FILL_CENTER }
        }
        inst.runOnMainSync { model.start(if(landscape) 270 else 0) }
        await("recording") { model.snapshots.value.state==RecordingState.Recording }
        await("five seconds") { model.snapshots.value.activeDurationMs>=5000 }
        snapshot("$name-recording")
        if(!baseline) {
            model.pause();await("paused") { model.snapshots.value.state==RecordingState.Paused }
            assertTrue(model.cameraReady)
            model.resume(if(landscape) 270 else 0)
            await("resumed") { model.snapshots.value.state==RecordingState.Recording }
            await("second segment") { model.snapshots.value.activeDurationMs>=1700 }
        }
        model.stop();await("completed") { model.snapshots.value.state==RecordingState.Completed }
        val session=checkNotNull(model.snapshots.value.session)
        File(folder,"$name-session.json").writeText(SessionCodec.encode(session))
        await("export",180) { ExportJournal.status.value?.sessionId==session.id && ExportJournal.status.value?.active==false }
        val status=ExportJournal.status.value!!
        File(folder,"$name-export.json").writeText(checkNotNull(ExportJournal.read(context,session.id)).toString(2))
        assertEquals(status.toString(),ExportState.Completed,status.state)
        context.contentResolver.openInputStream(android.net.Uri.parse(status.uri!!))!!.use { input -> File(folder,"$name-finished.mp4").outputStream().use { input.copyTo(it) } }
        File(session.segments.first().path).copyTo(File(folder,"$name-source.mp4"),overwrite=false)
        await("live camera after export") { model.cameraReady && camera.streaming }
        snapshot("$name-returned")
    }
    @Test fun videoTouchFocusAndPinchUseVisibleFrame() {
        org.junit.Assume.assumeFalse(baseline)
        choose(CameraMode.SkitVideo,CameraLens.Rear)
        val position=IntArray(2);var width=0;var height=0
        inst.runOnMainSync { val view=preview()!!;view.getLocationOnScreen(position);width=view.width;height=view.height }
        val cx=position[0]+width/2f;val cy=position[1]+height/2f
        val down=android.os.SystemClock.uptimeMillis()
        fun single(action: Int,x: Float,y: Float) {
            val event=android.view.MotionEvent.obtain(down,android.os.SystemClock.uptimeMillis(),action,x,y,0)
            event.source=android.view.InputDevice.SOURCE_TOUCHSCREEN
            assertTrue(inst.uiAutomation.injectInputEvent(event,true));event.recycle()
        }
        if(position[0]>10) {
            single(android.view.MotionEvent.ACTION_DOWN,position[0]/2f,cy)
            single(android.view.MotionEvent.ACTION_UP,position[0]/2f,cy)
            Thread.sleep(150)
            assertNull("Letterbox taps must not focus",camera.focus)
        }
        single(android.view.MotionEvent.ACTION_DOWN,cx,cy)
        single(android.view.MotionEvent.ACTION_UP,cx,cy)
        await("video focus feedback") { camera.focus!=null || camera.message?.contains("focus",true)==true }
        camera.focus?.let { assertEquals(width/2f,it.x,3f);assertEquals(height/2f,it.y,3f) }
        fun multi(action: Int,spread: Float,count: Int) {
            val props=Array(count) { n -> android.view.MotionEvent.PointerProperties().apply { id=n;toolType=android.view.MotionEvent.TOOL_TYPE_FINGER } }
            val coords=Array(count) { n -> android.view.MotionEvent.PointerCoords().apply { x=cx+if(n==0) -spread else spread;y=cy;pressure=1f;size=1f } }
            val event=android.view.MotionEvent.obtain(down,android.os.SystemClock.uptimeMillis(),action,count,props,coords,0,0,1f,1f,0,0,android.view.InputDevice.SOURCE_TOUCHSCREEN,0)
            assertTrue(inst.uiAutomation.injectInputEvent(event,true));event.recycle()
        }
        multi(android.view.MotionEvent.ACTION_DOWN,40f,1)
        multi(android.view.MotionEvent.ACTION_POINTER_DOWN or (1 shl android.view.MotionEvent.ACTION_POINTER_INDEX_SHIFT),40f,2)
        for(step in 1..10) { multi(android.view.MotionEvent.ACTION_MOVE,40f+step*10f,2);Thread.sleep(25) }
        multi(android.view.MotionEvent.ACTION_POINTER_UP or (1 shl android.view.MotionEvent.ACTION_POINTER_INDEX_SHIFT),140f,2)
        multi(android.view.MotionEvent.ACTION_UP,140f,1)
        await("video pinch changed actual zoom") { camera.observedZoom>1.1f }
        inst.runOnMainSync { camera.adjustZoom(1f) }
        await("zoom restored") { kotlin.math.abs(camera.observedZoom-1f)<.01f }
    }
    @Test fun zoomResetAndLifecycleContinuity() {
        org.junit.Assume.assumeFalse(baseline)
        choose(CameraMode.SkitVideo,CameraLens.Rear)
        inst.runOnMainSync { camera.adjustZoom(2f) }
        await("requested zoom observed") { kotlin.math.abs(camera.observedZoom-2f)<.01f }
        scenario.recreate();scenario.onActivity { activity=it }
        await("user zoom survives same-choice recreation") { model.cameraReady && camera.zoomReady && kotlin.math.abs(camera.observedZoom-2f)<.01f }
        choose(CameraMode.Photo,CameraLens.Rear)
        assertEquals(1f,camera.observedZoom,.01f)
        inst.runOnMainSync { camera.adjustZoom(2f) }
        await("photo zoom") { camera.observedZoom>1.9f }
        choose(CameraMode.SkitVideo,CameraLens.Rear)
        assertEquals(1f,camera.observedZoom,.01f)
        inst.runOnMainSync { camera.adjustZoom(2f) }
        await("video zoom") { camera.observedZoom>1.9f }
        choose(CameraMode.SkitVideo,CameraLens.Front)
        assertEquals(1f,camera.observedZoom,.01f)
        inst.runOnMainSync {
            camera.adjustZoom(2f);camera.adjustZoom(1.5f);camera.adjustZoom(1f)
        }
        await("superseded zoom requests cannot restore old UI") { kotlin.math.abs(camera.observedZoom-1f)<.01f && kotlin.math.abs(camera.zoom-1f)<.01f }
        assertTrue(camera.zoomReady)
    }
    @Test fun stabilizationOnFraming() = exerciseStabilization(CameraLens.Rear)
    @Test fun frontStabilizationOnFraming() = exerciseStabilization(CameraLens.Front)
    private fun exerciseStabilization(lens: CameraLens) {
        org.junit.Assume.assumeFalse(baseline)
        choose(CameraMode.SkitVideo,lens)
        org.junit.Assume.assumeTrue(camera.video.stabilizationSupported)
        val original=camera.video.settings
        inst.runOnMainSync { camera.video.choose(original.copy(stabilization=true)) }
        await("EIS configuration ready") { model.cameraReady && !camera.video.pending && camera.video.settings.stabilization }
        variant="-eis-on"
        exercise(lens,false)
        inst.runOnMainSync { camera.video.choose(original) }
        await("EIS original setting restored") { model.cameraReady && !camera.video.pending && camera.video.settings==original }
    }
    /** Explicitly close only a named, isolated test checkpoint; never a production session. */
    @Test fun finishOwnedPausedCheckpoint() {
        val id=InstrumentationRegistry.getArguments().getString("ownedSession")
        org.junit.Assume.assumeNotNull(id)
        assertEquals(id,model.snapshots.value.session!!.id)
        assertEquals(RecordingState.Paused,model.snapshots.value.state)
        model.stop()
        await("owned checkpoint completed") { model.snapshots.value.state==RecordingState.Completed }
        await("owned checkpoint published",180) { ExportJournal.status.value?.let { it.sessionId==id && it.state==ExportState.Completed }==true && model.cameraReady }
    }
    @Test fun rearPortrait()=exercise(CameraLens.Rear,false)
    @Test fun frontPortrait()=exercise(CameraLens.Front,false)
    @Test fun rearLandscape()=exercise(CameraLens.Rear,true)
    @Test fun frontLandscape()=exercise(CameraLens.Front,true)
}
