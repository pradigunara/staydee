package io.github.pradigunara.staydee.core

/** `auto` never detects below this long edge (a 1080p-class frame). */
const val DETECT_FLOOR = 1920

const val ASCII_ONLY = "this vid.stab writes ASCII transforms only, which can't be rescaled"

/**
 * Pass-1 geometry. [target] is the detection long edge, [k] the factor the
 * transforms must be multiplied by to act on rendered frames (1.0 = none),
 * [reason] a user-facing description.
 */
data class DetectGeometry(val target: Int, val k: Double, val reason: String)

/**
 * Pick the pass-1 long edge. Without binary TRF support (vid.stab 1.1.0) a
 * reduced detection could not be rescaled, so it falls back to the render size
 * with reason [ASCII_ONLY].
 */
fun detectGeometry(renderLong: Int, detectEdge: DetectEdge, binaryTrf: Boolean = true): DetectGeometry {
    val target = when (detectEdge) {
        DetectEdge.Off -> return DetectGeometry(renderLong, 1.0, "disabled")
        // half the long edge, floored: below 1080p the accuracy loss stops
        // being free, above it pass 1 dominates the runtime
        DetectEdge.Auto -> minOf(renderLong, maxOf(DETECT_FLOOR, renderLong / 2))
        is DetectEdge.Pixels -> minOf(renderLong, detectEdge.px)
    }
    if (target >= renderLong) return DetectGeometry(renderLong, 1.0, "source resolution")
    if (!binaryTrf) return DetectGeometry(renderLong, 1.0, ASCII_ONLY)
    return DetectGeometry(target, renderLong.toDouble() / target, "${renderLong}p -> ${target}p")
}

private val FFMPEG_VERSION = Regex("""ffmpeg version (\S+)""")

/** `'ffmpeg version 7.0.2-static https://...'` -> `'7.0.2-static'`. */
fun ffmpegVersion(versionText: String): String =
    FFMPEG_VERSION.find(versionText)?.groupValues?.get(1) ?: "unknown"
