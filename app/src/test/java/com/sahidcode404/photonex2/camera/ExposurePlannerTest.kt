package com.sahidcode404.photonex2.camera

import org.junit.Assert.assertEquals
import org.junit.Test

class ExposurePlannerTest {
    @Test
    fun daylightUsesShortBurst() {
        assertEquals(3, ExposurePlanner.chooseFrameCount(null, 1_000_000L, 100))
    }

    @Test
    fun darkerScenesIncreaseBurstLengthWithoutUnboundedAutoDelay() {
        val day = ExposurePlanner.chooseFrameCount(null, 2_000_000L, 100)
        val indoor = ExposurePlanner.chooseFrameCount(null, 10_000_000L, 400)
        val night = ExposurePlanner.chooseFrameCount(null, 30_000_000L, 1600)
        assert(day < indoor)
        assert(indoor < night)
        assertEquals(8, night)
    }

    @Test
    fun perLensOverrideStillWinsUpToTwelveFrames() {
        assertEquals(7, ExposurePlanner.chooseFrameCount(7, 50_000_000L, 3200))
        assertEquals(12, ExposurePlanner.chooseFrameCount(99, 1_000_000L, 100))
    }
}
