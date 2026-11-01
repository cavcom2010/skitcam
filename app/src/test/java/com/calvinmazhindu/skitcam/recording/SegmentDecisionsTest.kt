package com.calvinmazhindu.skitcam.recording

import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class SegmentDecisionsTest {
    private class Store : SessionStore {
        override val rootPath = "/test-sessions"
        var json: String? = null
        var broken = false
        override fun save(session: Session) {
            check(!broken) { "Disk write failed" }
            json = SessionCodec.encode(session)
        }
        override fun loadLatest() = json?.let(SessionCodec::decode)
        override fun freeBytes() = RecordingEngine.START_RESERVE * 2
        override fun validate(path: String) = 90
    }
    private class Recorder : RecordingPort {
        lateinit var event: (RecorderEvent) -> Unit
        val paths = mutableListOf<String>()
        override fun start(pending: PendingSegment, orientationDegrees: Int,
            listener: (RecorderEvent) -> Unit): RecordingHandle {
            event = listener
            paths += pending.path
            return RecordingHandle {}
        }
    }
    private val store = Store()
    private val recorder = Recorder()
    private fun engine() = RecordingEngine(recorder, store, Watchdog { Cancellation {} })
    private val engine = engine()
    private fun RecordingEngine.edit(sessionId: String, number: Int, kept: Boolean) =
        setSegmentKept(sessionId, snapshot.session?.segments?.find { it.order == number }?.id ?: "unknown", kept)
    private fun fixture(count: Int = 2): Session {
        val session = Session("fixture", 123, 0, RecordingState.Paused,
            List(count) { Segment(it + 1, "/test-sessions/fixture/segment_${it + 1}.mp4", 1000L, 0, 90) })
        store.save(session); engine.restore()
        return session
    }
    @Test fun defaultKeepAndMixedStatusPersistAndRestore() {
        val original = fixture()
        assertTrue(original.segments.all { it.kept })
        assertTrue(engine.edit(original.id, 1, false).success)
        assertEquals(1, engine.snapshot.count)
        assertEquals(1000L, engine.snapshot.durationMs)
        val restored = engine(); restored.restore()
        assertEquals(engine.snapshot.session, restored.snapshot.session)
        assertFalse(restored.snapshot.session!!.segments[0].kept)
        assertTrue(restored.snapshot.session!!.segments[1].kept)
        assertTrue(restored.edit(original.id, 1, true).success)
        assertEquals(2, restored.snapshot.count)
        assertEquals(original.segments.map { it.path }, restored.snapshot.session!!.segments.map { it.path })
    }
    @Test fun legacyJsonDefaultsToKeepAndUpgradesOnSave() {
        val session = fixture()
        val legacy = JSONObject(SessionCodec.encode(session)).put("version", 1)
        val segments = legacy.getJSONArray("segments")
        repeat(segments.length()) { segments.getJSONObject(it).remove("kept") }
        val decoded = SessionCodec.decode(legacy.toString())
        assertTrue(decoded.segments.all { it.kept })
        assertEquals(4, JSONObject(SessionCodec.encode(decoded)).getInt("version"))
    }
    @Test fun v2MissingStatusDoesNotSilentlyRestoreDiscardedSegment() {
        val json = JSONObject(SessionCodec.encode(fixture()))
        json.getJSONArray("segments").getJSONObject(0).remove("kept")
        try { SessionCodec.decode(json.toString()); fail("Missing decision must fail") }
        catch (_: org.json.JSONException) { /* Expected. */ }
    }
    @Test fun failedSaveKeepsPriorDecisionAndPausedState() {
        val original = fixture(); val json = store.json
        store.broken = true
        assertFalse(engine.edit(original.id, 1, false).success)
        assertEquals(json, store.json)
        assertEquals(original, engine.snapshot.session)
        assertEquals(RecordingState.Paused, engine.snapshot.state)
    }
    @Test fun discardFreesSlotAndNextFileUsesFullHistory() {
        val session = fixture(100)
        engine.resume(0)
        assertEquals(RecordingState.Paused, engine.snapshot.state)
        assertTrue(engine.edit(session.id, 50, false).success)
        assertEquals(99, engine.snapshot.count)
        engine.resume(0); recorder.event(RecorderEvent.Started)
        assertTrue(recorder.paths.single().endsWith("segment_101.mp4"))
        engine.pause(); recorder.event(RecorderEvent.Finalized(1200))
        assertEquals(100, engine.snapshot.count)
        assertEquals(101, engine.snapshot.session!!.segments.size)
        assertFalse(engine.edit(session.id, 50, true).success)
        assertTrue(engine.edit(session.id, 101, false).success)
        assertTrue(engine.edit(session.id, 50, true).success)
        assertEquals(100, engine.snapshot.count)
    }
    @Test fun allDiscardedCanCompleteWithoutLosingEntries() {
        val session = fixture()
        engine.edit(session.id, 1, false); engine.edit(session.id, 2, false)
        assertEquals(0, engine.snapshot.count)
        assertEquals(0L, engine.snapshot.durationMs)
        engine.stop()
        assertEquals(RecordingState.Completed, engine.snapshot.state)
        assertEquals(2, store.loadLatest()!!.segments.size)
        assertTrue(store.loadLatest()!!.segments.none { it.kept })
    }
    @Test fun invalidCommandsAndIdempotentEditsDoNotChangeMetadata() {
        val session = fixture(); val json = store.json
        assertFalse(engine.edit("stale", 1, false).success)
        assertFalse(engine.edit(session.id, 999, false).success)
        assertTrue(engine.edit(session.id, 1, true).success)
        assertEquals(json, store.json)
        engine.resume(0)
        assertFalse(engine.edit(session.id, 1, false).success)
        recorder.event(RecorderEvent.Started)
        assertFalse(engine.edit(session.id, 1, false).success)
        engine.pause()
        assertFalse(engine.edit(session.id, 1, false).success)
        recorder.event(RecorderEvent.Finalized(1000))
        engine.stop()
        assertFalse(engine.edit(session.id, 1, false).success)
    }
    @Test fun readyErrorAndStoppingRejectEdits() {
        assertFalse(engine.edit("none", 1, false).success)
        val session = fixture(); engine.resume(0); recorder.event(RecorderEvent.Started); engine.stop()
        assertFalse(engine.edit(session.id, 1, false).success)
        recorder.event(RecorderEvent.Finalized(1000, "Recording failed"))
        assertFalse(engine.edit(session.id, 1, false).success)
    }
}
