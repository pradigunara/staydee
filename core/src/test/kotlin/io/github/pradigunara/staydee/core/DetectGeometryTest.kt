package io.github.pradigunara.staydee.core

import kotlin.test.Test
import kotlin.test.assertEquals

class DetectGeometryTest {
    private fun geometry(renderLong: Int, edge: DetectEdge = DetectEdge.Auto, binaryTrf: Boolean = true) =
        detectGeometry(renderLong, edge, binaryTrf)

    @Test
    fun autoFloorIsANoOpAtOrBelow1080pClass() {
        // 1920 long edge == a 1080p-class frame: nothing to gain
        for (longEdge in listOf(1920, 1440, 1080, 720)) {
            val g = geometry(longEdge)
            assertEquals(longEdge, g.target)
            assertEquals(1.0, g.k)
        }
    }

    @Test
    fun autoHalvesUhdPortraitOrLandscape() {
        // 3840 long edge halves to 1920
        for (longEdge in listOf(3840, 2160)) {
            val g = geometry(longEdge)
            assertEquals(1920, g.target)
            assertEquals(longEdge.toDouble() / 1920, g.k)
        }
    }

    @Test
    fun auto2kFloorsTo1920() {
        // 1440p is 2560 long edge; half (1280) is under the floor
        val g = geometry(2560)
        assertEquals(1920, g.target)
        assertEquals(2560.0 / 1920, g.k, 1e-12)
    }

    @Test
    fun auto5k() {
        assertEquals(2560, geometry(5120).target)
    }

    @Test
    fun offAndExplicitTargets() {
        assertEquals(3840, geometry(3840, DetectEdge.Off).target)
        assertEquals(1.0, geometry(3840, DetectEdge.Off).k)
        assertEquals("disabled", geometry(3840, DetectEdge.Off).reason)
        assertEquals(2560, geometry(3840, DetectEdge.Pixels(2560)).target)
        assertEquals(1.5, geometry(3840, DetectEdge.Pixels(2560)).k)
        // a target above the render size is clamped, not upscaled
        assertEquals(1920, geometry(1920, DetectEdge.Pixels(3840)).target)
        assertEquals(1.0, geometry(1920, DetectEdge.Pixels(3840)).k)
    }

    @Test
    fun asciiOnlyVidstabNeverReducesDetection() {
        // vid.stab 1.1.0 writes ASCII trfs, which scaleTrf rejects: a reduced
        // pass 1 would crash after detection, so detect at render size instead
        assertEquals(DetectGeometry(3840, 1.0, ASCII_ONLY), geometry(3840, DetectEdge.Auto, binaryTrf = false))
        assertEquals(DetectGeometry(2560, 1.0, ASCII_ONLY), geometry(2560, DetectEdge.Auto, binaryTrf = false))
        assertEquals(DetectGeometry(1920, 1.0, ASCII_ONLY), geometry(1920, DetectEdge.Pixels(960), binaryTrf = false))
        // nothing to fall back from: same answers as with binary support
        assertEquals(DetectGeometry(1920, 1.0, "source resolution"), geometry(1920, DetectEdge.Auto, binaryTrf = false))
        assertEquals(DetectGeometry(3840, 1.0, "disabled"), geometry(3840, DetectEdge.Off, binaryTrf = false))
    }

    @Test
    fun ffmpegVersionFromRealBanners() {
        val banners = mapOf(
            "ffmpeg version 6.1.1-3ubuntu5 Copyright (c) 2000-2023 the FFmpeg developers" to "6.1.1-3ubuntu5",
            "ffmpeg version 7.0.2-static https://johnvansickle.com/ffmpeg/  Copyright (c) 2000-2024" to "7.0.2-static",
            "ffmpeg version 9.0.2 Copyright (c) 2000-2026 the FFmpeg developers" to "9.0.2",
            "" to "unknown",
        )
        for ((text, want) in banners) {
            assertEquals(want, ffmpegVersion(text))
        }
    }
}
