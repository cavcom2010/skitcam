package com.calvinmazhindu.skitcam.recording

import org.junit.Assert.*
import org.junit.Test

class RecordingEngineTest {
    private class Store : SessionStore {
        override val rootPath = "/sessions"
        var saved: Session? = null
        var bytes = RecordingEngine.START_RESERVE * 2
        var brokenSave = false
        var invalidMedia = false
        override fun freeBytes() = bytes
        override fun loadLatest() = saved
        override fun save(session: Session) {
            if (brokenSave) error("Disk write failed")
            saved = session
        }
        override fun validate(path: String): Int {
            check(!invalidMedia) { "Invalid MP4" }
            return 90
        }
    }
    private class Recorder : RecordingPort {
        val listeners = mutableListOf<(RecorderEvent) -> Unit>()
        val paths = mutableListOf<String>()
        var stops = 0
        override fun start(pending: PendingSegment, orientationDegrees: Int,
            listener: (RecorderEvent) -> Unit): RecordingHandle {
            paths += pending.path
            listeners += listener
            return RecordingHandle { stops++ }
        }
        fun started() = listeners.last()(RecorderEvent.Started)
        fun done(error: String? = null) = listeners.last()(RecorderEvent.Finalized(2000, error))
    }
    private val store = Store()
    private val recorder = Recorder()
    private var timeout: (() -> Unit)? = null
    private var unavailable: String? = null
    private fun engine() = RecordingEngine(recorder, store,
        Watchdog { callback -> timeout = callback; Cancellation { if (timeout === callback) timeout = null } },
        availability = { unavailable })
    private val engine = engine()
    private fun begin() { engine.start(0); recorder.started() }
    private fun pause() { engine.pause(); recorder.done() }

    @Test fun pauseWaitsForFinalizeAndResumeCreatesSeparateSegment() {
        begin()
        engine.pause()
        assertEquals(RecordingState.Finalising, engine.snapshot.state)
        assertEquals(0, engine.snapshot.count)
        assertNotNull(store.saved!!.pending)
        recorder.done()
        assertEquals(RecordingState.Paused, engine.snapshot.state)
        assertEquals(1, engine.snapshot.count)
        assertNull(store.saved!!.pending)
        engine.resume(0); recorder.started(); engine.stop()
        assertEquals(RecordingState.Stopping, engine.snapshot.state)
        assertEquals(1, engine.snapshot.count)
        recorder.done()
        assertEquals(RecordingState.Completed, engine.snapshot.state)
        assertEquals(listOf(1, 2), engine.snapshot.session!!.segments.map { it.order })
        assertEquals(4000L, engine.snapshot.durationMs)
        assertEquals(2, recorder.paths.distinct().size)
    }
    @Test fun stopWhilePausedDoesNotCreateFile() {
        begin(); pause(); engine.stop()
        assertEquals(RecordingState.Completed, engine.snapshot.state)
        assertEquals(1, recorder.paths.size)
    }
    @Test fun duplicateAndInvalidCommandsAreIgnored() {
        engine.pause(); engine.resume(0); engine.stop()
        assertEquals(RecordingState.Ready, engine.snapshot.state)
        engine.start(0); engine.start(0); engine.resume(0); engine.stop()
        assertEquals(1, recorder.paths.size)
        recorder.started(); engine.pause(); engine.pause(); engine.resume(0); engine.stop()
        assertEquals(1, recorder.stops)
        recorder.done(); recorder.done()
        assertEquals(1, engine.snapshot.count)
    }
    @Test fun failedLaterRecordingRetainsEarlierSegmentAndPendingPath() {
        begin(); pause(); val first = store.saved!!.segments.single()
        engine.resume(0); recorder.started(); engine.stop(); recorder.done("Encoder failed")
        assertEquals(RecordingState.Error, engine.snapshot.state)
        assertEquals(listOf(first), store.saved!!.segments)
        assertNotNull(store.saved!!.pending)
        engine.resume(0)
        assertEquals(2, recorder.paths.size)
    }
    @Test fun staleCallbackCannotRegisterIntoNewAttempt() {
        begin(); val old = recorder.listeners.single(); pause()
        engine.resume(0); old(RecorderEvent.Finalized(5000)); recorder.started(); engine.stop(); recorder.done()
        assertEquals(2, engine.snapshot.count)
        assertEquals(4000L, engine.snapshot.durationMs)
    }
    @Test fun registrationWriteFailureDoesNotPublishSegment() {
        begin(); engine.pause(); store.brokenSave = true; recorder.done()
        assertEquals(RecordingState.Error, engine.snapshot.state)
        assertEquals(0, engine.snapshot.count)
        assertNotNull(store.saved!!.pending)
    }
    @Test fun pendingJournalFailureDoesNotStartCamera() {
        store.brokenSave = true; engine.start(0)
        assertEquals(RecordingState.Error, engine.snapshot.state)
        assertTrue(recorder.paths.isEmpty())
    }
    @Test fun lowStorageRefusesStart() {
        store.bytes = RecordingEngine.START_RESERVE - 1; engine.start(0)
        assertEquals(RecordingState.Error, engine.snapshot.state)
        assertTrue(recorder.paths.isEmpty())
    }
    @Test fun lowStorageDuringRecordingFinalizesThenPreservesSuccessfulFile() {
        begin(); store.bytes = RecordingEngine.STOP_RESERVE - 1
        recorder.listeners.last()(RecorderEvent.Status(900))
        assertEquals(RecordingState.Finalising, engine.snapshot.state)
        recorder.done()
        assertEquals(RecordingState.Error, engine.snapshot.state)
        assertEquals(1, engine.snapshot.count)
    }
    @Test fun backgroundDuringStartRequestsStopAndWaits() {
        engine.start(0); engine.background(); recorder.started()
        assertEquals(RecordingState.Finalising, engine.snapshot.state)
        assertEquals(1, recorder.stops)
        recorder.done()
        assertEquals(RecordingState.Paused, engine.snapshot.state)
    }
    @Test fun backgroundDoesNotChangeStopIntentOrDuplicateFinalization() {
        begin(); engine.stop(); engine.background(); recorder.done()
        assertEquals(RecordingState.Completed, engine.snapshot.state)
        assertEquals(1, recorder.stops)
    }
    @Test fun timeoutBlocksNewRecordingUntilLateFinalize() {
        begin(); engine.pause(); timeout!!()
        assertEquals(RecordingState.Error, engine.snapshot.state)
        assertTrue(engine.snapshot.unresolved)
        engine.stop(); engine.start(0)
        assertEquals(1, recorder.paths.size)
        recorder.done()
        assertFalse(engine.snapshot.unresolved)
        assertEquals(1, engine.snapshot.count)
        engine.stop(); engine.start(0)
        assertEquals(2, recorder.paths.size)
    }
    @Test fun hundredSegmentLimitCannotBeBypassed() {
        begin(); pause()
        repeat(99) { engine.resume(0); recorder.started(); pause() }
        engine.resume(0)
        assertEquals(100, engine.snapshot.count)
        assertEquals(100, recorder.paths.size)
        engine.stop()
        assertEquals(RecordingState.Completed, engine.snapshot.state)
    }
    @Test fun exactOrientationAndUnknownPoseBlockResume() {
        begin(); pause()
        for (pose in listOf(null, 90, 180, 270)) engine.resume(pose)
        assertEquals(RecordingState.Paused, engine.snapshot.state)
        assertEquals(1, recorder.paths.size)
        engine.resume(0)
        assertEquals(2, recorder.paths.size)
    }
    @Test fun permissionOrCameraUnavailableDoesNotCreateSession() {
        unavailable = "Microphone permission required"; engine.start(0)
        assertEquals(RecordingState.Ready, engine.snapshot.state)
        assertTrue(recorder.paths.isEmpty())
    }
    @Test fun restorePausedSessionAllowsNextSegment() {
        begin(); pause()
        val restored = engine(); restored.restore(); restored.resume(0)
        assertEquals(RecordingState.Starting, restored.snapshot.state)
        assertEquals(1, restored.snapshot.count)
    }
    @Test fun restoreInterruptedSessionDoesNotCertifyPendingFile() {
        begin()
        val restored = engine(); restored.restore()
        assertEquals(RecordingState.Error, restored.snapshot.state)
        assertEquals(0, restored.snapshot.count)
        assertNotNull(restored.snapshot.session!!.pending)
        restored.stop()
        assertEquals(RecordingState.Completed, restored.snapshot.state)
    }
    @Test fun invalidMp4CannotBeRegistered() {
        begin(); engine.stop(); store.invalidMedia = true; recorder.done()
        assertEquals(0, engine.snapshot.count)
        assertEquals(RecordingState.Error, engine.snapshot.state)
    }
    @Test fun audioErrorStopsAndKeepsConfirmedSegments() {
        begin(); recorder.listeners.last()(RecorderEvent.Status(1200, audioError = true))
        assertEquals(RecordingState.Finalising, engine.snapshot.state)
        recorder.done()
        assertEquals(RecordingState.Error, engine.snapshot.state)
        assertEquals(1, engine.snapshot.count)
    }
    @Test fun startupTimeoutRejectsLateStartAndWaitsForFinalize() {
        engine.start(0); timeout!!(); recorder.started()
        assertEquals(RecordingState.Error, engine.snapshot.state)
        assertTrue(engine.snapshot.unresolved)
        recorder.done("Startup cancelled")
        assertFalse(engine.snapshot.unresolved)
        assertEquals(0, engine.snapshot.count)
    }
    @Test fun backgroundWhilePausingDoesNotStopTwice() {
        begin(); engine.pause(); engine.background(); recorder.done()
        assertEquals(1, recorder.stops)
        assertEquals(RecordingState.Paused, engine.snapshot.state)
    }
    @Test fun completedSessionRestoresSeparateFilesNotice() {
        begin(); pause(); engine.stop()
        val restored = engine(); restored.restore()
        assertEquals(RecordingState.Completed, restored.snapshot.state)
        assertTrue(restored.snapshot.message!!.contains("combining is not implemented"))
    }
}
