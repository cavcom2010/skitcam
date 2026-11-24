package com.calvinmazhindu.skitcam.export

import android.content.Context
import com.calvinmazhindu.skitcam.recording.*
import com.calvinmazhindu.skitcam.trim.TrimRange
import com.calvinmazhindu.skitcam.video.CaptureProfile
import com.calvinmazhindu.skitcam.video.VideoSettings
import java.io.File
import java.util.UUID

/** New disposable sessions over staged validation media only. */
internal data class RecoveryFixture(val session: Session, val hashes: List<String>, val decoy: File)

internal object ExportRecoveryFixtures {
    fun stagedInput(context: Context, name: String = "segment_001.mp4") =
        File(context.filesDir, "validation-m15b31a-inputs/$name")

    fun fixture(context: Context, trim: Boolean = true): RecoveryFixture {
        val source = stagedInput(context)
        require(source.isFile) { "Stage the validation media before running this suite." }
        val id = UUID.randomUUID().toString()
        val root = File(context.filesDir, "sessions/$id").apply { check(mkdirs()) }
        val target = source.copyTo(File(root, "segment_001.mp4"))
        val facts = ExportInputProbe.inspect(target)
        val range = if (trim) TrimRange(facts.durationMs, 0, minOf(2500L, facts.durationMs))
            else TrimRange.full(facts.durationMs)
        require(range.valid()) { "Staged media is too short for a trimmed validation fixture." }
        val orientation = if (facts.rotation % 180 == 0) 270 else 0
        val segment = Segment(1, target.path, facts.durationMs, orientation, facts.rotation,
            kept = true, id = "$id-1", trim = range)
        val profile = CaptureProfile("fixture", VideoSettings(if (maxOf(facts.width, facts.height) > 1280) "FHD" else "HD"),
            facts.width, facts.height)
        val session = Session(id, System.currentTimeMillis(), orientation, RecordingState.Completed,
            listOf(segment), playbackOrder = listOf(segment.id), captureProfile = profile)
        SessionFileStore(File(context.filesDir, "sessions")).save(session)
        // Proves candidate cleanup stays inside output.mp4 and never sweeps the job directory.
        val decoy = File(ExportJournal.directory(context, id), "unrelated-note.txt")
            .apply { writeText("not export media") }
        return RecoveryFixture(session, listOf(sha(target)), decoy)
    }
}
