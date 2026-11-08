package io.github.pradigunara.staydee.core

import kotlin.math.abs
import kotlin.math.pow
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class EstimateTest {
    private fun assertClose(expected: Double, actual: Double, tol: Double = 1e-9) {
        assertTrue(abs(expected - actual) <= tol, "expected $expected, got $actual")
    }

    @Test
    fun zeroDurationEstimatesZero() {
        assertEquals(0.0, estimateSeconds(0.0, 1920, 1920))
    }

    @Test
    fun estimateScalesWithDuration() {
        // 1080p-class render: 2.0 + 1.18 seconds of work per second of footage
        assertClose(15 * 3.18, estimateSeconds(15.0, 1920, 1920))
    }

    @Test
    fun estimateAt4kRenderWith1080Detect() {
        // render: 2.0 * (3840/1920)^2 = 8.0 ; detect: 1.18 * (1920/1920)^2.4 = 1.18
        assertClose(15.0 * (8.0 + 1.18), estimateSeconds(15.0, 3840, 1920))
        assertClose(137.7, estimateSeconds(15.0, 3840, 1920), tol = 0.05)
    }

    @Test
    fun speedGainIsOneWhenNothingToSave() {
        assertEquals(1.0, speedGain(1920, 1920))
        assertEquals(1.0, speedGain(1920, 3840))
        assertEquals(1.0, speedGain(1920, 0))
    }

    @Test
    fun speedGainFollowsExponent() {
        assertClose(2.0.pow(2.4), speedGain(3840, 1920))
    }

    @Test
    fun weightsAt1080pAboutThirtySevenSixtyThree() {
        val w = progressWeights(1920, 1920)
        assertTrue(abs(w.detect - 0.371) < 0.001, "detect ${w.detect}")
        assertTrue(abs(w.transform - 0.629) < 0.001, "transform ${w.transform}")
        assertClose(1.0, w.detect + w.transform)
    }

    @Test
    fun weightsWithoutDetectPassAreZeroAndOne() {
        assertEquals(PassWeights(detect = 0.0, transform = 1.0), progressWeights(1920, 1920, detectPass = false))
    }

    @Test
    fun weightsRejectNonPositiveEdges() {
        assertFailsWith<IllegalArgumentException> { progressWeights(0, 1920) }
        assertFailsWith<IllegalArgumentException> { progressWeights(1920, -1) }
    }
}
