package com.calvinmazhindu.skitcam.export

import android.Manifest
import android.content.pm.ActivityInfo
import android.net.Uri
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.calvinmazhindu.skitcam.MainActivity
import com.calvinmazhindu.skitcam.recording.*
import org.junit.*
import org.junit.Assert.*
import org.junit.runner.RunWith
import java.io.File

@RunWith(AndroidJUnit4::class)
class ExportDeviceTest {
    private val instrumentation=InstrumentationRegistry.getInstrumentation()
    private val context get()=instrumentation.targetContext
    private fun shell(command: String)=instrumentation.uiAutomation.executeShellCommand(command).use {
        android.os.ParcelFileDescriptor.AutoCloseInputStream(it).bufferedReader().readText().trim()
    }
    private fun await(description: String, condition: () -> Boolean) {
        val end=System.nanoTime()+180_000_000_000L
        while(!condition() && System.nanoTime()<end) Thread.sleep(100)
        assertTrue("$description: ${ExportJournal.status.value}",condition())
    }
    private fun clickText(text: String) {
        fun find(node: android.view.accessibility.AccessibilityNodeInfo): android.view.accessibility.AccessibilityNodeInfo? {
            if(node.isVisibleToUser && (node.text?.toString()==text || node.contentDescription?.toString()==text)) return node
            for(i in 0 until node.childCount) node.getChild(i)?.let { child -> find(child)?.let { return it } }
            return null
        }
        var target: android.view.accessibility.AccessibilityNodeInfo?=null
        await("Visible $text control") {
            if(android.os.Build.VERSION.SDK_INT>=33) instrumentation.uiAutomation.clearCache()
            target=instrumentation.uiAutomation.rootInActiveWindow?.let(::find)
            target!=null
        }
        var node=target!!
        while(!node.isClickable && node.parent!=null) node=node.parent
        assertTrue(node.performAction(android.view.accessibility.AccessibilityNodeInfo.ACTION_CLICK))
    }
    @Test fun stopAutomaticallyExportsAndPublishesTwoSegments() {
        for(p in listOf(Manifest.permission.CAMERA,Manifest.permission.RECORD_AUDIO,Manifest.permission.POST_NOTIFICATIONS)) shell("pm grant ${context.packageName} $p")
        val rotation=shell("wm user-rotation")
        val scenario=ActivityScenario.launch(MainActivity::class.java)
        try {
            lateinit var model: RecordingViewModel
            lateinit var activity: MainActivity
            scenario.onActivity { activity=it;model=it.recordingModel }
            Assume.assumeTrue("Preserve any user session or export",!ExportJournal.busy && model.snapshots.value.state in setOf(RecordingState.Ready,RecordingState.Completed))
            // Dismiss only a finished status screen; this never mutates a recording.
            instrumentation.runOnMainSync { ExportJournal.status.value=null }
            await("camera ready") { model.cameraReady && activity.orientation.pose==0 }
            model.start(0);await("recording") { model.snapshots.value.state==RecordingState.Recording }
            await("first samples") { model.snapshots.value.activeDurationMs>=1500 };model.pause()
            await("paused") { model.snapshots.value.state==RecordingState.Paused }
            model.resume(0);await("second recording") { model.snapshots.value.state==RecordingState.Recording }
            await("second samples") { model.snapshots.value.activeDurationMs>=1500 }
            model.stop();model.stop()
            await("recording finalised") { model.snapshots.value.state==RecordingState.Completed }
            val session=model.snapshots.value.session!!
            val hashes=session.segments.map { sha(File(it.path)) }
            await("export terminal") { ExportJournal.status.value?.let { !it.active }==true }
            val result=ExportJournal.status.value!!
            assertEquals(ExportState.Completed,result.state)
            val uri=Uri.parse(result.uri)
            context.contentResolver.openInputStream(uri)!!.use { assertTrue(it.read()>=0) }
            assertEquals(hashes,session.segments.map { sha(File(it.path)) })
            val job=ExportJournal.read(context,session.id)!!
            assertEquals(2,job.getJSONArray("spec").length())
            val previous=result.uri
            ExportService.launch(context,session.id)
            Thread.sleep(500)
            assertEquals(previous,ExportJournal.status.value!!.uri)
            await("Automatic return to a live camera") { model.cameraReady }
            scenario.onActivity { assertEquals(result.uri,it.cameraModel.latestUri);assertTrue(it.cameraModel.streaming) }
            clickText("Open saved media gallery")
            var gallery: com.calvinmazhindu.skitcam.gallery.SavedMediaActivity?=null
            var selected: com.calvinmazhindu.skitcam.gallery.SavedMedia?=null
            await("Gallery lists published MP4") {
                instrumentation.runOnMainSync {
                    gallery=androidx.test.runner.lifecycle.ActivityLifecycleMonitorRegistry.getInstance()
                        .getActivitiesInStage(androidx.test.runner.lifecycle.Stage.RESUMED)
                        .filterIsInstance<com.calvinmazhindu.skitcam.gallery.SavedMediaActivity>().firstOrNull()
                    selected=gallery?.currentItems?.find { it.uri==previous }
                }
                selected!=null
            }
            clickText("Open video ${selected!!.name}")
            var playback: FinishedPlaybackActivity?=null
            await("Finished player ready") {
                instrumentation.runOnMainSync {
                    playback=androidx.test.runner.lifecycle.ActivityLifecycleMonitorRegistry.getInstance()
                        .getActivitiesInStage(androidx.test.runner.lifecycle.Stage.RESUMED)
                        .filterIsInstance<FinishedPlaybackActivity>().firstOrNull()
                }
                var ready=false
                instrumentation.runOnMainSync { ready=playback?.currentPlayer?.playbackState==androidx.media3.common.Player.STATE_READY }
                ready
            }
            instrumentation.runOnMainSync { playback!!.currentPlayer!!.play() }
            Thread.sleep(500)
            instrumentation.runOnMainSync { assertTrue(playback!!.currentPlayer!!.currentPosition>=100) }
            clickText("Share video")
            await("Android share chooser") {
                if(android.os.Build.VERSION.SDK_INT>=33) instrumentation.uiAutomation.clearCache()
                instrumentation.uiAutomation.rootInActiveWindow?.packageName?.toString() in setOf("android","com.android.intentresolver")
            }
            instrumentation.sendKeyDownUpSync(android.view.KeyEvent.KEYCODE_BACK)
            instrumentation.runOnMainSync { playback!!.finish() }
            clickText("Back to camera")
            await("Camera available after finished actions") { model.cameraReady }
        } finally { scenario.close();assertEquals("Export test must not change global rotation",rotation,shell("wm user-rotation")) }
    }
    @Test fun exportDedicatedSceneFixture() {
        val id=InstrumentationRegistry.getArguments().getString("fixture")
        Assume.assumeNotNull(id)
        val scenario=ActivityScenario.launch(MainActivity::class.java)
        try {
            lateinit var model: RecordingViewModel
            scenario.onActivity { model=it.recordingModel }
            Assume.assumeTrue("Do not interrupt a user recording",!ExportJournal.busy && model.snapshots.value.state in setOf(RecordingState.Ready,RecordingState.Completed))
            val session=SessionCodec.decode(File(context.filesDir,"sessions/$id/session.json").readText())
            ExportJournal.request(context,session);ExportJournal.finalised(context,session)
            ExportJournal.status.value=null // A previous failed attempt must not satisfy the completion wait.
            instrumentation.runOnMainSync { ExportService.launch(context,session.id) }
            scenario.moveToState(androidx.lifecycle.Lifecycle.State.CREATED)
            await("fixture exported") { ExportJournal.status.value?.let { it.sessionId==session.id && !it.active }==true }
            assertEquals(ExportJournal.status.value.toString(),ExportState.Completed,ExportJournal.status.value!!.state)
        } finally { scenario.close() }
    }

}
