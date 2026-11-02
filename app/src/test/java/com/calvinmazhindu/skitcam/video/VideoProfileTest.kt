package com.calvinmazhindu.skitcam.video

import com.calvinmazhindu.skitcam.recording.*
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class VideoProfileTest {
    private val profile=CaptureProfile("0",VideoSettings(),1920,1080)
    private val facts=SegmentMediaFacts(1920,1080,90,"video/avc","audio/mp4a-latm",48000,2,31,0,1_000_000,33333,33334,false,2_500_000,1_033_333)
    @Test fun conservativeDefaults() { assertEquals(VideoSettings("FHD",null,false,"AUTO"),VideoSettings());assertTrue(VideoSettings().valid()) }
    @Test fun excludesUnsupportedStabilizationCombinations() {
        assertFalse(VideoSettings("UHD",stabilization=true).valid())
        assertFalse(VideoSettings("FHD",60,true).valid())
        assertTrue(VideoSettings("FHD",30,true).valid())
    }
    @Test fun malformedSettingsRejected() {
        assertFalse(VideoSettings("8K").valid());assertFalse(VideoSettings(fps=120).valid());assertFalse(VideoSettings(whiteBalance="MANUAL_KELVIN").valid())
    }
    @Test fun requestedAndObservedRemainSeparate() {
        assertNull(profile.settings.fps);assertEquals(30.0,facts.averageFps!!,.0001)
        assertTrue(facts.averageVideoBitrate>0);assertFalse(facts.variableCadence)
        assertEquals(30,profile.copy(settings=VideoSettings(fps=30)).settings.fps)
    }
    @Test fun variableAndReorderedCadenceReported() {
        assertTrue(facts.copy(maximumIntervalUs=66667).variableCadence)
        assertTrue(facts.copy(reordered=true,minimumIntervalUs=null,maximumIntervalUs=null).variableCadence)
        assertNull(facts.copy(videoFrames=1,lastPtsUs=0).averageFps)
    }
    @Test fun codecAndDimensionMismatchesCannotRegister() {
        assertTrue(profile.accepts(facts));assertTrue(profile.accepts(facts.copy(width=1080,height=1920)))
        assertFalse(profile.accepts(facts.copy(width=1280,height=720)))
        assertFalse(profile.accepts(facts.copy(videoMime="video/hevc")))
        assertFalse(profile.accepts(facts.copy(audioMime="audio/opus")))
    }
    @Test fun profileIdentityIncludesAllFrozenControls() {
        assertFalse(profile.sameConfiguration(profile.copy(cameraId="1")))
        for(setting in listOf(VideoSettings("HD"),VideoSettings(fps=30),VideoSettings(stabilization=true),VideoSettings(whiteBalance="DAYLIGHT")))
            assertFalse(profile.sameConfiguration(profile.copy(settings=setting)))
        assertTrue(profile.sameConfiguration(profile.copy(legacy=true)))
    }
    @Test fun schemaSixRoundTripPreservesProfileAndSourceFacts() {
        val segment=Segment(1,"/source.mp4",1000,0,90,id="clip",media=facts)
        val session=Session("session",1,0,RecordingState.Paused,listOf(segment),captureProfile=profile)
        assertEquals(session,SessionCodec.decode(SessionCodec.encode(session)))
    }
    @Test fun versionsOneThroughFiveDoNotGainInventedProfileFacts() {
        for(version in 1..5) {
            val json=JSONObject(SessionCodec.encode(Session("s",1,0,RecordingState.Paused))).put("version",version)
            json.remove("captureProfile")
            assertNull(SessionCodec.decode(json.toString()).captureProfile)
        }
    }
    @Test fun malformedContractIsPreservedForUnrelatedEdits() {
        val json=JSONObject(SessionCodec.encode(Session("s",1,0,RecordingState.Paused))).put("captureProfile",JSONObject().put("unknown","future"))
        val decoded=SessionCodec.decode(json.toString())
        assertNotNull(decoded.invalidProfileJson)
        assertEquals("future",JSONObject(SessionCodec.encode(decoded.copy(error="note"))).getJSONObject("captureProfile").getString("unknown"))
    }
    @Test fun invalidMediaPropertiesAreRejected() {
        assertFalse(facts.copy(trackDurationUs=0).valid());assertFalse(facts.copy(videoFrames=0).valid())
        assertFalse(facts.copy(lastPtsUs=-1).valid());assertFalse(facts.copy(audioChannels=0).valid())
    }
    @Test fun retainedHistoryDoesNotPermitProfileEditing() {
        for(state in RecordingState.entries) {
            val snapshot=RecordingSnapshot(state,Session("s",1,0,state))
            assertEquals(state in listOf(RecordingState.Completed),VideoProfilePolicy.canChange(snapshot,false,false))
        }
        assertTrue(VideoProfilePolicy.canChange(RecordingSnapshot(),false,false))
        assertFalse(VideoProfilePolicy.canChange(RecordingSnapshot(),true,false))
        assertFalse(VideoProfilePolicy.canChange(RecordingSnapshot(),false,true))
    }
    @Test fun predictableFallbackDoesNotPreferUnsupportedUhd() {
        assertEquals("FHD",VideoProfilePolicy.fallback(listOf("UHD","HD","FHD")))
        assertEquals("HD",VideoProfilePolicy.fallback(listOf("HD")))
        assertNull(VideoProfilePolicy.fallback(emptyList()))
    }
    @Test fun fractionalAndOverflowingMetadataRejected() {
        for(value in listOf(30.5, 4294967326L, "30")) {
            assertThrows(IllegalArgumentException::class.java) { VideoJson.settings(VideoJson.settings(VideoSettings()).put("fps",value)) }
        }
        assertThrows(IllegalArgumentException::class.java) { VideoJson.media(VideoJson.media(facts).put("videoFrames",31.1)) }
        assertFalse(facts.copy(firstPtsUs=Long.MIN_VALUE).valid())
        assertFalse(facts.copy(minimumIntervalUs=-1).valid())
        assertFalse(facts.copy(minimumIntervalUs=40000,maximumIntervalUs=33333).valid())
    }
    @Test fun storageBudgetScalesWithResolutionAndRate() {
        val hd=VideoProfilePolicy.reserveBytes(60_000,1280,720,null)
        val fhd=VideoProfilePolicy.reserveBytes(60_000,1920,1080,null)
        assertTrue(fhd>hd);assertTrue(VideoProfilePolicy.reserveBytes(60_000,1920,1080,60)>fhd)
        assertThrows(ArithmeticException::class.java) { VideoProfilePolicy.reserveBytes(Long.MAX_VALUE,3840,2160,60) }
        assertThrows(IllegalArgumentException::class.java) { VideoProfilePolicy.reserveBytes(-1,1920,1080,null) }
    }
}
