package com.calvinmazhindu.skitcam.audio

import org.junit.Assert.*
import org.junit.Test

class AudioPolicyTest {
    private fun input(id: Int, kind: InputKind) = AudioInput(id, kind, "input")
    @Test fun externalRemovalButNotAdditionOrInternalChanges() {
        for(kind in listOf(InputKind.Usb, InputKind.Wired, InputKind.Bluetooth))
            assertTrue(AudioPolicy.removedExternal(listOf(input(1,kind)), emptyList()))
        assertFalse(AudioPolicy.removedExternal(emptyList(),listOf(input(1,InputKind.Usb))))
        assertFalse(AudioPolicy.removedExternal(listOf(input(1,InputKind.BuiltIn)),emptyList()))
    }
    @Test fun unchangedAndDuplicateConnectionsDoNotPause() {
        val devices=listOf(input(1,InputKind.Usb));assertFalse(AudioPolicy.removedExternal(devices,devices))
    }
    @Test fun observedRouteChangeRequiresSameCaptureSession() {
        val first=listOf(AudioObservation(4,input(1,InputKind.Usb)))
        assertTrue(AudioPolicy.routeChanged(first,listOf(AudioObservation(4,input(2,InputKind.BuiltIn)))))
        assertFalse(AudioPolicy.routeChanged(first,listOf(AudioObservation(5,input(2,InputKind.BuiltIn)))))
        assertFalse(AudioPolicy.routeChanged(first,emptyList()))
    }
    @Test fun peakSanitisesInvalidAndOutOfRangeSamples() {
        assertNull(AudioPolicy.peak(Double.NaN));assertNull(AudioPolicy.peak(Double.POSITIVE_INFINITY))
        assertEquals(0.0,AudioPolicy.peak(-1.0));assertEquals(1.0,AudioPolicy.peak(3.0));assertEquals(.4,AudioPolicy.peak(.4))
    }
    @Test fun staleSamplesAndIdleNeverShowLiveLevel() {
        val a=AudioSnapshot(permission=true,health=CaptureHealth.Active,peak=.5,sampledAt=1000)
        assertEquals(.5,a.visiblePeak(1500));assertNull(a.visiblePeak(3001));assertNull(a.visiblePeak(999))
        assertNull(a.copy(health=CaptureHealth.Idle).visiblePeak(1500))
    }
    @Test fun muteAndPermissionOverrideCaptureHealth() {
        val a=AudioSnapshot(permission=true,health=CaptureHealth.Active,peak=.5,sampledAt=1000)
        assertNull(a.copy(permission=false).visiblePeak(1000));assertNull(a.copy(systemMuted=true).visiblePeak(1000))
        assertEquals("Microphone permission needed",a.copy(permission=false).title)
        assertEquals("Microphone muted by Android",a.copy(systemMuted=true).title)
    }
    @Test fun connectionAndObservationNeverClaimVerifiedInput() {
        val a=AudioSnapshot(permission=true,health=CaptureHealth.Active,inputs=listOf(input(1,InputKind.Usb)),observations=listOf(AudioObservation(1,input(1,InputKind.Usb))))
        assertTrue(a.title.contains("unverified"))
    }
    @Test fun silenceIsNotCaptureFailure() {
        assertFalse(AudioPolicy.captureFailure(CaptureHealth.Active))
        for(state in listOf(CaptureHealth.Muted,CaptureHealth.Silenced,CaptureHealth.Failed,CaptureHealth.Unavailable)) assertTrue(AudioPolicy.captureFailure(state))
        assertEquals(0.0,AudioPolicy.peak(0.0))
    }
}
