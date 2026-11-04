package com.calvinmazhindu.skitcam.project

import android.app.*
import android.content.*
import android.content.pm.ServiceInfo
import android.net.Uri
import androidx.core.net.toUri
import android.os.*
import androidx.core.app.NotificationCompat
import com.calvinmazhindu.skitcam.export.ExportJournal
import com.calvinmazhindu.skitcam.photo.PhotoModel
import com.calvinmazhindu.skitcam.recording.*
import org.json.JSONArray
import org.json.JSONObject
import java.io.Closeable
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicReference

/** Copies bytes only. Owns no camera, player, encoder or microphone. */
class ProjectExportService : Service() {
    companion object {
        @Volatile private var instance: ProjectExportService?=null
        fun launch(context: Context,id: String) {
            if(ProjectJobs.running.get()!=null || ProjectJobs.state(ProjectJobs.read(context,id))==ProjectState.Completed) return
            context.startForegroundService(Intent(context,ProjectExportService::class.java).putExtra("job",id))
        }
        fun cancel(context: Context,id: String) = synchronized(ProjectJobs) {
            val j=ProjectJobs.read(context,id)
            if(ProjectJobs.state(j).active && ProjectJobs.state(j)!=ProjectState.Finalising && ProjectJobs.running.get()==id) {
                ProjectJobs.cancelRequested=true;instance?.closeIo()
            }
        }
    }
    private val worker=Executors.newSingleThreadExecutor()
    private val io=AtomicReference<Closeable?>(null)
    private var jobId: String?=null
    private var lastProgress=0L
    private var copied=0L
    private var deadline=Long.MAX_VALUE
    override fun onBind(intent: Intent?)=null
    override fun onCreate() { super.onCreate();instance=this }
    private fun closeIo() { val resource=io.getAndSet(null);if(resource!=null) Thread { runCatching { resource.close() } }.start() }
    override fun onStartCommand(intent: Intent?,flags: Int,startId: Int): Int {
        val id=intent?.getStringExtra("job") ?: run { stopSelf(startId);return START_NOT_STICKY }
        getSystemService(NotificationManager::class.java).createNotificationChannel(NotificationChannel("projects","Portable project exports",NotificationManager.IMPORTANCE_LOW))
        try {
            val notificationId=ProjectJobs.running.get() ?: id
            if(Build.VERSION.SDK_INT>=29) startForeground(11,notification(notificationId,"Preparing project"),ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
            else startForeground(11,notification(notificationId,"Preparing project"))
        } catch(e: Exception) {
            runCatching { ProjectJobs.update(this,id,ProjectState.Failed,"Could not start project export: ${e.message}") }
            stopSelf(startId);return START_NOT_STICKY
        }
        val j=runCatching { ProjectJobs.read(this,id) }.getOrElse {
            if(ProjectJobs.running.get()==null) { stopForeground(STOP_FOREGROUND_REMOVE);stopSelf(startId) }
            return START_NOT_STICKY
        }
        if(ProjectJobs.state(j)==ProjectState.Completed) {
            if(ProjectJobs.running.get()==null) { stopForeground(STOP_FOREGROUND_REMOVE);stopSelf(startId) }
            return START_NOT_STICKY
        }
        if(!ProjectJobs.running.compareAndSet(null,id)) return START_NOT_STICKY
        jobId=id;ProjectJobs.cancelRequested=false;ProjectJobs.timedOut=false
        worker.execute { execute(id) }
        return START_NOT_STICKY
    }
    private fun notification(id: String,message: String,ongoing: Boolean=true): Notification {
        val intent=Intent(this,ProjectExportActivity::class.java).putExtra("job",id)
            .setData(Uri.Builder().scheme("skitcam").authority("project-export").appendPath(id).build())
        val open=PendingIntent.getActivity(this,11,intent,PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        return NotificationCompat.Builder(this,"projects").setSmallIcon(android.R.drawable.ic_menu_save)
            .setContentTitle(if(ongoing) "SkitCam · Exporting project" else "SkitCam · Project export")
            .setContentText(message).setContentIntent(open).setOngoing(ongoing).setAutoCancel(!ongoing).build()
    }
    private fun progress(id: String,phase: String,total: Long?=null,increment: Long=0) {
        copied=Math.addExact(copied,increment)
        val now=SystemClock.elapsedRealtime()
        if(now-lastProgress>=500) {
            lastProgress=now;ProjectJobs.progress.value=ProjectProgress(id,phase,copied,total)
            getSystemService(NotificationManager::class.java).notify(11,notification(id,phase))
        }
    }
    private fun checkpoint(id: String) {
        if(System.nanoTime()>deadline) ProjectJobs.timedOut=true
        if(ProjectJobs.timedOut || Thread.currentThread().isInterrupted) throw InterruptedException("Project export was interrupted. Retry is safe.")
        if(ProjectJobs.cancelRequested) throw ProjectCancelled()
        while(ExportJournal.busy || RecordingViewModel.captureBusyForProject() || PhotoModel.savingForProject()) {
            if(System.nanoTime()>deadline) ProjectJobs.timedOut=true
            if(ProjectJobs.cancelRequested) throw ProjectCancelled()
            if(ProjectJobs.timedOut || Thread.currentThread().isInterrupted) throw InterruptedException()
            progress(id,"Waiting for camera work to finish")
            Thread.sleep(50)
        }
    }
    private fun entries(j: JSONObject): List<ArchivePayload> {
        val a=j.getJSONArray("entries")
        fun number(o: JSONObject,k: String): Long { val v=o.get(k);require(v is Int || v is Long);return (v as Number).toLong() }
        return List(a.length()) { i -> a.getJSONObject(i).let { o -> ArchivePayload(o.getString("name"),number(o,"size"),number(o,"crc"),o.getString("sha256")) { error("Verification must not reopen originals.") } } }
    }
    private fun prepare(id: String,j: JSONObject): List<ArchivePayload> {
        val snapshot=ProjectSnapshot.fromJournal(j.getString("snapshot"));val options=ProjectJobs.options(j)
        val catalog=CompletedProjectCatalog(this);val durations=mutableMapOf<String,Long>()
        val payloads=mutableListOf<ArchivePayload>();val check={ checkpoint(id) }
        fun scan(name: String,expected: String?=null,open: ()->java.io.InputStream): ArchivePayload {
            copied=0;return ArchiveIO.scan(name,open,expected,check) { progress(id,"Checking $name",increment=it) }
        }
        val public=snapshot.finishedUri.toUri()
        payloads+=scan("Finished_Video.mp4",snapshot.finishedSha) {
            try { contentResolver.openInputStream(public) ?: error("Finished video unavailable.") }
            catch(e: Exception) {
                val backup=java.io.File(filesDir,"sessions/${snapshot.session.id}/export/output.mp4")
                if(backup.isFile && backup.canRead()) backup.inputStream() else throw IllegalStateException("Finished video is missing or unreadable.",e)
            }
        }
        require(payloads.first().size>0)
        val sourceStore=SessionFileStore(java.io.File(filesDir,"sessions"))
        for(segment in snapshot.selected(options)) {
            checkpoint(id)
            try {
                val file=catalog.source(snapshot,segment.path)
                sourceStore.validate(file.path)
                val facts=sourceStore.sourceFacts(file.path)
                if(segment.trim?.isTrimmed==true) require(kotlin.math.abs(facts.durationMs-segment.trim.sourceDurationMs)<=1 && segment.trim.trimEndMs<=facts.durationMs) { "Source duration no longer matches the saved trim." }
                durations[segment.id]=facts.durationMs
                payloads+=scan(snapshot.name(segment),snapshot.sourceHashes[segment.id]) { file.inputStream() }
                require(file.length()==facts.length && file.lastModified()==facts.modifiedMs) { "Source changed during preparation." }
            } catch(e: Exception) { throw IllegalStateException("Segment ${segment.order}: ${e.message}",e) }
        }
        return ArchiveIO.entries(payloads,PortableManifest.encode(snapshot,options,payloads,durations))
    }
    private fun execute(id: String) {
        android.os.Process.setThreadPriority(android.os.Process.THREAD_PRIORITY_BACKGROUND)
        val destination=ProjectDestination(this)
        var phase=ProjectState.Preparing
        var wakeLock: PowerManager.WakeLock?=null
        deadline=System.nanoTime()+java.util.concurrent.TimeUnit.HOURS.toNanos(5)
        try {
            wakeLock=getSystemService(PowerManager::class.java).newWakeLock(PowerManager.PARTIAL_WAKE_LOCK,"SkitCam:project").apply { acquire(5*60*60*1000L) }
            var j=ProjectJobs.read(this,id)
            ProjectJobs.update(this,id,ProjectState.Preparing,"Preparing portable project") { it.put("attempt",Math.addExact(it.optLong("attempt"),1L)) }
            checkpoint(id)
            var uri=j.getString("uri").toUri();require(j.getString("uri").isNotBlank()) { "Choose a destination." }
            val payloads: List<ArchivePayload>
            val receipt: ArchiveReceipt
            if(j.has("archiveSha")) {
                payloads=entries(j);receipt=destination.receipt(j)
            } else {
                uri=destination.prepare(id)
                payloads=prepare(id,j)
                val total=payloads.fold(0L) { n,e -> Math.addExact(n,e.size) }
                // SAF providers can hide free space; write/readback errors remain authoritative.
                require(getSystemService(android.os.storage.StorageManager::class.java).let { it.getAllocatableBytes(it.getUuidForPath(filesDir)) }>RecordingEngine.START_RESERVE) { "Phone storage is low. Free space and retry; originals are preserved." }
                ProjectJobs.update(this,id,ProjectState.Writing,"Writing project") { job ->
                    job.put("entries",JSONArray().apply { payloads.forEach { e -> put(JSONObject().put("name",e.name).put("size",e.size).put("crc",e.crc).put("sha256",e.sha256)) } })
                }
                phase=ProjectState.Writing;copied=0
                val descriptor=contentResolver.openFileDescriptor(uri,"wt") ?: error("Destination cannot be opened for writing.")
                val stream=ParcelFileDescriptor.AutoCloseOutputStream(descriptor);io.set(stream)
                receipt=try { ArchiveIO.write(stream,payloads,{ checkpoint(id) }) { progress(id,"Writing project",total,it) } } finally { io.compareAndSet(stream,null) }
                ProjectJobs.update(this,id,ProjectState.Verifying,"Checking archive") { it.put("archiveBytes",receipt.bytes).put("archiveSha",receipt.sha256) }
            }
            j=ProjectJobs.read(this,id)
            if(ProjectJobs.state(j)==ProjectState.Preparing) {
                ProjectJobs.update(this,id,ProjectState.Writing,"Using completed archive checkpoint")
                ProjectJobs.update(this,id,ProjectState.Verifying,"Checking saved archive")
            }
            phase=ProjectState.Verifying;copied=0
            val input=contentResolver.openInputStream(uri) ?: error("Cannot read the saved archive. Locate it or choose another destination.")
            io.set(input)
            try { ArchiveIO.verify(input,payloads,receipt,{ checkpoint(id) }) { progress(id,"Verifying project",receipt.bytes,it) } } finally { io.compareAndSet(input,null) }
            checkpoint(id)
            synchronized(ProjectJobs) {
                if(ProjectJobs.cancelRequested) throw ProjectCancelled()
                if(ProjectJobs.timedOut) throw InterruptedException()
                ProjectJobs.update(this,id,ProjectState.Finalising,"Finalising verified archive")
                phase=ProjectState.Finalising
            }
            val final=if(j.optBoolean("located")) uri else destination.finish(id,uri)
            j=ProjectJobs.read(this,id);j.put("uri",final.toString());ProjectJobs.write(this,j)
            if(!j.optBoolean("located")) destination.retain(final)
            val verify=contentResolver.openInputStream(final) ?: error("Final archive cannot be reopened.");io.set(verify)
            try { ArchiveIO.verifyHash(verify,receipt) { checkpoint(id) } } finally { io.compareAndSet(verify,null) }
            ProjectJobs.update(this,id,ProjectState.Completed,"Project exported and verified") { it.remove("interruptedPhase") }
        } catch(e: Exception) {
            android.util.Log.e("SkitCamProject","Project export failed",e)
            val cancelled=ProjectJobs.cancelRequested && phase!=ProjectState.Finalising
            val state=if(cancelled) ProjectState.Cancelled else if(ProjectJobs.timedOut || phase==ProjectState.Finalising) ProjectState.Interrupted else ProjectState.Failed
            val message=if(cancelled) "Project export cancelled. Originals are preserved." else e.message ?: "Project export interrupted. Originals are preserved."
            runCatching { ProjectJobs.update(this,id,state,message) {
                if(phase==ProjectState.Finalising) it.put("interruptedPhase",phase.name)
            } }
            if(cancelled) runCatching { destination.deletePartial(ProjectJobs.read(this,id)) }
        } finally {
            io.getAndSet(null)?.let { runCatching { it.close() } }
            if(wakeLock?.isHeld==true) wakeLock.release()
            Handler(mainLooper).post {
                // Finish this service lifecycle before exposing retry readiness.
                // An old onDestroy must never interrupt a newly launched attempt.
                jobId=null
                stopForeground(STOP_FOREGROUND_REMOVE);stopSelf()
                runCatching {
                    val result=ProjectJobs.read(this,id)
                    getSystemService(NotificationManager::class.java).notify(12,notification(id,result.getString("message"),false))
                }
                ProjectJobs.running.compareAndSet(id,null);ProjectJobs.progress.value=null;ProjectJobs.revision.value++
            }
        }
    }
    override fun onTimeout(startId: Int,fgsType: Int) {
        ProjectJobs.timedOut=true;closeIo()
        jobId?.let { id -> runCatching {
            val j=ProjectJobs.read(this,id)
            if(ProjectJobs.state(j).active) ProjectJobs.update(this,id,ProjectState.Interrupted,"Android stopped this long-running export. Originals are preserved.") { it.put("interruptedPhase",j.getString("state")) }
        } }
        stopSelf()
    }
    override fun onDestroy() {
        if(instance===this && jobId!=null && ProjectJobs.running.get()==jobId) { ProjectJobs.timedOut=true;closeIo() }
        if(instance===this) instance=null
        worker.shutdown();super.onDestroy()
    }
}
