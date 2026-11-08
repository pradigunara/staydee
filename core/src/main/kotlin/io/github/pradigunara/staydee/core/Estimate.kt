package io.github.pradigunara.staydee.core

import kotlin.math.pow

// Costs measured on an 8-core arm64 phone: seconds of work per second of footage
// at 1080p (DETECT_FLOOR). Pass 2 scales with area; pass 1 with DETECT_EXPONENT,
// which is a fit to measured ratios rather than a law.
private const val RENDER_COST_1080 = 2.0
private const val DETECT_COST_1080 = 1.18
private const val DETECT_EXPONENT = 2.4

private fun renderCost(renderLong: Int): Double =
    RENDER_COST_1080 * (renderLong / DETECT_FLOOR.toDouble()).pow(2)

private fun detectCost(detectLong: Int): Double =
    DETECT_COST_1080 * (detectLong / DETECT_FLOOR.toDouble()).pow(DETECT_EXPONENT)

/** How many times cheaper pass 1 is than pass 2 at these sizes; 1.0 when there is nothing to gain. */
fun speedGain(renderLong: Int, detectLong: Int): Double {
    if (detectLong >= renderLong || detectLong <= 0) return 1.0
    return (renderLong.toDouble() / detectLong).pow(DETECT_EXPONENT)
}

/** Estimated wall-clock seconds for both passes over [duration] seconds of footage. */
fun estimateSeconds(duration: Double, renderLong: Int, detectLong: Int): Double {
    if (duration <= 0.0) return 0.0
    return duration * (renderCost(renderLong) + detectCost(detectLong))
}

/** Share of the total work in each pass; the two sum to 1. */
data class PassWeights(val detect: Double, val transform: Double)

/**
 * Progress weights for the two passes, from the same cost model as [estimateSeconds]
 * (plan §14.4: about 37 % detect and 63 % transform at 1080p). With no pass 1,
 * for example when a reused trf is applied, the transform owns all progress.
 */
fun progressWeights(renderLong: Int, detectLong: Int, detectPass: Boolean = true): PassWeights {
    if (!detectPass) return PassWeights(detect = 0.0, transform = 1.0)
    require(renderLong > 0 && detectLong > 0) { "long edges must be positive" }
    val render = renderCost(renderLong)
    val detect = detectCost(detectLong)
    val total = render + detect
    return PassWeights(detect = detect / total, transform = render / total)
}
