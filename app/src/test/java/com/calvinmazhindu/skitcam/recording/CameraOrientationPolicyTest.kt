package com.calvinmazhindu.skitcam.recording

import com.calvinmazhindu.skitcam.recording.CameraOrientationPolicy.Request.*
import org.junit.Assert.*
import org.junit.Test

class CameraOrientationPolicyTest {
    private fun snapshot(state: RecordingState, pose: Int=270) = RecordingSnapshot(state,
        Session("rotation-test",0,pose,state))

    @Test fun recoveryMustSettleBeforeChoosingActivityPolicy() {
        assertNull(CameraOrientationPolicy.request(snapshot(RecordingState.Paused),false,false))
    }
    @Test fun idleAndCompletedSessionsUseFullSensorInEitherNaturalOrientation() {
        for(natural in listOf(true,false)) {
            assertEquals(FullSensor,CameraOrientationPolicy.request(RecordingSnapshot(),true,false,naturalPortrait=natural))
            assertEquals(FullSensor,CameraOrientationPolicy.request(snapshot(RecordingState.Completed),true,false,naturalPortrait=natural))
        }
    }
    @Test fun everyUnfinishedSessionStateKeepsTheOriginalPose() {
        for(state in listOf(RecordingState.Starting,RecordingState.Recording,RecordingState.Finalising,
            RecordingState.Paused,RecordingState.Stopping,RecordingState.Error)) {
            assertEquals(Landscape,CameraOrientationPolicy.request(snapshot(state),true,false))
            assertEquals(ReverseLandscape,CameraOrientationPolicy.request(snapshot(state,90),true,false))
        }
    }
    @Test fun completedExportHoldsPoseUntilReturnThenRestoresSensorPolicy() {
        val completed=snapshot(RecordingState.Completed)
        assertEquals(Landscape,CameraOrientationPolicy.request(completed,true,true))
        assertEquals(FullSensor,CameraOrientationPolicy.request(completed,true,false))
    }
    @Test fun pendingStartClosesTheWorkerHopWithoutOverridingAnOpenSession() {
        assertEquals(ReverseLandscape,CameraOrientationPolicy.request(RecordingSnapshot(),true,false,90))
        assertEquals(Landscape,CameraOrientationPolicy.request(snapshot(RecordingState.Paused),true,false,90))
    }
    @Test fun allFourPosesMapForPortraitAndLandscapeNaturalDisplays() {
        assertEquals(listOf(Portrait,ReverseLandscape,ReversePortrait,Landscape),
            listOf(0,90,180,270).map { CameraOrientationPolicy.lock(it,true) })
        assertEquals(listOf(Landscape,Portrait,ReverseLandscape,ReversePortrait),
            listOf(0,90,180,270).map { CameraOrientationPolicy.lock(it,false) })
    }
    @Test fun startAndResumeRequireTheActualDisplayToMatchStablePose() {
        for((pose,rotation) in listOf(0 to 0,90 to 3,180 to 2,270 to 1)) {
            for(actual in 0..3) assertEquals(actual==rotation,CameraOrientationPolicy.matchesDisplay(pose,actual))
        }
        for(invalid in listOf(null,-1,45,360)) assertFalse(CameraOrientationPolicy.matchesDisplay(invalid,0))
    }
    @Test fun coldRecoveryWaitsForRestoredDisplayLockBeforeBinding() {
        assertFalse(CameraOrientationPolicy.cameraMayBind(null,null,0))
        assertFalse(CameraOrientationPolicy.cameraMayBind(Landscape,270,0))
        assertTrue(CameraOrientationPolicy.cameraMayBind(Landscape,270,1))
        assertFalse(CameraOrientationPolicy.cameraMayBind(ReverseLandscape,90,1))
        assertTrue(CameraOrientationPolicy.cameraMayBind(ReverseLandscape,90,3))
        for(rotation in 0..3) assertTrue(CameraOrientationPolicy.cameraMayBind(FullSensor,null,rotation))
    }
}
