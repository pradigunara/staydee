package io.github.pradigunara.staydee.core

import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

/** Motion tuples are (dx, dy, px, py, field_size). */
private fun makeTrf(records: List<Pair<Int, List<IntArray>>>): ByteArray {
    val size = 24 + records.sumOf { 8 + it.second.size * 26 }
    val buf = ByteBuffer.allocate(size).order(ByteOrder.LITTLE_ENDIAN)
    buf.put("TRF1".toByteArray(Charsets.US_ASCII))
    buf.putInt(15)
    buf.putInt(5)
    buf.putInt(6)
    buf.putDouble(0.25)
    for ((frame, motions) in records) {
        buf.putInt(frame)
        buf.putInt(motions.size)
        for (m in motions) {
            for (v in m) buf.putShort(v.toShort())
            buf.putDouble(0.5)
            buf.putDouble(1.5)
        }
    }
    return buf.array()
}

private fun parseMotions(data: ByteArray): List<List<Int>> {
    val buf = ByteBuffer.wrap(data).order(ByteOrder.LITTLE_ENDIAN)
    val out = mutableListOf<List<Int>>()
    var pos = 24
    while (pos < data.size) {
        val count = buf.getInt(pos + 4)
        pos += 8
        repeat(count) {
            out += (0 until 5).map { buf.getShort(pos + it * 2).toInt() }
            pos += 26
        }
    }
    return out
}

private fun motion(dx: Int, dy: Int, px: Int, py: Int, size: Int = 108) = intArrayOf(dx, dy, px, py, size)

class TrfScalingTest {
    private val original = makeTrf(
        listOf(
            0 to listOf(motion(1, -1, 344, 586)),
            1 to listOf(motion(-4, 3, 734, 1082), motion(2, 0, 10, 20)),
        ),
    )

    @Test
    fun scaleUpAndDownRoundsHalfToEven() {
        assertEquals(
            listOf(
                listOf(2, -2, 688, 1172, 108),
                listOf(-8, 6, 1468, 2164, 108),
                listOf(4, 0, 20, 40, 108),
            ),
            parseMotions(scaleTrf(original, 2.0)),
        )
        assertEquals(original.size, scaleTrf(original, 2.0).size)
        // banker's rounding: dx = 1 at k = 0.5 -> 0 (roundToInt would give 1)
        assertEquals(
            listOf(
                listOf(0, 0, 172, 293, 108),
                listOf(-2, 2, 367, 541, 108),
                listOf(1, 0, 5, 10, 108),
            ),
            parseMotions(scaleTrf(original, 0.5)),
        )
    }

    @Test
    fun preservesHeaderFieldSizeAndDoubles() {
        val src = makeTrf(listOf(0 to listOf(motion(1, 1, 1, 1))))
        val scaled = scaleTrf(src, 2.0)
        assertContentEquals(src.copyOfRange(0, 24), scaled.copyOfRange(0, 24))
        // field_size and the doubles of the first motion are untouched; only dx,dy,px,py scale
        val fieldSizeOffset = 24 + 8 + 8   // header, frame+count, then dx,dy,px,py
        assertContentEquals(src.copyOfRange(fieldSizeOffset, src.size), scaled.copyOfRange(fieldSizeOffset, scaled.size))
    }

    @Test
    fun rejectsOverflowAndJunk() {
        assertFailsWith<IllegalArgumentException> {
            scaleTrf(makeTrf(listOf(0 to listOf(motion(1, 1, 32000, 1)))), 2.0)
        }
        assertFailsWith<IllegalArgumentException> { scaleTrf("TRF2".toByteArray() + ByteArray(40), 2.0) }
        assertFailsWith<IllegalArgumentException> { scaleTrf("TRF1".toByteArray() + ByteArray(19), 2.0) }
    }

    @Test
    fun int16BoundIsExact() {
        // 16383 * 2 = 32766 fits; 16384 * 2 = 32768 is refused, not clamped
        val fits = scaleTrf(makeTrf(listOf(0 to listOf(motion(16383, 0, 0, 0)))), 2.0)
        assertEquals(32766, parseMotions(fits)[0][0])
        assertFailsWith<IllegalArgumentException> {
            scaleTrf(makeTrf(listOf(0 to listOf(motion(16384, 0, 0, 0)))), 2.0)
        }
        // symmetric: -32767 fits, -32768 is refused
        assertEquals(-32766, parseMotions(scaleTrf(makeTrf(listOf(0 to listOf(motion(-16383, 0, 0, 0)))), 2.0))[0][0])
        assertFailsWith<IllegalArgumentException> {
            scaleTrf(makeTrf(listOf(0 to listOf(motion(-16384, 0, 0, 0)))), 2.0)
        }
        // at k = 1 a value of 32767 passes, -32768 is refused (reference bound is ±32767)
        assertEquals(32767, parseMotions(scaleTrf(makeTrf(listOf(0 to listOf(motion(32767, 0, 0, 0)))), 1.0))[0][0])
        assertFailsWith<IllegalArgumentException> {
            scaleTrf(makeTrf(listOf(0 to listOf(motion(-32768, 0, 0, 0)))), 1.0)
        }
    }

    @Test
    fun trailingOrTruncatedBytesFail() {
        val full = makeTrf(listOf(0 to listOf(motion(1, 1, 1, 1))))
        assertFailsWith<IllegalArgumentException> { scaleTrf(full + byteArrayOf(0), 1.0) }
        assertFailsWith<IllegalArgumentException> { scaleTrf(full.copyOf(full.size - 1), 1.0) }
    }

    @Test
    fun negativeMotionCountMeansNoMotions() {
        val buf = ByteBuffer.allocate(32).order(ByteOrder.LITTLE_ENDIAN)
        buf.put("TRF1".toByteArray(Charsets.US_ASCII))
        buf.putInt(15).putInt(5).putInt(6).putDouble(0.25)
        buf.putInt(0).putInt(-1)
        assertEquals(emptyList(), parseMotions(scaleTrf(buf.array(), 2.0)))
    }
}

class TrfReuseTest {
    private val raw = makeTrf(listOf(0 to listOf(motion(1, 1, 10, 10))))

    @Test
    fun fourKAutoRetuneRescalesOnceFromMeasuredEdge() {
        // first run: 3840 render, detected at 1920; re-tune with the same settings
        assertEquals(TrfReuse(2.0, 1920, true), trfReuse(sidecarJson(1920), 3840))
    }

    @Test
    fun retuneAtOtherRenderSize() {
        assertEquals(1.0, trfReuse(sidecarJson(1920), 1920).k)        // --max-edge 1920
        assertEquals(1280.0 / 1920, trfReuse(sidecarJson(1920), 1280).k)
    }

    @Test
    fun sidecarIsWrittenAsReferenceJson() {
        assertEquals("{\"long_edge\": 1920}", sidecarJson(1920))
    }

    @Test
    fun sidecarWithExtraValidJsonIsAccepted() {
        assertEquals(TrfReuse(2.0, 1920, true), trfReuse("{\"long_edge\": 1920, \"note\": [1, 2.5e1, null, \"x\\n\"]}", 3840))
    }

    @Test
    fun withoutSidecarMatchesRender() {
        assertEquals(TrfReuse(1.0, 3840, false), trfReuse(null, 3840))
    }

    @Test
    fun badSidecarIsAnError() {
        for (junk in listOf(
            "{",
            "{\"long_edge\": 0}",
            "{\"long_edge\": \"1920\"}",
            "[]",
            "{\"long_edge\": 1920.0}",
            "{\"long_edge\": 1920} x",
            "{\"long_edge\": 01920}",
            "{\"other\": 1920}",
        )) {
            assertFailsWith<IllegalArgumentException>(junk) { trfReuse(junk, 3840) }
        }
    }

    @Test
    fun renderTrfPassesThroughAtK1() {
        assertContentEquals(raw, renderTrf(raw, 1.0))
    }

    @Test
    fun renderTrfRescalesExactlyOnce() {
        assertContentEquals(scaleTrf(raw, 2.0), renderTrf(raw, 2.0))
    }

    @Test
    fun renderTrfRefusesAsciiAtKNot1() {
        val ascii = "VID.STAB 1\n#      accuracy = 15\n".toByteArray(Charsets.US_ASCII)
        val error = assertFailsWith<IllegalArgumentException> { renderTrf(ascii, 2.0) }
        assertEquals(true, error.message!!.contains("TRF1"))
        assertContentEquals(ascii, renderTrf(ascii, 1.0))
    }
}
