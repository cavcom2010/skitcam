package com.calvinmazhindu.skitcam.photo

import org.junit.Assert.*
import org.junit.Test

class PhotoPolicyTest {
    @Test fun captureRequiresItsReserve() {
        assertFalse(PhotoPolicy.canCapture(PhotoPolicy.CAPTURE_RESERVE-1))
        assertTrue(PhotoPolicy.canCapture(PhotoPolicy.CAPTURE_RESERVE))
    }
    @Test fun negativeAndUnavailableSpaceCannotCapture() {
        assertFalse(PhotoPolicy.canCapture(-1));assertFalse(PhotoPolicy.canCapture(0))
    }
    @Test fun publicationNeedsRoomForCopyAndReserve() {
        assertFalse(PhotoPolicy.canPublish(PhotoPolicy.PUBLISH_RESERVE+999,1000))
        assertTrue(PhotoPolicy.canPublish(PhotoPolicy.PUBLISH_RESERVE+1000,1000))
    }
    @Test fun emptyAndInvalidSourcesAreRejected() {
        assertFalse(PhotoPolicy.canPublish(Long.MAX_VALUE,0));assertFalse(PhotoPolicy.canPublish(Long.MAX_VALUE,-1))
    }
    @Test fun missingFreeSpaceCannotPublish() {
        assertFalse(PhotoPolicy.canPublish(0,1000));assertFalse(PhotoPolicy.canPublish(-1,1000))
    }
    @Test fun veryLargeSourceCannotOverflowIntoAcceptance() {
        assertFalse(PhotoPolicy.canPublish(Long.MAX_VALUE,Long.MAX_VALUE))
        assertTrue(PhotoPolicy.canPublish(Long.MAX_VALUE,Long.MAX_VALUE-PhotoPolicy.PUBLISH_RESERVE))
    }
}
