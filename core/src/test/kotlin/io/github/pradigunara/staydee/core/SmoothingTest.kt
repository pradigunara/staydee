package io.github.pradigunara.staydee.core

import kotlin.test.Test
import kotlin.test.assertEquals

class SmoothingTest {
    @Test
    fun defaultKeepsTheCliValueAt30fpsAndScalesWithFrameRate() {
        assertEquals(15, smoothingFrames(SMOOTHING_SECONDS_DEFAULT, 30.0))
        assertEquals(30, smoothingFrames(SMOOTHING_SECONDS_DEFAULT, 60.0))
        // NTSC 29.97: 14.985 rounds to 15, not truncates to 14
        assertEquals(15, smoothingFrames(0.5, 30000.0 / 1001))
    }

    @Test
    fun unknownOrBrokenFrameRateFallsBackTo30() {
        for (fps in listOf(null, 0.0, -1.0, Double.NaN, Double.POSITIVE_INFINITY)) {
            assertEquals(15, smoothingFrames(0.5, fps), "fps=$fps")
        }
    }

    @Test
    fun neverReturnsTheStaticCameraValueOrExceedsTheFilterLimit() {
        assertEquals(1, smoothingFrames(0.0, 30.0))
        assertEquals(1000, smoothingFrames(2.0, 1000.0))
    }
}
