package com.calvinmazhindu.skitcam.recording

import com.calvinmazhindu.skitcam.export.*
import com.calvinmazhindu.skitcam.trim.TrimRange
import org.junit.Assert.*
import org.junit.Test

class ExportSpecTest {
    private fun session()=Session("test",1,0,RecordingState.Completed,List(3) { Segment(it+1,"/source/$it.mp4",2000,0,90,id="id$it",trim=TrimRange.full(2000)) })
    @Test fun retainedSavedOrderAndUserCuts() {
        val s=session();val modified=s.copy(segments=s.segments.mapIndexed { i,v -> if(i==1)v.copy(kept=false) else v.copy(trim=TrimRange(2000,300,1800)) },playbackOrder=listOf("id2","id0"))
        val spec=ExportSpec.from(modified){2000};assertEquals(listOf("id2","id0"),spec.clips.map{it.id});assertEquals(3000L,spec.durationMs)
    }
    @Test fun emptyAndSingle() { assertTrue(ExportSpec.from(session().copy(segments=emptyList(),playbackOrder=emptyList())){2000}.clips.isEmpty());val s=session();assertEquals(1,ExportSpec.from(s.copy(segments=s.segments.take(1),playbackOrder=listOf("id0"))){2000}.clips.size) }
    @Test fun legacyFullIntentResolutionDoesNotChangeSession() { val s=session().copy(segments=session().segments.map{it.copy(trim=null)});val spec=ExportSpec.from(s){1999};assertEquals(1999L,spec.clips.first().range.trimEndMs);assertNull(s.segments.first().trim) }
    @Test fun malformedAndOutOfBoundsReject() { for(seg in listOf(session().segments[0].copy(invalidTrimJson="{}"),session().segments[0].copy(trim=TrimRange(2000,100,1900)))) { val s=session().copy(segments=listOf(seg),playbackOrder=listOf(seg.id));try {ExportSpec.from(s){1800};fail()}catch(_:IllegalArgumentException){} } }
    @Test fun invalidOrderAndUnfinalisedReject() { for(s in listOf(session().copy(playbackOrder=listOf("id0")),session().copy(pending=PendingSegment("p","/pending",4)),session().copy(state=RecordingState.Recording),session().copy(error="failed"))) { try{ExportSpec.from(s){2000};fail()}catch(_:IllegalArgumentException){} } }
    @Test fun incompatibleOrientationRejects() {val s=session();try{ExportSpec.from(s.copy(segments=s.segments.map{it.copy(orientationDegrees=90)})){2000};fail()}catch(_:IllegalArgumentException){} }
    @Test fun hundredSegmentsRemainOrdered() {val segments=List(100){session().segments[0].copy(id="$it",order=it+1)};val s=session().copy(segments=segments,playbackOrder=segments.map{it.id}.reversed());assertEquals(100,ExportSpec.from(s){2000}.clips.size)}
    @Test fun terminalStatusDoesNotLockRecording() {for(s in ExportState.entries) assertEquals(s in setOf(ExportState.Preparing,ExportState.Exporting,ExportState.Verifying,ExportState.Publishing),ExportStatus("id",s).active)}
    @Test fun negativeAacPrerollIsAccepted() { val order=PresentationOrder();order.accept(-12000);order.accept(9333);order.accept(30666);assertEquals(-12000L,order.first);assertEquals(30666L,order.last) }
    @Test fun genuineTimestampRegressionRejected() { val order=PresentationOrder();order.accept(10000);try{order.accept(9999);fail()}catch(_:IllegalArgumentException){} }
    @Test fun completedCannotRestartAndFailureCanRetry() {assertFalse(exportTransitionAllowed(ExportState.Completed,ExportState.Exporting));assertTrue(exportTransitionAllowed(ExportState.Failed,ExportState.Preparing));assertFalse(exportTransitionAllowed(ExportState.Publishing,ExportState.Cancelled))}
    @Test fun staleDurationRejectsSavedCutEvenWhenEndIsInBounds() {
        val s=session().let { it.copy(segments=it.segments.map { segment -> segment.copy(trim=TrimRange(2000,300,1500)) }) }
        try { ExportSpec.from(s) { 2500 };fail("Stale source facts must not change a user's cut") } catch(_: IllegalArgumentException) { }
        assertEquals(1500L,ExportSpec.from(s) { 1999 }.clips.first().range.trimEndMs)
    }
    @Test fun unavailableDurationAndSourceFailureAreNotSkipped() {
        for(value in listOf(0L,-1L,Long.MAX_VALUE)) {
            try { ExportSpec.from(session()) { value };fail() } catch(_: IllegalArgumentException) { }
        }
        try { ExportSpec.from(session()) { error("Missing/corrupt source") };fail() } catch(_: IllegalStateException) { }
    }
    @Test fun snapshotSurvivesLaterDecisionsAndRoundTrip() {
        val original=session()
        val frozen=ExportSpec.from(original) { 2000 }
        val later=original.copy(segments=original.segments.map { it.copy(kept=false) },playbackOrder=emptyList())
        assertEquals(3,frozen.clips.size)
        assertEquals(0,ExportSpec.from(later) { 2000 }.clips.size)
        assertEquals(frozen,ExportSpec.from(SessionCodec.decode(SessionCodec.encode(original))) { 2000 })
    }
    @Test fun profileIsPartOfImmutableExportContract() {
        val profile=com.calvinmazhindu.skitcam.video.CaptureProfile("0",com.calvinmazhindu.skitcam.video.VideoSettings(fps=30),1920,1080)
        val original=session().copy(captureProfile=profile)
        val spec=ExportSpec.from(original) { 2000 }
        assertEquals(profile,spec.captureProfile)
        assertEquals(spec,ExportSpec.from(SessionCodec.decode(SessionCodec.encode(original))) { 2000 })
        assertNull(ExportSpec.from(session()) { 2000 }.captureProfile)
        assertThrows(IllegalArgumentException::class.java) { ExportSpec.from(original.copy(captureProfile=null,invalidProfileJson="{}")) { 2000 } }
    }
    @Test fun stageTransitionsCannotPublishBeforeVerification() {
        assertFalse(exportTransitionAllowed(ExportState.Exporting,ExportState.Completed))
        assertFalse(exportTransitionAllowed(ExportState.Verifying,ExportState.Completed))
        assertTrue(exportTransitionAllowed(ExportState.Verifying,ExportState.Publishing))
        assertTrue(exportTransitionAllowed(ExportState.Publishing,ExportState.Failed))
        assertTrue(exportTransitionAllowed(ExportState.Cancelled,ExportState.Preparing))
        assertFalse(exportTransitionAllowed(ExportState.Empty,ExportState.Exporting))
    }
}
