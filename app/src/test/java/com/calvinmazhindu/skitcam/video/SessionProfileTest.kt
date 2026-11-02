package com.calvinmazhindu.skitcam.video

import com.calvinmazhindu.skitcam.recording.*
import org.junit.Assert.*
import org.junit.Test

class SessionProfileTest {
    private val profile=CaptureProfile("0",VideoSettings(),1920,1080)
    private val media=SegmentMediaFacts(1920,1080,90,"video/avc","audio/mp4a-latm",48000,2,60,0,1966667,33333,33334,false,4000000,2000000)
    private var facts=media
    private var saved: Session?=null
    private var event: ((RecorderEvent)->Unit)?=null
    private var starts=0
    private val store=object : SessionStore {
        override val rootPath="/session"
        override fun freeBytes()=Long.MAX_VALUE
        override fun loadLatest()=saved
        override fun save(session: Session) { saved=SessionCodec.decode(SessionCodec.encode(session)) }
        override fun validate(path: String)=90
        override fun mediaFacts(path: String)=facts
    }
    private fun engine()=RecordingEngine(object : RecordingPort {
        override fun start(pending: PendingSegment,orientationDegrees: Int,listener: (RecorderEvent)->Unit): RecordingHandle {
            starts++;event=listener;return RecordingHandle { }
        }
    },store,Watchdog { Cancellation {} })
    private fun paused(engine: RecordingEngine) {
        engine.start(0,profile=profile);event!!(RecorderEvent.Started)
        engine.pause();event!!(RecorderEvent.Finalized(2000))
        assertEquals(RecordingState.Paused,engine.snapshot.state)
    }
    @Test fun profileJournaledBeforeRecorderStartsAndRetainedOnStop() {
        val engine=engine();engine.start(0,profile=profile)
        assertEquals(profile,saved!!.captureProfile)
        event!!(RecorderEvent.Started);engine.stop();event!!(RecorderEvent.Finalized(2000))
        assertEquals(RecordingState.Completed,saved!!.state)
        assertEquals(media,saved!!.segments.single().media)
        assertEquals(profile,saved!!.captureProfile)
    }
    @Test fun recoveringPausedProfileRejectsDifferentConfigurationWithoutNewFile() {
        paused(engine());val before=saved
        val recovered=engine();recovered.restore()
        recovered.resume(0) // A profiled session requires an actually applied profile.
        assertEquals(1,starts);assertEquals(before,saved)
        recovered.resume(0,profile.copy(settings=VideoSettings("HD"),width=1280,height=720))
        assertEquals(1,starts);assertEquals(RecordingState.Paused,recovered.snapshot.state);assertEquals(before,saved)
        recovered.resume(0,profile);assertEquals(2,starts)
    }
    @Test fun laterFormatMismatchPreservesEarlierSegmentAndCandidateJournal() {
        val engine=engine();paused(engine);val first=saved!!.segments.single()
        engine.resume(0,profile);event!!(RecorderEvent.Started);engine.pause()
        facts=media.copy(width=1280,height=720);event!!(RecorderEvent.Finalized(2000))
        assertEquals(RecordingState.Error,engine.snapshot.state)
        assertEquals(listOf(first),saved!!.segments);assertNotNull(saved!!.pending)
    }
    @Test fun decisionsOrderAndTrimFactsRemainIndependentFromProfile() {
        val engine=engine();paused(engine)
        engine.resume(0,profile);event!!(RecorderEvent.Started);engine.pause();event!!(RecorderEvent.Finalized(2000))
        val s=saved!!
        assertTrue(engine.savePlaybackOrder(s.id,s.playbackOrder,s.playbackOrder.reversed()).success)
        assertTrue(engine.setSegmentKept(s.id,s.segments[0].id,false).success)
        assertTrue(engine.setSegmentKept(s.id,s.segments[0].id,true).success)
        assertEquals(profile,saved!!.captureProfile);assertTrue(saved!!.segments.all { it.media==media })
    }
    @Test fun newSessionDoesNotInheritLegacyExportPolicyFromAnExistingBinding() {
        val legacy=profile.copy(legacy=true)
        saved=Session("old",1,0,RecordingState.Paused,captureProfile=legacy)
        val engine=engine();engine.restore();engine.stop()
        val previous=saved!!
        engine.start(0,profile=legacy)
        assertEquals(profile,saved!!.captureProfile)
        assertTrue(previous.captureProfile!!.legacy)
        assertNotEquals(previous.id,saved!!.id)
    }
    @Test fun legacySessionGainsOnlyExplicitlyAppliedProfileOnResume() {
        saved=Session("old",1,0,RecordingState.Paused,listOf(Segment(1,"/old.mp4",2000,0,90)))
        val engine=engine();engine.restore();assertNull(saved!!.captureProfile)
        engine.resume(0,profile.copy(legacy=true))
        assertEquals(profile.copy(legacy=true),saved!!.captureProfile)
    }
}
