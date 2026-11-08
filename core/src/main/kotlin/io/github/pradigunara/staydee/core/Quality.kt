package io.github.pradigunara.staydee.core

/** The main screen's quality choice; the CRF it means depends on the output resolution (plan §15.8). */
enum class QualityLevel { SMALLER, BALANCED, HIGHER }

/**
 * libx264 CRF for [level] at an output whose long edge is [renderLong]. Balanced is 21 / 22 / 23 / 25
 * for ≤ 720p / 1080p / 1440p / 4K-class; Smaller and Higher are ±2 (+6 ≈ half the size, ffmpeg wiki).
 * Every value lies inside HandBrake's recommended x264 range for its resolution (1080p 20–24,
 * 2160p 22–28): at higher resolution each artefact is a smaller share of the picture.
 */
fun autoCrf(renderLong: Int, level: QualityLevel): Int {
    val balanced = when {
        renderLong <= 1280 -> 21
        renderLong <= DETECT_FLOOR -> 22
        renderLong <= 2560 -> 23
        else -> 25
    }
    return when (level) {
        QualityLevel.SMALLER -> balanced + 2
        QualityLevel.BALANCED -> balanced
        QualityLevel.HIGHER -> balanced - 2
    }
}

/** Long edge of the rendered output: the source's, limited by [maxEdge] (0 = no limit). */
fun renderLongEdge(srcLong: Int, maxEdge: Int): Int = if (maxEdge > 0) minOf(srcLong, maxEdge) else srcLong
