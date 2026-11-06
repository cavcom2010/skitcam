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
    @Test fun portraitLabelsAndNineSixteenUseFullWidth() {
        assertEquals("1:1",PhotoAspectRatio.Square.label(false))
        assertEquals("3:4",PhotoAspectRatio.FourThree.label(false))
        assertEquals("9:16",PhotoAspectRatio.SixteenNine.label(false))
        assertEquals("4:3",PhotoAspectRatio.FourThree.label(true))
        assertEquals("16:9",PhotoAspectRatio.SixteenNine.label(true))
        assertEquals("Full",PhotoAspectRatio.Full.label(false))
        val fitted=ViewfinderGeometry.fit(1080f,2163f,9f/16f)!!
        assertEquals(1080f,fitted.width,.01f)
        assertEquals(1920f,fitted.height,.01f)
        assertTrue(fitted.width==1080f) // full width, no side letterboxing
    }
    @Test fun fullIsExactlyTheRuntimeWindowOnAnyDevice() {
        // Different screens produce different Full dimensions; no fixed ratio is assumed.
        for((w,h) in listOf(1080 to 2400,1440 to 3120,1080 to 1920,2208 to 1840)) {
            assertEquals(PhotoFrame(w,h),PhotoAspectRatio.Full.frame(false,w,h))
            assertEquals(PhotoFrame(h,w),PhotoAspectRatio.Full.frame(true,h,w))
            val full=PhotoAspectRatio.Full.frame(false,w,h)!!
            val fitted=ViewfinderGeometry.fit(w.toFloat(),h.toFloat(),full.aspect)!!
            assertEquals(w.toFloat(),fitted.width,.01f);assertEquals(h.toFloat(),fitted.height,.01f)
        }
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
