package com.calvinmazhindu.skitcam.recording

import com.calvinmazhindu.skitcam.arrange.ArrangementDraft
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class ArrangementTest {
    private class Store : SessionStore {
        override val rootPath = "/arrangement-tests"
        var text: String? = null
        var broken = false
        var writes = 0
        override fun save(session: Session) {
            check(!broken) { "Disk failed" }
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
            events = listener
            return RecordingHandle {}
        }
    }, store, Watchdog { Cancellation {} })
    private val engine = engine()
    private fun fixture(count: Int = 3): Session {
        val session = Session("session", 1, 0, RecordingState.Paused,
            List(count) { Segment(it + 1, "/files/segment_${it + 1}.mp4", 1000, 0, 90, id = "id-${it + 1}") })
        store.save(session); engine.restore()
        return session
    }
    @Test fun savesPermutationWithoutChangingChronologyOrFilesOrCounts() {
        val original = fixture(); val reversed = original.playbackOrder.reversed()
        assertTrue(engine.savePlaybackOrder(original.id, original.playbackOrder, reversed).success)
        assertEquals(original.segments, engine.snapshot.session!!.segments)
        assertEquals(reversed, engine.snapshot.session!!.playbackOrder)
        assertEquals(listOf(3, 2, 1), engine.snapshot.session!!.playbackSegments.map { it.order })
        assertEquals(3, engine.snapshot.count); assertEquals(3000L, engine.snapshot.durationMs)
        val restored = engine(); restored.restore()
        assertEquals(engine.snapshot.session, restored.snapshot.session)
        restored.stop()
        assertEquals(reversed, store.loadLatest()!!.playbackOrder)
    }
    @Test fun discardRestoreAndNewRecordingAppendWithoutDisturbingOthers() {
        val session = fixture(); val ids = session.playbackOrder
        engine.savePlaybackOrder(session.id, ids, listOf(ids[2], ids[0], ids[1]))
        engine.setSegmentKept(session.id, ids[0], false)
        assertEquals(listOf(ids[2], ids[1]), engine.snapshot.session!!.playbackOrder)
        engine.setSegmentKept(session.id, ids[0], true)
        val expected = listOf(ids[2], ids[1], ids[0])
        assertEquals(expected, engine.snapshot.session!!.playbackOrder)
        engine.setSegmentKept(session.id, ids[0], true)
        assertEquals(expected, engine.snapshot.session!!.playbackOrder)
        engine.resume(0); events(RecorderEvent.Started); engine.pause(); events(RecorderEvent.Finalized(1000))
        val updated = engine.snapshot.session!!
        assertEquals(expected, updated.playbackOrder.dropLast(1))
        assertEquals(updated.segments.last().id, updated.playbackOrder.last())
        assertEquals(listOf(1, 2, 3, 4), updated.segments.map { it.order })
    }
    @Test fun invalidRequestsNeverWrite() {
        val session = fixture(); val ids = session.playbackOrder; val writes = store.writes
        for (order in listOf(emptyList(), ids.dropLast(1), ids + ids[0], listOf(ids[0], ids[1], "foreign"))) {
            assertFalse(engine.savePlaybackOrder(session.id, ids, order).success)
        }
        assertFalse(engine.savePlaybackOrder("other-session", ids, ids.reversed()).success)
        assertFalse(engine.savePlaybackOrder(session.id, ids.reversed(), ids).success)
        assertEquals(writes, store.writes)
        assertEquals(session, engine.snapshot.session)
    }
    @Test fun discardedAndStaleReferencesAreRejected() {
        val original = fixture(); val ids = original.playbackOrder
        engine.setSegmentKept(original.id, ids[0], false)
        val current = engine.snapshot.session!!.playbackOrder
        assertFalse(engine.savePlaybackOrder(original.id, ids, ids.reversed()).success)
        assertFalse(engine.savePlaybackOrder(original.id, current, ids).success)
        assertTrue(engine.savePlaybackOrder(original.id, current, current.reversed()).success)
    }
    @Test fun diskFailureRetainsLastOrderAndPausedState() {
        val original = fixture(); val bytes = store.text
        store.broken = true
        assertFalse(engine.savePlaybackOrder(original.id, original.playbackOrder, original.playbackOrder.reversed()).success)
        assertEquals(bytes, store.text); assertEquals(original, engine.snapshot.session)
        assertEquals(RecordingState.Paused, engine.snapshot.state)
    }
    @Test fun invalidRecordingStatesRejectSave() {
        assertFalse(engine.savePlaybackOrder("none", emptyList(), emptyList()).success)
        val original = fixture(); val ids = original.playbackOrder
        engine.resume(0)
        assertFalse(engine.savePlaybackOrder(original.id, ids, ids.reversed()).success)
        events(RecorderEvent.Started)
        assertFalse(engine.savePlaybackOrder(original.id, ids, ids.reversed()).success)
        engine.pause()
        assertFalse(engine.savePlaybackOrder(original.id, ids, ids.reversed()).success)
        events(RecorderEvent.Finalized(1000)); engine.resume(0); events(RecorderEvent.Started); engine.stop()
        assertFalse(engine.savePlaybackOrder(original.id, ids, ids).success)
        events(RecorderEvent.Finalized(1000, "Source lost"))
        assertFalse(engine.savePlaybackOrder(original.id, ids, ids).success)
        engine.stop()
        assertFalse(engine.savePlaybackOrder(original.id, ids, ids).success)
    }
    @Test fun draftMovesAndCancelHaveNoPersistentSideEffects() {
        val session = fixture(); val bytes = store.text; val writes = store.writes
        val draft = ArrangementDraft.from(session)
        val changed = draft.move(session.segments[2].id, 0)
        assertEquals(listOf("id-3", "id-1", "id-2"), changed.ids)
        assertEquals(session.playbackOrder, draft.baseline)
        assertEquals(changed, changed.move("missing", 1))
        assertEquals(changed, changed.move("id-3", -1))
        assertEquals(changed, changed.move("id-3", 3))
        assertEquals(bytes, store.text); assertEquals(writes, store.writes)
        assertEquals(session.playbackOrder, engine().apply { restore() }.snapshot.session!!.playbackOrder)
    }
    @Test fun legacyMigrationIsDeterministicAndDoesNotWrite() {
        val session = fixture()
        for (version in listOf(1, 2)) {
            val json = JSONObject(SessionCodec.encode(session)).put("version", version)
            json.remove("playbackOrder")
            repeat(3) { json.getJSONArray("segments").getJSONObject(it).remove("id") }
            if (version == 1) repeat(3) { json.getJSONArray("segments").getJSONObject(it).remove("kept") }
            val text = json.toString()
            val first = SessionCodec.decode(text); val second = SessionCodec.decode(text)
            assertEquals(first, second)
            assertEquals(first.segments.map { it.id }, first.playbackOrder)
            assertEquals(session.segments.map { it.path }, first.segments.map { it.path })
            assertEquals(3, first.segments.map { it.id }.distinct().size)
            assertEquals(first, SessionCodec.decode(SessionCodec.encode(first)))
        }
    }
    @Test fun corruptV3OrderOrIdentityCannotBeSilentlyNormalised() {
        val original = fixture()
        val encoded = SessionCodec.encode(original)
        for (kind in 0..3) {
            val json = JSONObject(encoded)
            when (kind) {
                0 -> json.getJSONArray("playbackOrder").put(0, "missing")
                1 -> json.getJSONArray("playbackOrder").put(0, "id-2")
                2 -> json.getJSONArray("segments").getJSONObject(0).put("id", "id-2")
                3 -> json.getJSONArray("segments").getJSONObject(0).put("kept", false)
            }
            try { SessionCodec.decode(json.toString()); fail("Invalid metadata must fail safely") }
            catch (_: IllegalStateException) { /* Original MP4s are untouched. */ }
        }
    }
    @Test fun noOpSaveDoesNotWriteAndEmptyRetainedOrderCanComplete() {
        val session = fixture(); val writes = store.writes
        assertTrue(engine.savePlaybackOrder(session.id, session.playbackOrder, session.playbackOrder).success)
        assertEquals(writes, store.writes)
        session.segments.forEach { engine.setSegmentKept(session.id, it.id, false) }
        assertTrue(engine.snapshot.session!!.playbackOrder.isEmpty())
        engine.stop(); assertEquals(3, store.loadLatest()!!.segments.size)
    }
    @Test fun arrangingAtCapDoesNotAffectLimit() {
        val session = fixture(100)
        assertTrue(engine.savePlaybackOrder(session.id, session.playbackOrder, session.playbackOrder.reversed()).success)
        engine.resume(0)
        assertEquals(RecordingState.Paused, engine.snapshot.state)
        engine.setSegmentKept(session.id, "id-50", false)
        assertEquals(99, engine.snapshot.count)
        assertTrue(engine.setSegmentKept(session.id, "id-50", true).success)
        assertEquals("id-50", engine.snapshot.session!!.playbackOrder.last())
    }
}
