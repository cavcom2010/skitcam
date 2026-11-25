package com.calvinmazhindu.skitcam.export

import android.net.Uri
import android.os.SystemClock
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.filters.SdkSuppress
import androidx.test.platform.app.InstrumentationRegistry
import com.calvinmazhindu.skitcam.recording.Session
import com.calvinmazhindu.skitcam.recording.SessionCodec
import com.calvinmazhindu.skitcam.recording.commitRecordingMetadata
import java.io.File
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
@SdkSuppress(minSdkVersion = 30)
class PublicationHardeningDeviceTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context = instrumentation.targetContext
    private val ticket get() = checkNotNull(InstrumentationRegistry.getArguments().getString("ticket"))
        .also { require(it.matches(Regex("[a-zA-Z0-9-]+"))) }
    private val marker get() = File(context.filesDir, "publication-$ticket.json")
    private val phase get() = ExportFaults.Mode.valueOf(checkNotNull(InstrumentationRegistry.getArguments().getString("phase")))

    @Before fun guard() {
        check(context.packageName == ExportFaults.HARDENING_PACKAGE && ExportFaults.allowed(context))
        check(!ExportJournal.busy)
        cleanup()
    }

    @After fun cleanup() {
        ExportFaults.disarm()
        ExportJournal.cancelRequested = false
    }

    private fun start(session: Session, retry: Boolean = false) {
        ExportService.launch(context, session.id, retry)
    }

    private fun idle() {
        val deadline = SystemClock.elapsedRealtime() + 150_000
        while (ExportJournal.busy && SystemClock.elapsedRealtime() < deadline) Thread.sleep(25)
        assertFalse("Export still busy", ExportJournal.busy)
    }

    private data class Saved(val session: Session, val originalSha: String, val decoy: File)
    private fun saved(): Saved {
        val marker = JSONObject(marker.readText())
        val job = checkNotNull(ExportJournal.read(context, marker.getString("session")))
        return Saved(SessionCodec.decode(job.getString("session")), marker.getString("sourceSha"), File(marker.getString("decoy")))
    }

    private fun job(saved: Saved) = checkNotNull(ExportJournal.read(context, saved.session.id))
    private fun candidate(saved: Saved) = File(ExportJournal.directory(context, saved.session.id), "output.mp4")
    private fun originals(saved: Saved) {
        assertEquals(saved.originalSha, sha(File(saved.session.segments.single().path)))
        assertEquals("not export media", saved.decoy.readText())
    }

    private fun digest(uri: Uri) = checkNotNull(context.contentResolver.openInputStream(uri)).use(::hashGalleryStream)

    /** Mutation applies only to the test-owned Gallery row selected by assertInterrupted. */
    private fun flipByte(uri: Uri, offset: Long) {
        checkNotNull(context.contentResolver.openFileDescriptor(uri, "rw")).use {
            val fd = it.fileDescriptor
            android.system.Os.lseek(fd, offset, android.system.OsConstants.SEEK_SET)
            val old = ByteArray(1)
            check(android.system.Os.read(fd, old, 0, 1) == 1)
            android.system.Os.lseek(fd, offset, android.system.OsConstants.SEEK_SET)
            old[0] = (old[0].toInt() xor 1).toByte()
            check(android.system.Os.write(fd, old, 0, 1) == 1)
            android.system.Os.fsync(fd)
        }
    }

    private fun assertInterrupted(saved: Saved, at: ExportFaults.Mode): Uri? {
        val job = job(saved)
        assertEquals("Publishing", job.getString("state"))
        assertTrue(job.optBoolean("publicationStarted"))
        assertEquals(1, job.getInt("productionAttempts"))
        assertEquals(1, job.getInt("verificationAttempts"))
        assertEquals(job.getString("verifiedSha"), sha(candidate(saved)))
        originals(saved)
        val rows = fieldPublicationRows(context, job)
        if (at == ExportFaults.Mode.KILL_BEFORE_INSERT || at == ExportFaults.Mode.KILL_AFTER_INSERT_INTENT) {
            assertTrue(rows.isEmpty())
            assertTrue(job.optString("uri").isEmpty())
            assertEquals(at == ExportFaults.Mode.KILL_AFTER_INSERT_INTENT, job.optBoolean("publicationInsertAttempted"))
            return null
        }
        assertEquals(1, rows.size)
        val (uri, pending) = rows.single()
        assertEquals(at != ExportFaults.Mode.KILL_AFTER_VISIBLE, pending)
        assertTrue(job.optBoolean("publicationInsertAttempted"))
        if (at == ExportFaults.Mode.KILL_AFTER_INSERT) assertTrue(job.optString("uri").isEmpty())
        else assertEquals(uri.toString(), job.getString("uri"))
        if (at == ExportFaults.Mode.KILL_DURING_GALLERY_WRITE) {
            val size = checkNotNull(context.contentResolver.openFileDescriptor(uri, "r")).use { it.statSize }
            assertTrue("Expected a real partial write, got $size", size in 1 until candidate(saved).length())
        }
        if (at in setOf(ExportFaults.Mode.KILL_BEFORE_VISIBLE, ExportFaults.Mode.KILL_AFTER_VISIBLE))
            assertEquals(job.getString("verifiedSha"), digest(uri))
        return uri
    }

    private fun restore(saved: Saved) {
        ExportJournal.restore(context, saved.session)
        assertEquals("Failed", job(saved).getString("state"))
    }

    private fun assertCompleted(saved: Saved, expected: Uri?) {
        val job = job(saved)
        assertEquals(job.toString(), "Completed", job.getString("state"))
        assertEquals(1, job.getInt("productionAttempts"))
        assertEquals(1, job.getInt("verificationAttempts"))
        if (expected != null) assertEquals(expected.toString(), job.getString("uri"))
        assertFieldPublication(context, job, null)
        originals(saved)
    }

    @Test fun interruptPublication() {
        val fixture = ExportRecoveryFixtures.fixture(context)
        check(!marker.exists()) { "Use a unique ticket and fresh fixture." }
        commitRecordingMetadata(marker, JSONObject().put("session", fixture.session.id)
            .put("sourceSha", fixture.hashes.single()).put("decoy", fixture.decoy.path).toString())
        ExportJournal.request(context, fixture.session)
        ExportJournal.finalised(context, fixture.session)
        ExportFaults.arm(context, phase)
        start(fixture.session)
        idle()
        fail("Expected process death at $phase")
    }

    @Test fun interruptRecoveryWhileWriting() {
        val saved = saved()
        assertNotNull(assertInterrupted(saved, ExportFaults.Mode.KILL_AFTER_INSERT))
        restore(saved)
        ExportFaults.arm(context, ExportFaults.Mode.KILL_DURING_GALLERY_WRITE)
        start(saved.session, retry = true)
        idle()
        fail("Expected process death during the new process's partial copy")
    }

    @Test fun recoverAndRetryIdempotently() {
        val saved = saved()
        val original = assertInterrupted(saved, phase)
        restore(saved)
        if (phase in setOf(ExportFaults.Mode.KILL_AFTER_VISIBLE, ExportFaults.Mode.KILL_BEFORE_VISIBLE)) {
            // Exact rows, even still pending, need no new copy or local candidate.
            check(candidate(saved).delete())
            ExportFaults.arm(context, ExportFaults.Mode.FORBID_GALLERY_WRITE, ExportFaults.Mode.GALLERY_STORAGE_EXHAUSTED)
        }
        start(saved.session, retry = true)
        start(saved.session, retry = true)
        idle()
        assertCompleted(saved, original)
        val uri = job(saved).getString("uri")
        start(saved.session, retry = true)
        idle()
        assertCompleted(saved, Uri.parse(uri))
    }

    @Test fun unknownProviderResponsesDoNotTouchAnExistingVideo() {
        val saved = saved()
        val uri = checkNotNull(assertInterrupted(saved, ExportFaults.Mode.KILL_AFTER_VISIBLE))
        restore(saved)
        val verified = job(saved).getString("verifiedSha")
        for (fault in listOf(ExportFaults.Mode.NULL_GALLERY_QUERY, ExportFaults.Mode.EMPTY_GALLERY_QUERY,
            ExportFaults.Mode.FAILED_GALLERY_QUERY, ExportFaults.Mode.AMBIGUOUS_GALLERY_QUERY)) {
            ExportFaults.arm(context, fault, ExportFaults.Mode.FORBID_GALLERY_WRITE)
            start(saved.session, retry = true); idle()
            assertEquals(fault.name, "Failed", job(saved).getString("state"))
            assertTrue(job(saved).getString("message").contains("Cannot confirm"))
            assertEquals(listOf(uri to false), fieldPublicationRows(context, job(saved)))
            assertEquals(verified, digest(uri))
            originals(saved)
        }
        ExportFaults.arm(context, ExportFaults.Mode.FORBID_GALLERY_WRITE)
        start(saved.session, retry = true); idle()
        assertCompleted(saved, uri)
    }

    @Test fun possibleInsertWithEmptyQueryDoesNotInsertAgain() {
        val saved = saved()
        assertNull(assertInterrupted(saved, ExportFaults.Mode.KILL_AFTER_INSERT_INTENT))
        restore(saved)
        repeat(2) {
            start(saved.session, retry = true); idle()
            val job = job(saved)
            assertEquals("Failed", job.getString("state"))
            assertTrue(job.getString("message").contains("Cannot confirm"))
            assertTrue(fieldPublicationRows(context,job).isEmpty())
            assertEquals(job.getString("verifiedSha"), sha(candidate(saved)))
            originals(saved)
        }
    }

    @Test fun publishedDigestMismatchIsReadOnlyAndRepeatable() {
        val saved = saved()
        val uri = checkNotNull(assertInterrupted(saved, ExportFaults.Mode.KILL_AFTER_VISIBLE))
        restore(saved)
        flipByte(uri, candidate(saved).length() - 1)
        val modified = digest(uri)
        val verified = job(saved).getString("verifiedSha")
        assertNotEquals(verified, modified)
        ExportFaults.arm(context, ExportFaults.Mode.FORBID_GALLERY_WRITE)
        repeat(2) {
            start(saved.session, retry = true); idle()
            val job = job(saved)
            assertEquals("Failed", job.getString("state"))
            assertTrue(job.getString("message").contains("does not match"))
            assertEquals(listOf(uri to false), fieldPublicationRows(context, job))
            assertEquals(modified, digest(uri))
            assertEquals(verified, sha(candidate(saved)))
            originals(saved)
        }
    }

    @Test fun mismatchingPendingPrefixIsNotReplacedOrPublished() {
        val saved = saved()
        val uri = checkNotNull(assertInterrupted(saved, ExportFaults.Mode.KILL_DURING_GALLERY_WRITE))
        restore(saved)
        flipByte(uri, 0)
        val modified = digest(uri)
        start(saved.session, retry = true); idle()
        val job = job(saved)
        assertEquals("Failed", job.getString("state"))
        assertTrue(job.getString("message").contains("not overwritten"))
        assertEquals(listOf(uri to true), fieldPublicationRows(context, job))
        assertEquals(modified, digest(uri))
        assertEquals(job.getString("verifiedSha"), sha(candidate(saved)))
        originals(saved)
    }
}
