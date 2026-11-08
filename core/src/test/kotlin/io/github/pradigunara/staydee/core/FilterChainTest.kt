package io.github.pradigunara.staydee.core

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class FilterChainTest {
    private val s = Settings()
    private val trf = "/t/x.trf"

    @Test
    fun defaultsMatchReference() {
        assertEquals("veryfast", s.preset)
        assertEquals(22, s.crf)
        assertEquals(DetectEdge.Auto, s.detectEdge)
    }

    @Test
    fun detectChainCarriesDetectKnobs() {
        assertEquals(
            "vidstabdetect=result=/t/x.trf:shakiness=5:accuracy=15:stepsize=6:mincontrast=0.25",
            detectChain(s, trf, null),
        )
    }

    @Test
    fun detectChainPrefixedByScale() {
        val prefixed = detectChain(s, trf, scaleFilter(1080))
        assertTrue(prefixed.startsWith("scale="))
        assertTrue(",vidstabdetect=" in prefixed)
    }

    @Test
    fun detectChainTripodFlag() {
        assertTrue(detectChain(s.copy(tripod = true), trf, null).endsWith(":tripod=1"))
        assertFalse("tripod" in detectChain(s, trf, null))
    }

    @Test
    fun transformChainDefaults() {
        assertEquals(
            "vidstabtransform=input=/t/x.trf:smoothing=15:optzoom=1:optalgo=gauss:zoomspeed=0.25:" +
                "crop=keep:interpol=bicubic:relative=1:zoom=0:maxshift=-1:maxangle=-1," +
                "unsharp=5:5:0.8:3:3:0.4",
            transformChain(s, trf, null),
        )
    }

    @Test
    fun sharpenOffDropsUnsharp() {
        assertFalse("unsharp" in transformChain(s.copy(sharpen = false), trf, null))
    }

    @Test
    fun tripodFlagInTransform() {
        assertTrue(":tripod=1" in transformChain(s.copy(tripod = true), trf, null))
    }

    @Test
    fun optalgoIsPinnedByDefault() {
        assertEquals(OptAlgo.GAUSS, s.optalgo)
        for (algo in OptAlgo.values()) {
            assertTrue(":optalgo=${algo.wire}:" in transformChain(s.copy(optalgo = algo), trf, null))
        }
    }

    @Test
    fun scaleFilterIsOrientationAgnostic() {
        assertEquals(
            "scale=w='if(gte(iw,ih),min(iw,1920),-2)':h='if(gte(iw,ih),-2,min(ih,1920))'",
            scaleFilter(1920),
        )
    }

    @Test
    fun scaleIfShrinkingOnlyWhenSmaller() {
        assertNull(scaleIfShrinking(3840, 3840))
        assertNull(scaleIfShrinking(4000, 3840))
        assertEquals(scaleFilter(1920), scaleIfShrinking(1920, 3840))
    }

    @Test
    fun hdrToneMapSitsAfterRenderScaleAndBeforeTransform() {
        assertEquals(
            "zscale=t=linear:npl=100,format=gbrpf32le,zscale=p=bt709,tonemap=tonemap=hable:desat=0," +
                "zscale=t=bt709:m=bt709:r=tv,format=yuv420p",
            HDR_TONEMAP_CHAIN,
        )
        val chain = transformChain(s, trf, scaleFilter(1920), ColorTransfer.PQ)
        assertTrue(
            chain.startsWith("${scaleFilter(1920)},$HDR_TONEMAP_CHAIN,vidstabtransform=input=/t/x.trf:"),
            chain,
        )
    }

    @Test
    fun hlgToneMapsWithoutRenderScale() {
        assertTrue(transformChain(s, trf, null, ColorTransfer.HLG).startsWith("$HDR_TONEMAP_CHAIN,vidstabtransform="))
    }

    @Test
    fun sdrAddsNoToneMap() {
        assertFalse("zscale" in transformChain(s, trf, null, ColorTransfer.SDR))
    }

    @Test
    fun colorTransferFromFfprobe() {
        assertEquals(ColorTransfer.PQ, ColorTransfer.fromFfprobe("smpte2084"))
        assertEquals(ColorTransfer.HLG, ColorTransfer.fromFfprobe("arib-std-b67"))
        assertEquals(ColorTransfer.SDR, ColorTransfer.fromFfprobe("bt709"))
        assertEquals(ColorTransfer.SDR, ColorTransfer.fromFfprobe(""))
        assertEquals(ColorTransfer.SDR, ColorTransfer.fromFfprobe(null))
    }
}
