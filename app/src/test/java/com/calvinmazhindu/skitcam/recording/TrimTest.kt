package com.calvinmazhindu.skitcam.recording

import com.calvinmazhindu.skitcam.trim.*
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class TrimTest {
    private val facts = SourceFacts(4000, 12345, 1)
    private class Store : SessionStore {
        override val rootPath = "/test"
        var text: String? = null
        var broken = false
        var facts: SourceFacts? = SourceFacts(4000,12345,1)
        override fun save(session: Session) { check(!broken); text = SessionCodec.encode(session) }
        override fun loadLatest() = text?.let(SessionCodec::decode)
        override fun freeBytes() = Long.MAX_VALUE
        override fun validate(path: String) = 90
        override fun sourceFacts(path: String) = facts
    }
    private val store = Store()
    private lateinit var event: (RecorderEvent) -> Unit
    private fun engine() = RecordingEngine(object : RecordingPort {
        override fun start(pending: PendingSegment, orientationDegrees: Int, listener: (RecorderEvent) -> Unit): RecordingHandle {
            event=listener; return RecordingHandle {}
        }
    },store,Watchdog { Cancellation {} })
    private fun fixture(): Pair<RecordingEngine, Session> {
        val s = Session("s",1,0,RecordingState.Paused,List(3) { Segment(it+1,"/video/$it",3990,0,90,id="id$it",trim=TrimRange.full(4000)) })
        store.save(s); return engine().apply { restore() } to s
    }
    @Test fun fullAndEffective() { val t=TrimRange.full(4000); assertTrue(t.valid()); assertFalse(t.isTrimmed); assertEquals(4000,t.effectiveDurationMs) }
    @Test fun validIntervals() { assertTrue(TrimRange(4000,100,600).valid()); assertTrue(TrimRange(4000,500,4000).valid()) }
    @Test fun invalidAndOverflow() {
        for (r in listOf(TrimRange(0,0,0),TrimRange(4000,-1,1000),TrimRange(4000,2000,1000),TrimRange(4000,0,4001),TrimRange(Long.MAX_VALUE,0,Long.MAX_VALUE),TrimRange(4000,0,499))) assertFalse(r.valid())
    }
    @Test fun shortSourceFullOnly() { for (d in listOf(1L,100L,499L)) { assertTrue(TrimRange.full(d).valid()); assertFalse(TrimRange(d,1,d).valid()) } }
    @Test fun handlesDoNotCross() { val t=TrimRange(4000,1000,2000); assertEquals(1500,t.start(Long.MAX_VALUE).trimStartMs); assertEquals(1500,t.end(0).trimEndMs) }
    @Test fun saveAndRecoveryAndStop() {
        val (e,s)=fixture(); val original=s.segments[0]; val r=TrimRange(4000,100,3000)
        assertTrue(e.saveTrim(s.id,original.id,original,TrimSource(facts,4000),r).success)
        val restored=engine().apply { restore() }; assertEquals(r,restored.snapshot.session!!.segments[0].trim)
        restored.stop(); assertEquals(r,store.loadLatest()!!.segments[0].trim)
    }
    @Test fun cancelAndResetDoNotWrite() { val (_,s)=fixture(); val bytes=store.text; val draft=s.segments[0].trim!!.start(1000); assertTrue(draft.isTrimmed); assertEquals(TrimRange.full(4000),TrimRange.full(draft.sourceDurationMs)); assertEquals(bytes,store.text) }
    @Test fun decisionsAndArrangementPreserveAllTrims() {
        val (e,s)=fixture(); for (seg in s.segments) assertTrue(e.saveTrim(s.id,seg.id,seg,TrimSource(facts,4000),TrimRange(4000,100,3000)).success)
        val ids=s.playbackOrder; e.savePlaybackOrder(s.id,ids,ids.reversed()); e.setSegmentKept(s.id,ids[0],false);e.setSegmentKept(s.id,ids[0],true)
        assertTrue(e.snapshot.session!!.segments.all { it.trim == TrimRange(4000,100,3000) });assertEquals(8700,e.snapshot.durationMs)
    }
    @Test fun staleAndInvalidIdentityReject() { val (e,s)=fixture();val seg=s.segments[0]; val r=TrimRange(4000,100,1000); assertFalse(e.saveTrim("foreign",seg.id,seg,TrimSource(facts,4000),r).success); assertFalse(e.saveTrim(s.id,"missing",seg,TrimSource(facts,4000),r).success);e.saveTrim(s.id,seg.id,seg,TrimSource(facts,4000),r);assertFalse(e.saveTrim(s.id,seg.id,seg,TrimSource(facts,4000),r).success) }
    @Test fun missingCorruptOrChangedSourceReject() { val(e,s)=fixture();val seg=s.segments[0];store.facts=null;assertFalse(e.saveTrim(s.id,seg.id,seg,TrimSource(facts,4000),seg.trim!!).success);store.facts=facts.copy(length=2);assertFalse(e.saveTrim(s.id,seg.id,seg,TrimSource(facts,4000),seg.trim).success); assertEquals(s,e.snapshot.session) }
    @Test fun failedAtomicWriteKeepsPrevious() { val(e,s)=fixture();val bytes=store.text;store.broken=true;assertFalse(e.saveTrim(s.id,s.segments[0].id,s.segments[0],TrimSource(facts,4000),TrimRange(4000,100,1000)).success);assertEquals(bytes,store.text);assertEquals(s,e.snapshot.session) }
    @Test fun recordingStatesReject() { val(e,s)=fixture(); val seg=s.segments[0]; e.resume(0); assertFalse(e.saveTrim(s.id,seg.id,seg,TrimSource(facts,4000),seg.trim!!).success);event(RecorderEvent.Started);assertFalse(e.saveTrim(s.id,seg.id,seg,TrimSource(facts,4000),seg.trim).success);e.pause();assertFalse(e.saveTrim(s.id,seg.id,seg,TrimSource(facts,4000),seg.trim).success) }
    @Test fun legacyVersionsRemainUnverified() { val(_,s)=fixture();for(v in 1..3){ val j=JSONObject(SessionCodec.encode(s)).put("version",v); val a=j.getJSONArray("segments");repeat(a.length()){a.getJSONObject(it).remove("trim")};val loaded=SessionCodec.decode(j.toString());assertTrue(loaded.segments.all{it.trim==null});assertEquals(11970,loaded.durationMs) } }
    @Test fun malformedTrimRoundTripsWithoutBlockingOtherSegments() { val(_,s)=fixture();for(value in listOf(-1,1.5,"bad",JSONObject().put("sourceDurationMs",4000).put("trimStartMs",1.5).put("trimEndMs",3000),JSONObject().put("sourceDurationMs",4000).put("trimStartMs",3000).put("trimEndMs",1000))){val j=JSONObject(SessionCodec.encode(s));j.getJSONArray("segments").getJSONObject(0).put("trim",value);val loaded=SessionCodec.decode(j.toString());assertNotNull(loaded.segments[0].invalidTrimJson);assertNull(loaded.segments[0].effectiveDurationMs);val again=SessionCodec.decode(SessionCodec.encode(loaded));assertEquals(loaded,again)} }
    @Test fun mismatchedDurationDoesNotRewriteUntilSave() {val(e,s)=fixture();val before=store.text;val range=TrimRange(3900,100,3800);assertTrue(range.valid());assertEquals(before,store.text);assertTrue(e.saveTrim(s.id,s.segments[0].id,s.segments[0],TrimSource(facts,3900),range).success);assertEquals(3990,e.snapshot.session!!.segments[0].durationMs)}
    @Test fun aggregateOverflowSaturates() {assertEquals(Long.MAX_VALUE,safeDurationSum(listOf(Long.MAX_VALUE,1)))}
    @Test fun newSegmentDefaultsToVerifiedFullRange() { val e=engine(); e.start(0);event(RecorderEvent.Started);e.pause();event(RecorderEvent.Finalized(3990));assertEquals(TrimRange.full(4000),e.snapshot.session!!.segments.single().trim) }
    @Test fun unverifiedDurationCannotBeSaved() { val(e,s)=fixture();val seg=s.segments[0];assertFalse(e.saveTrim(s.id,seg.id,seg,TrimSource(facts,4000),TrimRange.full(5000)).success) }

    @Test fun reconcileFullIntentOnlyInDraftAndPreserveUserCuts() {
        val original=TrimRange.full(4000)
        assertEquals(TrimRange.full(3990),original.withObservedDuration(3990))
        assertEquals(4000L,original.trimEndMs)
        val cut=TrimRange(4000,300,3900)
        val invalid=cut.withObservedDuration(3800)
        assertEquals(3900L,invalid.trimEndMs);assertFalse(invalid.valid())
    }

}
