package com.calvinmazhindu.skitcam.project

import android.content.*
import android.net.Uri
import android.os.Bundle
import android.provider.DocumentsContract
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.calvinmazhindu.skitcam.MainActivity
import com.calvinmazhindu.skitcam.export.*
import com.calvinmazhindu.skitcam.recording.*
import org.json.JSONObject
import org.junit.*
import org.junit.Assert.*
import org.junit.runner.RunWith
import java.io.File

/** Simulated DocumentsProvider failures on real Android; source recordings remain real/read-only. */
@RunWith(AndroidJUnit4::class)
class ProjectFailureDeviceTest {
    private val inst=InstrumentationRegistry.getInstrumentation()
    private val context get()=inst.targetContext
    private val authority get()=inst.context.packageName+".project-docs"
    private lateinit var scenario: ActivityScenario<MainActivity>
    private lateinit var activity: MainActivity
    private lateinit var raw: String
    private lateinit var original: ProjectSnapshot
    private lateinit var originalMetadata: ByteArray
    private lateinit var originalHashes: List<String>
    private fun await(label: String,seconds: Int=120,condition: ()->Boolean) {
        val end=System.nanoTime()+seconds*1_000_000_000L
        while(!condition() && System.nanoTime()<end) Thread.sleep(100)
        assertTrue("$label: ${ProjectJobs.progress.value}",condition())
    }
    private fun call(method: String,extras: Bundle?=null): Bundle = context.contentResolver.call(Uri.parse("content://$authority"),method,null,extras)!!
    private fun configure(mode: String) { call("test:configure",Bundle().apply { putString("mode",mode);putString("grantTo",context.packageName) }) }
    private fun create(mode: String,options: ProjectOptions=ProjectOptions(),source: String=raw): String {
        configure(mode)
        val id=ProjectJobs.create(context,source,options)
        val uri=Uri.parse(call("test:create",Bundle().apply { putString("name","Project_${id}.partial.zip") }).getString("uri")!!)
        context.contentResolver.takePersistableUriPermission(uri,Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION)
        val j=ProjectJobs.read(context,id);j.put("uri",uri.toString()).put("owned",true);ProjectJobs.write(context,j)
        return id
    }
    private fun launch(id: String) {
        val attempt=ProjectJobs.read(context,id).optLong("attempt")
        inst.runOnMainSync { ProjectExportService.launch(context,id) }
        await("service acknowledged retry") { ProjectJobs.read(context,id).optLong("attempt")>attempt }
    }
    private fun terminal(id: String): JSONObject {
        await("terminal $id",180) { ProjectJobs.state(ProjectJobs.read(context,id)) in setOf(ProjectState.Completed,ProjectState.Failed,ProjectState.Cancelled,ProjectState.Interrupted) && ProjectJobs.running.get()==null }
        val j=ProjectJobs.read(context,id)
        File(context.filesDir,"validation-m11/$id.json").writeText(j.toString(2));return j
    }
    @Before fun open() {
        check(context.packageName.endsWith(".m11validation"))
        val marker=File(context.filesDir,"validation-m11/fixture-session-id")
        check(marker.isFile) { "Run the system-picker fixture first." }
        raw=CompletedProjectCatalog(context).load(marker.readText());original=ProjectSnapshot.fromJournal(raw)
        originalMetadata=File(context.filesDir,"sessions/${original.session.id}/session.json").readBytes()
        originalHashes=original.session.segments.map { sha(File(it.path)) }
        scenario=ActivityScenario.launch(MainActivity::class.java);scenario.onActivity { activity=it }
        await("ready camera") { activity.cameraModel.streaming && activity.recordingModel.cameraReady }
    }
    @After fun close() {
        if(::scenario.isInitialized) scenario.close()
        if(::original.isInitialized) {
            assertArrayEquals(originalMetadata,File(context.filesDir,"sessions/${original.session.id}/session.json").readBytes())
            assertEquals(originalHashes,original.session.segments.map { sha(File(it.path)) })
        }
    }
    @Test fun bootstrapSimulatedProviderThroughSystemPicker() {
        val uri=ProjectTestDocuments.pick(inst,"SkitCam_Test_Bootstrap_${java.util.UUID.randomUUID()}.partial.zip",true)
        context.contentResolver.takePersistableUriPermission(uri,Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION)
        configure("normal")
        assertNotNull(call("test:stats"))
    }
    @Test fun allOriginalsAndFinishedOnlyPreservePortableMetadata() {
        for(options in listOf(ProjectOptions(true,true),ProjectOptions(false))) {
            val id=create("normal",options);launch(id);val j=terminal(id)
            assertEquals(j.toString(2),ProjectState.Completed,ProjectJobs.state(j))
            assertEquals(if(options.originals) 6 else 3,j.getJSONArray("entries").length())
            context.contentResolver.openInputStream(Uri.parse(j.getString("uri")))!!.use { input ->
                val file=File(context.filesDir,"validation-m11/${if(options.originals) "all-originals" else "finished-only"}.zip")
                file.outputStream().use { input.copyTo(it) }
                java.util.zip.ZipFile(file).use { z ->
                    val m=JSONObject(z.getInputStream(z.getEntry("session.json")).bufferedReader().readText())
                    assertEquals(options.originals,m.getJSONArray("segments").getJSONObject(1).getBoolean("originalIncluded"))
                    assertFalse(m.toString().contains("content://"));assertFalse(m.toString().contains("/data/"))
                    if(options.originals) original.session.segments.forEach { s -> assertEquals(sha(File(s.path)),ArchiveIO.scan(original.name(s),{ z.getInputStream(z.getEntry(original.name(s))) }).sha256) }
                }
            }
        }
    }
    @Test fun writeFailureThenRetryReusesOneDocument() {
        val id=create("fail-write");launch(id);assertEquals(ProjectState.Failed,ProjectJobs.state(terminal(id)))
        val count=call("test:stats").getInt("documents")
        configure("normal");launch(id);val j=terminal(id)
        assertEquals(j.toString(2),ProjectState.Completed,ProjectJobs.state(j));assertEquals(count,call("test:stats").getInt("documents"))
    }
    @Test fun unicodeFilenameAndChangingDocumentIdsKeepVerifiedAccess() {
        val id=create("new-id")
        val first=Uri.parse(ProjectJobs.read(context,id).getString("uri"))
        val renamed=DocumentsContract.renameDocument(context.contentResolver,first,"SkitCam_家庭_é.zip")!!
        context.contentResolver.takePersistableUriPermission(renamed,Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION)
        val j=ProjectJobs.read(context,id).put("uri",renamed.toString());ProjectJobs.write(context,j)
        val count=call("test:stats").getInt("documents")
        launch(id);val result=terminal(id)
        assertEquals(result.toString(2),ProjectState.Completed,ProjectJobs.state(result))
        assertNotEquals(renamed.toString(),result.getString("uri"))
        assertTrue(result.getString("filename").contains("家庭_é"))
        assertEquals(count,call("test:stats").getInt("documents"))
        assertTrue(context.contentResolver.persistedUriPermissions.any { it.uri.toString()==result.getString("uri") && it.isReadPermission })
    }
    @Test fun corruptReadbackBlocksCompletionAndRetryDoesNotRewrite() {
        val id=create("corrupt-read");launch(id);assertEquals(ProjectState.Failed,ProjectJobs.state(terminal(id)))
        val writes=call("test:stats").getInt("writes")
        configure("normal");launch(id);assertEquals(ProjectState.Completed,ProjectJobs.state(terminal(id)))
        assertEquals(writes,call("test:stats").getInt("writes"))
    }
    @Test fun unsupportedRenameFailsBeforeWriting() {
        val writes=call("test:stats").getInt("writes")
        val id=create("no-rename");launch(id);assertEquals(ProjectState.Failed,ProjectJobs.state(terminal(id)))
        assertEquals(writes,call("test:stats").getInt("writes"))
    }
    @Test fun cancellationKeepsSourcesAndRemovesOnlyOwnedPartial() {
        val id=create("slow");launch(id)
        await("writing") { ProjectJobs.state(ProjectJobs.read(context,id))==ProjectState.Writing }
        ProjectExportService.cancel(context,id)
        assertEquals(ProjectState.Cancelled,ProjectJobs.state(terminal(id)))
        assertTrue(activity.recordingModel.cameraReady)
    }
    @Test fun lostRenameResponseCanLocateWithoutDuplicateOutput() {
        val id=create("lost-rename");launch(id)
        val j=terminal(id);assertEquals(ProjectState.Interrupted,ProjectJobs.state(j));assertEquals("Finalising",j.getString("interruptedPhase"))
        val uri=call("test:stats").getString("lastUri")!!;val count=call("test:stats").getInt("documents")
        configure("normal");launch(id);assertEquals(ProjectState.Failed,ProjectJobs.state(terminal(id)))
        assertEquals(count,call("test:stats").getInt("documents"))
        call("test:grant",Bundle().apply { putString("id",DocumentsContract.getDocumentId(Uri.parse(uri))) })
        val located=ProjectJobs.read(context,id).put("uri",uri).put("owned",false).put("located",true);ProjectJobs.write(context,located)
        launch(id);assertEquals(ProjectState.Completed,ProjectJobs.state(terminal(id)))
        assertEquals(count,call("test:stats").getInt("documents"))
    }
    @Test fun missingCorruptAndChangedOriginalsAreNotSilentlyOmitted() {
        for(kind in listOf("missing","corrupt","changed")) {
            val token=java.util.UUID.randomUUID().leastSignificantBits.toULong().toString()
            val path=File(context.filesDir,"sessions/${original.session.id}/segment_$token.mp4")
            if(kind=="corrupt") path.writeText("invalid MP4 fixture")
            if(kind=="changed") {
                File(original.session.segments[0].path).copyTo(path)
                java.io.RandomAccessFile(path,"rw").use { f -> val pos=f.length()-1;f.seek(pos);val b=f.read();f.seek(pos);f.write(b xor 1) }
            }
            val j=JSONObject(raw);val s=JSONObject(j.getString("session"));s.getJSONArray("segments").getJSONObject(0).put("path",path.path);j.put("session",s.toString())
            val spec=j.getJSONArray("spec");for(i in 0 until spec.length()) if(spec.getJSONObject(i).getString("id")==original.session.segments[0].id) spec.getJSONObject(i).put("path",path.path)
            val id=create("normal",source=j.toString());launch(id);val result=terminal(id)
            assertEquals(result.toString(2),ProjectState.Failed,ProjectJobs.state(result));assertTrue(result.getString("message").contains("Segment 1"))
        }
    }
    @Test fun recordingHasPriorityWhileArchiveIsWriting() {
        val id=create("slow",ProjectOptions(true,true));launch(id)
        await("writing") { ProjectJobs.state(ProjectJobs.read(context,id))==ProjectState.Writing }
        val m=activity.recordingModel
        inst.runOnMainSync { m.start(0) }
        await("real recording") { m.snapshots.value.state==RecordingState.Recording && m.snapshots.value.activeDurationMs>=1600 }
        await("archive waiting for capture") { ProjectJobs.progress.value?.phase?.startsWith("Waiting for camera")==true }
        m.pause();await("paused") { m.snapshots.value.state==RecordingState.Paused }
        m.stop();await("new MP4 exported",180) { m.snapshots.value.state==RecordingState.Completed && ExportJournal.status.value?.state==ExportState.Completed && m.cameraReady }
        assertEquals(ProjectState.Completed,ProjectJobs.state(terminal(id)))
    }
    @Test fun platformZip64StreamsAboveFourGiBThroughDownloadsProvider() {
        org.junit.Assume.assumeTrue(InstrumentationRegistry.getArguments().getString("zip64")=="true")
        val size=0x1_0000_0000L+1024*1024
        val storage=context.getSystemService(android.os.storage.StorageManager::class.java)
        org.junit.Assume.assumeTrue("Keep a safe free-space reserve",storage.getAllocatableBytes(storage.getUuidForPath(context.filesDir))>size+2L*1024*1024*1024)
        val token=java.util.UUID.randomUUID().toString();val name="SkitCam_ZIP64_$token.zip"
        val lock=context.getSystemService(android.os.PowerManager::class.java).newWakeLock(android.os.PowerManager.PARTIAL_WAKE_LOCK,"SkitCam:zip64test")
        lock.acquire(10*60*1000L)
        try {
            val uri=ProjectTestDocuments.pick(inst,name.removeSuffix(".zip")+".partial.zip",false)
            context.contentResolver.takePersistableUriPermission(uri,Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION)
            fun source()=object: java.io.InputStream() {
                var left=size
                override fun read(): Int { if(left==0L) return -1;left--;return 90 }
                override fun read(b: ByteArray,off: Int,len: Int): Int {
                    if(left==0L) return -1;val n=minOf(left,len.toLong()).toInt();java.util.Arrays.fill(b,off,off+n,90.toByte());left-=n;return n
                }
            }
            val payload=ArchiveIO.scan("large.bin",::source)
            val entries=ArchiveIO.entries(listOf(payload),"{\"format\":\"skitcam-zip64-test\",\"schemaVersion\":1}".toByteArray())
            val receipt=ArchiveIO.write(android.os.ParcelFileDescriptor.AutoCloseOutputStream(context.contentResolver.openFileDescriptor(uri,"wt")!!),entries)
            assertTrue(receipt.bytes>0xffffffffL)
            ArchiveIO.verify(context.contentResolver.openInputStream(uri)!!,entries,receipt)
            val final=DocumentsContract.renameDocument(context.contentResolver,uri,name)!!
            ArchiveIO.verifyHash(context.contentResolver.openInputStream(final)!!,receipt)
            File(context.filesDir,"validation-m11/zip64.json").writeText(JSONObject().put("uri",final.toString()).put("filename",name).put("bytes",receipt.bytes).put("sha256",receipt.sha256).put("payloadBytes",size).put("payloadSha256",payload.sha256).put("generatedBinaryNotCameraFootage",true).toString(2))
        } finally { if(lock.isHeld) lock.release() }
    }
    @Test fun simulatedAndroidTimeoutRetainsPartialAndAllowsRetry() {
        val id=create("slow");launch(id)
        await("writing") { ProjectJobs.state(ProjectJobs.read(context,id))==ProjectState.Writing }
        val field=ProjectExportService::class.java.getDeclaredField("instance").apply { isAccessible=true }
        val service=field.get(null) as ProjectExportService
        inst.runOnMainSync { service.onTimeout(1,android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC) }
        assertEquals(ProjectState.Interrupted,ProjectJobs.state(terminal(id)))
        configure("normal");launch(id);assertEquals(ProjectState.Completed,ProjectJobs.state(terminal(id)))
    }
    @Test fun beginProcessDeathCheckpoint() {
        org.junit.Assume.assumeTrue(InstrumentationRegistry.getArguments().getString("projectCheckpoint")=="true")
        val id=create("slow",ProjectOptions(true,true));launch(id)
        await("writing") { ProjectJobs.state(ProjectJobs.read(context,id))==ProjectState.Writing }
        File(context.filesDir,"validation-m11/process-project-id").writeText(id)
    }
    @Test fun recoverProcessDeathCheckpoint() {
        org.junit.Assume.assumeTrue(InstrumentationRegistry.getArguments().getString("projectRecovery")=="true")
        val id=File(context.filesDir,"validation-m11/process-project-id").readText()
        val j=ProjectJobs.recover(context,id);assertEquals(ProjectState.Interrupted,ProjectJobs.state(j))
        configure("normal");launch(id);assertEquals(ProjectState.Completed,ProjectJobs.state(terminal(id)))
    }
    /** Housekeeping only for explicitly supplied, successful generated binary fixtures. */
    @Test fun retireVerifiedGeneratedZip64Fixtures() {
        val argument=InstrumentationRegistry.getArguments().getString("retireZip64")
        org.junit.Assume.assumeNotNull(argument)
        val list=org.json.JSONArray(String(android.util.Base64.decode(argument,android.util.Base64.DEFAULT),Charsets.UTF_8))
        require(list.length() in 1..2)
        for(i in 0 until list.length()) {
            val item=list.getJSONObject(i);val uri=Uri.parse(item.getString("uri"))
            require(item.getBoolean("generatedBinaryNotCameraFootage") && item.getLong("bytes")>0xffffffffL)
            require(uri.authority=="com.android.providers.downloads.documents")
            val name=ProjectDestination(context).name(uri)
            require(name==item.getString("filename") && name.matches(Regex("SkitCam_ZIP64_[0-9a-f-]{36}\\.zip")))
            ArchiveIO.verifyHash(context.contentResolver.openInputStream(uri)!!,ArchiveReceipt(item.getLong("bytes"),item.getString("sha256")))
            assertTrue("Only this verified test document may be retired",DocumentsContract.deleteDocument(context.contentResolver,uri))
            item.put("retiredFromPhone",true)
        }
        File(context.filesDir,"validation-m11/zip64-retirement.json").writeText(list.toString(2))
    }
}
