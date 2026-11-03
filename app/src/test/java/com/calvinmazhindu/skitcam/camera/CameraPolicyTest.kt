package com.calvinmazhindu.skitcam.camera

import com.calvinmazhindu.skitcam.recording.*
import com.calvinmazhindu.skitcam.trim.TrimRange
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class CameraPolicyTest {
    @Test fun startupAndModeOrLensChangesResetZoom() {
        val video=CameraChoice();val photo=video.copy(mode=CameraMode.Photo)
        assertEquals(1f,CameraPolicy.zoomForBinding(null,video,4f),0f)
        assertEquals(1f,CameraPolicy.zoomForBinding(photo,video,4f),0f)
        assertEquals(1f,CameraPolicy.zoomForBinding(video,video.copy(lens=CameraLens.Front),4f),0f)
    }
    @Test fun sameChoiceRebindPreservesDeliberateZoom() {
        val video=CameraChoice()
        assertEquals(2.5f,CameraPolicy.zoomForBinding(video,video,2.5f),0f)
        assertEquals(1f,CameraPolicy.zoomForBinding(video,video,Float.NaN),0f)
    }
    @Test fun firstLaunchDefaultsToRearSkitVideo() { assertEquals(CameraChoice(CameraMode.SkitVideo,CameraLens.Rear),CameraChoice()) }
    @Test fun idleRestoresPhotoAndLensPreference() {
        val saved=CameraChoice(CameraMode.Photo,CameraLens.Front)
        assertEquals(saved,CameraPolicy.initial(saved,RecordingSnapshot(),false))
    }
    @Test fun unfinishedFrontSessionOverridesPhotoPreference() {
        val session=Session("s",1,270,RecordingState.Paused,lens=CameraLens.Front)
        assertEquals(CameraChoice(CameraMode.SkitVideo,CameraLens.Front),CameraPolicy.initial(CameraChoice(CameraMode.Photo),RecordingSnapshot(session.state,session),false))
    }
    @Test fun exportRecoveryOverridesPhotoPreference() {
        assertEquals(CameraMode.SkitVideo,CameraPolicy.initial(CameraChoice(CameraMode.Photo),RecordingSnapshot(),true).mode)
    }
    @Test fun recordingStatesAndPausedSessionBlockSwitching() {
        for(state in RecordingState.entries.filter { it !in setOf(RecordingState.Ready,RecordingState.Completed) }) {
            val session=Session("s",1,0,state)
            assertFalse(state.name,CameraPolicy.maySwitch(true,RecordingSnapshot(state,session),false,false))
        }
    }
    @Test fun loadingCaptureAndExportBlockSwitching() {
        val ready=RecordingSnapshot()
        assertFalse(CameraPolicy.maySwitch(false,ready,false,false))
        assertFalse(CameraPolicy.maySwitch(true,ready,true,false))
        assertFalse(CameraPolicy.maySwitch(true,ready,false,true))
        assertFalse(CameraPolicy.maySwitch(true,ready.copy(unresolved=true),false,false))
        assertTrue(CameraPolicy.maySwitch(true,ready,false,false))
    }
    @Test fun completedSessionCanSwitchWithoutMutatingHistory() {
        val session=Session("s",1,0,RecordingState.Completed,listOf(Segment(1,"/preserved.mp4",1500,0,90)))
        val snapshot=RecordingSnapshot(session.state,session)
        assertTrue(CameraPolicy.maySwitch(true,snapshot,false,false))
        assertEquals(session,snapshot.session)
    }
    @Test fun zoomClampsToRealLimitsAndRejectsNonFiniteValues() {
        assertEquals(8f,CameraPolicy.zoom(100f,1f,8f),0f)
        assertEquals(.5f,CameraPolicy.zoom(-1f,.5f,4f),0f)
        assertEquals(2f,CameraPolicy.zoom(2f,1f,8f),0f)
        assertEquals(1f,CameraPolicy.zoom(Float.NaN,1f,8f),0f)
        assertEquals(1f,CameraPolicy.zoom(Float.POSITIVE_INFINITY,1f,8f),0f)
    }
    @Test fun versionFiveRoundTripsLensWithoutChangingEdits() {
        val a=Segment(1,"/one.mp4",4000,0,90,id="a",trim=TrimRange(4000,300,3000))
        val b=Segment(2,"/two.mp4",2000,0,90,id="b",kept=false)
        val session=Session("s",42,0,RecordingState.Paused,listOf(a,b),lens=CameraLens.Front)
        val json=SessionCodec.encode(session)
        assertEquals(6,JSONObject(json).getInt("version"))
        assertEquals(session,SessionCodec.decode(json))
    }
    @Test fun legacyVersionsDefaultRearWithoutMutatingOriginalJson() {
        val original=SessionCodec.encode(Session("s",1,0,RecordingState.Paused))
        for(v in 1..4) {
            val json=JSONObject(original).put("version",v).apply { remove("lens") }.toString()
            assertEquals(CameraLens.Rear,SessionCodec.decode(json).lens)
            assertFalse(JSONObject(json).has("lens"))
        }
    }
    @Test fun unknownLensAndFutureVersionRejected() {
        val json=JSONObject(SessionCodec.encode(Session("s",1,0,RecordingState.Paused)))
        for(bad in listOf(json.toString().replace("Rear","Unsupported"),json.put("version",7).toString())) {
            assertThrows(Exception::class.java) { SessionCodec.decode(bad) }
        }
    }
}
