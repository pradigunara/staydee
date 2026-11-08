package io.github.pradigunara.staydee.core

/** Colour transfer from ffprobe's `color_transfer`; anything but PQ or HLG is treated as SDR. */
enum class ColorTransfer {
    SDR, PQ, HLG;

    /** True when pass 2 must tone-map to SDR. */
    val isHdr: Boolean get() = this != SDR

    companion object {
        fun fromFfprobe(colorTransfer: String?): ColorTransfer = when (colorTransfer) {
            "smpte2084" -> PQ
            "arib-std-b67" -> HLG
            else -> SDR
        }
    }
}

/** App-only pass-2 step (plan §4.2): tone-map HDR to SDR bt709 before `vidstabtransform`. */
const val HDR_TONEMAP_CHAIN: String =
    "zscale=t=linear:npl=100,format=gbrpf32le,zscale=p=bt709,tonemap=tonemap=hable:desat=0," +
        "zscale=t=bt709:m=bt709:r=tv,format=yuv420p"

/** Limit the long edge to [target]: orientation-agnostic, even dimensions. */
fun scaleFilter(target: Int): String =
    "scale=w='if(gte(iw,ih),min(iw,$target),-2)':" +
        "h='if(gte(iw,ih),-2,min(ih,$target))'"

/** [scaleFilter] when [target] shrinks a source whose long edge is [srcLong]; otherwise null. */
fun scaleIfShrinking(target: Int, srcLong: Int): String? =
    if (target < srcLong) scaleFilter(target) else null

private fun prefixed(pre: String?, chain: String): String =
    if (pre.isNullOrEmpty()) chain else "$pre,$chain"

/** Pass 1 video filter. [preScale] is the optional detect-resolution scale. */
fun detectChain(s: Settings, trf: String, preScale: String?): String {
    val chain = buildString {
        append("vidstabdetect=result=$trf:shakiness=${s.shakiness}:accuracy=${s.accuracy}")
        append(":stepsize=${s.stepsize}:mincontrast=${fmtnum(s.mincontrast)}")
        if (s.tripod) append(":tripod=1")
    }
    return prefixed(preScale, chain)
}

/**
 * Pass 2 video filter. [renderScale] is the optional render-resolution scale;
 * [transfer] adds the HDR tone-map between it and `vidstabtransform` when HDR.
 */
fun transformChain(
    s: Settings,
    trf: String,
    renderScale: String?,
    transfer: ColorTransfer = ColorTransfer.SDR,
): String {
    val chain = buildString {
        append("vidstabtransform=input=$trf:smoothing=${s.smoothing}:optzoom=${s.optzoom}")
        append(":optalgo=${s.optalgo.wire}:zoomspeed=${fmtnum(s.zoomspeed)}")
        append(":crop=${s.border.wire}:interpol=${s.interpol.wire}")
        append(":relative=1:zoom=${s.zoom}:maxshift=${s.maxshift}:maxangle=${fmtnum(s.maxangle)}")
        if (s.tripod) append(":tripod=1")
        if (s.sharpen) append(",unsharp=5:5:0.8:3:3:0.4")
    }
    val pre = listOfNotNull(renderScale, if (transfer.isHdr) HDR_TONEMAP_CHAIN else null)
    return prefixed(pre.joinToString(","), chain)
}
