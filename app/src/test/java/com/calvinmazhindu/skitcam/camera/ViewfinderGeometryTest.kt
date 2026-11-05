package com.calvinmazhindu.skitcam.camera

import org.junit.Assert.*
import org.junit.Test

class ViewfinderGeometryTest {
    @Test fun previewAspectFollowsProfileDimensionsForEitherSensorOrientation() {
        assertTrue(ViewfinderGeometry.isFourThree(640,480))
        assertTrue(ViewfinderGeometry.isFourThree(480,640))
        assertFalse(ViewfinderGeometry.isFourThree(1920,1080))
        assertFalse(ViewfinderGeometry.isFourThree(720,1280))
    }
    @Test fun videoUsesCaptureDimensionsAndRotation() {
        assertEquals(9f/16f,ViewfinderGeometry.aspect(1920,1080,90)!!,0f)
        assertEquals(16f/9f,ViewfinderGeometry.aspect(1920,1080,0)!!,0f)
        assertEquals(9f/16f,ViewfinderGeometry.aspect(1920,1080,270)!!,0f)
        assertEquals(16f/9f,ViewfinderGeometry.aspect(1920,1080,180)!!,0f)
    }
    @Test fun standardDefinitionIsNotAssumedToBeSixteenNine() {
        assertEquals(3f/4f,ViewfinderGeometry.aspect(640,480,90)!!,0f)
    }
    @Test fun portraitShowsEntireVideoWithoutScreenFillCropping() {
        val size=ViewfinderGeometry.fit(1080f,2400f,9f/16f)!!
        assertEquals(1080f,size.width,0f);assertEquals(1920f,size.height,.01f)
    }
    @Test fun landscapeFitsBetweenControlsWithoutStretching() {
        val size=ViewfinderGeometry.fit(1800f,900f,16f/9f)!!
        assertEquals(1600f,size.width,.01f);assertEquals(900f,size.height,0f)
    }
    @Test fun portraitVideoReachesFullWidthWhenDockDoesNotReserveImageHeight() {
        val old=ViewfinderGeometry.fit(1080f,1658f,9f/16f)!!
        val immersive=ViewfinderGeometry.fit(1080f,2400f-237f-120f,9f/16f)!!
        assertTrue(old.width<1080f)
        assertEquals(1080f,immersive.width,0f)
        assertEquals(1920f,immersive.height,.01f)
    }
    @Test fun shortSafeRegionStillShowsTheWholeFrameInsteadOfForcingFullWidth() {
        val result=ViewfinderGeometry.fit(1080f,1700f,9f/16f)!!
        assertEquals(956.25f,result.width,.01f)
        assertEquals(1700f,result.height,0f)
        assertEquals(9f/16f,result.width/result.height,.0001f)
    }
    @Test fun geometryHandlesSmallWindowsAndPhotoAspect() {
        for(aspect in listOf(3f/4f,4f/3f,9f/16f,16f/9f)) for(width in listOf(300f,1080f,2400f)) {
            val result=ViewfinderGeometry.fit(width,700f,aspect)!!
            assertTrue(result.width<=width+.001f && result.height<=700.001f)
            assertEquals(aspect,result.width/result.height,.0001f)
        }
    }
    @Test fun invalidGeometryDoesNotProduceAFrame() {
        assertNull(ViewfinderGeometry.aspect(0,1080,90));assertNull(ViewfinderGeometry.aspect(1920,1080,45))
        for(n in listOf(0f,-1f,Float.NaN,Float.POSITIVE_INFINITY)) {
            assertNull(ViewfinderGeometry.fit(n,100f,1f));assertNull(ViewfinderGeometry.fit(100f,n,1f))
            assertNull(ViewfinderGeometry.fit(100f,100f,n))
        }
    }
}
