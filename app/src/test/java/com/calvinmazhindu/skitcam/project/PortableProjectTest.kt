package com.calvinmazhindu.skitcam.project

import com.calvinmazhindu.skitcam.recording.*
import com.calvinmazhindu.skitcam.trim.TrimRange
import com.calvinmazhindu.skitcam.video.*
import org.json.*
import org.junit.Test
import org.junit.Assert.*
import java.io.*
import java.util.UUID
import java.util.zip.ZipFile

class PortableProjectTest {
    private val a=Segment(1,"/data/user/0/private/segment_001.mp4",3000,0,90,id="a",trim=TrimRange(3000,300,2500))
    private val b=Segment(2,"/data/user/0/private/segment_002.mp4",2200,0,90,kept=false,id="b",trim=TrimRange.full(2200))
    private val c=Segment(3,"/data/user/0/private/segment_003.mp4",2500,0,90,id="c",trim=TrimRange.full(2500))
    private fun journal(): JSONObject {
        val s=Session(UUID.randomUUID().toString(),100,0,RecordingState.Completed,listOf(a,b,c),playbackOrder=listOf("c","a"),captureProfile=CaptureProfile("private-camera-id",VideoSettings(),1920,1080))
        return JSONObject().put("version",2).put("id",UUID.randomUUID().toString()).put("sessionId",s.id).put("state","Completed").put("ready",true)
            .put("session",SessionCodec.encode(s)).put("uri","content://media/external_primary/video/media/99")
            .put("verifiedSha","a".repeat(64)).put("verification",JSONObject().put("sha256","a".repeat(64)))
            .put("spec",JSONArray().apply { for(seg in listOf(c,a)) put(JSONObject().put("id",seg.id).put("path",seg.path).put("duration",seg.trim!!.sourceDurationMs).put("start",seg.trim.trimStartMs).put("end",seg.trim.trimEndMs)) })
            .put("sources",JSONArray().apply { for(seg in listOf(c,a)) put(JSONObject().put("id",seg.id).put("sha256","b".repeat(64))) })
    }
    @Test fun savedOrderChronologyAndTrimsAreIndependent() {
        val j=journal();val original=j.toString();val p=ProjectSnapshot.fromJournal(original)
        assertEquals(listOf("c","a"),p.clips.map { it.id });assertEquals(listOf("a","b","c"),p.session.segments.map { it.id })
        assertEquals(300L,p.clips.last().range.trimStartMs);assertEquals(2500L,p.clips.last().range.trimEndMs)
        assertEquals(original,j.toString())
    }
    @Test fun originalsDefaultToKeptAndDiscardNeedsConsent() {
        val p=ProjectSnapshot.fromJournal(journal().toString())
        assertEquals(listOf("a","c"),p.selected(ProjectOptions()).map { it.id })
        assertEquals(listOf("a","b","c"),p.selected(ProjectOptions(true,true)).map { it.id })
        assertTrue(p.selected(ProjectOptions(false,false)).isEmpty())
        assertThrows(IllegalArgumentException::class.java) { ProjectOptions(false,true) }
    }
    @Test fun outerFilenamesRemainUnicodePortableBoundedAndCollisionSafe() {
        val id=UUID.randomUUID().toString()
        val name=projectFilename("家庭_é_🎬.partial.zip",id)
        assertEquals("家庭_é_🎬_$id.zip",name)
        assertNotEquals(name,projectFilename("家庭_é_🎬.partial.zip",UUID.randomUUID().toString()))
        val cleaned=projectFilename("../C:\\bad<name>|?*\n.zip",id)
        assertFalse(cleaned.contains(Regex("[<>:\"/\\\\|?*\\n]")))
        val long=projectFilename("🎬".repeat(1000),id)
        assertTrue(long.toByteArray(Charsets.UTF_8).size<240)
        assertFalse(long.contains('\uFFFD'))
        assertEquals("SkitCam_Project_$id.zip",projectFilename("<>\n.zip",id))
    }
    @Test fun singleAndHundredRetainedSegmentsKeepExactOrderAndUniqueNames() {
        for(count in listOf(1,100)) {
            val j=journal();val previous=SessionCodec.decode(j.getString("session"))
            val segments=(1..count).map { n -> a.copy(order=n,id="segment-$n",path="/data/private/segment_$n.mp4") }
            val order=segments.reversed()
            val session=previous.copy(segments=segments,playbackOrder=order.map { it.id })
            j.put("session",SessionCodec.encode(session)).put("spec",JSONArray().apply {
                order.forEach { put(JSONObject().put("id",it.id).put("path",it.path).put("duration",3000).put("start",300).put("end",2500)) }
            }).put("sources",JSONArray().apply { order.forEach { put(JSONObject().put("id",it.id).put("sha256","b".repeat(64))) } })
            val snapshot=ProjectSnapshot.fromJournal(j.toString())
            assertEquals(order.map { it.id },snapshot.clips.map { it.id })
            assertEquals(count,snapshot.selected(ProjectOptions()).map(snapshot::name).distinct().size)
        }
    }
    @Test fun noRetainedSegmentsCannotProduceAFakeProject() {
        val j=journal();val previous=SessionCodec.decode(j.getString("session"))
        j.put("session",SessionCodec.encode(previous.copy(segments=previous.segments.map { it.copy(kept=false) },playbackOrder=emptyList())))
            .put("spec",JSONArray()).put("sources",JSONArray())
        assertThrows(Exception::class.java) { ProjectSnapshot.fromJournal(j.toString()) }
    }
    @Test fun manifestIsPortableAndOmissionsKeepEditMetadata() {
        val p=ProjectSnapshot.fromJournal(journal().toString());val data=listOf(ArchiveIO.bytes("Finished_Video.mp4",byteArrayOf(1)))
        val text=PortableManifest.encode(p,ProjectOptions(false),data).toString(Charsets.UTF_8)
        assertFalse(text.contains("/data/"));assertFalse(text.contains("content://"));assertFalse(text.contains("private-camera-id"))
        val m=JSONObject(text);assertEquals(1,m.getInt("schemaVersion"));assertEquals(3,m.getJSONArray("segments").length())
        fun inspect(value: Any) {
            when(value) {
                is JSONObject -> value.keys().forEach { inspect(value.get(it)) }
                is JSONArray -> (0 until value.length()).forEach { inspect(value.get(it)) }
                is String -> {
                    assertFalse(value,value.contains("/data/"));assertFalse(value,value.contains("content://"))
                    assertFalse(value,value.contains("private-camera-id"))
                }
            }
        }
        inspect(m) // Inspect decoded strings: JSON may escape slashes in private paths/URIs.
        assertEquals("c",m.getJSONArray("playbackOrder").getString(0))
        assertEquals(300,m.getJSONArray("segments").getJSONObject(0).getJSONObject("savedTrim").getInt("trimStartMs"))
        assertFalse(m.getJSONArray("segments").getJSONObject(1).getBoolean("kept"));assertTrue(m.getJSONArray("segments").getJSONObject(1).isNull("originalPath"))
    }
    @Test fun includedNamesFollowChronologyAndPreserveStableIds() {
        val p=ProjectSnapshot.fromJournal(journal().toString());val opt=ProjectOptions(true,true)
        val files=listOf(ArchiveIO.bytes("Finished_Video.mp4",byteArrayOf(1)))+p.selected(opt).map { ArchiveIO.bytes(p.name(it),byteArrayOf(2)) }
        val m=JSONObject(PortableManifest.encode(p,opt,files).toString(Charsets.UTF_8))
        assertEquals("Original_Segments/segment_002.mp4",m.getJSONArray("segments").getJSONObject(1).getString("originalPath"))
        assertEquals("b",m.getJSONArray("segments").getJSONObject(1).getString("id"))
    }
    @Test fun legacyUntrimmedValuesRemainUnknownAndResolvedExportRangeSurvives() {
        val j=journal();val raw=JSONObject(j.getString("session")).put("version",3).apply { remove("captureProfile");remove("lens") }
        val segs=raw.getJSONArray("segments");for(i in 0 until segs.length()) segs.getJSONObject(i).remove("trim")
        j.put("version",1).put("session",raw.toString())
        val p=ProjectSnapshot.fromJournal(j.toString());val m=JSONObject(PortableManifest.encode(p,ProjectOptions(false),listOf(ArchiveIO.bytes("Finished_Video.mp4",byteArrayOf(1)))).toString(Charsets.UTF_8))
        assertTrue(m.getJSONArray("segments").getJSONObject(0).isNull("savedTrim"))
        assertEquals(300,m.getJSONArray("finishedTimeline").getJSONObject(1).getJSONObject("resolvedRange").getInt("trimStartMs"))
    }
    @Test fun incompleteOrChangedSnapshotsAreRejected() {
        for(state in listOf("Exporting","Publishing","Failed","Cancelled","Empty")) assertThrows(Exception::class.java) { ProjectSnapshot.fromJournal(journal().put("state",state).toString()) }
        assertThrows(Exception::class.java) { ProjectSnapshot.fromJournal(journal().put("verifiedSha","x").toString()) }
        val j=journal();j.getJSONArray("spec").getJSONObject(0).put("id","b")
        assertThrows(Exception::class.java) { ProjectSnapshot.fromJournal(j.toString()) }
    }
    @Test fun staleTrimAndFloatingTimestampAreRejected() {
        for(value in listOf<Any>(3.5,Long.MAX_VALUE,-1)) {
            val j=journal();j.getJSONArray("spec").getJSONObject(1).put("start",value)
            assertThrows(Exception::class.java) { ProjectSnapshot.fromJournal(j.toString()) }
        }
        val j=journal();j.getJSONArray("spec").getJSONObject(1).put("start",400)
        assertThrows(Exception::class.java) { ProjectSnapshot.fromJournal(j.toString()) }
    }
    @Test fun pathsRejectTraversalAbsoluteUnicodeAndDuplicateSeparators() {
        for(path in listOf("/private.mp4","../x","Original_Segments/../../x","a//b","a/./b","C:/x","x\\y","x\ny","é.mp4","folder/")) assertFalse(path,ArchiveIO.safePath(path))
        assertTrue(ArchiveIO.safePath("Original_Segments/segment_100.mp4"))
    }
    @Test fun archiveRoundTripChecksAllEntriesAndDesktopZipDirectory() {
        val payloads=listOf(ArchiveIO.bytes("Finished_Video.mp4",ByteArray(150000) { it.toByte() }))
        val entries=ArchiveIO.entries(payloads,"{\"schemaVersion\":1}".toByteArray())
        val out=ByteArrayOutputStream();val receipt=ArchiveIO.write(out,entries)
        assertEquals(out.size().toLong(),receipt.bytes);ArchiveIO.verify(out.toByteArray().inputStream(),entries,receipt)
        val file=kotlin.io.path.createTempFile(suffix=".zip").toFile()
        try { file.writeBytes(out.toByteArray());ZipFile(file).use { z -> assertEquals(3,z.size());assertTrue(z.getInputStream(z.getEntry("checksums.sha256")).bufferedReader().readText().contains("  session.json\n")) } } finally { file.delete() }
    }
    @Test fun checksumAndClosingDirectoryCorruptionAreRejected() {
        val entries=ArchiveIO.entries(listOf(ArchiveIO.bytes("Finished_Video.mp4",ByteArray(1000) { 42 })),"{}".toByteArray())
        val out=ByteArrayOutputStream();val r=ArchiveIO.write(out,entries);val bytes=out.toByteArray()
        for(index in listOf(100,bytes.size-1,bytes.size-30)) {
            val bad=bytes.clone();bad[index]=(bad[index].toInt() xor 1).toByte()
            assertThrows(Exception::class.java) { ArchiveIO.verify(bad.inputStream(),entries,r) }
        }
        assertThrows(Exception::class.java) { ArchiveIO.verify(bytes.copyOf(bytes.size-10).inputStream(),entries,r) }
    }
    @Test fun sourceMutationMissingSourceAndWriteFailureCannotSucceed() {
        var bytes=byteArrayOf(1,2,3);val entry=ArchiveIO.scan("x.mp4",{ bytes.inputStream() });bytes=byteArrayOf(3,2,1)
        assertThrows(Exception::class.java) { ArchiveIO.write(ByteArrayOutputStream(),listOf(entry)) }
        assertThrows(FileNotFoundException::class.java) { ArchiveIO.scan("missing.mp4",{ throw FileNotFoundException() }) }
        assertThrows(IOException::class.java) { ArchiveIO.write(object: OutputStream() { override fun write(n: Int) { throw IOException("provider full") } },listOf(ArchiveIO.bytes("a",byteArrayOf(1)))) }
    }
    @Test fun cancellationStopsScanningWritingAndVerification() {
        val entry=ArchiveIO.bytes("x",ByteArray(200000));val out=ByteArrayOutputStream();val r=ArchiveIO.write(out,listOf(entry))
        assertThrows(ProjectCancelled::class.java) { ArchiveIO.scan("x",{ byteArrayOf(1).inputStream() },checkpoint={ throw ProjectCancelled() }) }
        assertThrows(ProjectCancelled::class.java) { ArchiveIO.write(ByteArrayOutputStream(),listOf(entry),checkpoint={ throw ProjectCancelled() }) }
        assertThrows(ProjectCancelled::class.java) { ArchiveIO.verify(out.toByteArray().inputStream(),listOf(entry),r,checkpoint={ throw ProjectCancelled() }) }
    }
    @Test fun duplicateEntriesAndSizeOverflowAreRejected() {
        val e=ArchiveIO.bytes("x",byteArrayOf(1))
        assertThrows(Exception::class.java) { ArchiveIO.write(ByteArrayOutputStream(),listOf(e,e)) }
        assertThrows(ArithmeticException::class.java) { ArchiveIO.estimate(listOf(e.copy(size=Long.MAX_VALUE))) }
        assertThrows(IllegalArgumentException::class.java) { ArchiveIO.entries(listOf(e),ByteArray(4*1024*1024+1)) }
    }
    @Test fun footerCountIsCheckedEvenWhenWholeArchiveDigestIsRecomputed() {
        val entries=ArchiveIO.entries(listOf(ArchiveIO.bytes("x",byteArrayOf(1))),"{}".toByteArray())
        val out=ByteArrayOutputStream();ArchiveIO.write(out,entries)
        val bytes=out.toByteArray();bytes[bytes.size-12]=1
        val hash=ArchiveIO.scan("archive.zip",{ bytes.inputStream() })
        assertThrows(Exception::class.java) { ArchiveIO.verify(bytes.inputStream(),entries,ArchiveReceipt(bytes.size.toLong(),hash.sha256)) }
    }
    @Test fun outputCloseFailureCannotReturnAReceipt() {
        val out=object: ByteArrayOutputStream() { override fun close() { throw IOException("provider flush failed") } }
        assertThrows(IOException::class.java) { ArchiveIO.write(out,listOf(ArchiveIO.bytes("x",byteArrayOf(1)))) }
    }
    @Test fun failedEntryStillClosesTheDestinationWhenZipFinalisationFails() {
        var closed=false
        val output=object: ByteArrayOutputStream() { override fun close() { closed=true;super.close() } }
        val entry=ArchivePayload("missing.mp4",100,0,"a".repeat(64)) { throw FileNotFoundException("source removed") }
        assertThrows(IOException::class.java) { ArchiveIO.write(output,listOf(entry)) }
        assertTrue("A broken ZIP entry must not leak the provider descriptor",closed)
    }
    @Test fun storedPayloadCanDeclareZip64SizeWithoutIntegerTruncation() {
        val large=ArchivePayload("large.bin",0x1_0000_0001L,1,"0".repeat(64)) { ByteArrayInputStream(byteArrayOf()) }
        assertTrue(ArchiveIO.estimate(listOf(large))>0x1_0000_0000L)
    }
    @Test fun finalisationCannotBeCancelledAndCompletedCannotBeRetried() {
        assertTrue(projectTransitionAllowed(ProjectState.Verifying,ProjectState.Finalising))
        assertFalse(projectTransitionAllowed(ProjectState.Finalising,ProjectState.Cancelled))
        assertFalse(projectTransitionAllowed(ProjectState.Completed,ProjectState.Preparing))
        assertTrue(projectTransitionAllowed(ProjectState.Interrupted,ProjectState.Preparing))
    }
}
