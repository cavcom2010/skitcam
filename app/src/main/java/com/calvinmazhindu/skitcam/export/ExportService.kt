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
            if(retry && ExportJournal.busy) return
            if(retry) ExportJournal.cancelRequested=false
            context.startForegroundService(Intent(context,ExportService::class.java).putExtra("session",id))
        }
    }
    private val executor=Executors.newSingleThreadExecutor()
    private var sessionId: String?=null
    override fun onBind(intent: Intent?)=null
    override fun onStartCommand(intent: Intent?,flags: Int,startId: Int): Int {
        val id=intent?.getStringExtra("session") ?: return START_NOT_STICKY
        if(intent.action=="cancel") { if(sessionId==id) ExportJournal.cancelRequested=true;return START_NOT_STICKY }
        if(!running.compareAndSet(false,true)) return START_NOT_STICKY
        sessionId=id;ExportJournal.busy=true
        val manager=getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(NotificationChannel("finishing","Finishing videos",NotificationManager.IMPORTANCE_LOW))
        val open=PendingIntent.getActivity(this,0,Intent(this,MainActivity::class.java),PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        val notification=NotificationCompat.Builder(this,"finishing").setSmallIcon(android.R.drawable.ic_menu_save)
            .setContentTitle("SkitCam · Finishing video").setContentText("Recording files are preserved")
            .setContentIntent(open).setOngoing(true).build()
        if(Build.VERSION.SDK_INT>=29) startForeground(6,notification,if(Build.VERSION.SDK_INT>=35) ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROCESSING else ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
        else startForeground(6,notification)
        executor.execute { execute(id) }
        return START_NOT_STICKY
    }
    private fun execute(id: String) {
        var wakeLock: PowerManager.WakeLock?=null
        try {
            wakeLock=getSystemService(PowerManager::class.java).newWakeLock(PowerManager.PARTIAL_WAKE_LOCK,"SkitCam:export").apply { acquire(5*60*60*1000L) }
            val directory=ExportJournal.directory(this,id)
            var job=ExportJournal.read(this,id) ?: error("Export request unavailable")
            if(job.getString("state") in setOf(ExportState.Completed.name,ExportState.Empty.name)) return
            if(job.getString("state") in setOf(ExportState.Failed.name,ExportState.Cancelled.name)) ExportJournal.update(this,id,ExportState.Preparing)
            val deadline=System.nanoTime()+40_000_000_000L
            while(!job.optBoolean("ready")) {
                check(System.nanoTime()<deadline) { "Recording did not finalise. Sources are retained." }
                Thread.sleep(100);job=ExportJournal.read(this,id)!!
            }
            checkCancelled()
            val session=SessionCodec.decode(job.getString("session"))
            val sessionRoot=java.io.File(filesDir,"sessions/$id").canonicalFile
            session.segments.forEach { segment ->
                val source=java.io.File(segment.path).canonicalFile
                require(source.parentFile==sessionRoot && source.name.matches(Regex("segment_[0-9]+\\.mp4"))) { "Segment ${segment.order}: invalid source path." }
            }
            val spec=if(job.has("spec")) {
                val a=job.getJSONArray("spec")
                ExportSpec(id,session.orientationDegrees,List(a.length()) { index -> a.getJSONObject(index).let {
                    ExportClip(it.getString("id"),it.getString("path"),com.calvinmazhindu.skitcam.trim.TrimRange(it.getLong("duration"),it.getLong("start"),it.getLong("end")))
                } })
            } else ExportSpec.from(session) { inspect(File(it.path)).duration }
            require(spec.clips.map { it.id }==session.playbackOrder && spec.clips.all { clip ->
                clip.range.valid() && session.segments.any { it.id==clip.id && it.path==clip.path }
            }) { "Saved export specification is invalid." }
            if(spec.clips.isEmpty()) { ExportJournal.update(this,id,ExportState.Empty,"No retained segments. Original files are preserved.");return }
            if(!job.has("sources")) {
                val sources=JSONArray()
                spec.clips.forEach { clip -> checkCancelled();sources.put(JSONObject().put("id",clip.id).put("sha256",sha(File(clip.path)))) }
                job.put("sources",sources).put("spec",JSONArray().apply {
                    spec.clips.forEach { put(JSONObject().put("id",it.id).put("path",it.path).put("duration",it.range.sourceDurationMs).put("start",it.range.trimStartMs).put("end",it.range.trimEndMs)) }
                })
                ExportJournal.write(this,id,job)
            }
            val candidate=File(directory,"output.mp4")
            val verified=job.optString("verifiedSha")
            if(verified.isBlank() || !candidate.isFile || sha(candidate)!=verified) {
                spec.clips.forEachIndexed { index, clip ->
                    check(sha(File(clip.path))==job.getJSONArray("sources").getJSONObject(index).getString("sha256")) { "Source segment changed. Export not started." }
                }
                val bytes=Math.multiplyExact(spec.durationMs,1032L) // Two copies at ~4.1 Mb/s, plus reserve.
                check(directory.usableSpace>Math.addExact(bytes,100L*1024*1024)) { "Insufficient space for finishing and gallery copy." }
                check(!candidate.exists() || candidate.delete()) { "Cannot clear this job's incomplete output." }
                ExportJournal.update(this,id,ExportState.Exporting)
                val cameraDeadline=System.nanoTime()+10_000_000_000L
                while(!RecordingViewModel.cameraReleasedForExport()) {
                    check(System.nanoTime()<cameraDeadline) { "Camera is still in use. Retry when recording has closed." }
                    checkCancelled();Thread.sleep(50)
                }
                transform(this,spec,candidate) { percent ->
                    val current=ExportJournal.status.value
                    if(current?.sessionId==id && current.state==ExportState.Exporting) ExportJournal.status.value=current.copy(message=percent?.let { "Encoding · $it%" } ?: "Encoding video")
                }
                ExportJournal.update(this,id,ExportState.Verifying)
                val report=verify(candidate,spec.durationMs)
                job=ExportJournal.read(this,id)!!;job.put("verifiedSha",report.getString("sha256")).put("verification",report)
                ExportJournal.write(this,id,job)
            }
            checkCancelled()
            ExportJournal.update(this,id,ExportState.Publishing)
            val uri=publish(this,id,candidate,job.getString("id"))
            ExportJournal.update(this,id,ExportState.Completed,"Saved to Movies/SkitCam",uri.toString())
        } catch(e: Exception) {
            android.util.Log.e("SkitCamExport","Export failed",e)
            try { ExportJournal.update(this,id,if(ExportJournal.cancelRequested) ExportState.Cancelled else ExportState.Failed,
                e.message ?: "Finishing failed. Recording files are preserved.") } catch(_: Exception) { }
        } finally {
            if(wakeLock?.isHeld==true) wakeLock.release()
            ExportJournal.busy=false;running.set(false)
            stopForeground(STOP_FOREGROUND_REMOVE);stopSelf()
        }
    }
    override fun onTimeout(startId: Int,fgsType: Int) {
        ExportJournal.cancelRequested=true
        sessionId?.let { ExportJournal.update(this,it,ExportState.Failed,"System processing time limit reached. Sources are retained.") }
        stopForeground(STOP_FOREGROUND_REMOVE);stopSelf()
    }
    override fun onDestroy() { executor.shutdown();super.onDestroy() }
}
