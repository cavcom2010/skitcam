package com.calvinmazhindu.skitcam.export

import android.os.SystemClock
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.filters.SdkSuppress
import androidx.test.platform.app.InstrumentationRegistry
import com.calvinmazhindu.skitcam.recording.SessionCodec
import com.calvinmazhindu.skitcam.recording.commitRecordingMetadata
import com.calvinmazhindu.skitcam.video.VideoProfilePolicy
import java.io.File
import org.json.JSONArray
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
@SdkSuppress(minSdkVersion = 30)
class RetryHardeningDeviceTest {
    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private val marker: File get() {
        val run = checkNotNull(InstrumentationRegistry.getArguments().getString("ticket"))
        require(run.matches(Regex("[a-zA-Z0-9-]+")))
        return File(context.filesDir, "hardening-fallback-death-$run.json")
    }
    private fun fastFixture() = ExportRecoveryFixtures.fixture(context, trim = false, count = 2)

    @Before fun guard() {
        check(context.packageName == ExportFaults.HARDENING_PACKAGE && ExportFaults.allowed(context))
        check(!ExportJournal.busy)
        reset()
    }

    @After fun reset() {
        ExportFaults.disarm()
        ExportProcessor.availableBytesForTests = null
        ExportJournal.cancelRequested = false
    }

    private fun begin(f: RecoveryFixture) {
        ExportJournal.request(context, f.session)
        ExportJournal.finalised(context, f.session)
        ExportService.launch(context, f.session.id)
        idle()
    }

    private fun retry(f: RecoveryFixture) {
        ExportService.launch(context, f.session.id, retry = true)
        idle()
    }

    private fun idle() {
        val end = SystemClock.elapsedRealtime() + 150_000
        while (ExportJournal.busy && SystemClock.elapsedRealtime() < end) Thread.sleep(25)
        assertFalse("Export still busy", ExportJournal.busy)
    }

    private fun job(f: RecoveryFixture) = checkNotNull(ExportJournal.read(context, f.session.id))
    private fun output(f: RecoveryFixture) = File(ExportJournal.directory(context, f.session.id), "output.mp4")
    private fun assertOriginals(f: RecoveryFixture) {
        assertEquals(f.hashes, f.session.segments.map { sha(File(it.path)) })
        assertEquals("not export media", f.decoy.readText())
    }

    private fun assertNoPublication(f: RecoveryFixture, state: String = "Failed"): JSONObject {
        val job = job(f)
        assertEquals(job.toString(), state, job.getString("state"))
        assertTrue(fieldPublicationRows(context, job).isEmpty())
        assertOriginals(f)
        return job
    }

    private fun assertCompleted(f: RecoveryFixture, productions: Int, verifications: Int) {
        val job = job(f)
        assertEquals(job.toString(), "Completed", job.getString("state"))
        assertEquals(productions, job.getInt("productionAttempts"))
        assertEquals(verifications, job.getInt("verificationAttempts"))
        assertFieldPublication(context, job, null)
        assertOriginals(f)
    }

    @Test fun incompatibleRuntimeAvcFallsBackBeforeThereIsACandidate() {
        val f = fastFixture()
        ExportFaults.arm(context, ExportFaults.Mode.FORMAT_WITHOUT_AVC_CONFIG)
        begin(f)
        val j = job(f)
        assertEquals(ExportPath.RENDER.name, j.getString("selectedPath"))
        assertTrue(j.getString("pathReason").contains("Compatible whole AVC"))
        assertTrue(j.getString("fallbackReason").contains("compatible encoded AVC"))
        assertTrue(j.getBoolean("renderFallbackUsed"))
        assertFalse(output(f).parentFile!!.listFiles()!!.any { it.name.startsWith("rejected-") })
        assertCompleted(f, 2, 1)
    }

    @Test fun persistentRendererIoDoesNotLoopOrReturnToPassthrough() {
        val f = fastFixture()
        ExportFaults.arm(context, ExportFaults.Mode.FORMAT_WITHOUT_AVC_CONFIG, ExportFaults.Mode.RENDER_PRODUCTION_IO)
        begin(f)
        val first = assertNoPublication(f)
        assertEquals(2, first.getInt("productionAttempts"))
        assertEquals(ExportPath.RENDER.name, first.getString("selectedPath"))
        assertFalse(first.has("verificationAttempts"))
        assertFalse(output(f).exists())
        retry(f)
        val second = assertNoPublication(f)
        assertEquals(first.getString("id"), second.getString("id"))
        assertEquals(3, second.getInt("productionAttempts"))
        assertFalse(second.has("verificationAttempts"))
        ExportFaults.disarm()
        retry(f)
        assertCompleted(f, 4, 1)
    }

    @Test fun cancelAtRuntimePassthroughBoundaryDoesNotFallBack() {
        val f = fastFixture()
        ExportFaults.arm(context, ExportFaults.Mode.CANCEL_AT_PASSTHROUGH)
        begin(f)
        val j = assertNoPublication(f, "Cancelled")
        assertEquals(ExportPath.VIDEO_PASSTHROUGH_AUDIO_RENDER.name, j.getString("selectedPath"))
        assertFalse(j.optBoolean("renderFallbackUsed"))
        assertEquals(1, j.getInt("productionAttempts"))
        ExportFaults.disarm()
        retry(f)
        assertCompleted(f, 2, 1)
        assertEquals(ExportPath.VIDEO_PASSTHROUGH_AUDIO_RENDER.name, job(f).getString("selectedPath"))
    }

    @Test fun codecCreationIoAfterPassthroughDoesNotRenderOnRetry() {
        val f = fastFixture()
        ExportFaults.arm(context, ExportFaults.Mode.VIDEO_INIT)
        begin(f)
        val j = assertNoPublication(f)
        assertFalse(j.optBoolean("renderFallbackUsed"))
        assertEquals(ExportPath.VIDEO_PASSTHROUGH_AUDIO_RENDER.name, j.getString("selectedPath"))
        assertEquals(ExportCandidate.FAILURE_DECODER, j.getString("lastVerifyFailure"))
        assertTrue(j.getString("lastVerifyFailureDetail").contains("Injected codec creation I/O"))
        ExportFaults.disarm()
        retry(f)
        assertCompleted(f, 1, 2)
    }

    @Test fun fallbackVerificationRecordsCodecIoAndThenUsesTheSameCandidate() {
        val f = fastFixture()
        ExportFaults.arm(context, ExportFaults.Mode.FORMAT_WITHOUT_AVC_CONFIG, ExportFaults.Mode.VIDEO_INIT)
        begin(f)
        val j = assertNoPublication(f)
        assertEquals(ExportPath.RENDER.name, j.getString("selectedPath"))
        assertEquals(ExportCandidate.FAILURE_DECODER, j.getString("lastVerifyFailure"))
        assertEquals(2, j.getInt("productionAttempts"))
        assertEquals(1, j.getInt("verificationAttempts"))
        assertNotNull(ExportCandidate.from(j))
        ExportFaults.arm(context, ExportFaults.Mode.RENDER_PRODUCTION_IO)
        retry(f)
        assertCompleted(f, 2, 2)
        assertFalse(job(f).has("lastVerifyFailure"))
    }

    @Test fun fallbackVerificationRecordsExtractorIoAsOtherAndRebuilds() {
        val f = fastFixture()
        ExportFaults.arm(context, ExportFaults.Mode.FORMAT_WITHOUT_AVC_CONFIG, ExportFaults.Mode.VERIFIER_EXTRACTOR_IO)
        begin(f)
        val j = assertNoPublication(f)
        assertEquals(2, j.getInt("productionAttempts"))
        assertEquals(ExportCandidate.FAILURE_OTHER, j.getString("lastVerifyFailure"))
        assertTrue(j.getString("lastVerifyFailureDetail").contains("extractor input"))
        ExportFaults.disarm()
        retry(f)
        assertCompleted(f, 3, 2)
    }

    @Test fun fallbackVerificationRecordsIntegrityFailureAndRebuilds() {
        val f = fastFixture()
        ExportFaults.arm(context, ExportFaults.Mode.FORMAT_WITHOUT_AVC_CONFIG, ExportFaults.Mode.WRONG_VERIFICATION_DURATION)
        begin(f)
        val j = assertNoPublication(f)
        assertEquals(2, j.getInt("productionAttempts"))
        assertEquals(ExportCandidate.FAILURE_INTEGRITY, j.getString("lastVerifyFailure"))
        assertTrue(j.getString("lastVerifyFailureDetail").contains("duration"))
        ExportFaults.disarm()
        retry(f)
        assertCompleted(f, 3, 2)
    }

    private fun failedCandidate(): RecoveryFixture {
        val f = ExportRecoveryFixtures.fixture(context)
        ExportFaults.arm(context, ExportFaults.Mode.VIDEO_INIT)
        begin(f)
        val j = assertNoPublication(f)
        assertEquals(ExportCandidate.FAILURE_DECODER, j.getString("lastVerifyFailure"))
        assertNotNull(ExportCandidate.from(j))
        ExportFaults.disarm()
        return f
    }

    @Test fun oneCopyBudgetAllowsReuseButNotFreshProduction() {
        val f = failedCandidate()
        val cap = galleryCopyBytes(output(f).length())
        val profile = checkNotNull(f.session.captureProfile)
        val spec = ExportSpec.from(f.session) { inspect(File(it.path)).duration }
        assertTrue(cap < VideoProfilePolicy.reserveBytes(spec.durationMs, profile.width, profile.height, profile.settings.fps))
        ExportProcessor.availableBytesForTests = cap
        retry(f)
        assertCompleted(f, 1, 2)
    }

    @Test fun notEnoughForPublicationDoesNotConsumeTheRecheck() {
        val f = failedCandidate()
        val checkpoint = ExportCandidate.from(job(f))
        ExportProcessor.availableBytesForTests = galleryCopyBytes(output(f).length()) - 1
        retry(f)
        val result = assertNoPublication(f)
        assertTrue(result.getString("message").contains("Insufficient space"))
        assertEquals(checkpoint, ExportCandidate.from(result))
        assertFalse(result.optBoolean("candidateReverified"))
        assertEquals(1, result.getInt("verificationAttempts"))
        ExportProcessor.availableBytesForTests = null
        retry(f)
        assertCompleted(f, 1, 2)
    }

    @Test fun spaceCanRunOutAfterVerificationButBeforePublication() {
        val f = ExportRecoveryFixtures.fixture(context)
        ExportFaults.arm(context, ExportFaults.Mode.GALLERY_STORAGE_EXHAUSTED)
        begin(f)
        val result = assertNoPublication(f)
        assertTrue(result.getString("message").contains("Insufficient space"))
        assertFalse(result.optBoolean("publicationInsertAttempted"))
        assertEquals(sha(output(f)), result.getString("verifiedSha"))
        ExportFaults.disarm()
        retry(f)
        assertCompleted(f, 1, 1)
    }

    @Test fun invalidatedCandidateNeedsAFullRebuildBudget() {
        val f = failedCandidate()
        ExportProcessor.availableBytesForTests = galleryCopyBytes(output(f).length())
        check(output(f).delete())
        retry(f)
        val result = assertNoPublication(f)
        assertTrue(result.getString("message").contains("Insufficient space"))
        assertEquals(1, result.getInt("productionAttempts"))
        assertEquals(1, result.getInt("verificationAttempts"))
        ExportProcessor.availableBytesForTests = null
        retry(f)
        assertCompleted(f, 2, 2)
    }

    @Test fun interruptAfterDurableFallbackSelection() {
        val f = fastFixture()
        check(!marker.exists())
        commitRecordingMetadata(marker, JSONObject().put("session", f.session.id)
            .put("hashes", JSONArray(f.hashes)).put("decoy", f.decoy.path).toString())
        ExportFaults.arm(context, ExportFaults.Mode.FORMAT_WITHOUT_AVC_CONFIG, ExportFaults.Mode.KILL_AFTER_PATH_TRANSITION)
        begin(f)
        fail("Process should have died after persisting the transition")
    }

    @Test fun recoveryUsesOnlyRendererAfterDurableTransition() {
        val ticket = JSONObject(marker.readText())
        val interrupted = checkNotNull(ExportJournal.read(context, ticket.getString("session")))
        val hashes = ticket.getJSONArray("hashes")
        val f = RecoveryFixture(SessionCodec.decode(interrupted.getString("session")),
            List(hashes.length(), hashes::getString), File(ticket.getString("decoy")))
        assertNoPublication(f, "Exporting")
        assertEquals(ExportPath.RENDER.name, interrupted.getString("selectedPath"))
        assertTrue(interrupted.getBoolean("renderFallbackUsed"))
        assertEquals(1, interrupted.getInt("productionAttempts"))
        assertFalse(interrupted.has("candidate"))
        assertFalse(output(f).exists())
        ExportJournal.restore(context, f.session)
        ExportFaults.arm(context, ExportFaults.Mode.FORMAT_WITHOUT_AVC_CONFIG)
        retry(f)
        assertEquals(interrupted.getString("id"), job(f).getString("id"))
        assertCompleted(f, 2, 1)
    }
}
