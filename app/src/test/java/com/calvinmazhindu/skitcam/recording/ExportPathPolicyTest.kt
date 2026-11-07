package com.calvinmazhindu.skitcam.recording

import com.calvinmazhindu.skitcam.export.*
import com.calvinmazhindu.skitcam.trim.TrimRange
import com.calvinmazhindu.skitcam.video.*
import org.junit.Assert.*
import org.junit.Test

class ExportPathPolicyTest {
    private val profile=CaptureProfile("0",VideoSettings(),1920,1080)
    private val facts=ExportInputFacts(1920,1080,90,"video/avc",listOf("sps","pps"),listOf(1,2,3),
        true,true,true,0,9900000,false,"audio/mp4a-latm",48000,2,true,128000,0,9900000,10000,false)
    private fun spec(count: Int=1)=ExportSpec("s",0,List(count) { ExportClip("$it","/$it.mp4",TrimRange.full(10000)) },profile)
    @Test fun singleWholeCompatibleClipCanBeCopied() { assertEquals(ExportPath.DIRECT_COPY,ExportPathPolicy.choose(spec(),listOf(facts)).path) }
    @Test fun capture256kAudioRetainsEstablished128kAudioRenderer() { assertEquals(ExportPath.VIDEO_PASSTHROUGH_AUDIO_RENDER,ExportPathPolicy.choose(spec(),listOf(facts.copy(audioBitrate=256000))).path) }
    @Test fun unknownAudioBitrateIsNotCopyEligibility() { assertEquals(ExportPath.VIDEO_PASSTHROUGH_AUDIO_RENDER,ExportPathPolicy.choose(spec(),listOf(facts.copy(audioBitrate=null))).path) }
    @Test fun monoAudioIsConvertedNotCopied() { assertEquals(ExportPath.VIDEO_PASSTHROUGH_AUDIO_RENDER,ExportPathPolicy.choose(spec(),listOf(facts.copy(audioChannels=1))).path) }
    @Test fun positiveAudioGapOverGateRequiresAudioCorrection() { assertEquals(ExportPath.VIDEO_PASSTHROUGH_AUDIO_RENDER,ExportPathPolicy.choose(spec(),listOf(facts.copy(firstAudioUs=120000))).path) }
    @Test fun compatibleMultipleClipsPreserveVideoButRenderAudio() { assertEquals(ExportPath.VIDEO_PASSTHROUGH_AUDIO_RENDER,ExportPathPolicy.choose(spec(5),List(5) { facts }).path) }
    @Test fun mismatchedSpsPpsRenders() { assertEquals(ExportPath.RENDER,ExportPathPolicy.choose(spec(2),listOf(facts,facts.copy(videoConfig=listOf("other")))).path) }
    @Test fun mismatchedRotationRenders() { assertEquals(ExportPath.RENDER,ExportPathPolicy.choose(spec(2),listOf(facts,facts.copy(rotation=270))).path) }
    @Test fun mismatchedGeometryRenders() { assertEquals(ExportPath.RENDER,ExportPathPolicy.choose(spec(),listOf(facts.copy(width=1280,height=720))).path) }
    @Test fun bFrameOrNonzeroVideoOriginsAreConservativeFallbacks() {
        for(f in listOf(facts.copy(reordered=true),facts.copy(firstVideoUs=50000),facts.copy(startsWithKeyFrame=false)))
            assertEquals(ExportPath.RENDER,ExportPathPolicy.choose(spec(),listOf(f)).path)
    }
    @Test fun trimmedClipsKeepRenderer() {
        val trimmed=spec().copy(clips=listOf(ExportClip("0","/0.mp4",TrimRange(10000,300,9700))))
        assertEquals(ExportPath.RENDER,ExportPathPolicy.choose(trimmed,listOf(facts)).path)
    }
    @Test fun legacyJobsKeepBitrateGeometryAndCadencePolicy() {
        assertEquals(ExportPath.RENDER,ExportPathPolicy.choose(spec().copy(captureProfile=null),listOf(facts)).path)
        assertEquals(ExportPath.RENDER,ExportPathPolicy.choose(spec().copy(captureProfile=profile.copy(legacy=true)),listOf(facts)).path)
    }
    @Test fun unknownHdrEncryptedOrNonSquareSourcesRender() {
        for(f in listOf(facts.copy(sdr=false),facts.copy(encrypted=true),facts.copy(squarePixels=false),facts.copy(videoConfig=emptyList())))
            assertEquals(ExportPath.RENDER,ExportPathPolicy.choose(spec(),listOf(f)).path)
    }
    @Test fun durationChangedAndIncompleteProbeRender() {
        assertEquals(ExportPath.RENDER,ExportPathPolicy.choose(spec(),listOf(facts.copy(durationMs=10500))).path)
        assertEquals(ExportPath.RENDER,ExportPathPolicy.choose(spec(2),listOf(facts)).path)
    }
}
