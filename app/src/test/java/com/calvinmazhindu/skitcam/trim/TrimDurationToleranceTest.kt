package com.calvinmazhindu.skitcam.trim

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TrimDurationToleranceTest {
    @Test fun smallContainerAndSavedDurationsAreTolerated() {
        assertFalse(durationMetadataMismatch(2_005, 2_005, null))
        assertFalse(durationMetadataMismatch(1_971, 2_005, null))
        assertFalse(durationMetadataMismatch(2_005, 2_005, 1_950))
        assertFalse(durationMetadataMismatch(2_080, 2_005, 2_005))
    }

    @Test fun materialMismatchesStillWarn() {
        assertTrue(durationMetadataMismatch(2_150, 2_005, null))
        assertTrue(durationMetadataMismatch(2_005, 2_005, 1_800))
        assertTrue(durationMetadataMismatch(1_900, 2_005, null))
    }
}
