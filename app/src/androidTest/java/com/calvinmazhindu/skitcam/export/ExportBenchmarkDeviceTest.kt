package com.calvinmazhindu.skitcam.export

import android.Manifest
import android.os.BatteryManager
import android.content.Intent
import android.content.IntentFilter
import android.graphics.Bitmap
import android.os.Debug
import android.os.PowerManager
import android.os.Process
import android.os.SystemClock
import android.view.accessibility.AccessibilityNodeInfo
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.calvinmazhindu.skitcam.MainActivity
import com.calvinmazhindu.skitcam.camera.CameraMode
import com.calvinmazhindu.skitcam.recording.*
import com.calvinmazhindu.skitcam.trim.*
import com.calvinmazhindu.skitcam.video.VideoSettings
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/** Fresh jobs/recordings, never a verified checkpoint disguised as a faster export. */
@RunWith(AndroidJUnit4::class)
class ExportBenchmarkDeviceTest {
    private val inst = InstrumentationRegistry.getInstrumentation()
    private val context get() = inst.targetContext
    private val args get() = InstrumentationRegistry.getArguments()
    private fun shell(command: String) = inst.uiAutomation.executeShellCommand(command).use {
        android.os.ParcelFileDescriptor.AutoCloseInputStream(it).bufferedReader().readText()
    }
    private fun await(label: String, seconds: Int = 120, condition: () -> Boolean) {
        val end = SystemClock.elapsedRealtime() + seconds * 1000L
        while (!condition() && SystemClock.elapsedRealtime() < end) Thread.sleep(50)
        assertTrue(label, condition())
    }
    private fun edit(block: ((SegmentEditResult) -> Unit) -> Unit) {
        val done=CountDownLatch(1);var result: SegmentEditResult?=null
        block { result=it;done.countDown() }
        assertTrue(done.await(20,TimeUnit.SECONDS));assertTrue(result.toString(),result!!.success)
    }
    private fun clickStop() {
        fun find(n: AccessibilityNodeInfo): AccessibilityNodeInfo? {
            if(n.isVisibleToUser && n.contentDescription?.toString()=="Stop recording") return n
            for(i in 0 until n.childCount) n.getChild(i)?.let { find(it)?.let { found -> return found } }
            return null
        }
        var node: AccessibilityNodeInfo?=null
        await("STOP action") { inst.uiAutomation.clearCache();node=inst.uiAutomation.rootInActiveWindow?.let(::find);node!=null }
        var action=node!!
        while(!action.isClickable && action.parent!=null) action=action.parent
        assertTrue(action.performAction(AccessibilityNodeInfo.ACTION_CLICK))
    }
    private fun temperature(): Int = context.registerReceiver(null,IntentFilter(Intent.ACTION_BATTERY_CHANGED))
        ?.getIntExtra(BatteryManager.EXTRA_TEMPERATURE,-1) ?: -1
    private fun screenshot(name: String) {
        val image=inst.uiAutomation.takeScreenshot() ?: return
        val file=File(context.filesDir,"validation-m12b-shake/$name.png");file.parentFile!!.mkdirs()
        file.outputStream().use { image.compress(Bitmap.CompressFormat.PNG,100,it) };image.recycle()
    }

    @Test fun recordAndMeasure() {
        check(context.packageName.endsWith(".m12cvalidation") || context.packageName.endsWith(".m12bvalidation")) { "Isolated M12B only" }
        for(p in listOf(Manifest.permission.CAMERA,Manifest.permission.RECORD_AUDIO,Manifest.permission.POST_NOTIFICATIONS))
            shell("pm grant ${context.packageName} $p")
        val kind=args.getString("case") ?: "single"
        require(kind in listOf("single","five","twenty","trim","order"))
        val count=when(kind) { "single" -> 1;"twenty" -> 20;else -> 5 }
        val duration=when(kind) { "single" -> 10_000L;"twenty" -> 1200L;else -> 6000L }
        val lens=if(args.getString("lens")=="front") CameraLens.Front else CameraLens.Rear
        val pose=(args.getString("pose") ?: "0").toInt()
        val settings=VideoSettings(quality=args.getString("quality") ?: "FHD",fps=args.getString("fps")?.toInt())
        val run=args.getString("run") ?: "baseline"
        ExportProcessor.forceRenderForTests=args.getString("policy")=="render"
        val results=File(context.filesDir,"validation-m12b/$run").apply { mkdirs() }
        val scenario=ActivityScenario.launch(MainActivity::class.java)
        lateinit var activity: MainActivity
        scenario.onActivity { activity=it }
        val model=activity.recordingModel
        try {
            await("recovery") { model.recovered.value && activity.cameraModel.ready }
            check(!model.snapshots.value.openSession && !ExportJournal.busy) { "An existing session must not be interrupted" }
            ExportTestSupport.beforeCamera()
            await("initial stream before lens selection") { activity.cameraModel.streaming && !activity.cameraModel.switching }
            inst.runOnMainSync { activity.cameraModel.select(CameraMode.SkitVideo,lens,true) }
            await("camera") { model.cameraReady && model.boundLens==lens && activity.cameraModel.streaming }
            if(activity.cameraModel.video.settings.quality!=settings.quality && settings.fps!=null) {
                val base=settings.copy(fps=null)
                inst.runOnMainSync { activity.cameraModel.video.choose(base) }
                await("base quality before FPS") { model.cameraReady && !activity.cameraModel.video.pending && activity.cameraModel.video.settings==base }
            }
            inst.runOnMainSync { activity.cameraModel.video.choose(settings) }
            await("profile") { model.cameraReady && !activity.cameraModel.video.pending && activity.cameraModel.video.settings==settings }
            repeat((args.getString("iterations") ?: "3").toInt()) { iteration ->
                await("physical pose $pose") { activity.orientation.pose==pose && CameraOrientationPolicy.matchesDisplay(pose,activity.orientation.displayRotation) }
                repeat(count) { index ->
                    inst.runOnMainSync { if(index==0) model.start(pose) else model.resume(pose) }
                    await("recording") { model.snapshots.value.state==RecordingState.Recording }
                    await("recorded samples") { model.snapshots.value.activeDurationMs>=duration }
                    if(args.getString("screenshots")=="true") {
                        repeat(20) { shot -> screenshot("header-${kind}-${iteration}-${shot.toString().padStart(2,'0')}");Thread.sleep(100) }
                    }
                    if(index<count-1 || kind in listOf("trim","order")) {
                        model.pause();await("paused") { model.snapshots.value.state==RecordingState.Paused }
                    }
                }
                var session=model.snapshots.value.session!!
                if(kind=="trim") {
                    session.segments.forEach { segment ->
                        val facts=SessionFileStore(File(context.filesDir,"sessions")).sourceFacts(segment.path)
                        val range=TrimRange(segment.trim!!.sourceDurationMs,300,segment.trim.sourceDurationMs-300)
                        edit { model.saveTrim(session.id,segment,TrimSource(facts,range.sourceDurationMs),range,it) }
                    }
                }
                if(kind=="order") {
                    edit { model.setSegmentKept(session.id,session.segments[1].id,false,it) }
                    session=model.snapshots.value.session!!
                    edit { model.savePlaybackOrder(session.id,session.playbackOrder,session.playbackOrder.reversed(),it) }
                }
                val id=session.id
                val tempBefore=temperature();val cpuBefore=Process.getElapsedCpuTime()
                var peakPss=Debug.getPss();var peakThermal=0
                clickStop()
                val deadline=SystemClock.elapsedRealtime()+240_000
                while(SystemClock.elapsedRealtime()<deadline) {
                    peakPss=maxOf(peakPss,Debug.getPss())
                    peakThermal=maxOf(peakThermal,context.getSystemService(PowerManager::class.java).currentThermalStatus)
                    if(!ExportJournal.busy && ExportJournal.status.value?.sessionId==id && ExportJournal.status.value?.active==false && model.cameraReady && activity.cameraModel.streaming) break
                    Thread.sleep(100)
                }
                assertEquals(ExportJournal.status.value.toString(),ExportState.Completed,ExportJournal.status.value?.state)
                await("usable camera") { model.cameraReady && activity.cameraModel.streaming && activity.cameraModel.receipt?.let { it.video.sessionId==id && it.handled }==true }
                ExportTrace.mark(id,"testCameraReady")
                ExportTrace.flush(context,id)
                val job=ExportJournal.read(context,id)!!
                val result=JSONObject().put("case",kind).put("iteration",iteration).put("sessionId",id)
                    .put("lens",lens.name).put("profile",job.get("exportProfile"))
                    .put("temperatureBeforeTenthsC",tempBefore).put("temperatureAfterTenthsC",temperature())
                    .put("processCpuMs",Process.getElapsedCpuTime()-cpuBefore).put("peakSampledPssKiB",peakPss)
                    .put("peakThermalStatus",peakThermal).put("job",job)
                    .put("trace",JSONObject(File(ExportJournal.directory(context,id),"performance.json").readText()))
                File(results,"$kind-$iteration.json").writeText(result.toString(2))
                android.util.Log.i("SkitCamBenchmark",result.toString())
            }
        } finally { ExportProcessor.forceRenderForTests=false;scenario.close() }
    }
}
