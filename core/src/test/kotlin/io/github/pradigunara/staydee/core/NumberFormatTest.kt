package io.github.pradigunara.staydee.core

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class NumberFormatTest {
    @Test
    fun parseInt10AcceptsSignedDecimalWithSurroundingSpace() {
        assertEquals(-1, parseInt10("-1"))
        assertEquals(7, parseInt10("+7"))
        assertEquals(8, parseInt10("08"))
        assertEquals(15, parseInt10(" 15 "))
    }

    @Test
    fun parseInt10RejectsEverythingElse() {
        for (bad in listOf("", "1.5", "abc", "-", "1e3", "+-1", " ")) {
            assertFailsWith<IllegalArgumentException>(bad) { parseInt10(bad) }
        }
    }

    @Test
    fun fmtnumPrintsIntegralValuesWithoutDecimalPoint() {
        assertEquals("-1", fmtnum(-1.0))
        assertEquals("0", fmtnum(0.0))
        assertEquals("0", fmtnum(-0.0))
        assertEquals("25", fmtnum(25.0))
        assertEquals("150000000000000000000", fmtnum(1.5e20))
    }

    @Test
    fun fmtnumMatchesPythonReprForFractions() {
        assertEquals("0.25", fmtnum(0.25))
        assertEquals("-0.25", fmtnum(-0.25))
        assertEquals("0.1", fmtnum(0.1))
        assertEquals("1.5", fmtnum(1.5))
        assertEquals("0.0001", fmtnum(0.0001))
        assertEquals("1e-05", fmtnum(0.00001))
        assertEquals("1.23e-07", fmtnum(1.23e-7))
        assertEquals("0.30000000000000004", fmtnum(0.1 + 0.2))
    }
}
