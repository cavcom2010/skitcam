package com.calvinmazhindu.skitcam.export

import android.app.*
import android.content.*
import android.content.pm.ServiceInfo
import android.os.*
import androidx.core.app.NotificationCompat
import com.calvinmazhindu.skitcam.MainActivity
import com.calvinmazhindu.skitcam.recording.*
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

class ExportService : Service() {
    companion object {
        private val running=AtomicBoolean(false)
        fun launch(context: Context,id: String,retry: Boolean=false) {
            synchronized(ExportJournal) {
                if (retry && ExportJournal.busy) return
                if (ExportJournal.read(context, id) == null) return
                if (retry) ExportJournal.cancelRequested = false
                ExportJournal.busy = true
                try {
                    context.startForegroundService(Intent(context,ExportService::class.java).putExtra("session",id)
                        .putExtra("retry",retry))
                } catch (e: Exception) {
                    ExportJournal.busy = false
                    throw e
                }
            }
        }
    }
    private val executor=Executors.newSingleThreadExecutor()
    private var sessionId: String?=null
    override fun onBind(intent: Intent?)=null
    override fun onStartCommand(intent: Intent?,flags: Int,startId: Int): Int {
        val id=intent?.getStringExtra("session") ?: return START_NOT_STICKY
        if(intent.action=="cancel") { if(sessionId==id) ExportJournal.cancelRequested=true;return START_NOT_STICKY }
        if(!running.compareAndSet(false,true)) return START_NOT_STICKY
        val retry=intent.getBooleanExtra("retry",false)
        sessionId=id;ExportJournal.busy=true
        ExportTrace.begin(id,"service")
        ExportTrace.mark(id,"serviceStart")
        val manager=getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(NotificationChannel("finishing","Finishing videos",NotificationManager.IMPORTANCE_LOW))
        val open=PendingIntent.getActivity(this,0,Intent(this,MainActivity::class.java),PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        val notification=NotificationCompat.Builder(this,"finishing").setSmallIcon(android.R.drawable.ic_menu_save)
            .setContentTitle("SkitCam · Finishing video").setContentText("Recording files are preserved")
            .setContentIntent(open).setOngoing(true).build()
        if(Build.VERSION.SDK_INT>=29) startForeground(6,notification,if(Build.VERSION.SDK_INT>=35) ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROCESSING else ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
        else startForeground(6,notification)
        executor.execute { execute(id,retry) }
        return START_NOT_STICKY
    }
    private fun execute(id: String,retry: Boolean) {
        var wakeLock: PowerManager.WakeLock?=null
        try {
            wakeLock=getSystemService(PowerManager::class.java).newWakeLock(PowerManager.PARTIAL_WAKE_LOCK,"SkitCam:export").apply { acquire(5*60*60*1000L) }
            val directory=ExportJournal.directory(this,id)
            var job=ExportJournal.read(this,id) ?: error("Export request unavailable")
            if(job.getString("state") in setOf(ExportState.Completed.name,ExportState.Empty.name)) return
            if(job.getString("state") in setOf(ExportState.Failed.name,ExportState.Cancelled.name)) {
                ExportJournal.update(this,id,ExportState.Preparing)
                // Freeze sources/spec onto the current Preparing journal, never write the
                // stale Failed/Cancelled state back after a cancellation before hashing.
                job=ExportJournal.read(this,id)!!
            }
            val deadline=System.nanoTime()+40_000_000_000L
            while(!job.optBoolean("ready")) {
                check(!job.optBoolean("recordingFailed")) { "The last take needs recovery. Original clips are retained." }
                check(System.nanoTime()<deadline) { "Recording did not finalise. Sources are retained." }
                Thread.sleep(100);job=ExportJournal.read(this,id)!!
            }
            checkCancelled()
            val session=SessionCodec.decode(job.getString("session"))
            ExportTrace.mark(id,"serviceSnapshotReady")
            val sessionRoot=java.io.File(filesDir,"sessions/$id").canonicalFile
            session.segments.forEach { segment ->
                val source=java.io.File(segment.path).canonicalFile
                require(source.parentFile==sessionRoot && source.name.matches(Regex("segment_[0-9]+\\.mp4"))) { "Segment ${segment.order}: invalid source path." }
            }
            val outputProfile=if(job.getInt("version")>=2 && !job.isNull("exportProfile")) com.calvinmazhindu.skitcam.video.VideoJson.profile(job.getJSONObject("exportProfile")) else null
            require(job.getInt("version")==1 || outputProfile==session.captureProfile) { "Export profile changed." }
            val spec=if(job.has("spec")) {
                val a=job.getJSONArray("spec")
                ExportSpec(id,session.orientationDegrees,List(a.length()) { index -> a.getJSONObject(index).let {
                    ExportClip(it.getString("id"),it.getString("path"),com.calvinmazhindu.skitcam.trim.TrimRange(it.getLong("duration"),it.getLong("start"),it.getLong("end")))
                } },outputProfile)
            } else ExportTrace.measure(id,"specInspection") { ExportSpec.from(session) { inspect(File(it.path)).duration }.copy(captureProfile=outputProfile) }
            require(spec.clips.map { it.id }==session.playbackOrder && spec.clips.all { clip ->
                clip.range.valid() && session.segments.any { it.id==clip.id && it.path==clip.path }
            }) { "Saved export specification is invalid." }
            if(spec.clips.isEmpty()) { ExportJournal.update(this,id,ExportState.Empty,"No retained segments. Original files are preserved.");return }
            if(!job.has("sources")) {
                val sources=JSONArray()
                ExportTrace.measure(id,"sourceHashSnapshot") {
                    spec.clips.forEach { clip -> checkCancelled();sources.put(JSONObject().put("id",clip.id).put("sha256",sha(File(clip.path))));ExportTrace.bytes(id,"sourceHashRead",File(clip.path).length()) }
                }
                job.put("sources",sources).put("spec",JSONArray().apply {
                    spec.clips.forEach { put(JSONObject().put("id",it.id).put("path",it.path).put("duration",it.range.sourceDurationMs).put("start",it.range.trimStartMs).put("end",it.range.trimEndMs)) }
                })
                ExportJournal.write(this,id,job)
            }
            val candidate=File(directory,"output.mp4")
            val verified=job.optString("verifiedSha")
            val reusable=verified.isNotBlank() && candidate.isFile &&
                ExportTrace.measure(id,"verifiedCheckpointHash") { sha(candidate)==verified }
            if(!reusable) {
                // Only an explicit Retry may re-check the completed, unverified bytes from
                // the previous attempt. Nothing here can publish without the full verifier.
                val reused=retry && reuseCompletedCandidate(id,spec,outputProfile,candidate)
                if(!reused) {
                    // The reuse probe may have recorded its rejection on the journal.
                    job=ExportJournal.read(this,id)!!
                    if(verified.isNotBlank()) {
                        job.put("invalidCheckpointSha",verified).remove("verifiedSha")
                        job.remove("verification");ExportJournal.write(this,id,job)
                    }
                    ExportCandidate.clear(this,id,job)
                    ExportTrace.measure(id,"sourceHashRecheck") { spec.clips.forEachIndexed { index, clip ->
                        check(sha(File(clip.path))==job.getJSONArray("sources").getJSONObject(index).getString("sha256")) { "Source segment changed. Export not started." }
                        ExportTrace.bytes(id,"sourceHashRead",File(clip.path).length())
                    } }
                    val required=requiredBytes(spec,outputProfile)
                    check(ExportProcessor.freeBytes(this,directory)>required) { "Insufficient space for finishing and gallery copy." }
                    ExportTrace.measure(id,"candidateCleanup") {
                        check(!candidate.exists() || candidate.delete()) { "Cannot clear this job's incomplete output." }
                    }
                    ExportJournal.update(this,id,ExportState.Exporting)
                    awaitCameraRelease(id)
                    val report=ExportProcessor.process(this,id,spec,candidate,required)
                    job=ExportJournal.read(this,id)!!;job.put("verifiedSha",report.getString("sha256")).put("verification",report)
                    job.remove("candidate")
                    job.remove("candidateReverified")
                    ExportTrace.measure(id,"verifiedCheckpointCommit") { ExportJournal.write(this,id,job) }
                }
            }
            checkCancelled()
            ExportJournal.update(this,id,ExportState.Publishing)
            val uri=ExportTrace.measure(id,"publication") { publish(this,id,candidate,job.getString("id")) }
            ExportJournal.update(this,id,ExportState.Completed,"Saved to Movies/SkitCam",uri.toString())
            ExportTrace.mark(id,"published")
        } catch(e: Exception) {
            android.util.Log.e("SkitCamExport","Export failed",e)
            try {
                val failureJob=runCatching { ExportJournal.read(this,id) }.getOrNull()
                val interruptedCheck=failureJob?.takeIf { it.optString("state")==ExportState.Verifying.name &&
                    it.optString("lastVerifyFailure")==ExportCandidate.FAILURE_DECODER }
                val message=when {
                    ExportJournal.cancelRequested -> e.message ?: "Finishing cancelled."
                    interruptedCheck?.optBoolean("candidateReverified")==true ->
                        ExportCandidate.RETRY_REBUILD_MESSAGE
                    interruptedCheck!=null && ExportCandidate.from(interruptedCheck)!=null ->
                        ExportCandidate.RETRY_RECHECK_MESSAGE
                    else -> e.message ?: "Finishing failed. Recording files are preserved."
                }
                ExportJournal.update(this,id,if(ExportJournal.cancelRequested) ExportState.Cancelled else ExportState.Failed,message)
            } catch(_: Exception) { }
        } finally {
            if(wakeLock?.isHeld==true) wakeLock.release()
            ExportTrace.mark(id,"serviceIdle")
            ExportTrace.flush(this,id)
            stopForeground(STOP_FOREGROUND_REMOVE);stopSelf()
            running.set(false);ExportJournal.busy=false
        }
    }
    /**
     * Re-check the bytes produced by the previous attempt when the user explicitly retries
     * after a decoder failure. The allowance is journaled before any decoder is opened, so a
     * crash cannot reset it. On any doubt the caller falls back to the normal rebuild path.
     */
    private fun reuseCompletedCandidate(id: String, spec: ExportSpec,
        outputProfile: com.calvinmazhindu.skitcam.video.CaptureProfile?, candidate: File): Boolean {
        val directory=ExportJournal.directory(this,id)
        var job=ExportJournal.read(this,id) ?: return false
        val assessment=ExportTrace.measure(id,"candidateReuseProbe") {
            ExportCandidate.assess(this,id,spec,job,candidate,retry=true)
        }
        if(assessment is ExportCandidate.Reuse.Rejected) {
            job.put("candidateReuseRejected",assessment.reason);ExportJournal.write(this,id,job)
            return false
        }
        val checkpoint=(assessment as ExportCandidate.Reuse.Ready).checkpoint
        val sourcesMatch=runCatching {
            ExportTrace.measure(id,"sourceHashRecheck") { spec.clips.forEachIndexed { index, clip ->
                check(sha(File(clip.path))==job.getJSONArray("sources").getJSONObject(index).getString("sha256")) {
                    "Source segment changed. Export not started."
                }
                ExportTrace.bytes(id,"sourceHashRead",File(clip.path).length())
            } }
        }.onFailure { failure -> runCatching {
            val rejected=ExportJournal.read(this,id)!!
            rejected.put("candidateReuseRejected","Source check failed: ${failure.message}")
            ExportJournal.write(this,id,rejected)
        } }.isSuccess
        if(!sourcesMatch) return false
        check(ExportProcessor.freeBytes(this,directory)>requiredBytes(spec,outputProfile)) {
            "Insufficient space for finishing and gallery copy."
        }
        val bytesMatch=ExportTrace.measure(id,"candidateCheckpointHash") {
            candidate.length()==checkpoint.bytes && sha(candidate)==checkpoint.sha256
        }
        if(!bytesMatch) {
            job=ExportJournal.read(this,id)!!
            job.put("candidateReuseRejected","Candidate bytes changed after rendering")
            ExportJournal.write(this,id,job)
            return false
        }
        awaitCameraRelease(id)
        job=ExportJournal.read(this,id)!!
        // Durable before the decoder is opened: a later crash or failure cannot grant a
        // second free re-check of the same bytes.
        job.put("candidateReverified",true)
            .put("verificationAttempts",job.optInt("verificationAttempts",0)+1)
            .remove("candidateReuseRejected")
        ExportJournal.write(this,id,job)
        ExportTrace.mark(id,"candidateReverifyCommitted")
        ExportJournal.update(this,id,ExportState.Verifying)
        val report=ExportProcessor.reverify(this,id,spec,candidate)
        check(report.getString("sha256")==checkpoint.sha256) {
            "Re-verified bytes no longer match the completed candidate."
        }
        val committed=ExportJournal.read(this,id)!!
        committed.put("verifiedSha",report.getString("sha256")).put("verification",report)
        committed.remove("candidate")
        committed.remove("candidateReverified")
        committed.remove("lastVerifyFailure")
        committed.remove("lastVerifyFailureDetail")
        ExportTrace.measure(id,"verifiedCheckpointCommit") { ExportJournal.write(this,id,committed) }
        return true
    }

    private fun awaitCameraRelease(id: String) {
        val deadline=System.nanoTime()+10_000_000_000L
        val start=ExportTrace.now()
        while(!RecordingViewModel.cameraReleasedForExport()) {
            check(System.nanoTime()<deadline) { "Camera is still in use. Retry when recording has closed." }
            checkCancelled();Thread.sleep(50)
        }
        ExportTrace.duration(id,"cameraReleaseWait",start)
    }

    private fun requiredBytes(spec: ExportSpec, outputProfile: com.calvinmazhindu.skitcam.video.CaptureProfile?): Long =
        outputProfile?.takeUnless { it.legacy }?.let {
            com.calvinmazhindu.skitcam.video.VideoProfilePolicy.reserveBytes(spec.durationMs,it.width,it.height,it.settings.fps)
        } ?: Math.addExact(Math.multiplyExact(spec.durationMs,1032L),100L*1024*1024)

    override fun onTimeout(startId: Int,fgsType: Int) {
        ExportJournal.cancelRequested=true
        sessionId?.let { ExportJournal.update(this,it,ExportState.Failed,"System processing time limit reached. Sources are retained.") }
        stopForeground(STOP_FOREGROUND_REMOVE);stopSelf()
    }
    override fun onDestroy() { executor.shutdown();super.onDestroy() }
}
