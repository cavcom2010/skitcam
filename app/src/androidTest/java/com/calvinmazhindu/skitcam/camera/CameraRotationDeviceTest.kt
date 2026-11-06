package com.calvinmazhindu.skitcam.camera

import android.Manifest
import android.content.pm.ActivityInfo
import android.graphics.Bitmap
import android.provider.Settings
import android.view.View
import android.view.ViewGroup
import android.view.accessibility.AccessibilityNodeInfo
import androidx.camera.view.PreviewView
import androidx.activity.compose.setContent
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.SideEffect
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Density
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.ui.graphics.Color
import androidx.compose.material3.darkColorScheme
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.lifecycle.Lifecycle
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.calvinmazhindu.skitcam.MainActivity
import com.calvinmazhindu.skitcam.CameraScreen
import com.calvinmazhindu.skitcam.export.*
import com.calvinmazhindu.skitcam.recording.*
import org.json.JSONObject
import org.junit.*
import org.junit.Assert.*
import org.junit.runner.RunWith
import java.io.File
import java.util.UUID

/** No wm/settings rotation writes and no pose injection. Guided cases require a real person. */
@RunWith(AndroidJUnit4::class)
class CameraRotationDeviceTest {
    private val inst=InstrumentationRegistry.getInstrumentation()
    private val context get()=inst.targetContext
    private val args get()=InstrumentationRegistry.getArguments()
    private lateinit var scenario: ActivityScenario<MainActivity>
    private lateinit var activity: MainActivity
    private val model get()=activity.recordingModel
    private val camera get()=activity.cameraModel
    private val folder by lazy { File(context.filesDir,"validation-rotation/${args.getString("rotationRun") ?: UUID.randomUUID()}").apply { mkdirs() } }
    private var owned: String?=null
    private var leavePaused=false
    private var baselineAuto=0
    private fun shell(command: String)=inst.uiAutomation.executeShellCommand(command).use {
        android.os.ParcelFileDescriptor.AutoCloseInputStream(it).bufferedReader().readText().trim()
    }
    private fun <T> main(block: ()->T): T {
        val value=java.util.concurrent.atomic.AtomicReference<T>()
        inst.runOnMainSync { value.set(block()) };return value.get()
    }
    private fun await(label: String,seconds: Int=60,condition: ()->Boolean) {
        val deadline=System.nanoTime()+seconds*1_000_000_000L
        while(!condition() && System.nanoTime()<deadline) {
            Thread.sleep(100);scenario.onActivity { activity=it }
            if(!folder.name.startsWith("baseline")) {
                val live=main { activity.orientation.diagnostic().put("waiting",label).put("state",model.snapshots.value.state.name) }
                File(context.filesDir,"rotation-live.json").writeText(live.toString())
            }
        }
        assertTrue("$label: ${camera.message}; ${model.snapshots.value}",condition())
    }
    @Before fun open() {
        check(context.packageName.endsWith(".m11validation"))
        baselineAuto=Settings.System.getInt(context.contentResolver,Settings.System.ACCELEROMETER_ROTATION,0)
        for(p in listOf(Manifest.permission.CAMERA,Manifest.permission.RECORD_AUDIO,Manifest.permission.POST_NOTIFICATIONS)) shell("pm grant ${context.packageName} $p")
        scenario=ActivityScenario.launch(MainActivity::class.java)
        scenario.onActivity { activity=it }
        await("recovery and live camera") { model.recovered.value && camera.ready && camera.streaming }
    }
    @After fun close() {
        try {
            if(owned!=null && !leavePaused && model.snapshots.value.session?.id==owned) {
                if(model.snapshots.value.state==RecordingState.Recording) model.pause()
                await("owned source settled") { model.snapshots.value.state !in setOf(RecordingState.Starting,RecordingState.Recording,RecordingState.Finalising,RecordingState.Stopping) }
                if(model.snapshots.value.state==RecordingState.Paused) { model.stop();published() }
            }
            assertEquals("Test must not change global Auto-rotate",baselineAuto,
                Settings.System.getInt(context.contentResolver,Settings.System.ACCELEROMETER_ROTATION,0))
        } finally { if(::scenario.isInitialized) scenario.close() }
    }
    private fun preview(view: View=activity.window.decorView): PreviewView? {
        if(view is PreviewView) return view
        if(view is ViewGroup) for(i in 0 until view.childCount) preview(view.getChildAt(i))?.let { return it }
        return null
    }
    private fun node(label: String): AccessibilityNodeInfo? {
        inst.uiAutomation.clearCache()
        fun walk(n: AccessibilityNodeInfo): AccessibilityNodeInfo? {
            if(n.isVisibleToUser && (n.contentDescription?.toString()==label || n.text?.toString()==label)) return n
            for(i in 0 until n.childCount) n.getChild(i)?.let { walk(it)?.let { found -> return found } }
            return null
        }
        return inst.uiAutomation.rootInActiveWindow?.let(::walk)
    }
    private fun click(label: String) {
        var button: AccessibilityNodeInfo?=null
        await("$label enabled") {
            button=node(label)
            while(button?.isClickable!=true && button?.parent!=null) button=button?.parent
            button?.isEnabled==true && button?.isClickable==true
        }
        assertTrue(button!!.performAction(AccessibilityNodeInfo.ACTION_CLICK))
    }
    private fun enabled(label: String): Boolean? {
        var button=node(label) ?: return null
        while(!button.isClickable && button.parent!=null) button=button.parent
        return button.isEnabled
    }
    private fun phase(name: String,pose: Int?=null) {
        val report=JSONObject().put("phase",name).put("expectedPose",pose).put("run",folder.name)
        File(context.filesDir,"rotation-progress.json").writeText(report.toString())
        android.util.Log.i("SkitCamRotationTest",report.toString())
        val instruction=when(name) {
            "hold-portrait", "return-portrait" -> "Hold phone PORTRAIT (top edge UP)"
            "turn-landscape", "return-original-landscape" -> "Hold LANDSCAPE: top edge ${if(pose==270) "LEFT" else "RIGHT"}"
            "turn-any-landscape" -> "Turn phone LANDSCAPE now (top edge LEFT or RIGHT)"
            "turn-other-landscape" -> "Turn phone to the OTHER landscape direction"
            "turn-portrait-during-recording" -> "Turn phone PORTRAIT now; the recording UI should STAY landscape"
            "starting-recording-hold-landscape" -> "Recording test: keep holding LANDSCAPE"
            "complete" -> "Rotation test COMPLETE"
            else -> if(name.startsWith("photo-hold-")) "PHOTO ${camera.choice.lens}: hold ${when(pose) { 270 -> "LANDSCAPE top LEFT";90 -> "LANDSCAPE top RIGHT";else -> "PORTRAIT top UP" }}" else name
        }
        main { android.widget.Toast.makeText(activity,"$instruction · ${camera.choice.lens}",android.widget.Toast.LENGTH_LONG).show() }
    }
    private fun waitPose(pose: Int,name: String) {
        phase(name,pose)
        await("physical pose $pose",600) { activity.orientation.pose==pose &&
            CameraOrientationPolicy.matchesDisplay(pose,activity.orientation.displayRotation) && camera.streaming &&
            camera.previewDisplayRotation==activity.orientation.displayRotation }
        Thread.sleep(800)
    }
    private fun waitLandscape(name: String,exclude: Int?=null): Int {
        phase(name)
        await("physical landscape",600) {
            val p=activity.orientation.pose
            p!=null && p in setOf(90,270) && p!=exclude &&
                CameraOrientationPolicy.matchesDisplay(p,activity.orientation.displayRotation) && camera.streaming &&
                camera.previewDisplayRotation==activity.orientation.displayRotation
        }
        Thread.sleep(800)
        return activity.orientation.pose!!
    }
    private fun choose(mode: CameraMode,lens: CameraLens) {
        main { camera.select(mode,lens,true) }
        await("$mode $lens") { camera.choice==CameraChoice(mode,lens) && camera.streaming && !camera.switching &&
            (mode==CameraMode.Photo || model.cameraReady) }
        Thread.sleep(400)
    }
    private fun shot(name: String,baseline: Boolean=false): JSONObject {
        Thread.sleep(500)
        val report=main {
            val view=checkNotNull(preview())
            camera.framing.snapshot(view).apply {
                if(!baseline) put("orientation",activity.orientation.diagnostic())
                else put("requestedOrientation",activity.requestedOrientation)
                put("configurationOrientation",activity.resources.configuration.orientation)
                @Suppress("DEPRECATION") put("displayRotation",activity.windowManager.defaultDisplay.rotation)
                put("autoRotate",baselineAuto).put("state",model.snapshots.value.state.name)
                put("activeDurationMs",model.snapshots.value.activeDurationMs)
                put("fontScale",activity.resources.configuration.fontScale)
                put("videoSettings",camera.video.diagnostic()).put("exposure",camera.exposure)
                put("captureIdentity",System.identityHashCode(model.port.capture))
                put("activityIdentity",System.identityHashCode(activity))
                put("windowWidth",activity.window.decorView.width).put("windowHeight",activity.window.decorView.height)
                val inset=ViewCompat.getRootWindowInsets(view)!!.getInsets(WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout())
                put("safeInsets",JSONObject().put("left",inset.left).put("top",inset.top).put("right",inset.right).put("bottom",inset.bottom))
                view.bitmap?.let { bitmap -> File(folder,"$name-preview.png").outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG,100,it) };bitmap.recycle() }
            }
        }
        inst.uiAutomation.takeScreenshot()?.let { bitmap -> File(folder,"$name-screen.png").outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG,100,it) };bitmap.recycle() }
        File(folder,"$name.json").writeText(report.toString(2))
        return report
    }
    private fun geometry(report: JSONObject) {
        assertEquals("FIT_CENTER",report.getString("scaleType"))
        val cap=report.getJSONObject("capture")
        val expected=if(camera.choice.mode==CameraMode.Photo) {
            if(report.getInt("configurationOrientation")==1) 3f/4 else 4f/3
        } else ViewfinderGeometry.aspect(cap.getInt("width"),cap.getInt("height"),cap.getInt("rotationDegrees"))!!
        assertEquals(expected.toDouble(),report.getDouble("viewWidth")/report.getDouble("viewHeight"),.005)
        val o=report.getJSONObject("orientation")
        assertEquals(o.getInt("displayRotation"),report.getInt("displayRotation"))
        if(report.getInt("configurationOrientation")==1 && camera.choice.mode==CameraMode.SkitVideo)
            assertEquals(report.getInt("windowWidth")-report.getJSONObject("safeInsets").getInt("left")-report.getJSONObject("safeInsets").getInt("right"),report.getInt("viewWidth"))
    }
    private fun published() {
        val id=model.snapshots.value.session!!.id
        await("published and camera returned",180) { model.snapshots.value.state==RecordingState.Completed && !ExportJournal.busy &&
            ExportJournal.status.value?.let { it.sessionId==id && it.state==ExportState.Completed }==true &&
            camera.streaming && model.cameraReady && camera.receipt?.let { it.video.sessionId==id && it.handled }==true }
        assertEquals(ActivityInfo.SCREEN_ORIENTATION_FULL_SENSOR,main { activity.requestedOrientation })
    }
    @Test fun baselineCurrentPose() {
        check(!model.snapshots.value.openSession)
        choose(CameraMode.SkitVideo,CameraLens.Rear)
        var pose: Int?=null
        val tracker=main { OrientationTracker(activity) { pose=it }.apply { enable() } }
        try {
            await("baseline physical sensor") { pose!=null }
            shot("baseline",true).put("filteredPose",pose).also {
                File(folder,"baseline.json").writeText(it.toString(2))
            }
        } finally { main { tracker.stop() } }
    }
    @Test fun currentPoseLifecycleAndControls() {
        check(!model.snapshots.value.openSession)
        choose(CameraMode.SkitVideo,CameraLens.Rear)
        await("stable real pose") { activity.orientation.pose!=null && CameraOrientationPolicy.matchesDisplay(activity.orientation.pose,activity.orientation.displayRotation) }
        assertEquals(ActivityInfo.SCREEN_ORIENTATION_FULL_SENSOR,main { activity.requestedOrientation })
        val identity=main { System.identityHashCode(model.port.capture) }
        main { camera.adjustZoom(1.5f);if(camera.exposureRange.last>=1) camera.adjustExposure(1) }
        await("intentional zoom") { kotlin.math.abs(camera.observedZoom-1.5f)<.01f }
        val before=shot("before-recreate")
        scenario.recreate();scenario.onActivity { activity=it }
        await("camera after explicit recreation") { camera.streaming && model.cameraReady && kotlin.math.abs(camera.observedZoom-1.5f)<.01f }
        assertNotEquals(identity,main { System.identityHashCode(model.port.capture) })
        assertEquals(ActivityInfo.SCREEN_ORIENTATION_FULL_SENSOR,main { activity.requestedOrientation })
        main { camera.adjustZoom(1f) }
        await("default zoom") { kotlin.math.abs(camera.observedZoom-1f)<.01f }
        geometry(shot("after-recreate"))
        assertNotNull(before)
        for(lens in listOf(CameraLens.Rear,CameraLens.Front)) {
            choose(CameraMode.Photo,lens);assertEquals(ActivityInfo.SCREEN_ORIENTATION_FULL_SENSOR,main { activity.requestedOrientation })
            geometry(shot("photo-${lens.name}"))
        }
        choose(CameraMode.SkitVideo,CameraLens.Rear)
    }
    @Test fun guidedVideo() {
        check(args.getString("physical")=="true") { "Requires guided physical movements" }
        assertEquals(args.getString("autoRotate")!!.toInt(),baselineAuto)
        check(!model.snapshots.value.openSession)
        val lens=CameraLens.valueOf(args.getString("lens") ?: "Rear")
        val landscapePose=if(lens==CameraLens.Rear) 270 else 90
        val otherPose=if(landscapePose==270) 90 else 270
        choose(CameraMode.SkitVideo,lens)
        waitPose(0,"hold-portrait")
        val activityId=System.identityHashCode(activity)
        val capture=model.port.capture
        main { camera.adjustZoom(1.5f);if(camera.exposureRange.last>=1) camera.adjustExposure(1) }
        await("zoom set") { kotlin.math.abs(camera.observedZoom-1.5f)<.01f }
        if(camera.exposureRange.last>=1) await("EV set") { camera.exposure==1 }
        if(lens==CameraLens.Rear) {
            main { camera.toggleFocusHold() }
            await("AF hold result") { camera.video.focusHold || camera.message?.contains("focus",true)==true }
        }
        val focusHeld=camera.video.focusHold
        val ev=camera.exposure
        shot("portrait-controls")
        waitPose(landscapePose,"turn-landscape")
        assertEquals(activityId,System.identityHashCode(activity))
        assertSame("Rotation must retain the binding",capture,model.port.capture)
        assertEquals(1.5f,camera.observedZoom,.01f);assertEquals(ev,camera.exposure)
        if(focusHeld) assertTrue("Rotation must not reset AF hold",camera.video.focusHold)
        shot("landscape-controls")
        main { camera.adjustZoom(1f);camera.adjustExposure(0) }
        await("1x framing") { kotlin.math.abs(camera.observedZoom-1f)<.01f }
        geometry(shot("landscape-idle"))
        phase("starting-recording-hold-landscape",landscapePose)
        click("Start recording")
        await("actual recording") { model.snapshots.value.state==RecordingState.Recording && model.snapshots.value.activeDurationMs>=5000 }
        owned=model.snapshots.value.session!!.id
        val session=model.snapshots.value.session!!
        assertEquals(landscapePose,session.orientationDegrees)
        assertNotEquals(ActivityInfo.SCREEN_ORIENTATION_FULL_SENSOR,main { activity.requestedOrientation })
        geometry(shot("landscape-recording"))
        phase("turn-portrait-during-recording",0)
        await("physical mismatch while recording",600) { activity.orientation.pose==0 }
        assertEquals(2,activity.resources.configuration.orientation)
        assertEquals(RecordingState.Recording,model.snapshots.value.state)
        click("Pause recording");await("paused") { model.snapshots.value.state==RecordingState.Paused }
        shot("paused-mismatch-before-assert")
        fun describe(n: AccessibilityNodeInfo): JSONObject = JSONObject().put("text",n.text).put("description",n.contentDescription)
            .put("enabled",n.isEnabled).put("visible",n.isVisibleToUser).put("clickable",n.isClickable)
            .put("children",org.json.JSONArray((0 until n.childCount).mapNotNull { n.getChild(it)?.let(::describe) }))
        inst.uiAutomation.rootInActiveWindow?.let { File(folder,"paused-ui.json").writeText(describe(it).toString(2)) }
        val metadata=File(context.filesDir,"sessions/${session.id}/session.json").readBytes()
        await("disabled mismatch resume") { enabled("Resume recording")==false }
        model.resume(0);Thread.sleep(400)
        assertEquals(RecordingState.Paused,model.snapshots.value.state)
        assertArrayEquals(metadata,File(context.filesDir,"sessions/${session.id}/session.json").readBytes())
        shot("paused-mismatch")
        scenario.recreate();scenario.onActivity { activity=it }
        await("paused recreation keeps original orientation") { model.cameraReady && activity.resources.configuration.orientation==2 }
        assertArrayEquals(metadata,File(context.filesDir,"sessions/${session.id}/session.json").readBytes())
        assertEquals(session.captureProfile,model.port.profile)
        assertNotEquals(ActivityInfo.SCREEN_ORIENTATION_FULL_SENSOR,main { activity.requestedOrientation })
        shot("paused-recreated")
        // Display remains locked. Wait for the physical pose, not a new display orientation.
        phase("return-original-landscape",landscapePose)
        await("original physical pose",600) { activity.orientation.pose==landscapePose }
        click("Resume recording")
        await("resumed source") { model.snapshots.value.state==RecordingState.Recording && model.snapshots.value.activeDurationMs>=2000 }
        shot("landscape-resumed")
        click("Stop recording");published()
        val completed=model.snapshots.value.session!!
        assertEquals(2,completed.segments.size)
        assertTrue(completed.segments.all { it.orientationDegrees==landscapePose && it.media!!.rotation%180==0 })
        File(folder,"session.json").writeText(SessionCodec.encode(completed))
        File(completed.segments.first().path).copyTo(File(folder,"source.mp4"))
        context.contentResolver.openInputStream(android.net.Uri.parse(ExportJournal.status.value!!.uri!!))!!.use { input ->
            File(folder,"finished.mp4").outputStream().use { input.copyTo(it) }
        }
        shot("returned-landscape")
        waitPose(otherPose,"turn-other-landscape")
        geometry(shot("other-landscape"))
        waitPose(0,"return-portrait")
        geometry(shot("returned-portrait"))
        phase("complete")
    }
    @Test fun guidedPhoto() {
        check(args.getString("physical")=="true")
        assertEquals(args.getString("autoRotate")!!.toInt(),baselineAuto)
        check(!model.snapshots.value.openSession)
        val lenses=if(args.getString("both")=="true") listOf(CameraLens.Rear,CameraLens.Front)
            else listOf(CameraLens.valueOf(args.getString("lens") ?: "Rear"))
        for(lens in lenses) {
            choose(CameraMode.Photo,lens)
            val identity=System.identityHashCode(activity)
            val capture=activity.photoModel.capture
            for(pose in listOf(0,270,90,0)) {
                waitPose(pose,"photo-hold-$pose")
                assertEquals(identity,System.identityHashCode(activity));assertSame(capture,activity.photoModel.capture)
                assertEquals(ActivityInfo.SCREEN_ORIENTATION_FULL_SENSOR,main { activity.requestedOrientation })
                val name="photo-${lens.name}-$pose-${System.currentTimeMillis()}"
                geometry(shot(name))
                if(pose!=0) {
                    val before=activity.photoModel.saveCount
                    click("Take photo")
                    await("photo saved") { activity.photoModel.saveCount>before && !activity.photoModel.busy }
                    val uri=activity.photoModel.lastUri!!
                    context.contentResolver.openInputStream(uri)!!.use { input -> File(folder,"$name.jpg").outputStream().use { input.copyTo(it) } }
                }
            }
        }
        phase("complete")
    }
    @Test fun savePhysicalPausedCheckpoint() {
        check(!model.snapshots.value.openSession)
        choose(CameraMode.SkitVideo,CameraLens.Rear)
        await("real stable pose") { activity.orientation.pose!=null && CameraOrientationPolicy.matchesDisplay(activity.orientation.pose,activity.orientation.displayRotation) }
        click("Start recording")
        await("checkpoint samples") { model.snapshots.value.state==RecordingState.Recording && model.snapshots.value.activeDurationMs>=2500 }
        owned=model.snapshots.value.session!!.id
        click("Pause recording");await("checkpoint paused") { model.snapshots.value.state==RecordingState.Paused }
        val session=model.snapshots.value.session!!
        val raw=File(context.filesDir,"sessions/${session.id}/session.json").readText()
        File(context.filesDir,"rotation-checkpoint.json").writeText(JSONObject().put("metadata",raw).toString())
        shot("checkpoint-paused")
        leavePaused=true
    }
    @Test fun recoverPhysicalPausedCheckpoint() {
        val expected=SessionCodec.decode(JSONObject(File(context.filesDir,"rotation-checkpoint.json").readText()).getString("metadata"))
        owned=expected.id
        assertEquals(expected,model.snapshots.value.session)
        await("exact original capture binding") { model.cameraReady && model.port.profile==expected.captureProfile }
        assertNotEquals(ActivityInfo.SCREEN_ORIENTATION_FULL_SENSOR,main { activity.requestedOrientation })
        val raw=File(context.filesDir,"sessions/${expected.id}/session.json").readText()
        assertEquals(JSONObject(File(context.filesDir,"rotation-checkpoint.json").readText()).getString("metadata"),raw)
        shot("checkpoint-recovered")
        await("physical original pose") { activity.orientation.pose==expected.orientationDegrees }
        click("Resume recording")
        await("recovered samples") { model.snapshots.value.state==RecordingState.Recording && model.snapshots.value.activeDurationMs>=2000 }
        click("Stop recording");published()
        assertEquals(2,model.snapshots.value.session!!.segments.size)
        assertEquals(expected.captureProfile,model.snapshots.value.session!!.captureProfile)
        shot("checkpoint-returned")
    }
    @Test fun guidedLargeTextInsets() {
        check(args.getString("physical")=="true")
        check(!model.snapshots.value.openSession)
        var renderedFontScale=0f
        main {
            activity.setContent {
                MaterialTheme(colorScheme=darkColorScheme()) {
                    CompositionLocalProvider(LocalDensity provides Density(LocalDensity.current.density,2f)) {
                        val density=LocalDensity.current
                        SideEffect { renderedFontScale=density.fontScale }
                        Surface(color=Color(0xFF101014),contentColor=Color.White) { activity.CameraScreen() }
                    }
                }
            }
        }
        try {
            // Exercise the real camera composable at 2x text. Android settings remain untouched.
            await("large-text camera") { camera.streaming }
            choose(CameraMode.SkitVideo,CameraLens.Rear)
            for(pose in listOf(0,270,90,0)) {
                waitPose(pose,if(pose==0) "hold-portrait" else "turn-other-landscape")
                val name="large-text-$pose-${System.currentTimeMillis()}"
                assertEquals(2f,renderedFontScale,0f)
                val report=shot(name).put("composeFontScaleOverride",renderedFontScale)
                File(folder,"$name.json").writeText(report.toString(2))
                geometry(report)
                val inset=report.getJSONObject("safeInsets")
                for(label in listOf("Settings","Quick settings","Start recording","Open saved media gallery","Switch to front camera")) {
                    var n: AccessibilityNodeInfo?=null
                    await("large text $label") { n=node(label);n!=null }
                    while(n?.isClickable!=true && n?.parent!=null) n=n?.parent
                    val rect=android.graphics.Rect();n!!.getBoundsInScreen(rect)
                    val minimum=48*activity.resources.displayMetrics.density-2
                    assertTrue("$label target $rect",rect.width()>=minimum && rect.height()>=minimum)
                    assertTrue("$label outside safe area $rect",rect.left>=inset.getInt("left") && rect.top>=inset.getInt("top") &&
                        rect.right<=report.getInt("windowWidth")-inset.getInt("right") && rect.bottom<=report.getInt("windowHeight")-inset.getInt("bottom"))
                }
            }
            phase("complete")
        } finally {
            scenario.close()
        }
    }
    @Test fun verifyRecoveredPhysicalEvidence() {
        val run=args.getString("evidenceRun") ?: error("Specify the completed physical run")
        val evidence=File(context.filesDir,"validation-rotation/$run")
        for(name in listOf("landscape-idle","landscape-recording","landscape-resumed","other-landscape","returned-portrait")) {
            val report=JSONObject(File(evidence,"$name.json").readText())
            assertEquals(1.0,report.getDouble("zoomRatio"),.01)
            assertEquals("FIT_CENTER",report.getString("scaleType"))
        }
        val session=SessionCodec.decode(File(evidence,"session.json").readText())
        assertEquals(RecordingState.Completed,session.state)
        assertEquals(2,session.segments.size)
        for(segment in session.segments) assertEquals(0,segment.media!!.rotation%180)
        assertTrue(videoFileIsLandscape(File(evidence,"finished.mp4")))
        File(folder,"recovered-evidence.json").writeText(JSONObject().put("verifiedRun",run).put("sessionId",session.id).toString(2))
    }
    private fun videoFileIsLandscape(file: File): Boolean =
        com.calvinmazhindu.skitcam.video.VideoInspector.inspect(file.path).let { it.valid() && it.rotation%180==0 }
    @Test fun prepareOwnedLandscapeRecoveryCopy() {
        check(!model.snapshots.value.openSession)
        val original=SessionCodec.decode(File(context.filesDir,"validation-rotation/physical-off-front/session.json").readText())
        val id=UUID.randomUUID().toString()
        val directory=File(context.filesDir,"sessions/$id").apply { check(mkdirs()) }
        val segments=original.segments.map { segment ->
            val source=File(segment.path);val digest=sha(source)
            val copy=File(directory,source.name);source.copyTo(copy)
            assertEquals(digest,sha(copy));assertEquals(digest,sha(source))
            segment.copy(path=copy.path)
        }
        val fixture=original.copy(id=id,createdAtMs=System.currentTimeMillis(),state=RecordingState.Paused,segments=segments)
        SessionFileStore(File(context.filesDir,"sessions")).save(fixture)
        File(context.filesDir,"rotation-landscape-recovery.json").writeText(SessionCodec.encode(fixture))
    }
    @Test fun recoverOwnedLandscapeCopyFromPortrait() {
        val expected=SessionCodec.decode(File(context.filesDir,"rotation-landscape-recovery.json").readText())
        owned=expected.id
        await("cold restored landscape binding") { model.cameraReady && activity.resources.configuration.orientation==2 &&
            camera.previewDisplayRotation==CameraRecordingPort.rotationFor(expected.orientationDegrees) }
        assertEquals(expected,model.snapshots.value.session)
        assertEquals(expected.captureProfile,model.port.profile)
        val facts=shot("cold-landscape-recovery");geometry(facts)
        assertEquals(facts.getJSONObject("preview").getInt("rotationDegrees"),facts.getJSONObject("capture").getInt("rotationDegrees"))
        val raw=File(context.filesDir,"sessions/${expected.id}/session.json").readBytes()
        val hashes=expected.segments.map { sha(File(it.path)) }
        model.resume(0);Thread.sleep(300)
        assertEquals(RecordingState.Paused,model.snapshots.value.state)
        assertArrayEquals(raw,File(context.filesDir,"sessions/${expected.id}/session.json").readBytes())
        model.stop();published()
        await("completed recovery restores automatic portrait") { activity.resources.configuration.orientation==1 && activity.orientation.displayRotation==0 }
        geometry(shot("cold-landscape-returned-portrait"))
        assertEquals(hashes,expected.segments.map { sha(File(it.path)) })
    }
    @Test fun guidedExpandedLandscapeFraming() {
        check(args.getString("physical")=="true")
        check(!model.snapshots.value.openSession)
        for(lens in listOf(CameraLens.Rear,CameraLens.Front)) {
            choose(CameraMode.SkitVideo,lens)
            waitPose(0,"hold-portrait")
            val pose=waitLandscape("turn-any-landscape")
            val prefix="expanded-${lens.name}"
            val idle=shot("$prefix-idle");geometry(idle)
            assertTrue("Use nearly all safe height",idle.getInt("viewHeight")>idle.getInt("windowHeight")*.85)
            assertEquals(1f,camera.observedZoom,.01f)
            click("Start recording")
            await("expanded viewfinder recording") { model.snapshots.value.state==RecordingState.Recording && model.snapshots.value.activeDurationMs>=5000 }
            owned=model.snapshots.value.session!!.id
            geometry(shot("$prefix-recording"))
            click("Pause recording");await("expanded paused") { model.snapshots.value.state==RecordingState.Paused }
            geometry(shot("$prefix-paused"))
            click("Resume recording")
            await("expanded resumed") { model.snapshots.value.state==RecordingState.Recording && model.snapshots.value.activeDurationMs>=2000 }
            click("Stop recording");published()
            val session=model.snapshots.value.session!!
            assertEquals(2,session.segments.size)
            File(folder,"$prefix-session.json").writeText(SessionCodec.encode(session))
            File(session.segments.first().path).copyTo(File(folder,"$prefix-source.mp4"))
            context.contentResolver.openInputStream(android.net.Uri.parse(ExportJournal.status.value!!.uri!!))!!.use { input ->
                File(folder,"$prefix-finished.mp4").outputStream().use { input.copyTo(it) }
            }
            waitLandscape("turn-other-landscape",exclude=pose)
            val reverse=shot("$prefix-other-landscape");geometry(reverse)
            assertTrue(reverse.getInt("viewHeight")>reverse.getInt("windowHeight")*.85)
            waitPose(0,"return-portrait");geometry(shot("$prefix-portrait"))
        }
        phase("complete")
    }
}
