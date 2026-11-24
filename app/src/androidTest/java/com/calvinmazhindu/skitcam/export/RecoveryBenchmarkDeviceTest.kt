package com.calvinmazhindu.skitcam.export

import android.net.Uri
import android.os.PowerManager
import android.os.SystemClock
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.calvinmazhindu.skitcam.recording.*
import com.calvinmazhindu.skitcam.trim.TrimRange
import com.calvinmazhindu.skitcam.video.CaptureProfile
import com.calvinmazhindu.skitcam.video.VideoSettings
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.security.MessageDigest
import java.util.UUID

/**
 * One realistic long export, with exactly one explicit Retry if the first attempt fails.
 * Records whether recovery re-used the finished candidate or rendered again.
 */
@RunWith(AndroidJUnit4::class)
class RecoveryBenchmarkDeviceTest {
    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private val staged get() = File(context.filesDir, "m15b31-inputs")
    private val results get() = File(context.filesDir, "m15b31-results").apply { mkdirs() }

    private fun awaitTerminal(id: String, limitMs: Long): JSONObject {
        val deadline = SystemClock.elapsedRealtime() + limitMs
        while (ExportJournal.busy && SystemClock.elapsedRealtime() < deadline) SystemClock.sleep(100)
        assertFalse("Export did not become idle", ExportJournal.busy)
        return checkNotNull(ExportJournal.read(context, id))
    }

    private fun digest(uri: Uri): String = context.contentResolver.openInputStream(uri)!!.use { input ->
        val digest = MessageDigest.getInstance("SHA-256");val buffer = ByteArray(65536)
        while (true) { val size = input.read(buffer);if (size < 0) break;digest.update(buffer, 0, size) }
        digest.digest().joinToString("") { "%02x".format(it) }
    }

    @Test fun realisticAttemptWithExplicitRetry() {
        check(context.packageName == "com.calvinmazhindu.skitcam.m15b31candidate.m12bvalidation")
        check(!ExportJournal.busy)
        ExportJournal.cancelRequested = false
        val args = InstrumentationRegistry.getArguments()
        val run = requireNotNull(args.getString("run")).toInt().also { require(it in 1..40) }
        val report = File(results, "recovery-$run.json")
        check(!report.exists()) { "Use a fresh run number; evidence will not be overwritten." }

        val kept = listOf(3, 1, 2, 4, 5, 6, 7, 8, 10, 11)
        val trims = mapOf(2 to (1353L to 10461L), 3 to (2739L to 13066L))
        val id = UUID.randomUUID().toString()
        val dir = File(context.filesDir, "sessions/$id").apply { check(mkdirs()) }
        val before = linkedMapOf<File, String>()
        val clips = (1..11).map { number ->
            val source = File(staged, "segment_%03d.mp4".format(java.util.Locale.ROOT, number))
            require(source.isFile) { "Stage the M15B.3 validation sources first." }
            val local = source.copyTo(File(dir, source.name))
            before[local] = sha(source)
            assertEquals(before[local], sha(local))
            val duration = inspect(local).duration
            val trim = trims[number]?.let { TrimRange(duration, it.first, it.second) }
            Segment(number, local.path, duration, 0, 90, kept = number in kept,
                id = "$id-$number", trim = trim)
        }
        val session = Session(id, System.currentTimeMillis(), 0, RecordingState.Completed, clips,
            playbackOrder = kept.map { "$id-$it" },
            captureProfile = CaptureProfile("fixture", VideoSettings("FHD"), 1920, 1080))
        val power = context.getSystemService(PowerManager::class.java)
        val thermalBefore = power.currentThermalStatus
        var peakThermal = thermalBefore

        val firstStart = SystemClock.elapsedRealtimeNanos()
        ExportJournal.request(context, session)
        ExportJournal.finalised(context, session)
        ExportService.launch(context, id)
        val first = awaitTerminal(id, 600_000)
        val firstMs = (SystemClock.elapsedRealtimeNanos() - firstStart) / 1_000_000.0
        peakThermal = maxOf(peakThermal, power.currentThermalStatus)

        val retryTriggered = first.getString("state") == ExportState.Failed.name
        var retryMs: Double? = null
        var final = first
        if (retryTriggered) {
            val retryStart = SystemClock.elapsedRealtimeNanos()
            ExportService.launch(context, id, retry = true)
            final = awaitTerminal(id, 600_000)
            retryMs = (SystemClock.elapsedRealtimeNanos() - retryStart) / 1_000_000.0
            peakThermal = maxOf(peakThermal, power.currentThermalStatus)
        }
        val out = File(ExportJournal.directory(context, id), "output.mp4")
        assertEquals(before, before.keys.associateWith(::sha))
        val rows = fieldPublicationRows(context, final)
        if (final.getString("state") == ExportState.Completed.name) {
            assertEquals("Exactly one owned publication", 1, rows.size)
            assertFalse(rows.single().second)
            assertEquals(Uri.parse(final.getString("uri")), rows.single().first)
            assertEquals(final.getString("verifiedSha"), digest(rows.single().first))
            assertEquals(out.length(), context.contentResolver.openInputStream(rows.single().first)!!.use { input ->
                var total = 0L;val buffer = ByteArray(65536)
                while (true) { val size = input.read(buffer);if (size < 0) break;total += size }
                total
            })
        } else {
            assertTrue("A failed attempt must not publish", rows.isEmpty())
        }
        val trace = runCatching { JSONObject(File(out.parentFile, "performance.json").readText()) }.getOrNull()
        report.writeText(JSONObject().put("case", "real").put("run", run).put("sessionId", id).put("path", final.optString("selectedPath"))
            .put("pathReason", final.optString("pathReason"))
            .put("firstState", first.getString("state"))
            .put("firstElapsedMs", firstMs)
            .put("firstProductionAttempts", first.optInt("productionAttempts", -1))
            .put("firstVerificationAttempts", first.optInt("verificationAttempts", -1))
            .put("firstLastVerifyFailure", first.optString("lastVerifyFailure"))
            .put("firstLastVerifyFailureDetail", first.optString("lastVerifyFailureDetail"))
            .put("firstMessage", first.optString("message"))
            .put("candidateCheckpoint", ExportCandidate.from(first) != null)
            .put("retryTriggered", retryTriggered)
            .put("retryElapsedMs", retryMs ?: JSONObject.NULL)
            .put("finalState", final.getString("state"))
            .put("finalProductionAttempts", final.optInt("productionAttempts", -1))
            .put("finalVerificationAttempts", final.optInt("verificationAttempts", -1))
            .put("candidateReuseRejected", final.optString("candidateReuseRejected"))
            .put("reusedCandidate", retryTriggered &&
                final.optInt("productionAttempts", -1) == first.optInt("productionAttempts", -1))
            .put("publishedRows", rows.size)
            .put("outputBytes", if (out.isFile) out.length() else 0)
            .put("thermalBefore", thermalBefore).put("thermalPeak", peakThermal)
            .put("events", trace?.optJSONObject("eventsMs") ?: JSONObject.NULL)
            .put("spans", trace?.optJSONArray("spans") ?: JSONObject.NULL)
            .put("verification", final.optJSONObject("verification") ?: JSONObject.NULL)
            .toString(2))
        // The copied sources are disposable; the published Gallery file and the report
        // remain. Removing only this run's session keeps later attempts within free space.
        check(dir.deleteRecursively()) { "Could not clear this run's disposable session copy." }
    }
}
