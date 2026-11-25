package com.calvinmazhindu.skitcam.export

import android.net.Uri
import android.os.SystemClock
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.security.MessageDigest

/**
 * Deterministic verification failures on isolated fixtures. Natural vendor failures
 * are measured separately; these tests prove the recovery rules and their bounds.
 */
@RunWith(AndroidJUnit4::class)
class ExportRecoveryDeviceTest {
    private val context = InstrumentationRegistry.getInstrumentation().targetContext

    @Before fun guard() {
        check(ExportFaults.allowed(context))
        check(!ExportJournal.busy)
        ExportJournal.cancelRequested = false
        ExportFaults.disarm()
    }
    @After fun disarm() { ExportFaults.disarm() }

    private fun begin(f: RecoveryFixture) {
        ExportJournal.request(context, f.session)
        ExportJournal.finalised(context, f.session)
        ExportService.launch(context, f.session.id)
    }
    private fun retry(f: RecoveryFixture) = ExportService.launch(context, f.session.id, retry = true)
    private fun awaitIdle(limitMs: Long = 240_000) {
        val end = SystemClock.elapsedRealtime() + limitMs
        while (ExportJournal.busy && SystemClock.elapsedRealtime() < end) Thread.sleep(25)
        assertFalse("Export did not become idle", ExportJournal.busy)
    }
    private fun job(f: RecoveryFixture) = ExportJournal.read(context, f.session.id)!!
    private fun assertOriginals(f: RecoveryFixture) {
        assertEquals(f.hashes, f.session.segments.map { sha(File(it.path)) })
        assertTrue("Candidate cleanup removed an unrelated file", f.decoy.isFile)
        assertEquals("not export media", f.decoy.readText())
    }
    private fun assertNoRows(f: RecoveryFixture) {
        assertTrue(fieldPublicationRows(context, job(f)).isEmpty())
    }
    private fun digest(uri: Uri): String = context.contentResolver.openInputStream(uri)!!.use { input ->
        val digest = MessageDigest.getInstance("SHA-256");val buffer = ByteArray(65536)
        while (true) { val size = input.read(buffer);if (size < 0) break;digest.update(buffer, 0, size) }
        digest.digest().joinToString("") { "%02x".format(it) }
    }
    private fun assertPublished(f: RecoveryFixture) {
        val saved = job(f)
        assertEquals(saved.toString(), "Completed", saved.getString("state"))
        val rows = fieldPublicationRows(context, saved)
        assertEquals("Exactly one Gallery row is expected", 1, rows.size)
        assertFalse("Published media must not remain pending", rows.single().second)
        assertEquals(Uri.parse(saved.getString("uri")), rows.single().first)
        assertEquals(saved.getString("verifiedSha"), digest(Uri.parse(saved.getString("uri"))))
        assertOriginals(f)
    }

    /**
     * Finish a fixture whose subject is not the first verification attempt. The vendor
     * decoder can release itself at any time, so one explicit Retry is expected here;
     * every recovery still uses the same full verifier.
     */
    private fun completeOrRetry(f: RecoveryFixture) {
        repeat(3) { index ->
            if (job(f).getString("state") == ExportState.Completed.name) return
            val failed = job(f)
            assertEquals("Attempt ${index + 1}", "Failed", failed.getString("state"))
            assertEquals("Attempt ${index + 1}", ExportCandidate.FAILURE_DECODER,
                failed.optString("lastVerifyFailure"))
            assertNoRows(f)
            retry(f); awaitIdle()
        }
        assertEquals(job(f).toString(), "Completed", job(f).getString("state"))
    }

    private fun recoveryFault(mode: ExportFaults.Mode) {
        val f = ExportRecoveryFixtures.fixture(context)
        ExportFaults.arm(context, mode)
        begin(f)
        awaitIdle()
        val failed = job(f)
        assertEquals(failed.toString(), "Failed", failed.getString("state"))
        assertEquals(ExportCandidate.FAILURE_DECODER, failed.optString("lastVerifyFailure"))
        assertNotNull("The completed candidate must remain recorded", ExportCandidate.from(failed))
        assertEquals(1, failed.optInt("productionAttempts", -1))
        assertEquals(1, failed.optInt("verificationAttempts", -1))
        assertNoRows(f)
        assertOriginals(f)
        ExportFaults.disarm()
        retry(f)
        awaitIdle()
        val completed = job(f)
        assertPublished(f)
        assertEquals("Retry must not render again", 1, completed.optInt("productionAttempts", -1))
        assertEquals(2, completed.optInt("verificationAttempts", -1))
        assertFalse("The consumed checkpoint must be cleared", completed.has("candidate"))
        assertEquals("", completed.optString("lastVerifyFailure"))
    }

    @Test fun videoDecoderInitialisationFailureRecoversWithoutRendering() =
        recoveryFault(ExportFaults.Mode.VIDEO_INIT)

    @Test fun videoDecoderFailureDuringVerificationRecoversWithoutRendering() =
        recoveryFault(ExportFaults.Mode.VIDEO_MID)

    @Test fun expectedVendorSelfReleaseRecoversWithoutRendering() =
        recoveryFault(ExportFaults.Mode.VIDEO_RELEASE)

    @Test fun audioDecoderFailureDuringVerificationRecoversWithoutRendering() =
        recoveryFault(ExportFaults.Mode.AUDIO_MID)

    @Test fun persistentVerificationFailureIsBoundedAndNeverPublishes() {
        val f = ExportRecoveryFixtures.fixture(context)
        ExportFaults.arm(context, ExportFaults.Mode.PERSISTENT_VIDEO_MID)
        begin(f)
        awaitIdle()
        val first = job(f)
        assertEquals("Failed", first.getString("state"))
        assertEquals(ExportCandidate.FAILURE_DECODER, first.optString("lastVerifyFailure"))
        assertEquals(ExportCandidate.RETRY_RECHECK_MESSAGE, first.getString("message"))
        assertEquals(1, first.optInt("productionAttempts", -1))
        assertNoRows(f)
        assertOriginals(f)

        retry(f); awaitIdle()
        val second = job(f)
        assertEquals("Failed", second.getString("state"))
        assertEquals("The second attempt re-checks, it does not render", 1, second.optInt("productionAttempts", -1))
        assertEquals(2, second.optInt("verificationAttempts", -1))
        assertTrue(second.optBoolean("candidateReverified"))
        assertEquals(ExportCandidate.RETRY_REBUILD_MESSAGE, second.getString("message"))
        assertNoRows(f)

        retry(f); awaitIdle()
        val third = job(f)
        assertEquals("A consumed candidate must be rebuilt", 2, third.optInt("productionAttempts", -1))
        assertEquals(3, third.optInt("verificationAttempts", -1))
        assertEquals("Failed", third.getString("state"))
        assertNoRows(f)
        assertOriginals(f)
        assertEquals("The render path must not create a fast-path fallback candidate", 0,
            ExportJournal.directory(context, f.session.id).listFiles()!!.count { it.name.startsWith("rejected-") })

        ExportFaults.disarm()
        retry(f); awaitIdle()
        val recovered = job(f)
        assertPublished(f)
        assertEquals(2, recovered.optInt("productionAttempts", -1))
        assertEquals(4, recovered.optInt("verificationAttempts", -1))
    }

    @Test fun corruptCandidateIsRebuiltAndNeverPublished() {
        val f = ExportRecoveryFixtures.fixture(context)
        ExportFaults.arm(context, ExportFaults.Mode.CORRUPT_AFTER_CHECKPOINT)
        begin(f); awaitIdle()
        val first = job(f)
        assertEquals("Failed", first.getString("state"))
        assertNotNull(ExportCandidate.from(first))
        assertNoRows(f)
        ExportFaults.disarm()
        retry(f); awaitIdle()
        // Decoding deliberately damaged media can disturb the vendor decoder service;
        // the rebuilt attempt may need one more explicit Retry. It can only reuse the
        // newly rendered candidate, never the damaged one.
        completeOrRetry(f)
        val completed = job(f)
        assertPublished(f)
        assertTrue("Corrupt bytes must be re-rendered, not reused",
            completed.optInt("productionAttempts", -1) >= 2)
    }

    @Test fun truncatedCandidateIsRebuiltAndNeverPublished() {
        val f = ExportRecoveryFixtures.fixture(context)
        ExportFaults.arm(context, ExportFaults.Mode.TRUNCATE_AFTER_CHECKPOINT)
        begin(f); awaitIdle()
        val first = job(f)
        assertEquals("Failed", first.getString("state"))
        assertNotNull(ExportCandidate.from(first))
        assertNoRows(f)
        ExportFaults.disarm()
        retry(f); awaitIdle()
        completeOrRetry(f)
        val completed = job(f)
        assertPublished(f)
        assertTrue("Truncated bytes must be re-rendered, not reused",
            completed.optInt("productionAttempts", -1) >= 2)
    }

    @Test fun missingCandidateIsRebuiltAndNeverPublished() {
        val f = ExportRecoveryFixtures.fixture(context)
        ExportFaults.arm(context, ExportFaults.Mode.DELETE_AFTER_CHECKPOINT)
        begin(f); awaitIdle()
        val first = job(f)
        assertEquals("Failed", first.getString("state"))
        assertNoRows(f)
        ExportFaults.disarm()
        retry(f); awaitIdle()
        completeOrRetry(f)
        val completed = job(f)
        assertPublished(f)
        assertTrue("Truncated bytes must be re-rendered, not reused",
            completed.optInt("productionAttempts", -1) >= 2)
    }

    @Test fun durationMismatchIsAnIntegrityFailureNotADecoderFailure() {
        val f = ExportRecoveryFixtures.fixture(context)
        begin(f); awaitIdle()
        completeOrRetry(f)
        assertPublished(f)
        val output = File(ExportJournal.directory(context, f.session.id), "output.mp4")
        val expected = job(f).getJSONObject("verification").getLong("durationMs")
        try {
            verify(output, expected + 500)
            fail("A duration mismatch must fail")
        } catch (failure: ExportIntegrityException) {
            assertTrue(failure.message!!.contains("duration"))
        }
        assertPublished(f)
    }

    @Test fun cancellationLeavesNoPublicationAndRetryPublishesOnce() {
        val f = ExportRecoveryFixtures.fixture(context)
        begin(f)
        // Cancel before the service reaches production; the flag is durable and checked
        // before rendering, so this cannot race a short fixture to completion.
        ExportJournal.cancelRequested = true
        awaitIdle()
        val cancelled = job(f)
        assertEquals("Cancelled", cancelled.getString("state"))
        assertFalse(cancelled.optBoolean("publicationStarted"))
        assertNoRows(f)
        assertOriginals(f)
        retry(f); awaitIdle()
        completeOrRetry(f)
        assertPublished(f)
        assertEquals(1, fieldPublicationRows(context, job(f)).size)
    }

    @Test fun completedJobDoesNotReuseCandidateWithoutRetryIntent() {
        val f = ExportRecoveryFixtures.fixture(context)
        begin(f); awaitIdle()
        completeOrRetry(f)
        assertPublished(f)
        val saved = job(f).getString("uri")
        ExportService.launch(context, f.session.id)
        awaitIdle()
        assertEquals("A completed job must remain a no-op", saved, job(f).getString("uri"))
        assertEquals(1, fieldPublicationRows(context, job(f)).size)
    }
}
