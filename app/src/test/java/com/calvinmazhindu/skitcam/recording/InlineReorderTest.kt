package com.calvinmazhindu.skitcam.recording

import com.calvinmazhindu.skitcam.arrange.InlineReorderDraft
import com.calvinmazhindu.skitcam.playback.SkitPlaybackPlanFactory
import com.calvinmazhindu.skitcam.export.ExportSpec
import com.calvinmazhindu.skitcam.trim.TrimRange
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class InlineReorderTest {
    private class Store : SessionStore {
        override val rootPath = "/inline-tests"
        var text: String? = null
        var writes = 0
        var broken = false
        override fun save(session: Session) {
            check(!broken)
            text = SessionCodec.encode(session); writes++
        }
        override fun loadLatest() = text?.let(SessionCodec::decode)
        override fun freeBytes() = RecordingEngine.START_RESERVE * 2
        override fun validate(path: String) = 90
    }
    private val store = Store()
    private lateinit var events: (RecorderEvent) -> Unit
    private fun engine() = RecordingEngine(object : RecordingPort {
        override fun start(pending: PendingSegment, orientationDegrees: Int, listener: (RecorderEvent) -> Unit): RecordingHandle {
            events = listener; return RecordingHandle {}
        }
    }, store, Watchdog { Cancellation {} })
    private val engine = engine()
    private fun fixture(count: Int = 3, discarded: Set<Int> = setOf(2)): Session {
        val session = Session("inline-session", 1, 0, RecordingState.Paused,
            List(count) { i -> Segment(i + 1, "/files/$i.mp4", 2000, 0, 90,
                kept = i + 1 !in discarded, id = "id-${i + 1}", trim = TrimRange(2000, 200, 1800)) })
        store.save(session); engine.restore()
        return session
    }
    @Test fun mixedShelfCommitsOnlyKeptSubsequenceAndPreservesEverySegment() {
        val original = fixture()
        val order = listOf("id-3", "id-2", "id-1")
        val writes = store.writes
        assertTrue(engine.saveShelfOrder(original, order).success)
        val saved = engine.snapshot.session!!
        assertEquals(writes + 1, store.writes)
        assertEquals(order, saved.orderedSegmentIds)
        assertEquals(listOf("id-3", "id-1"), saved.playbackOrder)
        assertEquals(saved.playbackOrder, SkitPlaybackPlanFactory.from(saved).plan!!.clips.map { it.segmentId })
        assertEquals(saved.playbackOrder, ExportSpec.from(saved.copy(state = RecordingState.Completed)) { it.durationMs }.clips.map { it.id })
        assertEquals(original.segments, saved.segments)
        assertEquals(saved, engine().apply { restore() }.snapshot.session)
    }
    @Test fun cancelAndIntermediateMovesNeverWriteSession() {
        val original = fixture(); val text = store.text; val writes = store.writes
        var draft = InlineReorderDraft(original)
        repeat(30) { draft = draft.move(draft.ids.last(), 0) }
        assertEquals(text, store.text); assertEquals(writes, store.writes)
        assertEquals(original.orderedSegmentIds, InlineReorderDraft(original).ids)
    }
    @Test fun discardedOnlyMovePersistsWithoutChangingPlayback() {
        val original = fixture()
        val order = listOf("id-2", "id-1", "id-3")
        assertTrue(engine.saveShelfOrder(original, order).success)
        assertEquals(original.playbackOrder, engine.snapshot.session!!.playbackOrder)
        assertEquals(order, store.loadLatest()!!.shelfOrder)
    }
    @Test fun invalidDuplicateMissingAndForeignIdsNeverWrite() {
        val original = fixture(); val writes = store.writes
        for (ids in listOf(emptyList(), listOf("id-1", "id-1", "id-3"),
            listOf("id-1", "id-2"), listOf("id-1", "foreign", "id-3"))) {
            assertFalse(engine.saveShelfOrder(original, ids).success)
        }
        assertEquals(writes, store.writes)
    }
    @Test fun staleTrimDispositionAndOrderAreRejected() {
        val original = fixture()
        assertTrue(engine.setSegmentKept(original.id, "id-2", true).success)
        val writes = store.writes
        assertFalse(engine.saveShelfOrder(original, original.orderedSegmentIds.reversed()).success)
        assertFalse(InlineReorderDraft(original).matches(engine.snapshot.session!!))
        assertEquals(writes, store.writes)
        val draft = InlineReorderDraft(original)
        assertFalse(draft.matches(original.copy(segments = original.segments.map { it.copy(trim = TrimRange.full(2000)) })))
        assertFalse(draft.matches(original.copy(id = "other")))
    }
    @Test fun failedSaveRetainsMetadataAndDraft() {
        val original = fixture(); val bytes = store.text
        val draft = InlineReorderDraft(original).move("id-3", 0)
        store.broken = true
        assertFalse(engine.saveShelfOrder(original, draft.ids).success)
        assertEquals(bytes, store.text); assertEquals(original, engine.snapshot.session)
        assertTrue(draft.dirty)
    }
    @Test fun oneTwentyAndHundredSegments() {
        for (count in listOf(1, 3, 20, 100)) {
            val session = Session("count-$count", 1, 0, RecordingState.Paused,
                List(count) { Segment(it + 1, "/$it.mp4", 1000, 0, 90, id = "s$it") })
            store.save(session)
            val runtime = engine(); runtime.restore()
            assertTrue(runtime.saveShelfOrder(session, session.orderedSegmentIds.reversed()).success)
            assertEquals(session.playbackOrder.reversed(), runtime.snapshot.session!!.playbackOrder)
        }
    }
    @Test fun allDiscardedAndEmptySessionsRemainValid() {
        val session = fixture(discarded = setOf(1, 2, 3))
        assertTrue(engine.saveShelfOrder(session, session.orderedSegmentIds.reversed()).success)
        assertTrue(engine.snapshot.session!!.playbackOrder.isEmpty())
        assertTrue(Session("empty", 1, 0, RecordingState.Paused).validShelfOrder(emptyList()))
    }
    @Test fun recordingFinalisingCompletedAndUnresolvedCannotCommit() {
        val session = fixture(discarded = emptySet())
        engine.resume(0)
        assertFalse(engine.saveShelfOrder(session, session.orderedSegmentIds.reversed()).success)
        events(RecorderEvent.Started)
        assertFalse(engine.saveShelfOrder(session, session.orderedSegmentIds.reversed()).success)
        engine.pause()
        assertFalse(engine.saveShelfOrder(session, session.orderedSegmentIds.reversed()).success)
        events(RecorderEvent.Finalized(1000))
        engine.stop()
        assertFalse(engine.saveShelfOrder(session, session.orderedSegmentIds.reversed()).success)
    }
    @Test fun checkpointRoundtripIsSeparateAndRejectsInvalidIds() {
        val original = fixture()
        val draft = InlineReorderDraft(original).move("id-3", 0)
        val text = store.text
        assertEquals(draft, InlineReorderDraft.decode(draft.encode()))
        assertEquals(text, store.text)
        val corrupt = JSONObject(draft.encode()).put("ids", JSONArray(listOf("id-1", "id-1", "id-3")))
        try { InlineReorderDraft.decode(corrupt.toString()); fail("Invalid checkpoint accepted") }
        catch (_: IllegalStateException) { }
    }
    @Test fun noOpDoesNotWrite() {
        val session = fixture(); val writes = store.writes
        assertTrue(engine.saveShelfOrder(session, session.orderedSegmentIds).success)
        assertEquals(writes, store.writes)
    }
    @Test fun legacyV6RetainsDiscardedSlotsAndExistingPlayback() {
        val original = fixture().copy(playbackOrder = listOf("id-3", "id-1"))
        val legacy = JSONObject(SessionCodec.encode(original)).put("version", 6).apply { remove("shelfOrder") }
        val decoded = SessionCodec.decode(legacy.toString())
        assertEquals(listOf("id-3", "id-2", "id-1"), decoded.orderedSegmentIds)
        assertNull(decoded.shelfOrder)
        assertEquals(original.segments, decoded.segments)
    }
    @Test fun corruptShelfMetadataCannotSilentlyNormalise() {
        val original = fixture()
        for (ids in listOf(listOf("id-1"), listOf("id-3", "id-2", "id-1"),
            listOf("id-1", "id-1", "id-3"), listOf("id-1", "foreign", "id-3"))) {
            val bad = JSONObject(SessionCodec.encode(original)).put("shelfOrder", JSONArray(ids))
            try { SessionCodec.decode(bad.toString()); fail("Invalid shelf accepted") }
            catch (_: IllegalStateException) { }
        }
    }
    @Test fun discardRestoreLegacyArrangeAndNewCaptureKeepShelfConsistent() {
        val session = fixture()
        engine.saveShelfOrder(session, listOf("id-3", "id-2", "id-1"))
        engine.setSegmentKept(session.id, "id-3", false)
        engine.setSegmentKept(session.id, "id-3", true)
        var current = engine.snapshot.session!!
        assertEquals(listOf("id-2", "id-1", "id-3"), current.orderedSegmentIds)
        assertTrue(engine.savePlaybackOrder(session.id, current.playbackOrder, current.playbackOrder.reversed()).success)
        current = engine.snapshot.session!!
        assertEquals(listOf("id-2", "id-3", "id-1"), current.orderedSegmentIds)
        engine.resume(0); events(RecorderEvent.Started); engine.pause(); events(RecorderEvent.Finalized(1000))
        current = engine.snapshot.session!!
        assertEquals(current.segments.last().id, current.orderedSegmentIds.last())
        assertEquals(current, SessionCodec.decode(SessionCodec.encode(current)))
    }
    @Test fun invalidMoveAndDraftStaleOrderAreSafe() {
        val original = fixture(); val draft = InlineReorderDraft(original)
        assertEquals(draft, draft.move("missing", 0))
        assertEquals(draft, draft.move("id-1", -1))
        assertEquals(draft, draft.move("id-1", 3))
        assertFalse(draft.matches(original.copy(playbackOrder = original.playbackOrder.reversed())))
    }
}
