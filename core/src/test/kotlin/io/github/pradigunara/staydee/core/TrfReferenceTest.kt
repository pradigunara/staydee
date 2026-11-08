package io.github.pradigunara.staydee.core

import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertSame

/**
 * M1 hard gate (plan §8): the pass-2 trf the app renders must be byte-identical to the
 * reference CLI's `scale_trf`. Fixtures in `resources/trf/`:
 * - `detect-960.trf`: a real binary TRF1 written by `vidstabdetect` (static ffmpeg 7.0.2)
 *   on a synthetic shaky 960×540 clip, default detect settings;
 * - `detect-960.<k>.expected.trf`: `stabilize.py` v2.0.8 `scale_trf(raw, k)` output.
 * k = 1.5 (1920 render from 1280 detection) exercises half-to-even rounding on real data.
 */
class TrfReferenceTest {
    private fun fixture(name: String): ByteArray =
        checkNotNull(javaClass.getResourceAsStream("/trf/$name")) { "missing fixture $name" }.use { it.readBytes() }

    private val raw = fixture("detect-960.trf")

    @Test
    fun matchesReferenceAtK2() {
        assertContentEquals(fixture("detect-960.k2.expected.trf"), renderTrf(raw, 2.0))
    }

    @Test
    fun matchesReferenceAtNonIntegerK() {
        assertContentEquals(fixture("detect-960.k1.5.expected.trf"), renderTrf(raw, 1920.0 / 1280))
    }

    @Test
    fun unitFactorPassesTheMeasurementThrough() {
        assertSame(raw, renderTrf(raw, 1.0))
    }
}
