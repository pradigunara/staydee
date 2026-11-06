package io.github.pradigunara.staydee.core

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class SettingsTest {
    @Test
    fun defaultsMatchPlanSection41() {
        val s = Settings()
        assertEquals(5, s.shakiness)
        assertEquals(15, s.accuracy)
        assertEquals(15, s.smoothing)
        assertEquals(1, s.optzoom)
        assertEquals(OptAlgo.GAUSS, s.optalgo)
        assertEquals(0.25, s.zoomspeed)
        assertEquals(Border.KEEP, s.border)
        assertEquals(Interpol.BICUBIC, s.interpol)
        assertEquals(true, s.sharpen)
        assertEquals(22, s.crf)
        assertEquals("veryfast", s.preset)
        assertEquals(true, s.audio)
        assertEquals(false, s.tripod)
        assertEquals(6, s.stepsize)
        assertEquals(0.25, s.mincontrast)
        assertEquals(-1, s.maxshift)
        assertEquals(-1.0, s.maxangle)
        assertEquals(0, s.zoom)
        assertEquals(0, s.maxEdge)
        assertEquals(DetectEdge.Auto, s.detectEdge)
    }

    @Test
    fun defaultsNeedNoAdjustment() {
        val r = Settings().reconciled()
        assertEquals(Settings(), r.settings)
        assertEquals(emptyList(), r.warnings)
    }

    @Test
    fun accuracyBelowShakinessIsBumpedWithWarning() {
        val r = Settings(shakiness = 8, accuracy = 5).reconciled()
        assertEquals(8, r.settings.accuracy)
        assertEquals(listOf("accuracy must be >= shakiness — bumped accuracy to 8"), r.warnings)
    }

    @Test
    fun accuracyEqualToShakinessIsNotBumped() {
        val r = Settings(shakiness = 10, accuracy = 10).reconciled()
        assertEquals(10, r.settings.accuracy)
        assertEquals(emptyList(), r.warnings)
    }

    @Test
    fun tripodWarnsAboutSmoothingAndKeepsIt() {
        val r = Settings(tripod = true).reconciled()
        assertEquals(15, r.settings.smoothing)
        assertEquals(
            listOf("tripod mode sets relative=0:smoothing=0 — your smoothing value is ignored"),
            r.warnings,
        )
    }

    @Test
    fun rangesAreInclusiveAtBothEnds() {
        Settings(shakiness = 1, accuracy = 1, crf = 0, maxEdge = 0, maxangle = -1.0, zoom = -100)
        Settings(shakiness = 10, accuracy = 15, crf = 51, maxEdge = 8640, maxangle = 3.14, zoom = 100,
            smoothing = 1000, optzoom = 2, zoomspeed = 5.0, stepsize = 32, mincontrast = 1.0, maxshift = 500)
    }

    @Test
    fun outOfRangeValuesAreRejected() {
        assertFailsWith<IllegalArgumentException> { Settings(shakiness = 0) }
        assertFailsWith<IllegalArgumentException> { Settings(shakiness = 11) }
        assertFailsWith<IllegalArgumentException> { Settings(accuracy = 16) }
        assertFailsWith<IllegalArgumentException> { Settings(smoothing = 1001) }
        assertFailsWith<IllegalArgumentException> { Settings(optzoom = 3) }
        assertFailsWith<IllegalArgumentException> { Settings(zoomspeed = 5.5) }
        assertFailsWith<IllegalArgumentException> { Settings(crf = 52) }
        assertFailsWith<IllegalArgumentException> { Settings(crf = -1) }
        assertFailsWith<IllegalArgumentException> { Settings(preset = "fastest") }
        assertFailsWith<IllegalArgumentException> { Settings(stepsize = 33) }
        assertFailsWith<IllegalArgumentException> { Settings(mincontrast = 1.5) }
        assertFailsWith<IllegalArgumentException> { Settings(maxshift = 501) }
        assertFailsWith<IllegalArgumentException> { Settings(maxshift = -2) }
        assertFailsWith<IllegalArgumentException> { Settings(maxangle = 3.2) }
        assertFailsWith<IllegalArgumentException> { Settings(zoom = 101) }
        assertFailsWith<IllegalArgumentException> { Settings(maxEdge = 8641) }
        assertFailsWith<IllegalArgumentException> { Settings(maxEdge = -1) }
    }

    @Test
    fun parseDetectEdgeAcceptsAutoOffAndPositiveTargets() {
        assertEquals(DetectEdge.Auto, parseDetectEdge("auto"))
        assertEquals(DetectEdge.Off, parseDetectEdge("off"))
        assertEquals(DetectEdge.Pixels(960), parseDetectEdge("960"))
        assertEquals(DetectEdge.Pixels(960), parseDetectEdge(" 960 "))
        // beyond Int: clamped, which detection treats the same (it clamps to the render size)
        assertEquals(DetectEdge.Pixels(Int.MAX_VALUE), parseDetectEdge("99999999999999"))
    }

    @Test
    fun parseDetectEdgeRejectsZeroNegativeAndJunk() {
        for (bad in listOf("0", "-1", "1.5", "abc", "", "Auto")) {
            assertFailsWith<IllegalArgumentException>(bad) { parseDetectEdge(bad) }
        }
    }

    @Test
    fun optionEnumsParseTheirWireNames() {
        assertEquals(OptAlgo.OPT, OptAlgo.fromWire("opt"))
        assertEquals(null, OptAlgo.fromWire("fast"))
        assertEquals(Border.BLACK, Border.fromWire("black"))
        assertEquals(Interpol.NO, Interpol.fromWire("no"))
        assertTrue(X264_PRESETS.contains("veryfast"))
    }
}
