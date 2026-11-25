package com.calvinmazhindu.skitcam.export

import android.net.Uri
import android.os.SystemClock
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.calvinmazhindu.skitcam.recording.SessionCodec
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.security.MessageDigest

/**
 * Two-phase process-death recovery. Phase one is expected to be killed by its injected
 * fault, so the driver runs each pair separately and ignores that phase's test result.
 */
@RunWith(AndroidJUnit4::class)
class ProcessDeathRecoveryDeviceTest {
    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private val marker get() = File(context.filesDir, "m15b31a-death.json")

    @Before fun guard() {
        check(ExportFaults.allowed(context))
        check(!ExportJournal.busy)
        ExportFaults.disarm()
    }
    @After fun disarm() { ExportFaults.disarm() }

    private fun awaitIdle(limitMs: Long) {
        val end = SystemClock.elapsedRealtime() + limitMs
        while (ExportJournal.busy && SystemClock.elapsedRealtime() < end) Thread.sleep(25)
        assertFalse(ExportJournal.busy)
    }

    private fun phaseOne(mode: ExportFaults.Mode, label: String) {
        val f = ExportRecoveryFixtures.fixture(context)
        marker.writeText(JSONObject().put("session", f.session.id).put("phase", label)
            .put("source", f.session.segments.single().path)
            .put("sha256", f.hashes.single()).put("decoy", f.decoy.path).toString())
        val duringRecheck=mode==ExportFaults.Mode.KILL_DURING_RECHECK
        ExportFaults.arm(context, if(duringRecheck) ExportFaults.Mode.VIDEO_INIT else mode)
        ExportJournal.request(context, f.session)
        ExportJournal.finalised(context, f.session)
        ExportService.launch(context, f.session.id)
        if(duringRecheck) {
            awaitIdle(120_000)
            val failed=checkNotNull(ExportJournal.read(context,f.session.id))
            assertEquals("Failed",failed.getString("state"))
            assertFalse(failed.optBoolean("candidateReverified"))
            assertNotNull(ExportCandidate.from(failed))
            assertTrue(fieldPublicationRows(context,failed).isEmpty())
            ExportFaults.arm(context,mode)
            ExportService.launch(context,f.session.id,retry=true)
        }
        awaitIdle(120_000)
        fail("The injected process death did not occur during $label")
    }

    private fun phaseTwo(label: String, expectedProduction: Int, expectCheckpoint: Boolean,
        expectVerification: Int, expectConsumed: Boolean = false) {
        check(marker.isFile) { "Run the matching phase one first." }
        val saved = JSONObject(marker.readText())
        assertEquals(label, saved.getString("phase"))
        ExportFaults.disarm()
        val id = saved.getString("session")
        val before = checkNotNull(ExportJournal.read(context, id))
        val session = SessionCodec.decode(before.getString("session"))
        assertEquals(if(expectConsumed) "Verifying" else "Exporting", before.getString("state"))
        assertEquals(expectCheckpoint, ExportCandidate.from(before) != null)
        assertEquals(expectConsumed,before.optBoolean("candidateReverified"))
        assertTrue(fieldPublicationRows(context,before).isEmpty())
        ExportJournal.restore(context, session)
        val restored=checkNotNull(ExportJournal.read(context,id))
        assertEquals(ExportState.Failed.name,restored.getString("state"))
        if(expectConsumed) assertEquals(ExportCandidate.RETRY_REBUILD_MESSAGE,restored.getString("message"))
        else if(expectCheckpoint) assertEquals(ExportCandidate.RETRY_RECHECK_MESSAGE,restored.getString("message"))
        ExportService.launch(context, id, retry = true)
        awaitIdle(300_000)
        val completed = checkNotNull(ExportJournal.read(context, id))
        assertEquals(completed.toString(), "Completed", completed.getString("state"))
        assertEquals(expectedProduction, completed.optInt("productionAttempts", -1))
        assertEquals(expectVerification, completed.optInt("verificationAttempts", -1))
        assertEquals(saved.getString("sha256"), sha(File(saved.getString("source"))))
        assertTrue(File(saved.getString("decoy")).isFile)
        val rows = fieldPublicationRows(context, completed)
        assertEquals(1, rows.size)
        assertFalse(rows.single().second)
        val uri = Uri.parse(completed.getString("uri"))
        assertEquals(uri, rows.single().first)
        assertEquals(completed.getString("verifiedSha"), context.contentResolver.openInputStream(uri)!!.use { input ->
            val digest = MessageDigest.getInstance("SHA-256");val buffer = ByteArray(65536)
            while (true) { val size = input.read(buffer);if (size < 0) break;digest.update(buffer, 0, size) }
            digest.digest().joinToString("") { "%02x".format(it) }
        })
    }

    @Test fun phase1KillAfterCandidateCheckpoint() =
        phaseOne(ExportFaults.Mode.KILL_AFTER_CHECKPOINT, "afterCheckpoint")

    @Test fun phase1KillBeforeCandidateCheckpoint() =
        phaseOne(ExportFaults.Mode.KILL_BEFORE_CHECKPOINT, "beforeCheckpoint")

    @Test fun phase1KillDuringCandidateRecheck() =
        phaseOne(ExportFaults.Mode.KILL_DURING_RECHECK, "duringRecheck")

    @Test fun phase2AfterCheckpointReusesTheCandidate() =
        phaseTwo("afterCheckpoint", expectedProduction = 1, expectCheckpoint = true, expectVerification = 1)

    @Test fun phase2BeforeCheckpointRebuildsSafely() =
        phaseTwo("beforeCheckpoint", expectedProduction = 2, expectCheckpoint = false, expectVerification = 1)

    @Test fun phase2ConsumedCandidateRebuildsSafely() =
        phaseTwo("duringRecheck", expectedProduction = 2, expectCheckpoint = true,
            expectVerification = 3, expectConsumed = true)
}
