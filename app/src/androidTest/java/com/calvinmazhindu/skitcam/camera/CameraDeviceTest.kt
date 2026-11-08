package com.calvinmazhindu.skitcam.camera

import android.Manifest
import android.content.pm.ActivityInfo
import android.graphics.BitmapFactory
import androidx.exifinterface.media.ExifInterface
import android.provider.MediaStore
import android.view.View
import android.view.ViewGroup
import androidx.camera.view.PreviewView
import androidx.lifecycle.Lifecycle
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.calvinmazhindu.skitcam.MainActivity
import com.calvinmazhindu.skitcam.export.*
import com.calvinmazhindu.skitcam.photo.PhotoStore
import com.calvinmazhindu.skitcam.recording.*
import org.junit.*
import org.junit.Assert.*
import org.junit.runner.RunWith
import org.junit.runners.MethodSorters
import java.io.File

/** Must run in the dedicated .m8validation application; never changes the user's sessions. */
@RunWith(AndroidJUnit4::class)
@FixMethodOrder(MethodSorters.NAME_ASCENDING)
class CameraDeviceTest {
    private val inst = InstrumentationRegistry.getInstrumentation()
    private val context get() = inst.targetContext
    private lateinit var scenario: ActivityScenario<MainActivity>
    private lateinit var activity: MainActivity
    private lateinit var camera: CameraModel
    private var rotation: String? = null
    private fun shell(command: String) = inst.uiAutomation.executeShellCommand(command).use {
        android.os.ParcelFileDescriptor.AutoCloseInputStream(it).bufferedReader().readText().trim()
    }
    private fun await(label: String, block: () -> Boolean) {
        val deadline=System.nanoTime()+45_000_000_000L
        while(!block() && System.nanoTime()<deadline) Thread.sleep(100)
        if(!block()) {
            layout("failure-${label.replace(' ','-')}")
            val root=inst.uiAutomation.rootInActiveWindow
            fun describe(node: android.view.accessibility.AccessibilityNodeInfo,depth: Int): String {
                val bounds=android.graphics.Rect();node.getBoundsInScreen(bounds)
                return " ".repeat(depth)+"${node.packageName} ${node.text} ${node.contentDescription} visible=${node.isVisibleToUser} $bounds\n"+
                    (0 until node.childCount).mapNotNull { node.getChild(it) }.joinToString("") { describe(it,depth+1) }
            }
            File(context.filesDir,"validation-m8/failure-tree.txt").writeText(root?.let { describe(it,0) } ?: "no active window")
        }
        assertTrue("$label: ${camera.message}; ${activity.photoModel.message}; ${activity.recordingModel.snapshots.value}",block())
    }
    @Before fun open() {
        check(context.packageName.endsWith(".m8validation") || (context.packageName.endsWith(".m9validation") || (context.packageName.endsWith(".m10validation") || (context.packageName.endsWith(".framingvalidation") || context.packageName.endsWith(".m12cvalidation") || context.packageName.endsWith(".m11validation"))))) { "Use the isolated Milestone 8 validation application." }
        for(p in listOf(Manifest.permission.CAMERA,Manifest.permission.RECORD_AUDIO,Manifest.permission.POST_NOTIFICATIONS)) shell("pm grant ${context.packageName} $p")
        rotation=shell("wm user-rotation"); shell("wm user-rotation lock 0")
        scenario=ActivityScenario.launch(MainActivity::class.java)
        scenario.onActivity { activity=it; camera=it.cameraModel; it.requestedOrientation=ActivityInfo.SCREEN_ORIENTATION_PORTRAIT }
        await("recovery") { camera.ready && activity.recordingModel.recovered.value && activity.photoModel.recovered }
        if((context.packageName.endsWith(".m9validation") || (context.packageName.endsWith(".m10validation") || (context.packageName.endsWith(".framingvalidation") || context.packageName.endsWith(".m12cvalidation") || context.packageName.endsWith(".m11validation")))) && activity.recordingModel.snapshots.value.state==RecordingState.Error && !activity.recordingModel.snapshots.value.unresolved) {
            activity.recordingModel.stop()
            await("close settled validation failure") { activity.recordingModel.snapshots.value.state==RecordingState.Completed }
        }
        inst.runOnMainSync { ExportJournal.status.value?.takeIf { !it.active }?.let { camera.acknowledge(it.sessionId) } }
        await("streaming") { camera.streaming }
    }
    @After fun close() {
        if(::scenario.isInitialized) {
            if(InstrumentationRegistry.getArguments().getString("leavePhoto") != "true" && !activity.recordingModel.snapshots.value.openSession && !activity.photoModel.busy && camera.streaming) {
                inst.runOnMainSync { camera.select(CameraMode.SkitVideo,CameraLens.Rear,true) }
                await("video restored") { camera.streaming && !camera.switching && camera.choice == CameraChoice() }
            }
            scenario.close()
        }
        rotation?.let { shell("wm user-rotation $it") }
    }
    private fun choose(mode: CameraMode, lens: CameraLens = CameraLens.Rear) {
        check(CameraPolicy.maySwitch(true,activity.recordingModel.snapshots.value,false,activity.photoModel.busy))
        inst.runOnMainSync { camera.select(mode,lens,true) }
        await("$mode $lens binding") { camera.streaming && !camera.switching && camera.choice==CameraChoice(mode,lens) && (mode!=CameraMode.SkitVideo || activity.recordingModel.cameraReady) }
    }
    private fun capture(portrait: Boolean): android.net.Uri {
        val previous=activity.photoModel.lastUri
        inst.runOnMainSync {
            @Suppress("DEPRECATION") val rotation=activity.windowManager.defaultDisplay.rotation
            activity.photoModel.take(0,rotation)
            activity.photoModel.take(0,rotation) // A repeated shutter cannot create another job.
        }
        await("saved photo") { !activity.photoModel.busy && activity.photoModel.lastUri!=null && activity.photoModel.lastUri!=previous }
        val uri=activity.photoModel.lastUri!!
        val options=BitmapFactory.Options().apply { inJustDecodeBounds=true }
        context.contentResolver.openInputStream(uri)!!.use { BitmapFactory.decodeStream(it,null,options) }
        assertTrue(options.outWidth>0 && options.outHeight>0)
        val orientation=context.contentResolver.openFileDescriptor(uri,"r")!!.use {
            ExifInterface(it.fileDescriptor).getAttributeInt(ExifInterface.TAG_ORIENTATION,1)
        }
        assertTrue("Saved output must be unmirrored",orientation in listOf(1,3,6,8))
        val swapped=orientation in listOf(6,8)
        val displayWidth=if(swapped) options.outHeight else options.outWidth
        val displayHeight=if(swapped) options.outWidth else options.outHeight
        assertEquals("Display orientation",portrait,displayHeight>displayWidth)
        context.contentResolver.query(uri,arrayOf(MediaStore.Images.Media.IS_PENDING,MediaStore.Images.Media.RELATIVE_PATH),null,null,null)!!.use {
            assertTrue(it.moveToFirst()); assertEquals(0,it.getInt(0)); assertEquals("Pictures/SkitCam/",it.getString(1))
        }
        val dir=File(context.filesDir,"validation-m8").apply { mkdirs() }
        context.contentResolver.openInputStream(uri)!!.use { input -> File(dir,"${if(portrait) "portrait" else "landscape"}-${camera.choice.lens}-${System.currentTimeMillis()}.jpg").outputStream().use { input.copyTo(it) } }
        android.util.Log.i("SkitCamM8","PHOTO $uri ${options.outWidth}x${options.outHeight} EXIF=$orientation display=${displayWidth}x$displayHeight lens=${camera.choice.lens}")
        return uri
    }
    private fun layout(name: String) {
        val bitmap=inst.uiAutomation.takeScreenshot() ?: return
        val directory=File(context.filesDir,"validation-m8").apply { mkdirs() }
        File(directory,"ui-$name.png").outputStream().use { bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG,100,it) }
        bitmap.recycle()
    }
    @Test fun a_defaultAndRearPhoto() {
        assertEquals(CameraMode.valueOf(context.getSharedPreferences("camera_preferences",0).getString("mode",CameraMode.SkitVideo.name)!!),camera.choice.mode)
        choose(CameraMode.Photo); capture(true);layout("photo-portrait")
    }
    @Test fun b_frontPhotoAndPreferenceRestore() {
        choose(CameraMode.Photo,CameraLens.Front); capture(true)
        camera.grid(true); camera.timer(3)
        scenario.recreate(); scenario.onActivity { activity=it; camera=it.cameraModel }
        await("photo after recreation") { camera.streaming }
        assertEquals(CameraChoice(CameraMode.Photo,CameraLens.Front),camera.choice)
        assertTrue(camera.grid); assertEquals(3,camera.timer)
        await("recent photo preference committed") { context.getSharedPreferences("camera_preferences",0).getString("media_uri",null)==activity.photoModel.lastUri.toString() }
        lateinit var fresh: CameraModel
        inst.runOnMainSync { fresh = CameraModel(context.applicationContext as android.app.Application) }
        await("disk preferences loaded") { fresh.loaded }
        inst.runOnMainSync { fresh.initialize(activity.recordingModel.snapshots.value,false) }
        assertEquals(CameraChoice(CameraMode.Photo,CameraLens.Front),fresh.choice)
        assertTrue(fresh.grid); assertEquals(3,fresh.timer)
        assertEquals(activity.photoModel.lastUri.toString(),fresh.latestUri);assertEquals("image/jpeg",fresh.latestType)
        camera.timer(0); camera.grid(false)
    }
    @Test fun c_landscapePhoto() {
        choose(CameraMode.Photo)
        scenario.onActivity { it.requestedOrientation=ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE }
        Thread.sleep(1500); scenario.onActivity { activity=it; camera=it.cameraModel }
        await("landscape streaming") { camera.streaming && activity.resources.configuration.orientation==android.content.res.Configuration.ORIENTATION_LANDSCAPE }
        capture(false)
        fun switchNode(node: android.view.accessibility.AccessibilityNodeInfo): android.view.accessibility.AccessibilityNodeInfo? {
            if(node.contentDescription?.toString()=="Switch to front camera" && node.isVisibleToUser) return node
            for(i in 0 until node.childCount) node.getChild(i)?.let { child -> switchNode(child)?.let { return it } }
            return null
        }
        var target: android.view.accessibility.AccessibilityNodeInfo?=null
        await("landscape camera switch") { inst.uiAutomation.clearCache();target=inst.uiAutomation.rootInActiveWindow?.let(::switchNode);target!=null }
        val bounds=android.graphics.Rect();target!!.getBoundsInScreen(bounds)
        val minimum=(48*activity.resources.displayMetrics.density).toInt()-2
        assertTrue("Landscape camera-switch target is clipped: $bounds",bounds.width()>=minimum && bounds.height()>=minimum)
        layout("photo-landscape")
    }
    @Test fun d_focusZoomAndTimerCancellation() {
        choose(CameraMode.Photo)
        inst.runOnMainSync { camera.adjustZoom(camera.maxZoom+10f) }
        assertEquals(camera.maxZoom,camera.zoom,0f)
        await("actual CameraX zoom") { kotlin.math.abs(camera.observedZoom-camera.maxZoom)<.01f }
        inst.runOnMainSync { camera.adjustZoom(1f) }
        fun preview(view: View): PreviewView? {
            if(view is PreviewView) return view
            if(view is ViewGroup) for(i in 0 until view.childCount) preview(view.getChildAt(i))?.let { return it }
            return null
        }
        inst.runOnMainSync {
            val view=preview(activity.window.decorView)!!
            camera.focus(view,androidx.compose.ui.geometry.Offset(view.width/2f,view.height/2f))
            activity.photoModel.take(3,0)
        }
        await("countdown") { activity.photoModel.countdown>0 }
        scenario.moveToState(Lifecycle.State.CREATED)
        assertEquals(0,activity.photoModel.countdown)
        scenario.moveToState(Lifecycle.State.RESUMED)
        await("preview after background") { camera.streaming }
        assertFalse(activity.photoModel.busy)
    }
    @Test fun e_photoToVideoTwoSegmentsAndRecovery() {
        choose(CameraMode.Photo); capture(true); capture(true)
        choose(CameraMode.SkitVideo,CameraLens.Front)
        val model=activity.recordingModel
        inst.runOnMainSync { model.start(0) }
        await("recording") { model.snapshots.value.state==RecordingState.Recording }
        assertEquals(CameraLens.Front,model.snapshots.value.session!!.lens)
        assertFalse(CameraPolicy.maySwitch(true,model.snapshots.value,false,false))
        await("samples") { model.snapshots.value.activeDurationMs>=1600 };model.pause()
        await("paused") { model.snapshots.value.state==RecordingState.Paused }
        val saved=model.snapshots.value.session!!
        scenario.recreate();scenario.onActivity { activity=it;camera=it.cameraModel }
        await("paused front recovery") { camera.streaming }
        assertEquals(saved,model.snapshots.value.session)
        assertEquals(CameraLens.Front,camera.choice.lens)
        model.resume(0);await("resuming") { model.snapshots.value.state==RecordingState.Recording }
        await("second samples") { model.snapshots.value.activeDurationMs>=1600 }
        model.stop();await("finished recording") { model.snapshots.value.state==RecordingState.Completed }
        await("export done") { ExportJournal.status.value?.active==false }
        assertEquals(ExportJournal.status.value.toString(),ExportState.Completed,ExportJournal.status.value!!.state)
        model.snapshots.value.session!!.segments.forEach { SessionFileStore(File(context.filesDir,"sessions")).validate(it.path) }
        inst.runOnMainSync { camera.acknowledge(ExportJournal.status.value!!.sessionId) }
        await("camera after export") { camera.streaming }
    }
    @Test fun f_corruptPhotoRejectedWithoutPublishing() {
        val store=PhotoStore(context);val dir=store.create()
        val file=File(dir,"source.jpg").apply { writeText("not a JPEG") }
        val bytes=file.readBytes()
        try { store.validate(file);fail("Invalid JPEG accepted") } catch(_: IllegalStateException) { }
        assertArrayEquals(bytes,file.readBytes())
    }
    @Test fun g_photoJournalRecoveryAndIdempotentPublication() {
        val store=PhotoStore(context)
        val interrupted=store.create()
        val partial=File(interrupted,"source.jpg").apply { writeText("partial capture") }
        store.recover()
        assertEquals("Interrupted",store.read(interrupted).getString("state"))
        assertEquals("partial capture",partial.readText())
        val dir=store.create()
        val bitmap=android.graphics.Bitmap.createBitmap(96,64,android.graphics.Bitmap.Config.ARGB_8888)
        File(dir,"source.jpg").outputStream().use { assertTrue(bitmap.compress(android.graphics.Bitmap.CompressFormat.JPEG,95,it)) }
        bitmap.recycle()
        store.captured(dir)
        assertEquals(dir,store.recover())
        val first=store.publish(dir)
        val second=store.publish(dir)
        assertEquals(first,second)
        assertEquals("Completed",store.read(dir).getString("state"))
    }
    @Test fun h_injectedTouchFocusPinchAndSettings() {
        choose(CameraMode.Photo)
        fun find(view: View): PreviewView? {
            if(view is PreviewView) return view
            if(view is ViewGroup) for(i in 0 until view.childCount) find(view.getChildAt(i))?.let { return it }
            return null
        }
        val position=IntArray(2); var width=0;var height=0
        inst.runOnMainSync { val view=find(activity.window.decorView)!!;view.getLocationOnScreen(position);width=view.width;height=view.height }
        val cx=position[0]+width/2f;val cy=position[1]+height/2f
        val down=android.os.SystemClock.uptimeMillis()
        fun single(action: Int,time: Long) {
            val event=android.view.MotionEvent.obtain(down,time,action,cx,cy,0)
            event.source=android.view.InputDevice.SOURCE_TOUCHSCREEN
            assertTrue(inst.uiAutomation.injectInputEvent(event,true));event.recycle()
        }
        single(android.view.MotionEvent.ACTION_DOWN,down)
        single(android.view.MotionEvent.ACTION_UP,down+40)
        await("tap focus feedback") { camera.focus!=null || camera.message?.contains("focus",true)==true }
        inst.runOnMainSync { camera.adjustZoom(1f) }
        await("zoom reset") { kotlin.math.abs(camera.observedZoom-1f)<.01f }
        fun multi(action: Int,spread: Float,number: Int) {
            val props=Array(number) { n -> android.view.MotionEvent.PointerProperties().apply { id=n;toolType=android.view.MotionEvent.TOOL_TYPE_FINGER } }
            val coords=Array(number) { n -> android.view.MotionEvent.PointerCoords().apply { x=cx+if(n==0) -spread else spread;y=cy;pressure=1f;size=1f } }
            val event=android.view.MotionEvent.obtain(down,android.os.SystemClock.uptimeMillis(),action,number,props,coords,0,0,1f,1f,0,0,android.view.InputDevice.SOURCE_TOUCHSCREEN,0)
            assertTrue(inst.uiAutomation.injectInputEvent(event,true));event.recycle()
        }
        multi(android.view.MotionEvent.ACTION_DOWN,40f,1)
        multi(android.view.MotionEvent.ACTION_POINTER_DOWN or (1 shl android.view.MotionEvent.ACTION_POINTER_INDEX_SHIFT),40f,2)
        for(step in 1..10) { multi(android.view.MotionEvent.ACTION_MOVE,40f+step*10f,2);Thread.sleep(25) }
        multi(android.view.MotionEvent.ACTION_POINTER_UP or (1 shl android.view.MotionEvent.ACTION_POINTER_INDEX_SHIFT),140f,2)
        multi(android.view.MotionEvent.ACTION_UP,140f,1)
        await("pinch changed actual CameraX zoom") { camera.observedZoom>1.1f }
        inst.runOnMainSync { camera.adjustZoom(1f) }
        fun click(text: String) {
            fun target(node: android.view.accessibility.AccessibilityNodeInfo): android.view.accessibility.AccessibilityNodeInfo? {
                if((node.text?.toString()==text || node.contentDescription?.toString()==text) && node.isVisibleToUser) return node
                for(i in 0 until node.childCount) node.getChild(i)?.let { child -> target(child)?.let { return it } }
                return null
            }
            var node: android.view.accessibility.AccessibilityNodeInfo?=null
            await("$text visible") { inst.uiAutomation.clearCache();node=inst.uiAutomation.rootInActiveWindow?.let(::target);node!=null }
            while(node?.isClickable!=true && node?.parent!=null) node=node?.parent
            assertTrue(node!!.performAction(android.view.accessibility.AccessibilityNodeInfo.ACTION_CLICK))
        }
        click("Settings");click("Close")
        click("Quick settings");click("Close")
        assertTrue(camera.streaming)
    }

    @Test fun i_lowSpaceRetainsPhotoAndMetadata() {
        assertThrows(IllegalStateException::class.java) { PhotoStore(context) { 0L }.create() }
        val store=PhotoStore(context);val dir=store.create()
        val source=File(dir,"source.jpg")
        val bitmap=android.graphics.Bitmap.createBitmap(96,64,android.graphics.Bitmap.Config.ARGB_8888)
        source.outputStream().use { bitmap.compress(android.graphics.Bitmap.CompressFormat.JPEG,95,it) };bitmap.recycle()
        store.captured(dir)
        val bytes=source.readBytes();val metadata=store.read(dir).toString()
        assertThrows(IllegalStateException::class.java) { PhotoStore(context) { 0L }.publish(dir) }
        assertArrayEquals(bytes,source.readBytes());assertEquals(metadata,store.read(dir).toString())
    }

    @Test fun j_photoPreferenceCheckpointAfterCompletedTestSession() {
        val model=activity.recordingModel
        if(model.snapshots.value.openSession) {
            assertEquals("Only the dedicated paused test fixture can be completed",RecordingState.Paused,model.snapshots.value.state)
            model.stop()
            await("test session completed") { model.snapshots.value.state==RecordingState.Completed }
            await("test session export terminal") { ExportJournal.status.value?.active==false }
            assertEquals(ExportState.Completed,ExportJournal.status.value!!.state)
            inst.runOnMainSync { camera.acknowledge(ExportJournal.status.value!!.sessionId) }
            await("camera returned") { camera.streaming }
        }
        choose(CameraMode.Photo)
        await("durable Photo preference") {
            context.getSharedPreferences("camera_preferences",0).getString("mode",null)==CameraMode.Photo.name
        }
        assertEquals(CameraMode.Photo,camera.choice.mode)
    }

    @Test fun k_galleryOpensPublishedPhotoAndReturnsToCamera() {
        choose(CameraMode.Photo);capture(true)
        fun target(node: android.view.accessibility.AccessibilityNodeInfo): android.view.accessibility.AccessibilityNodeInfo? {
            if(node.contentDescription?.contains("Open saved media gallery")==true && node.isVisibleToUser) return node
            for(i in 0 until node.childCount) node.getChild(i)?.let { child -> target(child)?.let { return it } }
            return null
        }
        var node: android.view.accessibility.AccessibilityNodeInfo?=null
        await("gallery shortcut") { inst.uiAutomation.clearCache();node=inst.uiAutomation.rootInActiveWindow?.let(::target);node!=null }
        while(node?.isClickable!=true && node?.parent!=null) node=node?.parent
        assertTrue(node!!.performAction(android.view.accessibility.AccessibilityNodeInfo.ACTION_CLICK))
        var entry: com.calvinmazhindu.skitcam.gallery.SavedMedia?=null
        await("gallery includes captured photo") {
            inst.runOnMainSync {
                val gallery=androidx.test.runner.lifecycle.ActivityLifecycleMonitorRegistry.getInstance()
                    .getActivitiesInStage(androidx.test.runner.lifecycle.Stage.RESUMED)
                    .filterIsInstance<com.calvinmazhindu.skitcam.gallery.SavedMediaActivity>().firstOrNull()
                entry=gallery?.currentItems?.find { it.uri==activity.photoModel.lastUri.toString() }
            }
            entry!=null
        }
        fun photoRow(n: android.view.accessibility.AccessibilityNodeInfo): android.view.accessibility.AccessibilityNodeInfo? {
            if(n.contentDescription?.toString()=="Open photo ${entry!!.name}" && n.isVisibleToUser) return n
            for(i in 0 until n.childCount) n.getChild(i)?.let { photoRow(it)?.let { found->return found } }
            return null
        }
        await("photo row") { inst.uiAutomation.clearCache();node=inst.uiAutomation.rootInActiveWindow?.let(::photoRow);node!=null }
        while(node?.isClickable!=true && node?.parent!=null) node=node?.parent
        assertTrue(node!!.performAction(android.view.accessibility.AccessibilityNodeInfo.ACTION_CLICK))
        await("external image viewer") {
            inst.uiAutomation.clearCache()
            inst.uiAutomation.rootInActiveWindow?.packageName?.toString()?.let { it!=context.packageName && it!="com.android.systemui" }==true
        }
        inst.sendKeyDownUpSync(android.view.KeyEvent.KEYCODE_BACK)
        // The browser remains available after review; a second Back returns to camera.
        Thread.sleep(300)
        inst.sendKeyDownUpSync(android.view.KeyEvent.KEYCODE_BACK)
        await("camera after photo viewing") { camera.streaming }
    }

}
