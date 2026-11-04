package com.calvinmazhindu.skitcam.project

import android.content.*
import android.net.Uri
import androidx.core.net.toUri
import android.os.Build
import android.provider.DocumentsContract
import android.provider.MediaStore
import android.util.AtomicFile
import com.calvinmazhindu.skitcam.recording.*
import kotlinx.coroutines.flow.MutableStateFlow
import org.json.JSONObject
import java.io.File
import java.text.SimpleDateFormat
import java.util.*
import java.util.concurrent.atomic.AtomicReference

/** No AtomicFile.openRead here: cataloguing must not recover/rewrite an existing session. */
class CompletedProjectCatalog(private val context: Context) {
    fun load(sessionId: String): String {
        UUID.fromString(sessionId)
        val base=File(context.filesDir,"sessions/$sessionId/export/job.json")
        val committed=File(base.path+".bak").takeIf { it.isFile } ?: base
        require(committed.isFile && committed.length() in 1..4L*1024*1024) { "Completed project metadata is unavailable." }
        val text=committed.inputStream().bufferedReader().use { reader ->
            val buffer=CharArray(4096);val result=StringBuilder()
            while(true) { val n=reader.read(buffer);if(n<0) break;require(result.length+n<=4*1024*1024);result.append(buffer,0,n) };result.toString()
        }
        require(ProjectSnapshot.fromJournal(text).session.id==sessionId)
        return text
    }
    fun find(uri: Uri): String? {
        val key=identity(uri) ?: return null
        val matches=File(context.filesDir,"sessions").listFiles().orEmpty().mapNotNull { dir ->
            runCatching { load(dir.name).takeIf { identity(ProjectSnapshot.fromJournal(it).finishedUri.toUri())==key } }.getOrNull()
        }
        return matches.singleOrNull()
    }
    private fun identity(uri: Uri): String? {
        val p=uri.pathSegments
        if(uri.scheme!="content" || uri.authority!="media" || p.size!=4 || p[1]!="video" || p[2]!="media" || p[3].toLongOrNull()==null) return null
        var volume=p[0]
        if(Build.VERSION.SDK_INT>=29 && volume=="external") {
            volume=runCatching { context.contentResolver.query(uri,arrayOf(MediaStore.MediaColumns.VOLUME_NAME),null,null,null)?.use {
                if(it.moveToFirst()) it.getString(0) else null
            } }.getOrNull() ?: return null
        }
        return "$volume/video/${p[3]}"
    }
    fun source(snapshot: ProjectSnapshot, path: String): File {
        val sessions=File(context.filesDir,"sessions").canonicalFile
        val directory=File(sessions,snapshot.session.id).canonicalFile
        val file=File(path).canonicalFile
        require(directory.parentFile==sessions && file.parentFile==directory && file.name.matches(Regex("segment_[0-9]+\\.mp4"))) { "Invalid original segment location." }
        require(file.isFile && file.canRead() && file.length()>0) { "Original segment is missing or unreadable: ${file.name}" }
        return file
    }
}

data class ProjectProgress(val id: String,val phase: String,val bytes: Long=0,val total: Long?=null)
object ProjectJobs {
    val revision=MutableStateFlow(0L)
    val progress=MutableStateFlow<ProjectProgress?>(null)
    val running=AtomicReference<String?>(null)
    @Volatile var cancelRequested=false
    @Volatile var timedOut=false
    private fun root(context: Context)=File(context.filesDir,"project-exports")
    fun directory(context: Context,id: String): File { UUID.fromString(id);return File(root(context),id) }
    @Synchronized fun read(context: Context,id: String): JSONObject {
        val f=File(directory(context,id),"job.json")
        require(f.length()<=8L*1024*1024)
        return AtomicFile(f).openRead().bufferedReader().use { JSONObject(it.readText()) }.also {
            require(it.getInt("version")==1 && it.getString("id")==id);ProjectState.valueOf(it.getString("state"))
        }
    }
    @Synchronized fun write(context: Context,j: JSONObject) {
        val bytes=j.toString(2).toByteArray(Charsets.UTF_8)
        require(bytes.size<=8*1024*1024) { "Project metadata is too large to archive safely. Originals are preserved." }
        val dir=directory(context,j.getString("id"));check(dir.isDirectory || dir.mkdirs())
        val atomic=AtomicFile(File(dir,"job.json"));val out=atomic.startWrite()
        try { out.write(bytes);atomic.finishWrite(out) }
        catch(e: Exception) { atomic.failWrite(out);throw e }
        revision.value++
    }
    @Synchronized fun create(context: Context,raw: String,options: ProjectOptions): String {
        require(running.get()==null) { "A project export is already running." }
        val snapshot=ProjectSnapshot.fromJournal(raw);val id=UUID.randomUUID().toString()
        val date=SimpleDateFormat("yyyyMMdd_HHmmss",Locale.ROOT).format(Date())
        val j=JSONObject().put("version",1).put("id",id).put("sessionId",snapshot.session.id).put("createdAt",System.currentTimeMillis())
            .put("state",ProjectState.WaitingDestination.name).put("snapshot",raw).put("originals",options.originals).put("discarded",options.discarded)
            .put("filename","SkitCam_Project_${date}_$id.zip").put("uri","").put("message","Choose where to save the project.")
        write(context,j);return id
    }
    @Synchronized fun update(context: Context,id: String,state: ProjectState,message: String="",change: (JSONObject)->Unit = {}) {
        val j=read(context,id);require(projectTransitionAllowed(ProjectState.valueOf(j.getString("state")),state)) { "Invalid project transition." }
        change(j);j.put("state",state.name).put("message",message);write(context,j)
    }
    @Synchronized fun recover(context: Context,id: String): JSONObject {
        val j=read(context,id);val phase=ProjectState.valueOf(j.getString("state"))
        if(phase.active && running.get()!=id) update(context,id,ProjectState.Interrupted,"Project export was interrupted. Originals are preserved.") { it.put("interruptedPhase",phase.name) }
        return read(context,id)
    }
    fun latest(context: Context): String? = root(context).listFiles().orEmpty().mapNotNull { runCatching { read(context,it.name) }.getOrNull() }
        .maxByOrNull { it.getLong("createdAt") }?.getString("id")
    fun options(j: JSONObject)=ProjectOptions(j.getBoolean("originals"),j.getBoolean("discarded"))
    fun state(j: JSONObject)=ProjectState.valueOf(j.getString("state"))
}

/** Only a newly created document belonging to this job may be renamed/written. */
class ProjectDestination(private val context: Context) {
    private val resolver=context.contentResolver
    fun name(uri: Uri): String = resolver.query(uri,arrayOf(DocumentsContract.Document.COLUMN_DISPLAY_NAME),null,null,null)?.use {
        require(it.moveToFirst());it.getString(0)
    } ?: error("Destination is unavailable.")
    fun requireCapabilities(uri: Uri) {
        require(DocumentsContract.isDocumentUri(context,uri)) { "Choose a document destination." }
        resolver.query(uri,arrayOf(DocumentsContract.Document.COLUMN_FLAGS),null,null,null)?.use {
            require(it.moveToFirst())
            val flags=it.getLong(0)
            require(flags and DocumentsContract.Document.FLAG_SUPPORTS_WRITE.toLong()!=0L && flags and DocumentsContract.Document.FLAG_SUPPORTS_RENAME.toLong()!=0L) {
                "This destination cannot safely write and rename an archive. Choose local storage."
            }
        } ?: error("Cannot inspect destination capabilities.")
        resolver.openInputStream(uri)?.use { it.read() } ?: error("Destination does not support verification readback.")
    }
    fun prepare(id: String): Uri {
        var j=ProjectJobs.read(context,id);val uri=j.getString("uri").toUri();require(j.optBoolean("owned"))
        requireCapabilities(uri)
        val actual=name(uri)
        val final=projectFilename(actual,id)
        val partial=final.removeSuffix(".zip")+".partial.zip"
        j.put("filename",final);ProjectJobs.write(context,j)
        val ready=if(actual==partial) uri else DocumentsContract.renameDocument(resolver,uri,partial) ?: error("Could not prepare an incomplete archive name.")
        j=ProjectJobs.read(context,id);j.put("uri",ready.toString());ProjectJobs.write(context,j)
        retain(ready)
        return ready
    }
    fun retain(uri: Uri) {
        resolver.takePersistableUriPermission(uri,Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION)
    }
    fun finish(id: String,uri: Uri): Uri {
        val j=ProjectJobs.read(context,id)
        return if(name(uri)==j.getString("filename")) uri else DocumentsContract.renameDocument(resolver,uri,j.getString("filename")) ?: error("Archive is verified but final naming failed.")
    }
    fun receipt(j: JSONObject)=ArchiveReceipt(j.getLong("archiveBytes"),j.getString("archiveSha"))
    fun deletePartial(j: JSONObject) {
        if(!j.optBoolean("owned") || j.optString("uri").isBlank()) return
        val uri=j.getString("uri").toUri()
        if(runCatching { name(uri).endsWith(".partial.zip") }.getOrDefault(false)) runCatching { DocumentsContract.deleteDocument(resolver,uri) }
    }
}
