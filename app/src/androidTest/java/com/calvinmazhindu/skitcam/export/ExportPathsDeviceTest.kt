package com.calvinmazhindu.skitcam.export

import android.net.Uri
import android.os.SystemClock
import android.provider.MediaStore
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.calvinmazhindu.skitcam.recording.*
import com.calvinmazhindu.skitcam.trim.TrimRange
import com.calvinmazhindu.skitcam.video.*
import org.json.JSONObject
import org.json.JSONArray
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.util.UUID

/** Synthetic/new sessions only. Historical inputs and production media remain read-only. */
@RunWith(AndroidJUnit4::class)
class ExportPathsDeviceTest {
    private val context=InstrumentationRegistry.getInstrumentation().targetContext
    private val inputs get()=File(context.filesDir,"validation-m12b-inputs")
    private data class Fixture(val session: Session, val hashes: List<String>)
    @Before fun guard() {
        check(context.packageName.endsWith(".m12cvalidation") || context.packageName.endsWith(".m12bvalidation"));check(!ExportJournal.busy)
        ExportJournal.cancelRequested=false
    }
    private fun await(label: String, condition: () -> Boolean) {
        val end=SystemClock.elapsedRealtime()+120000
        while(!condition() && SystemClock.elapsedRealtime()<end) Thread.sleep(50)
        assertTrue(label,condition())
    }
    private fun golden(): File {
        val report=JSONObject(File(context.filesDir,"validation-m12b/baseline-single/single-0.json").readText())
        return File(context.filesDir,"sessions/${report.getString("sessionId")}/export/output.mp4")
    }
    private fun fixture(sources: List<File>, trimmed: Boolean=false, order: List<Int> = sources.indices.toList()): Fixture {
        val id=UUID.randomUUID().toString();val root=File(context.filesDir,"sessions/$id").apply { mkdirs() }
        val segments=sources.mapIndexed { index,source ->
            val target=File(root,"segment_%03d.mp4".format(index+1));source.copyTo(target)
            val facts=ExportInputProbe.inspect(target)
            Segment(index+1,target.path,facts.durationMs,if(facts.rotation%180==0) 270 else 0,facts.rotation,
                kept=index in order,id="$id-$index",trim=if(trimmed) TrimRange(facts.durationMs,350,1750) else TrimRange.full(facts.durationMs))
        }
        val info=ExportInputProbe.inspect(File(segments.first().path))
        val profile=CaptureProfile("fixture",VideoSettings(if(maxOf(info.width,info.height)>1280) "FHD" else "HD"),info.width,info.height)
        val session=Session(id,System.currentTimeMillis(),segments.first().orientationDegrees,RecordingState.Completed,
            segments,playbackOrder=order.map { segments[it].id },captureProfile=profile)
        SessionFileStore(File(context.filesDir,"sessions")).save(session)
        return Fixture(session,segments.map { sha(File(it.path)) })
    }
    private fun request(f: Fixture) {
        ExportTrace.begin(f.session.id,"fixture")
        ExportJournal.request(context,f.session);ExportJournal.finalised(context,f.session)
    }
    private fun run(f: Fixture, retry: Boolean=false): JSONObject {
        val previous=ExportJournal.status.value
        ExportService.launch(context,f.session.id,retry)
        await("terminal ${f.session.id}") { !ExportJournal.busy && ExportJournal.status.value?.let { it!=previous && it.sessionId==f.session.id && !it.active }==true }
        assertEquals(f.hashes,f.session.segments.map { sha(File(it.path)) })
        return ExportJournal.read(context,f.session.id)!!
    }
    private fun success(name: String, f: Fixture, expected: ExportPath): JSONObject {
        request(f);val job=run(f)
        assertEquals(job.toString(),ExportState.Completed.name,job.getString("state"))
        assertEquals(job.toString(),expected.name,job.getString("selectedPath"))
        val report=File(context.filesDir,"validation-m12b-checks/$name.json")
        report.parentFile!!.mkdirs();report.writeText(job.toString(2))
        return job
    }
    @Test fun singleContractCompatibleClipIsByteCopied() {
        val f=fixture(listOf(golden()));val job=success("direct-copy",f,ExportPath.DIRECT_COPY)
        assertEquals(f.hashes.single(),job.getString("verifiedSha"))
        val original=job.getString("uri")
        ExportService.launch(context,f.session.id)
        Thread.sleep(300);assertEquals(original,ExportJournal.status.value?.uri)
    }
    @Test fun markerSequenceUsesVideoPassthroughAndAudioCorrection() {
        success("zero-markers",fixture(listOf(File(inputs,"zero/segment_001.mp4"),File(inputs,"zero/segment_002.mp4"))),ExportPath.VIDEO_PASSTHROUGH_AUDIO_RENDER)
    }
    @Test fun positiveAudioOriginsKeepTheLeadingGapCorrection() {
        success("offset-markers",fixture(listOf(File(inputs,"offset/segment_001.mp4"),File(inputs,"offset/segment_002.mp4"))),ExportPath.VIDEO_PASSTHROUGH_AUDIO_RENDER)
    }
    @Test fun trimmedReorderedAndDiscardedSourcesKeepRenderer() {
        success("trimmed-order",fixture(listOf(File(inputs,"zero/segment_001.mp4"),File(inputs,"zero/segment_002.mp4"),File(inputs,"zero/segment_001.mp4")),true,listOf(1,0)),ExportPath.RENDER)
    }
    @Test fun bFramesKeepRenderer() { success("bframes",fixture(listOf(File(inputs,"bframes.mp4"))),ExportPath.RENDER) }
    @Test fun differentAvcConfigurationKeepsRenderer() {
        success("different-config",fixture(listOf(File(inputs,"zero/segment_001.mp4"),File(inputs,"different.mp4"))),ExportPath.RENDER)
    }
    @Test fun variableCadenceDoesNotGetReplacedByConstantRate() {
        val f=fixture(listOf(File(inputs,"vfr.mp4")));request(f);val job=run(f)
        assertEquals(job.toString(),"Completed",job.getString("state"))
        assertNotEquals(ExportPath.RENDER.name,job.getString("selectedPath"))
        File(context.filesDir,"validation-m12b-checks/vfr.json").writeText(job.toString(2))
    }
    @Test fun rejectedFastCandidateFallsBackUnderSameJob() {
        val f=fixture(listOf(File(inputs,"zero/segment_001.mp4")),true)
        request(f)
        val initial=ExportJournal.read(context,f.session.id)!!
        initial.put("selectedPath",ExportPath.DIRECT_COPY.name);ExportJournal.write(context,f.session.id,initial)
        val job=run(f)
        assertEquals(job.toString(),"Completed",job.getString("state"))
        assertEquals(initial.getString("id"),job.getString("id"))
        assertTrue(job.getBoolean("renderFallbackUsed"))
        assertEquals(ExportPath.RENDER.name,job.getString("selectedPath"))
        assertTrue(ExportJournal.directory(context,f.session.id).listFiles()!!.any { it.name.startsWith("rejected-") })
    }
    @Test fun lowSpaceFailsBeforeCandidateThenSameJobRetries() {
        val f=fixture(listOf(golden()));request(f)
        val identity=ExportJournal.read(context,f.session.id)!!.getString("id")
        try {
            ExportProcessor.availableBytesForTests=1
            val failure=run(f)
            assertEquals("Failed",failure.getString("state"))
            assertFalse(File(ExportJournal.directory(context,f.session.id),"output.mp4").exists())
        } finally { ExportProcessor.availableBytesForTests=null }
        val success=run(f,true)
        assertEquals(success.toString(),"Completed",success.getString("state"));assertEquals(identity,success.getString("id"))
    }
    @Test fun cancellationAndPartialCandidateRetryDoNotChangeOriginals() {
        val f=fixture(listOf(golden()));request(f)
        val identity=ExportJournal.read(context,f.session.id)!!.getString("id")
        ExportJournal.cancelRequested=true
        assertEquals("Cancelled",run(f).getString("state"))
        File(ExportJournal.directory(context,f.session.id),"output.mp4").writeText("unverified partial")
        val completed=run(f,true)
        assertEquals(completed.toString(),"Completed",completed.getString("state"));assertEquals(identity,completed.getString("id"))
    }
    @Test fun publicationRejectsChangedCandidateThenReusesOnePendingRow() {
        val f=fixture(listOf(golden()));request(f)
        val spec=ExportSpec.from(f.session) { inspect(File(it.path)).duration }
        val output=File(ExportJournal.directory(context,f.session.id),"output.mp4")
        File(f.session.segments.single().path).copyTo(output)
        val report=verify(output,spec.durationMs)
        val job=ExportJournal.read(context,f.session.id)!!
        job.put("sources",JSONArray().put(JSONObject().put("id",spec.clips.single().id).put("sha256",f.hashes.single())))
            .put("spec",JSONArray().put(JSONObject().put("id",spec.clips.single().id).put("path",spec.clips.single().path)
                .put("duration",spec.durationMs).put("start",0).put("end",spec.durationMs)))
            .put("verifiedSha",report.getString("sha256")).put("verification",report)
        ExportJournal.write(context,f.session.id,job)
        ExportJournal.update(context,f.session.id,ExportState.Publishing)
        output.writeText("changed after verification")
        try { publish(context,f.session.id,output,job.getString("id"));fail("Changed candidate must not publish") }
        catch(_: IllegalStateException) { }
        val pending=ExportJournal.read(context,f.session.id)!!.getString("uri")
        context.contentResolver.query(Uri.parse(pending),arrayOf(MediaStore.Video.Media.IS_PENDING),null,null,null)!!.use {
            assertTrue(it.moveToFirst());assertEquals(1,it.getInt(0))
        }
        File(f.session.segments.single().path).copyTo(output,overwrite=true)
        ExportJournal.update(context,f.session.id,ExportState.Failed,"Fixture publication interrupted")
        val completed=run(f,true)
        assertEquals(completed.toString(),"Completed",completed.getString("state"));assertEquals(pending,completed.getString("uri"))
        context.contentResolver.query(MediaStore.Video.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY),
            arrayOf(MediaStore.Video.Media._ID),"${MediaStore.Video.Media.DESCRIPTION}=?",arrayOf("SkitCam export job:${job.getString("id")}"),null)!!.use {
            assertEquals(1,it.count)
        }
    }
    @Test fun directCopyComparisonOnIdenticalVerifiedFixture() {
        val timings=JSONArray()
        try {
            for(render in listOf(true,false)) repeat(3) {
                ExportProcessor.forceRenderForTests=render
                val f=fixture(listOf(golden()));request(f)
                val start=SystemClock.elapsedRealtimeNanos();val job=run(f)
                assertEquals(job.toString(),"Completed",job.getString("state"))
                assertEquals(if(render) "RENDER" else "DIRECT_COPY",job.getString("selectedPath"))
                timings.put(JSONObject().put("path",job.getString("selectedPath")).put("ms",(SystemClock.elapsedRealtimeNanos()-start)/1_000_000.0))
            }
        } finally { ExportProcessor.forceRenderForTests=false }
        File(context.filesDir,"validation-m12b-checks/direct-copy-comparison.json").writeText(timings.toString(2))
    }
}
