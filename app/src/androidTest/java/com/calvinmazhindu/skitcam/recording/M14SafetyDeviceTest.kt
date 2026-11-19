package com.calvinmazhindu.skitcam.recording

import android.Manifest
import android.os.ParcelFileDescriptor
import android.view.accessibility.AccessibilityNodeInfo
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.calvinmazhindu.skitcam.MainActivity
import com.calvinmazhindu.skitcam.export.ExportJournal
import com.calvinmazhindu.skitcam.export.ExportState
import com.calvinmazhindu.skitcam.export.sha
import com.calvinmazhindu.skitcam.project.CompletedProjectCatalog
import com.calvinmazhindu.skitcam.trim.TrimRange
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.util.UUID

@RunWith(AndroidJUnit4::class)
class M14SafetyDeviceTest {
    private val instrument = InstrumentationRegistry.getInstrumentation()
    private val context get() = instrument.targetContext
    private val store get() = SessionFileStore(File(context.filesDir, "sessions"))
    private val checkpoint get() = File(context.filesDir, "m14-interruption-id")
    private lateinit var scenario: ActivityScenario<MainActivity>
    private lateinit var activity: MainActivity
    private val model get() = activity.recordingModel
    private val snapshot get() = model.snapshots.value

    @Before fun grantValidationPermissions() {
        check(context.packageName == "com.calvinmazhindu.skitcam.m14validation")
        for (permission in listOf(Manifest.permission.CAMERA, Manifest.permission.RECORD_AUDIO,
            Manifest.permission.POST_NOTIFICATIONS)) {
            instrument.uiAutomation.executeShellCommand("pm grant ${context.packageName} $permission").use {
                ParcelFileDescriptor.AutoCloseInputStream(it).readBytes()
            }
        }
    }

    @After fun close() {
        if (::scenario.isInitialized) scenario.close()
    }

    private fun await(label: String, timeoutMs: Long = 35_000, predicate: () -> Boolean) {
        val deadline = android.os.SystemClock.elapsedRealtime() + timeoutMs
        while (!predicate() && android.os.SystemClock.elapsedRealtime() < deadline) Thread.sleep(75)
        assertTrue("Timed out waiting for $label. ${if (::activity.isInitialized) snapshot else ""}", predicate())
    }

    private fun open() {
        scenario = ActivityScenario.launch(MainActivity::class.java)
        scenario.onActivity { activity = it }
        await("session discovery") { model.recovered.value }
    }

    private fun battery(percentage: Double?, charging: Boolean = false) =
        scenario.onActivity { model.updateBattery(BatteryReading(percentage, charging)) }

    private fun find(label: String): AccessibilityNodeInfo? {
        fun search(node: AccessibilityNodeInfo): AccessibilityNodeInfo? {
            if (node.text?.toString() == label || node.contentDescription?.toString() == label) return node
            for (index in 0 until node.childCount) {
                node.getChild(index)?.let { search(it)?.let { result -> return result } }
            }
            return null
        }
        if (android.os.Build.VERSION.SDK_INT >= 33) instrument.uiAutomation.clearCache()
        return instrument.uiAutomation.rootInActiveWindow?.let(::search)
    }

    private fun click(label: String) {
        await(label) { find(label) != null }
        var node = find(label)
        while (node != null && !node.isClickable) node = node.parent
        assertNotNull("No clickable target for $label", node)
        assertTrue("Click failed for $label", node!!.performAction(AccessibilityNodeInfo.ACTION_CLICK))
    }

    private fun fresh() {
        open()
        battery(80.0)
        await("idle camera") {
            model.cameraReady && activity.orientation.pose == 0 &&
                snapshot.state in setOf(RecordingState.Ready, RecordingState.Completed)
        }
    }

    private fun record(resume: Boolean = false, duration: Long = 1500) {
        await("camera bound") { model.cameraReady && activity.orientation.pose == 0 }
        scenario.onActivity { if (resume) model.resume(0) else model.start(0) }
        await("recording") { snapshot.state == RecordingState.Recording && snapshot.activeDurationMs >= duration }
    }

    private fun pause() {
        model.pause()
        await("finalized pause") { snapshot.state == RecordingState.Paused && !snapshot.unresolved }
    }

    private fun abandon(id: String) {
        scenario.onActivity { model.abandon(id) { assertTrue(it.message, it.success) } }
        await("discard completed") { snapshot.state == RecordingState.Ready && !model.commandPending.value }
    }

    @Test fun pausedDiscardConfirmationAndUnrelatedPreservation() {
        fresh()
        record()
        pause()
        record(resume = true)
        pause()
        val session = snapshot.session!!
        val sibling = File(store.rootPath, "fixture-${UUID.randomUUID()}").apply { mkdir() }
        val unrelated = File(sibling, "segment_001.mp4").apply { writeText("unrelated") }
        try {
            click("Finish skit")
            click("Discard entire skit")
            click("Keep skit")
            assertEquals(session, snapshot.session)
            assertTrue(session.segments.all { File(it.path).isFile })
            click("Finish skit")
            click("Discard entire skit")
            click("Discard 2 clips")
            await("idle after discard") { snapshot.state == RecordingState.Ready }
            assertFalse(File(store.rootPath, session.id).exists())
            assertEquals("unrelated", unrelated.readText())
        } finally {
            unrelated.delete()
            sibling.delete()
        }
    }

    @Test fun activeDiscardWaitsForSafeFinalise() {
        fresh()
        record()
        val session = snapshot.session!!
        val pending = session.pending!!
        click("Finish skit")
        click("Discard entire skit")
        click("Keep skit")
        assertEquals(RecordingState.Recording, snapshot.state)
        assertTrue(File(pending.path).isFile)
        click("Finish skit")
        click("Discard entire skit")
        click("Discard 0 clips")
        await("active discard complete") { snapshot.state == RecordingState.Ready && !snapshot.unresolved }
        assertFalse(File(pending.path).exists())
    }

    @Test fun criticalBatteryKeepsReviewAndTrimAvailableAndCanResume() {
        fresh()
        battery(null)
        scenario.onActivity { model.start(0) }
        await("unknown battery blocks start") { snapshot.message?.contains("battery") == true }
        assertTrue(snapshot.state in setOf(RecordingState.Ready, RecordingState.Completed))
        battery(5.0, charging = true)
        scenario.onActivity { model.start(0) }
        assertTrue(snapshot.state in setOf(RecordingState.Ready, RecordingState.Completed))
        battery(80.0)
        record()
        pause()
        record(resume = true)
        pause()
        record(resume = true)
        battery(10.0)
        await("ten percent warning") { find("Low battery · 10%") != null }
        assertEquals(RecordingState.Recording, snapshot.state)
        battery(7.0)
        await("auto-pause committed all clips") { snapshot.state == RecordingState.Paused && snapshot.count == 3 }
        val session = snapshot.session!!
        assertNull(ExportJournal.read(context, session.id))
        val hashes = session.segments.map { sha(File(it.path)) }
        battery(5.0)
        scenario.onActivity { model.resume(0) }
        await("battery restriction at five percent") { snapshot.message?.contains("battery") == true }
        assertEquals(RecordingState.Paused, snapshot.state)
        val clip = session.segments[1]
        val trimSource = com.calvinmazhindu.skitcam.trim.TrimSource(
            store.sourceFacts(clip.path), clip.trim!!.sourceDurationMs)
        val range = TrimRange(trimSource.presentationDurationMs, 200, trimSource.presentationDurationMs - 200)
        scenario.onActivity {
            model.saveTrim(session.id, clip, trimSource, range) { assertTrue(it.message, it.success) }
        }
        await("trim saved at critical battery") { snapshot.session?.segments?.get(1)?.trim == range }
        scenario.onActivity {
            val trimmed = snapshot.session!!.segments[1]
            model.saveTrim(session.id, trimmed, trimSource, TrimRange.full(trimSource.presentationDurationMs)) {
                assertTrue(it.message, it.success)
            }
        }
        await("trim reset") { snapshot.session?.segments?.get(1)?.trim == clip.trim }
        scenario.onActivity {
            model.saveTrim(session.id, snapshot.session!!.segments[1], trimSource, range) { assertTrue(it.message, it.success) }
            model.setSegmentKept(session.id, session.segments[0].id, false) { assertTrue(it.message, it.success) }
        }
        await("trim and discard decision") { snapshot.count == 2 && snapshot.session?.segments?.get(1)?.trim == range }
        val baseline = snapshot.session!!
        scenario.onActivity { model.saveShelfOrder(baseline, baseline.orderedSegmentIds.reversed()) {
            assertTrue(it.message, it.success)
        } }
        await("saved reordered clips") { snapshot.session?.playbackOrder == baseline.playbackOrder.reversed() }
        assertEquals(hashes, session.segments.map { sha(File(it.path)) })
        click("Play skit from beginning")
        await("whole-skit playback at critical battery") { activity.playback.active }
        scenario.onActivity { activity.playback.close() }
        battery(5.0, charging = true)
        assertEquals(RecordingState.Paused, snapshot.state)
        battery(10.0, charging = true)
        record(resume = true)
        pause()
        assertEquals(4, snapshot.session!!.segments.last().order)
        val verified = snapshot.session!!
        model.stop()
        await("trimmed export saved", 60_000) { ExportJournal.status.value?.let {
            it.sessionId == verified.id && it.state == ExportState.Completed && !ExportJournal.busy
        } == true }
        val export = ExportJournal.read(context, verified.id)!!
        assertEquals(verified.playbackOrder.first(), export.getJSONArray("spec").getJSONObject(0).getString("id"))
        assertTrue(CompletedProjectCatalog(context).load(verified.id).contains(verified.id))
    }

    @Test fun interruptedCheckpoint() {
        org.junit.Assume.assumeTrue(InstrumentationRegistry.getArguments().getString("checkpoint") == "true")
        fresh()
        record()
        pause()
        record(resume = true)
        pause()
        record(resume = true, duration = 700)
        checkpoint.writeText(snapshot.session!!.id)
        assertEquals(3, snapshot.session!!.pending!!.order)
        // The host force-stops this isolated package after reading the checkpoint.
        Thread.sleep(60_000)
        fail("Expected validation-only host process interruption")
    }

    @Test fun recoveryCanBeDeferredWithoutLosingConfirmedClips() {
        org.junit.Assume.assumeTrue(InstrumentationRegistry.getArguments().getString("recovery") == "true")
        open()
        val saved = snapshot.session!!
        assertEquals(checkpoint.readText(), saved.id)
        assertEquals(2, snapshot.recovery!!.playableIds.size)
        assertEquals(3, snapshot.recovery!!.candidates.single().order)
        val metadata = File(store.rootPath, "${saved.id}/session.json")
        val original = metadata.readText()
        click("Later")
        assertEquals(original, metadata.readText())
        assertEquals(RecordingState.Error, snapshot.state)
    }

    @Test fun recoveryContinuesIntoNewFileAndPreservesCandidate() {
        org.junit.Assume.assumeTrue(InstrumentationRegistry.getArguments().getString("recovery") == "true")
        open()
        battery(80.0)
        val saved = snapshot.session!!
        assertEquals(checkpoint.readText(), saved.id)
        val candidate = File(snapshot.recovery!!.candidates.single().path)
        assertTrue(candidate.isFile)
        val candidateDigest = sha(candidate)
        val confirmed = saved.segments.map { sha(File(it.path)) }
        click("Continue recording")
        await("recovered paused session") { snapshot.state == RecordingState.Paused }
        record(resume = true)
        pause()
        val continued = snapshot.session!!
        assertEquals(listOf(1, 2, 4), continued.segments.map { it.order })
        assertEquals(candidateDigest, sha(candidate))
        assertEquals(confirmed, saved.segments.map { sha(File(it.path)) })
        abandon(continued.id)
    }

    @Test fun cancelledExportCanBeRetriedWithOriginals() {
        fresh()
        record()
        pause()
        val session = snapshot.session!!
        val hashes = session.segments.map { sha(File(it.path)) }
        cancelExport(session)
        click("New Recording")
        click("Cancel")
        assertEquals(hashes, session.segments.map { sha(File(it.path)) })
        click("Retry export")
        await("same skit published", 60_000) { ExportJournal.status.value?.state == ExportState.Completed && !ExportJournal.busy }
        assertEquals(hashes, session.segments.map { sha(File(it.path)) })
    }

    @Test fun cleanupFailureLeavesRecordedFilesAndCanBeRetried() {
        fresh()
        record()
        pause()
        val session = snapshot.session!!
        val clip = File(session.segments.single().path)
        val hash = sha(clip)
        val unexpected = File(clip.parentFile, "unrecognised-file.bin").apply { writeText("preserve") }
        try {
            val result = java.util.concurrent.CompletableFuture<SegmentEditResult>()
            scenario.onActivity { model.abandon(session.id, result::complete) }
            assertFalse(result.get(15, java.util.concurrent.TimeUnit.SECONDS).success)
            assertEquals("preserve", unexpected.readText())
            assertEquals(hash, sha(clip))
            assertEquals(RecordingState.Paused, snapshot.state)
        } finally {
            unexpected.delete()
        }
        abandon(session.id)
    }

    @Test fun newRecordingRequiresChoiceAndDeferredDraftCanBeReopened() {
        fresh()
        record()
        pause()
        val signed = snapshot.session!!
        val hash = sha(File(signed.segments.single().path))
        cancelExport(signed)
        click("New Recording")
        click("Keep as draft and start new")
        await("idle with a durable draft") { snapshot.state == RecordingState.Ready &&
            model.drafts.value.any { it.id == signed.id && it.session?.deferred == true } }
        assertEquals(hash, sha(File(signed.segments.single().path)))
        val result = java.util.concurrent.CompletableFuture<SegmentEditResult>()
        scenario.onActivity { model.selectDraft(signed.id, result::complete) }
        assertTrue(result.get(15, java.util.concurrent.TimeUnit.SECONDS).success)
        click("Review recovered segments")
        await("draft reopened for edits") { snapshot.state == RecordingState.Paused && snapshot.session?.id == signed.id }
        assertNull(ExportJournal.read(context, signed.id))
        assertEquals(hash, sha(File(signed.segments.single().path)))
        abandon(signed.id)
    }

    @Test fun discardAndStartNewMustBeConfirmedAndKeepsCompletedProjects() {
        fresh()
        val published = ExportJournal.status.value?.takeIf { it.state == ExportState.Completed }
        record()
        pause()
        val session = snapshot.session!!
        cancelExport(session)
        click("New Recording")
        click("Discard and start new")
        click("Keep skit")
        assertTrue(File(session.segments.single().path).exists())
        click("New Recording")
        click("Discard and start new")
        click("Discard 1 clip")
        await("fresh camera") { snapshot.state == RecordingState.Ready }
        assertFalse(File(store.rootPath, session.id).exists())
        published?.let {
            assertTrue(CompletedProjectCatalog(context).load(it.sessionId).contains(it.sessionId))
            context.contentResolver.openInputStream(android.net.Uri.parse(it.uri))!!.use { input ->
                assertTrue(input.read() >= 0)
            }
        }
    }

    @Test fun recoveryExportsOnlyConfirmedSegments() {
        org.junit.Assume.assumeTrue(InstrumentationRegistry.getArguments().getString("recovery") == "true")
        open()
        val saved = snapshot.session!!
        assertEquals(checkpoint.readText(), saved.id)
        val candidate = File(snapshot.recovery!!.candidates.single().path)
        val hash = sha(candidate)
        click("Export recovered segments")
        await("recovered clips published", 60_000) { ExportJournal.status.value?.state == ExportState.Completed &&
            !ExportJournal.busy }
        val job = ExportJournal.read(context, saved.id)!!
        assertEquals(2, job.getJSONArray("spec").length())
        assertEquals(hash, sha(candidate))
    }

    private fun cancelExport(session: Session) {
        scenario.onActivity {
            model.stop()
            // Keep the main-thread service launch queued until cancellation is requested.
            await("export requested") { ExportJournal.status.value?.sessionId == session.id }
            ExportJournal.cancelRequested = true
        }
        await("export cancelled") { ExportJournal.status.value?.state == ExportState.Cancelled && !ExportJournal.busy }
    }
}
