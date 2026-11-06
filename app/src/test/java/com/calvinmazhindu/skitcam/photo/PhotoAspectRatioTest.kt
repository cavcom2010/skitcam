package com.calvinmazhindu.skitcam.photo

import com.calvinmazhindu.skitcam.camera.ViewfinderGeometry
import org.junit.Assert.*
import org.junit.Test

class PhotoAspectRatioTest {
    @Test fun missingOrUnknownPreferenceKeepsOriginalFourThree() {
        for(value in listOf(null,"","unknown")) assertEquals(PhotoAspectRatio.FourThree,PhotoAspectRatio.restored(value))
        for(ratio in PhotoAspectRatio.entries) assertEquals(ratio,PhotoAspectRatio.restored(ratio.name))
    }
    @Test fun presetsRotateTheirFrameWithoutDependingOnControlHeights() {
        for((ratio,wide) in listOf(PhotoAspectRatio.Square to PhotoFrame(1,1),
            PhotoAspectRatio.FourThree to PhotoFrame(4,3),PhotoAspectRatio.SixteenNine to PhotoFrame(16,9))) {
            assertEquals(wide,ratio.frame(true,1783,1010))
            assertEquals(wide.swapped(),ratio.frame(false,1080,1658))
            assertEquals(wide,ratio.frame(true,700,400))
        }
    }
    @Test fun fullUsesAvailableViewfinderRatherThanPhoneScreenRatio() {
        val full=PhotoAspectRatio.Full.frame(true,1783,1010)!!
        assertEquals(1783f/1010f,full.aspect,0f)
        assertNotEquals(2400f/1080f,full.aspect)
        val fitted=ViewfinderGeometry.fit(1783f,1010f,full.aspect)!!
        assertEquals(1783f,fitted.width,.01f);assertEquals(1010f,fitted.height,.01f)
        assertEquals(PhotoFrame(540,829),PhotoAspectRatio.Full.frame(false,1080,1658))
    }
    @Test fun emptyWindowNeverCreatesAnInvalidCaptureRatio() {
        for(ratio in PhotoAspectRatio.entries) {
            assertNull(ratio.frame(false,0,100));assertNull(ratio.frame(true,100,-1))
        }
        assertThrows(IllegalArgumentException::class.java) { PhotoFrame(0,1) }
    }
    @Test fun proportionalResizesKeepTheSameFullBindingKey() {
        assertEquals(PhotoFrame(1783,1010),PhotoFrame(3566,2020).reduced())
    }
    @Test fun oppositeLandscapeDirectionsKeepTheSameNaturalCrop() {
        val full=PhotoFrame(1783,1010)
        assertEquals(full.rotated(1),full.rotated(3))
        for(rotation in 0..3) assertEquals(full,full.rotated(rotation).rotated(rotation))
    }
    @Test fun countdownAndCaptureHoldTheOldCropAcrossRotation() {
        val portrait=PhotoFrame(540,829)
        val landscape=PhotoFrame(1783,1010).rotated(1)
        val frozen=photoCaptureFrame(portrait,landscape,true)
        assertEquals(portrait,frozen)
        assertEquals(PhotoFrame(829,540),frozen.rotated(1))
        assertEquals(landscape,photoCaptureFrame(frozen,landscape,false))
    }
    @Test fun firstFrameCanInitializeWhileRecoveryIsBusy() {
        val desired=PhotoFrame(4,3)
        assertEquals(desired,photoCaptureFrame(null,desired,true))
    }
}
