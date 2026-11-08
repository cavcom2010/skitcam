package com.calvinmazhindu.skitcam.project

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Rect
import android.net.Uri
import android.os.SystemClock
import android.view.MotionEvent
import android.view.accessibility.AccessibilityNodeInfo
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.core.content.ContextCompat
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.calvinmazhindu.skitcam.MainActivity
import com.calvinmazhindu.skitcam.recording.*
import com.calvinmazhindu.skitcam.export.*
import com.calvinmazhindu.skitcam.trim.*
import org.json.JSONObject
import org.junit.*
import org.junit.Assert.*
import org.junit.runner.RunWith
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/** Real CameraX, MediaStore and Android system save picker on the isolated app. */
@RunWith(AndroidJUnit4::class)
class ProjectDeviceTest {
    private val inst=InstrumentationRegistry.getInstrumentation()
    private val context get()=inst.targetContext
    private fun shell(s: String)=inst.uiAutomation.executeShellCommand(s).use { android.os.ParcelFileDescriptor.AutoCloseInputStream(it).bufferedReader().readText().trim() }
    private fun await(label: String,seconds: Int=90,condition: ()->Boolean) {
        val end=System.nanoTime()+seconds*1_000_000_000L
        while(!condition() && System.nanoTime()<end) Thread.sleep(100)
        assertTrue(label,condition())
    }
    private fun cameraDiagnostics(activity: MainActivity): String {
        val bound = runCatching {
            ProcessCameraProvider.getInstance(context).get().isBound(activity.recordingModel.port.capture)
        }.getOrElse { "error=${it.message}" }
        return listOf(
            "cameraReady=${activity.recordingModel.cameraReady}",
            "pose=${activity.orientation.pose}",
            "streaming=${activity.cameraModel.streaming}",
            "cameraBound=$bound",
            "engineState=${activity.recordingModel.snapshots.value.state}",
            "commandPending=${activity.recordingModel.commandPending.value}",
            "exportBusy=${ExportJournal.busy}",
            "lifecycle=${activity.lifecycle.currentState}",
            "cameraPermission=${ContextCompat.checkSelfPermission(context,Manifest.permission.CAMERA)==PackageManager.PERMISSION_GRANTED}",
            "switching=${activity.cameraModel.switching}",
            "videoPending=${activity.cameraModel.video.pending}",
            "videoReady=${activity.cameraModel.video.ready}",
            "videoMessage=${activity.cameraModel.video.message}",
            "videoDiagnostic=${activity.cameraModel.video.diagnostic()}",
            "portProfile=${activity.recordingModel.port.profile}",
            "zoomReady=${activity.cameraModel.zoomReady}",
            "choice=${activity.cameraModel.choice}",
            "cameraMessage=${activity.cameraModel.message}"
        ).joinToString(", ")
    }
    private fun awaitCamera(label: String,seconds: Int=90,activity: ()->MainActivity,condition: ()->Boolean) {
        val end=System.nanoTime()+seconds*1_000_000_000L
        while(!condition() && System.nanoTime()<end) Thread.sleep(100)
        if(!condition()) {
            val diagnostics=cameraDiagnostics(activity())
            File(context.filesDir,"validation-m11").mkdirs()
            File(context.filesDir,"validation-m11/camera-return-diagnostics.txt").writeText(diagnostics)
            fail("$label: $diagnostics")
        }
    }
    private fun find(label: String): AccessibilityNodeInfo? {
        inst.uiAutomation.clearCache()
        fun walk(n: AccessibilityNodeInfo): AccessibilityNodeInfo? {
            if(n.isVisibleToUser && (n.text?.toString()==label || n.contentDescription?.toString()==label || n.viewIdResourceName==label)) return n
            for(i in 0 until n.childCount) n.getChild(i)?.let { walk(it)?.let { found -> return found } };return null
        }
        return inst.uiAutomation.rootInActiveWindow?.let(::walk)
    }
    private fun click(label: String) {
        var n: AccessibilityNodeInfo?=null
        await("Enabled $label") {
            n=find(label)
            while(n?.isClickable!=true && n?.parent!=null) n=n?.parent
            n?.isClickable==true && n?.isEnabled==true
        }
        assertTrue("Clickable $label",n!!.performAction(AccessibilityNodeInfo.ACTION_CLICK))
    }
    private fun pickerRoot(label: String): AccessibilityNodeInfo {
        val end=System.nanoTime()+30_000_000_000L
        while(System.nanoTime()<end) {
            inst.uiAutomation.clearCache()
            fun walk(n: AccessibilityNodeInfo): AccessibilityNodeInfo? {
                if(n.isVisibleToUser && n.text?.toString()==label && n.viewIdResourceName?.endsWith("android:id/title")==true) return n
                for(i in 0 until n.childCount) n.getChild(i)?.let { walk(it)?.let { found -> return found } }
                return null
            }
            inst.uiAutomation.rootInActiveWindow?.let(::walk)?.let { return it }
            Thread.sleep(150)
        }
        error("Picker root missing: $label")
    }
    private fun tap(node: AccessibilityNodeInfo) {
        val bounds=Rect();node.getBoundsInScreen(bounds)
        val down=SystemClock.uptimeMillis()
        val x=bounds.exactCenterX();val y=bounds.exactCenterY()
        for(event in listOf(
            MotionEvent.obtain(down,down,MotionEvent.ACTION_DOWN,x,y,0),
            MotionEvent.obtain(down,down+80,MotionEvent.ACTION_UP,x,y,0)
        )) {
            assertTrue(inst.uiAutomation.injectInputEvent(event,true));event.recycle()
        }
    }
    private fun shot(name: String) {
        val dir=File(context.filesDir,"validation-m11").apply { mkdirs() }
        inst.uiAutomation.takeScreenshot()?.let { bitmap -> File(dir,"$name.png").outputStream().use { bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG,100,it) };bitmap.recycle() }
    }
    private fun edit(command: ((SegmentEditResult)->Unit)->Unit) {
        val latch=CountDownLatch(1);var result: SegmentEditResult?=null
        command { result=it;latch.countDown() };assertTrue(latch.await(15,TimeUnit.SECONDS));assertTrue(result.toString(),result!!.success)
    }
    private fun recordFixture(): Pair<Session,String> {
        val marker=File(context.filesDir,"validation-m11/fixture-session-id")
        val existing=if(marker.isFile) runCatching { CompletedProjectCatalog(context).load(marker.readText()) }.getOrNull()
            else ProjectJobs.latest(context)?.let { runCatching { ProjectJobs.read(context,it).getString("snapshot") }.getOrNull() }
        if(existing!=null) {
            val snap=ProjectSnapshot.fromJournal(existing)
            marker.parentFile!!.mkdirs();marker.writeText(snap.session.id)
            return snap.session to existing
        }
        val camera=ActivityScenario.launch(MainActivity::class.java)
        try {
            lateinit var a: MainActivity
            camera.onActivity { a=it;it.requestedOrientation=android.content.pm.ActivityInfo.SCREEN_ORIENTATION_PORTRAIT }
            await("recovered") { a.recordingModel.recovered.value && a.cameraModel.streaming }
            check(!a.recordingModel.snapshots.value.openSession)
            inst.runOnMainSync { a.cameraModel.select(com.calvinmazhindu.skitcam.camera.CameraMode.SkitVideo,CameraLens.Rear,true) }
            val m=a.recordingModel
            await("ready") { m.cameraReady && a.orientation.pose==0 }
            repeat(3) { i ->
                inst.runOnMainSync { if(i==0) m.start(0) else m.resume(0) }
                await("recording") { m.snapshots.value.state==RecordingState.Recording && m.snapshots.value.activeDurationMs>=1700 }
                m.pause();await("paused") { m.snapshots.value.state==RecordingState.Paused }
            }
            val s=m.snapshots.value.session!!;val segment=s.segments.first();val facts=SessionFileStore(File(context.filesDir,"sessions")).sourceFacts(segment.path)
            edit { m.saveTrim(s.id,segment,TrimSource(facts,segment.trim!!.sourceDurationMs),TrimRange(segment.trim!!.sourceDurationMs,200,segment.trim.sourceDurationMs-100),it) }
            edit { m.setSegmentKept(s.id,s.segments[1].id,false,it) }
            edit { m.savePlaybackOrder(s.id,m.snapshots.value.session!!.playbackOrder,listOf(s.segments[2].id,s.segments[0].id),it) }
            m.stop();await("completed") { m.snapshots.value.state==RecordingState.Completed }
            await("published",180) { ExportJournal.status.value?.let { it.sessionId==s.id && it.state==ExportState.Completed }==true && m.cameraReady }
            assertTrue(a.cameraModel.streaming)
            val result=m.snapshots.value.session!!
            File(context.filesDir,"validation-m11").mkdirs()
            File(context.filesDir,"validation-m11/fixture-session-id").writeText(result.id)
            return result to CompletedProjectCatalog(context).load(result.id)
        } finally { camera.close() }
    }
    @Before fun guard() {
        val productionRun = context.packageName=="com.calvinmazhindu.skitcam" &&
            InstrumentationRegistry.getArguments().getString("allowProduction")=="true"
        check(productionRun || context.packageName.endsWith(".m12cvalidation") || context.packageName.endsWith(".m11validation") || context.packageName.endsWith(".m12cvalidation") || context.packageName.endsWith(".m12bvalidation"))
        for(p in listOf(Manifest.permission.CAMERA,Manifest.permission.RECORD_AUDIO,Manifest.permission.POST_NOTIFICATIONS)) shell("pm grant ${context.packageName} $p")
    }
    @Test fun systemPickerCreatesVerifiedPortableProjectAndCameraStillRecords() {
        val old=shell("wm user-rotation")
        try {
            val (s,raw)=recordFixture()
            val metadata=File(context.filesDir,"sessions/${s.id}/session.json").readBytes()
            val before=s.segments.associate { it.id to sha(File(it.path)) }
            val snapshot=ProjectSnapshot.fromJournal(raw)
            assertNotNull(CompletedProjectCatalog(context).find(Uri.parse(snapshot.finishedUri)))
            val page=ActivityScenario.launch<ProjectExportActivity>(Intent(context,ProjectExportActivity::class.java).setData(Uri.parse(snapshot.finishedUri)))
            try {
                await("export action") { find("Choose destination")?.isEnabled==true }
                shot("project-options")
                click("Choose destination")
                await("system save picker") { inst.uiAutomation.rootInActiveWindow?.packageName?.toString() in setOf("com.google.android.documentsui","com.android.documentsui") }
                shot("system-save-picker")
                // Android DocumentsUI uses this resource ID on the Moto's local Downloads provider.
                click("Show roots")
                // The drawer row exposes android:id/title but is not itself marked clickable.
                // Target that root row explicitly instead of the non-clickable breadcrumb.
                tap(pickerRoot("Downloads"))
                click("SAVE")
                val id=ProjectJobs.latest(context)!!
                await("project terminal",180) { ProjectJobs.state(ProjectJobs.read(context,id)) in setOf(ProjectState.Completed,ProjectState.Failed,ProjectState.Interrupted) }
                val job=ProjectJobs.read(context,id)
                File(context.filesDir,"validation-m11/project-job.json").writeText(job.toString(2))
                assertEquals(job.toString(2),ProjectState.Completed,ProjectJobs.state(job))
                await("completion notification") {
                    context.getSystemService(android.app.NotificationManager::class.java).activeNotifications.any {
                        it.id==12 && it.notification.extras.getString(android.app.Notification.EXTRA_TEXT)=="Project exported and verified"
                    }
                }
                assertEquals(5,job.getJSONArray("entries").length())
                val uri=Uri.parse(job.getString("uri"))
                assertFalse(ProjectDestination(context).name(uri).endsWith(".partial.zip"))
                assertTrue(context.contentResolver.persistedUriPermissions.any { it.uri==uri && it.isReadPermission })
                context.contentResolver.openInputStream(uri)!!.use { input -> File(context.filesDir,"validation-m11/project.zip").outputStream().use { input.copyTo(it) } }
                val zip=java.util.zip.ZipFile(File(context.filesDir,"validation-m11/project.zip"))
                zip.use {
                    val manifest=JSONObject(it.getInputStream(it.getEntry("session.json")).bufferedReader().readText())
                    assertEquals(s.playbackOrder,manifest.getJSONArray("playbackOrder").let { a -> List(a.length()) { a.getString(it) } })
                    assertEquals(200,manifest.getJSONArray("segments").getJSONObject(0).getJSONObject("savedTrim").getInt("trimStartMs"))
                    assertNull(it.getEntry("Original_Segments/segment_002.mp4"))
                    assertEquals(snapshot.finishedSha,ArchiveIO.scan("Finished_Video.mp4",{ it.getInputStream(it.getEntry("Finished_Video.mp4")) }).sha256)
                }
                val journal=ProjectJobs.read(context,id).toString()
                inst.runOnMainSync { ProjectExportService.launch(context,id) };Thread.sleep(600)
                assertEquals(journal,ProjectJobs.read(context,id).toString())
                shot("project-completed")
            } finally { page.close() }
            assertArrayEquals(metadata,File(context.filesDir,"sessions/${s.id}/session.json").readBytes())
            assertEquals(before,s.segments.associate { it.id to sha(File(it.path)) })
            val camera=ActivityScenario.launch(MainActivity::class.java)
            try {
                lateinit var a: MainActivity;camera.onActivity { a=it }
                // The app intentionally remembers the last mode. This acceptance test needs
                // the video camera regardless of whether the user last used Photo or Video.
                await("recovery after project") { a.recordingModel.recovered.value && a.cameraModel.streaming }
                inst.runOnMainSync { a.cameraModel.select(com.calvinmazhindu.skitcam.camera.CameraMode.SkitVideo,CameraLens.Rear,true) }
                awaitCamera("camera after project",activity={a}) { a.recordingModel.cameraReady && a.orientation.pose==0 }
                inst.runOnMainSync { a.recordingModel.start(0) }
                await("new real recording after project") { a.recordingModel.snapshots.value.state==RecordingState.Recording && a.recordingModel.snapshots.value.activeDurationMs>=1500 }
                a.recordingModel.stop()
                await("new export",180) { a.recordingModel.snapshots.value.state==RecordingState.Completed && ExportJournal.status.value?.state==ExportState.Completed && a.recordingModel.cameraReady }
                assertNotEquals(s.id,a.recordingModel.snapshots.value.session!!.id)
            } finally { camera.close() }
        } finally { assertEquals("Project test must not change global rotation",old,shell("wm user-rotation")) }
    }
    @Test fun videoOptionsOpenOptionalProjectAndDraftSurvivesRecreation() {
        val (_,raw)=recordFixture();val snapshot=ProjectSnapshot.fromJournal(raw)
        // Gallery uses the union "external" URI; the completed journal uses its
        // concrete volume. Both must identify the same selected video.
        val galleryUri=Uri.parse(snapshot.finishedUri).buildUpon().path("/external/video/media/${Uri.parse(snapshot.finishedUri).lastPathSegment}").build()
        assertNotNull(CompletedProjectCatalog(context).find(galleryUri))
        val player=ActivityScenario.launch<FinishedPlaybackActivity>(Intent(context,FinishedPlaybackActivity::class.java).setData(galleryUri))
        try {
            click("Video options");click("Export Project")
            await("optional project options") { find("Choose destination")!=null }
            assertNotNull(find("Include original segments"))
            assertFalse(find("Include discarded takes")!!.isChecked)
            click("Include discarded takes")
            await("explicit consent") { find("Include discarded takes")?.isChecked==true }
            // Capture the current Activity using the lifecycle registry; no test-only product hooks.
            inst.runOnMainSync {
                val editor=androidx.test.runner.lifecycle.ActivityLifecycleMonitorRegistry.getInstance()
                    .getActivitiesInStage(androidx.test.runner.lifecycle.Stage.RESUMED).filterIsInstance<ProjectExportActivity>().single()
                editor.recreate()
            }
            await("draft after recreation") { find("Include discarded takes")?.isChecked==true }
            shot("project-draft-recreated")
            inst.runOnMainSync {
                androidx.test.runner.lifecycle.ActivityLifecycleMonitorRegistry.getInstance()
                    .getActivitiesInStage(androidx.test.runner.lifecycle.Stage.RESUMED).filterIsInstance<ProjectExportActivity>().single()
                    .requestedOrientation=android.content.pm.ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE
            }
            await("landscape project draft") { context.resources.configuration.orientation==android.content.res.Configuration.ORIENTATION_LANDSCAPE && find("Include discarded takes")?.isChecked==true }
            inst.waitForIdleSync();Thread.sleep(800) // Let Android's rotation animation finish before visual evidence.
            shot("project-landscape")
            click("Back from project export")
            await("finished player restored") { find("Share video")!=null }
        } finally { player.close() }
    }

}
