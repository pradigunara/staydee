package io.github.pradigunara.staydee.core

import kotlin.test.Test
import kotlin.test.assertEquals

class QualityTest {
    @Test
    fun balancedKeepsTodaysValueAt1080pAndRelaxesAt4k() {
        assertEquals(22, autoCrf(1920, QualityLevel.BALANCED))
        assertEquals(25, autoCrf(3840, QualityLevel.BALANCED))
    }

    @Test
    fun classBoundariesUseTheLongEdge() {
        assertEquals(21, autoCrf(1280, QualityLevel.BALANCED))
        assertEquals(22, autoCrf(1281, QualityLevel.BALANCED))
        assertEquals(23, autoCrf(2560, QualityLevel.BALANCED))
        assertEquals(25, autoCrf(2561, QualityLevel.BALANCED))
    }

    @Test
    fun levelsStayInsideHandbrakesRanges() {
        // 1080p 20–24, 2160p 22–28
        assertEquals(listOf(24, 22, 20), QualityLevel.entries.map { autoCrf(1920, it) })
        assertEquals(listOf(27, 25, 23), QualityLevel.entries.map { autoCrf(3840, it) })
    }

    @Test
    fun maxEdgeOnlyEverShrinksTheOutput() {
        assertEquals(1920, renderLongEdge(3840, 1920))
        assertEquals(1920, renderLongEdge(1920, 3840))
        assertEquals(3840, renderLongEdge(3840, 0))
    }
}
