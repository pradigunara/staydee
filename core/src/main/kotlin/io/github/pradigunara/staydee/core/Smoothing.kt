package io.github.pradigunara.staydee.core

import kotlin.math.roundToLong

/** The app's smoothing slider, in seconds (plan §15.7). Default 0.5 s = the CLI's 15 frames at 30 fps. */
const val SMOOTHING_SECONDS_DEFAULT = 0.5
val SMOOTHING_SECONDS_RANGE = 0.2..2.0

/** Frame rate assumed when the container does not report one. */
const val SMOOTHING_FALLBACK_FPS = 30.0

/**
 * vidstabtransform's `smoothing` (frames each side of the Gaussian window) for a time span of
 * [seconds] at [fps]. Never 0: vid.stab treats 0 as "static camera", not as weak smoothing.
 */
fun smoothingFrames(seconds: Double, fps: Double?): Int {
    val rate = fps?.takeIf { it.isFinite() && it > 0 } ?: SMOOTHING_FALLBACK_FPS
    return (seconds * rate).roundToLong().coerceIn(1, 1000).toInt()
}
