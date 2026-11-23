package com.calvinmazhindu.skitcam.recording

import org.junit.Assert.*
import org.junit.Test

class OrientationFilterTest {
    @Test fun requiresStableCardinalPoseAndRejectsUnknown() {
        val filter = OrientationFilter()
        assertNull(filter.update(3, 0))
        assertNull(filter.update(4, 499))
        assertEquals(0, filter.update(359, 500))
        assertNull(filter.update(45, 600))
        assertNull(filter.update(-1, 1200))
    }
    @Test fun reversalIsDifferentAndMustStabilize() {
        val filter = OrientationFilter()
        filter.update(0, 0)
        assertEquals(0, filter.update(0, 500))
        assertNull(filter.update(180, 1000))
        assertEquals(180, filter.update(180, 1500))
        assertNull(filter.update(270, 1600))
        assertEquals(270, filter.update(270, 2100))
    }

    @Test fun samePortraitOrLandscapeReadingMustRestabilizeAfterStop() {
        for (pose in listOf(0, 90, 180, 270)) {
            val filter = OrientationFilter()
            assertNull(filter.update(pose, 0))
            assertEquals(pose, filter.update(pose, 500))
            assertNull(filter.update(-1, 600))
            assertNull(filter.update(pose, 1500))
            assertNull(filter.update(pose, 1999))
            assertEquals(pose, filter.update(pose, 2000))
        }
    }

    @Test fun freshReadingAfterRotationIsNotReplacedByTheOldPortraitPose() {
        val filter = OrientationFilter()
        filter.update(0, 0)
        assertEquals(0, filter.update(0, 500))
        assertNull(filter.update(-1, 600))
        assertNull(filter.update(45, 1500))
        assertNull(filter.update(270, 2000))
        val pose = filter.update(270, 2500)
        assertEquals(270, pose)
        assertFalse(CameraOrientationPolicy.matchesDisplay(pose, 0))
    }
}
