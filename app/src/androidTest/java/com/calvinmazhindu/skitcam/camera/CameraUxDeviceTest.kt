package com.calvinmazhindu.skitcam.camera

import android.Manifest
import android.content.pm.ActivityInfo
import android.graphics.Bitmap
import android.view.accessibility.AccessibilityNodeInfo
import androidx.lifecycle.Lifecycle
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.calvinmazhindu.skitcam.MainActivity
import com.calvinmazhindu.skitcam.export.*
import com.calvinmazhindu.skitcam.recording.*
import org.junit.*
import org.junit.Assert.*
import org.junit.runner.RunWith
import org.junit.runners.MethodSorters
import java.io.File

/** Real CameraX/export on the isolated app. Pose arguments are injected, not physical gesture claims. */
@RunWith(AndroidJUnit4::class)
@FixMethodOrder(MethodSorters.NAME_ASCENDING)
class CameraUxDeviceTest {
    private val inst=InstrumentationRegistry.getInstrumentation()
    private val context get()=inst.targetContext
    private lateinit var scenario: ActivityScenario<MainActivity>
    private lateinit var activity: MainActivity
    private val camera get()=activity.cameraModel
    private val model get()=activity.recordingModel
    private var rotation=""
    private fun shell(command: String)=inst.uiAutomation.executeShellCommand(command).use {
        android.os.ParcelFileDescriptor.AutoCloseInputStream(it).bufferedReader().readText().trim()
    }
    private fun await(label: String, timeout: Long=90_000, condition: ()->Boolean) {
        val end=System.nanoTime()+timeout*1_000_000
        while(!condition() && System.nanoTime()<end) Thread.sleep(100)
        assertTrue("$label: ${ExportJournal.status.value}, ${model.snapshots.value.state}, ${camera.message}",condition())
    }
    private fun find(label: String, prefix: Boolean=false): AccessibilityNodeInfo? {
        if(android.os.Build.VERSION.SDK_INT>=33) inst.uiAutomation.clearCache()
        fun walk(node: AccessibilityNodeInfo): AccessibilityNodeInfo? {
            if(node.isVisibleToUser && (if(prefix) node.contentDescription?.toString()?.startsWith(label)==true else (node.text?.toString()==label || node.contentDescription?.toString()==label))) return node
            for(i in 0 until node.childCount) node.getChild(i)?.let { walk(it)?.let { found->return found } }
            return null
        }
        return inst.uiAutomation.rootInActiveWindow?.let(::walk)
    }
    private fun click(label: String, prefix: Boolean=false) {
        var node: AccessibilityNodeInfo?=null
        await("$label visible") { node=find(label,prefix);node!=null }
        while(node?.isClickable!=true && node?.parent!=null) node=node?.parent
        assertTrue("$label clickable",node!!.performAction(AccessibilityNodeInfo.ACTION_CLICK))
    }
    @Before fun open() {
        check((context.packageName.endsWith(".m10validation") || (context.packageName.endsWith(".framingvalidation") || context.packageName.endsWith(".m11validation")))) { "Use the isolated M10 app" }
        for(p in listOf(Manifest.permission.CAMERA,Manifest.permission.RECORD_AUDIO,Manifest.permission.POST_NOTIFICATIONS)) shell("pm grant ${context.packageName} $p")
        rotation=shell("wm user-rotation");shell("wm user-rotation lock 0")
        scenario=ActivityScenario.launch(MainActivity::class.java)
        scenario.onActivity { activity=it;it.requestedOrientation=ActivityInfo.SCREEN_ORIENTATION_PORTRAIT }
        await("recovered") { camera.ready && model.recovered.value }
        check(!model.snapshots.value.openSession && !ExportJournal.busy) { "Do not interrupt an open session" }
        ExportTestSupport.beforeCamera()
        await("streaming") { camera.streaming }
        if(camera.choice!=CameraChoice()) {
            inst.runOnMainSync { camera.select(CameraMode.SkitVideo,CameraLens.Rear,true) }
        }
        await("video ready") { camera.streaming && model.cameraReady && !camera.switching }
    }
    @After fun close() {
        if(::scenario.isInitialized) scenario.close()
        shell("wm user-rotation $rotation")
    }
    private fun record() {
        await("recording ready") { model.cameraReady }
        model.start(0)
        await("actual source recording") { model.snapshots.value.state==RecordingState.Recording && model.snapshots.value.activeDurationMs>=1800 }
    }
    private fun finished(): Session {
        val id=model.snapshots.value.session!!.id
        await("published",180_000) { model.snapshots.value.state==RecordingState.Completed &&
            ExportJournal.status.value?.sessionId==id && ExportJournal.status.value?.state==ExportState.Completed && !ExportJournal.busy }
        val session=model.snapshots.value.session!!
        await("returned actual camera") { model.cameraReady && camera.streaming && camera.receipt?.handled==true && camera.latestUri==ExportJournal.status.value?.uri }
        assertEquals(CameraMode.SkitVideo,camera.choice.mode)
        assertNull("No permanent success page",find("Video finished"))
        assertNull("No manual New Recording required",find("New Recording"))
        await("Start control visible") { find("Start recording")!=null }
        return session
    }
    @Test fun a_stopPausedReturnsLiveCameraAndNextStartGetsDistinctSession() {
        record();model.pause();await("paused") { model.snapshots.value.state==RecordingState.Paused }
        val paused=model.snapshots.value.session!!
        model.stop();model.stop()
        val first=finished()
        assertEquals(paused.segments,first.segments)
        val firstUri=camera.latestUri
        val json=File(context.filesDir,"sessions/${first.id}/session.json")
        val original=json.readBytes();val hashes=first.segments.map { sha(File(it.path)) }
        record()
        assertNotEquals(first.id,model.snapshots.value.session!!.id)
        assertEquals(0,model.snapshots.value.session!!.segments.size)
        model.stop();finished()
        assertNotEquals(firstUri,camera.latestUri)
        assertArrayEquals(original,json.readBytes())
        assertEquals(hashes,first.segments.map { sha(File(it.path)) })
        shot("returned-video-portrait")
    }
    @Test fun b_duplicateSuccessAndActivityRecreationPreservePhotoPreference() {
        val result=ExportJournal.status.value ?: error("Run after a real export")
        val job=ExportJournal.read(context,result.sessionId)!!
        val before=job.toString()
        val receipt=camera.receipt!!
        inst.runOnMainSync { camera.select(CameraMode.Photo,CameraLens.Rear,true) }
        await("photo ready") { camera.streaming && !camera.switching && camera.choice.mode==CameraMode.Photo }
        repeat(3) { inst.runOnMainSync { ExportJournal.status.value=result.copy(message="Repeated delivery $it") } }
        scenario.recreate();scenario.onActivity { activity=it }
        await("photo after recreation") { camera.streaming && camera.choice.mode==CameraMode.Photo }
        assertEquals(receipt,camera.receipt)
        assertEquals(CameraMode.Photo.name,context.getSharedPreferences("camera_preferences",0).getString("mode",null))
        assertEquals(before,ExportJournal.read(context,result.sessionId)!!.toString())
        assertNull(find("Video finished"));shot("photo-portrait")
    }
    @Test fun c_pausedSettingsKeepCameraBoundAndProfileLocked() {
        record();model.pause();await("paused") { model.snapshots.value.state==RecordingState.Paused }
        val profile=model.snapshots.value.session!!.captureProfile
        click("Settings");await("settings panel shown") { find("Settings")!=null && find("Close")!=null };assertFalse(camera.video.canEdit())
        assertTrue("Settings does not unbind preview",camera.streaming && model.cameraReady)
        click("Close")
        assertEquals(profile,model.snapshots.value.session!!.captureProfile)
        model.resume(0);await("resumed audio capture") { model.snapshots.value.state==RecordingState.Recording && model.snapshots.value.activeDurationMs>=1600 }
        model.stop();finished()
    }
    @Test fun d_compactQuickAndLandscapeTargets() {
        click("Quick settings");await("quick panel") { find("Close")!=null };shot("quick-portrait");click("Close")
        shell("wm user-rotation lock 1")
        scenario.onActivity { it.requestedOrientation=ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE }
        await("landscape") { scenario.onActivity { activity=it };activity.resources.configuration.orientation==android.content.res.Configuration.ORIENTATION_LANDSCAPE && camera.streaming }
        for(label in listOf("Settings","Quick settings","Open saved media gallery","Switch to front camera","Start recording")) {
            var node: AccessibilityNodeInfo?=null
            await("$label target") { node=find(label);node!=null }
            val r=android.graphics.Rect();node!!.getBoundsInScreen(r)
            val minimum=48*activity.resources.displayMetrics.density-2
            assertTrue("$label target clipped $r",r.width()>=minimum && r.height()>=minimum)
        }
        shot("video-landscape");click("Quick settings");await("quick panel") { find("Close")!=null };shot("quick-landscape");click("Close")
        click("Settings");await("settings panel") { find("Close")!=null };shot("settings-landscape");click("Close")
    }
    @Test fun e_failurePresentationPreservesExplicitRecoveryAndJournal() {
        val original=ExportJournal.status.value ?: error("Need a completed test recording")
        val journal=File(context.filesDir,"sessions/${original.sessionId}/export/job.json")
        val before=journal.readBytes()
        await("previous notification dismissed") { find("Video saved")==null }
        try {
            // UI-only failure injection; no journal/codec/MediaStore state is changed.
            inst.runOnMainSync { camera.acknowledge("ui-failure-check");ExportJournal.status.value=original.copy(state=ExportState.Failed,message="Injected presentation check") }
            await("failure presentation") { find("Could not finish video")!=null }
            assertNotNull(find("Retry"));assertNotNull(find("New Recording"));assertNull(find("Video saved"))
            shot("failure-recovery")
        } finally { inst.runOnMainSync { camera.acknowledge(original.sessionId);ExportJournal.status.value=original } }
        await("camera after presentation check") { model.cameraReady && camera.streaming }
        assertArrayEquals(before,journal.readBytes())
    }
    @Test fun f_microphoneSummaryAndRawDiagnosticsKeepPreviewReady() {
        val profile=model.port.profile
        await("microphone discovery") { model.audio.state.value.inputs.isNotEmpty() }
        click("Audio input details. ",prefix=true)
        await("microphone summary") { find("Microphone")!=null }
        assertNotNull(find("Phone microphone"));shot("microphone-summary")
        fun scrollUntil(label: String) {
            fun scrollable(n: AccessibilityNodeInfo): AccessibilityNodeInfo? {
                if(n.isScrollable && n.isVisibleToUser) return n
                for(i in 0 until n.childCount) n.getChild(i)?.let { scrollable(it)?.let { found->return found } }
                return null
            }
            repeat(8) {
                if(find(label)!=null) return
                inst.uiAutomation.rootInActiveWindow?.let(::scrollable)?.performAction(AccessibilityNodeInfo.ACTION_SCROLL_FORWARD)
                Thread.sleep(150)
            }
            assertNotNull(label,find(label))
        }
        scrollUntil("Diagnostics");click("Diagnostics");scrollUntil("Raw endpoints");shot("microphone-diagnostics")
        click("Close")
        assertEquals(profile,model.port.profile)
        assertTrue(camera.streaming && model.cameraReady)
    }
    @Test fun g_portraitOverlayTargetsAndDockGapDoNotFocusCamera() {
        await("profile editable") { camera.video.canEdit() }
        inst.runOnMainSync { camera.video.choose(com.calvinmazhindu.skitcam.video.VideoSettings()) }
        await("default profile") { model.cameraReady && !camera.video.pending && camera.video.settings==com.calvinmazhindu.skitcam.video.VideoSettings() }
        fun target(label: String): android.graphics.Rect {
            var node: AccessibilityNodeInfo?=null
            await("$label target") { node=find(label);node!=null }
            while(node?.isClickable!=true && node?.parent!=null) node=node?.parent
            return android.graphics.Rect().apply {
                node!!.getBoundsInScreen(this)
                val minimum=48*activity.resources.displayMetrics.density-2
                assertTrue("$label touch target clipped $this",width()>=minimum && height()>=minimum)
                val safe=androidx.core.view.ViewCompat.getRootWindowInsets(activity.window.decorView)!!
                    .getInsets(androidx.core.view.WindowInsetsCompat.Type.systemBars() or androidx.core.view.WindowInsetsCompat.Type.displayCutout())
                assertTrue("$label outside safe area $this",left>=safe.left && top>=safe.top &&
                    right<=activity.window.decorView.width-safe.right && bottom<=activity.window.decorView.height-safe.bottom)
            }
        }
        for(label in listOf("Settings","Quick settings","Open saved media gallery","Switch to front camera","Start recording")) target(label)
        // This point is inside the overlaid dock but outside its buttons. Inject actual
        // screen input, not an accessibility action, to check sibling hit testing.
        val shutter=target("Start recording")
        val x=4*activity.resources.displayMetrics.density
        val y=shutter.top+8*activity.resources.displayMetrics.density
        fun preview(view: android.view.View): androidx.camera.view.PreviewView? {
            if(view is androidx.camera.view.PreviewView) return view
            if(view is android.view.ViewGroup) for(i in 0 until view.childCount) preview(view.getChildAt(i))?.let { return it }
            return null
        }
        inst.runOnMainSync {
            val view=checkNotNull(preview(activity.window.decorView));val xy=IntArray(2);view.getLocationOnScreen(xy)
            assertTrue("Dock-gap probe must actually overlap the live image",x>=xy[0] && x<xy[0]+view.width && y>=xy[1] && y<xy[1]+view.height)
        }
        val down=android.os.SystemClock.uptimeMillis()
        for(action in listOf(android.view.MotionEvent.ACTION_DOWN,android.view.MotionEvent.ACTION_UP)) {
            val event=android.view.MotionEvent.obtain(down,android.os.SystemClock.uptimeMillis(),action,x,y,0)
            event.source=android.view.InputDevice.SOURCE_TOUCHSCREEN
            assertTrue(inst.uiAutomation.injectInputEvent(event,true));event.recycle()
        }
        Thread.sleep(300)
        assertNull("Dock gap must not meter the underlying image",camera.focus)
        assertEquals(1f,camera.observedZoom,.01f)
        shot("immersive-idle-portrait")
        record();model.pause();await("paused") { model.snapshots.value.state==RecordingState.Paused }
        val resume=target("Resume recording");val stop=target("Stop recording")
        val arrange=target("Arrange")
        assertFalse(android.graphics.Rect.intersects(resume,stop))
        assertFalse(android.graphics.Rect.intersects(stop,arrange))
        var thumbnail: AccessibilityNodeInfo?=null
        await("thumbnail") { thumbnail=find("Review segment 1,",prefix=true);thumbnail!=null }
        val thumb=android.graphics.Rect();thumbnail!!.getBoundsInScreen(thumb)
        assertTrue("Thumbnail must stay above dock",thumb.bottom<=resume.top)
        shot("immersive-paused-portrait")
        click("Review segment 1,",prefix=true)
        await("review visible") { find("Trim")!=null && find("Discard")!=null && find("Close")!=null && find("Keep")==null }
        await("review player ready: ${activity.reviewController.error}") {
            var ready=false
            inst.runOnMainSync { ready=activity.reviewController.player?.playbackState==androidx.media3.common.Player.STATE_READY }
            ready
        }
        for(label in listOf("Trim","Discard","Close")) {
            val r=target(label)
            assertFalse("$label collides with RESUME",android.graphics.Rect.intersects(r,resume))
        }
        shot("immersive-review-portrait")
        click("Close")
        click("Resume recording")
        await("resumed") { model.snapshots.value.state==RecordingState.Recording && model.snapshots.value.activeDurationMs>=1500 }
        target("Pause recording");target("Stop recording")
        click("Stop recording");finished()
        shot("immersive-returned-portrait")
    }
    @Test fun z_backgroundSaveCheckpoint() {
        // Next invocation force-stops only this validation package and runs recoveryCheckpoint.
        record();model.stop()
        await("finalised") { model.snapshots.value.state==RecordingState.Completed }
        scenario.moveToState(Lifecycle.State.CREATED)
        await("background publication",180_000) { ExportJournal.status.value?.state==ExportState.Completed && !ExportJournal.busy }
        val session=model.snapshots.value.session!!
        val job=ExportJournal.read(context,session.id)!!
        val pending=SaveReceipt(SavedVideo(session.id,job.getString("id"),job.getString("uri"),job.getLong("createdAt")))
        // Exercise an unseen durable publication, with the user's ordinary launch mode still Photo.
        assertTrue(context.getSharedPreferences("camera_preferences",0).edit().putString("mode","Photo")
            .putString("video_return_v1",pending.encode()).remove("acknowledged_export").commit())
        File(context.filesDir,"m10-recovery-checkpoint").writeText(session.id)
    }
    @Test fun recoveryCheckpoint() {
        val id=File(context.filesDir,"m10-recovery-checkpoint").takeIf { it.isFile }?.readText()
        Assume.assumeNotNull("Run z_backgroundSaveCheckpoint in a separate invocation first",id)
        assertEquals(id,model.snapshots.value.session?.id)
        await("unseen save returned once") { camera.receipt?.handled==true && model.cameraReady }
        assertEquals("Photo",context.getSharedPreferences("camera_preferences",0).getString("mode",null))
        assertEquals(CameraMode.SkitVideo,camera.choice.mode)
        await("receipt committed") { context.getSharedPreferences("camera_preferences",0).getString("video_return_v1",null)?.let { SaveReceipt.decode(it).handled }==true }
        lateinit var fresh: CameraModel
        inst.runOnMainSync { fresh=CameraModel(context.applicationContext as android.app.Application) }
        await("return receipt durable") { fresh.loaded && fresh.receipt?.handled==true }
        inst.runOnMainSync { fresh.initialize(model.snapshots.value,false) }
        assertEquals(CameraMode.Photo,fresh.choice.mode)
    }
    private fun shot(name: String) {
        check(inst.uiAutomation.rootInActiveWindow?.packageName?.toString()==context.packageName) { "Validation app must be foreground for screenshot" }
        inst.waitForIdleSync();Thread.sleep(150)
        val bitmap=inst.uiAutomation.takeScreenshot() ?: error("No screenshot")
        val dir=File(context.filesDir,"validation-m10").apply { mkdirs() }
        File(dir,"$name.png").outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG,100,it) };bitmap.recycle()
    }
}
