package com.calvinmazhindu.skitcam.export

import android.net.Uri
import android.os.Build
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

/** Real service and publisher with owned staged sources; never opens the camera or microphone. */
@RunWith(AndroidJUnit4::class)
class FieldExportBenchmarkDeviceTest {
    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private val staged get() = File(context.filesDir, "m15b31-inputs")
    private data class Case(val sources: List<Int>, val order: List<Int> = sources,
        val trims: Map<Int, Pair<Long, Long>> = emptyMap())

    @Test fun exportFixture() {
        check(context.packageName.matches(Regex("""com\.calvinmazhindu\.skitcam\.m15b31(base|candidate)\.m12bvalidation""")))
        check(Build.VERSION.SDK_INT >= 30 && !ExportJournal.busy)
        val args = InstrumentationRegistry.getArguments()
        val name = requireNotNull(args.getString("case"))
        val run = requireNotNull(args.getString("run")).toInt().also { require(it in 1..10) }
        val sources = (1..11).toList()
        val kept = listOf(3, 1, 2, 4, 5, 6, 7, 8, 10, 11)
        val selected = when (name) {
            "single" -> Case(listOf(1))
            "multi" -> Case(listOf(1, 2, 3))
            "order" -> Case(listOf(1, 2, 3), listOf(3, 1, 2))
            "discard" -> Case(listOf(1, 2, 3, 4), listOf(1, 2, 3))
            "trim" -> Case(listOf(1, 2, 3), listOf(1, 2, 3), mapOf(2 to (1353L to 10461L)))
            "long" -> Case(listOf(12))
            "real" -> Case(sources, kept, mapOf(2 to (1353L to 10461L), 3 to (2739L to 13066L)))
            "realwhole" -> Case(sources, kept)
            else -> error("Unknown benchmark case")
        }
        val results = File(context.filesDir, "m15b31-results").apply { mkdirs() }
        val report = File(results, "$name-$run.json")
        check(!report.exists()) { "Use a fresh run number; evidence will not be overwritten." }
        val id = UUID.randomUUID().toString()
        val dir = File(context.filesDir, "sessions/$id").apply { check(mkdirs()) }
        val before = linkedMapOf<File, String>()
        val clips = selected.sources.map { number ->
            val source = File(staged, "segment_%03d.mp4".format(java.util.Locale.ROOT, number))
            val local = source.copyTo(File(dir, source.name))
            before[local] = sha(source)
            assertEquals(before[local], sha(local))
            val duration = inspect(local).duration
            val trim = selected.trims[number]?.let { TrimRange(duration, it.first, it.second) }
            Segment(number, local.path, duration, 0, 90, kept = number in selected.order,
                id = "$id-$number", trim = trim)
        }
        val session = Session(id, System.currentTimeMillis(), 0, RecordingState.Completed, clips,
            playbackOrder = selected.order.map { "$id-$it" },
            captureProfile = CaptureProfile("fixture", VideoSettings("FHD"), 1920, 1080))
        val power = context.getSystemService(PowerManager::class.java)
        val thermalBefore = power.currentThermalStatus
        var peakThermal = thermalBefore
        val start = SystemClock.elapsedRealtimeNanos()
        ExportJournal.request(context, session)
        ExportJournal.finalised(context, session)
        ExportService.launch(context, id)
        val deadline = SystemClock.elapsedRealtime() + 600_000
        while (ExportJournal.busy && SystemClock.elapsedRealtime() < deadline) {
            peakThermal = maxOf(peakThermal, power.currentThermalStatus)
            SystemClock.sleep(100)
        }
        val elapsedMs = (SystemClock.elapsedRealtimeNanos() - start) / 1_000_000.0
        val job = checkNotNull(ExportJournal.read(context, id))
        assertEquals("Export did not complete", ExportState.Completed.name, job.getString("state"))
        assertFalse(ExportJournal.busy)
        val out = File(ExportJournal.directory(context, id), "output.mp4")
        assertEquals(before, before.keys.associateWith(::sha))
        val uri = Uri.parse(job.getString("uri"))
        assertEquals(listOf(uri to false), fieldPublicationRows(context, job))
        val digest = MessageDigest.getInstance("SHA-256")
        context.contentResolver.openInputStream(uri)!!.use { input ->
            val buffer = ByteArray(65536)
            while (true) {
                val size = input.read(buffer)
                if (size < 0) break
                digest.update(buffer, 0, size)
            }
        }
        assertEquals(job.getString("verifiedSha"), digest.digest().joinToString("") { "%02x".format(it) })
        val trace = JSONObject(File(out.parentFile, "performance.json").readText())
        report.writeText(JSONObject().put("case", name).put("run", run).put("sessionId", id)
            .put("path", job.getString("selectedPath")).put("reason", job.getString("pathReason"))
            .put("elapsedMs", elapsedMs).put("outputBytes", out.length())
            .put("thermalBefore", thermalBefore).put("thermalPeak", peakThermal)
            .put("verification", job.getJSONObject("verification"))
            .put("events", trace.getJSONObject("eventsMs")).put("spans", trace.getJSONArray("spans"))
            .put("decoderTotals", trace.optJSONObject("decoderTotals"))
            .put("bytes", trace.getJSONObject("bytes")).toString(2))
    }
}
