package io.github.pradigunara.staydee.core

/** x264 presets, fastest first (Python `PRESETS`). */
val X264_PRESETS: List<String> = listOf(
    "ultrafast", "superfast", "veryfast", "faster", "fast",
    "medium", "slow", "slower", "veryslow",
)

enum class OptAlgo(val wire: String) {
    GAUSS("gauss"), OPT("opt"), AVG("avg");

    companion object {
        fun fromWire(text: String): OptAlgo? = values().firstOrNull { it.wire == text }
    }
}

enum class Border(val wire: String) {
    KEEP("keep"), BLACK("black");

    companion object {
        fun fromWire(text: String): Border? = values().firstOrNull { it.wire == text }
    }
}

enum class Interpol(val wire: String) {
    NO("no"), LINEAR("linear"), BILINEAR("bilinear"), BICUBIC("bicubic");

    companion object {
        fun fromWire(text: String): Interpol? = values().firstOrNull { it.wire == text }
    }
}

/** Pass-1 resolution target (Python `detect_edge`: "auto" | "off" | pixel target). */
sealed interface DetectEdge {
    /** Half the long edge, floored at [DETECT_FLOOR]. */
    object Auto : DetectEdge

    object Off : DetectEdge

    data class Pixels(val px: Int) : DetectEdge {
        init {
            require(px > 0) { "detect edge must be a positive pixel target (got $px)" }
        }
    }
}

/**
 * Parse a `--detect-edge` value: `auto`, `off`, or a positive integer.
 * Integers beyond [Int.MAX_VALUE] clamp to it; detection always clamps to the
 * render size anyway, so the result is the same.
 * Throws [IllegalArgumentException] otherwise.
 */
fun parseDetectEdge(text: String): DetectEdge = when (text) {
    "auto" -> DetectEdge.Auto
    "off" -> DetectEdge.Off
    else -> {
        val n = parseBigInt10(text)
        require(n.signum() > 0) { "detect edge must be positive (got '$text')" }
        DetectEdge.Pixels(if (n > java.math.BigInteger.valueOf(Int.MAX_VALUE.toLong())) Int.MAX_VALUE else n.toInt())
    }
}

/** Result of [Settings.reconciled]: the adjusted settings plus user-facing warnings. */
data class Reconciled(val settings: Settings, val warnings: List<String>)

/**
 * All stabilisation knobs with the defaults from the reference CLI (plan §4.1).
 * Ranges are the ones the reference CLI accepts; out-of-range values throw
 * [IllegalArgumentException]. The app does not expose the `compare` knob.
 */
data class Settings(
    val shakiness: Int = 5,
    val accuracy: Int = 15,
    val smoothing: Int = 15,
    val optzoom: Int = 1,
    val optalgo: OptAlgo = OptAlgo.GAUSS,
    val zoomspeed: Double = 0.25,
    val border: Border = Border.KEEP,
    val interpol: Interpol = Interpol.BICUBIC,
    val sharpen: Boolean = true,
    val crf: Int = 22,
    val preset: String = "veryfast",
    val audio: Boolean = true,
    val tripod: Boolean = false,
    val stepsize: Int = 6,
    val mincontrast: Double = 0.25,
    val maxshift: Int = -1,
    val maxangle: Double = -1.0,
    val zoom: Int = 0,
    /** 0 = never downscale the render. */
    val maxEdge: Int = 0,
    val detectEdge: DetectEdge = DetectEdge.Auto,
) {
    init {
        require(shakiness in 1..10) { "shakiness must be 1-10 (got $shakiness)" }
        require(accuracy in 1..15) { "accuracy must be 1-15 (got $accuracy)" }
        require(smoothing in 0..1000) { "smoothing must be 0-1000 (got $smoothing)" }
        require(optzoom in 0..2) { "optzoom must be 0-2 (got $optzoom)" }
        require(zoomspeed in 0.0..5.0) { "zoomspeed must be 0-5 (got $zoomspeed)" }
        require(crf in 0..51) { "crf must be 0-51 (got $crf)" }
        require(preset in X264_PRESETS) { "preset must be one of $X264_PRESETS (got '$preset')" }
        require(stepsize in 1..32) { "stepsize must be 1-32 (got $stepsize)" }
        require(mincontrast in 0.0..1.0) { "mincontrast must be 0-1 (got $mincontrast)" }
        require(maxshift in -1..500) { "maxshift must be -1-500 (got $maxshift)" }
        require(maxangle in -1.0..3.14) { "maxangle must be -1-3.14 (got $maxangle)" }
        require(zoom in -100..100) { "zoom must be -100-100 (got $zoom)" }
        require(maxEdge in 0..8640) { "maxEdge must be 0-8640 (got $maxEdge)" }
    }

    /**
     * Apply the reference CLI's post-prompt rules: accuracy is raised to
     * shakiness (with a warning), and tripod mode warns that smoothing is
     * ignored. Smoothing and relative are left as-is, as the reference does.
     */
    fun reconciled(): Reconciled {
        val warnings = mutableListOf<String>()
        var adjusted = this
        if (accuracy < shakiness) {
            adjusted = copy(accuracy = shakiness)
            warnings += "accuracy must be >= shakiness — bumped accuracy to $shakiness"
        }
        if (tripod) {
            warnings += "tripod mode sets relative=0:smoothing=0 — your smoothing value is ignored"
        }
        return Reconciled(adjusted, warnings)
    }
}
