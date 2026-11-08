package io.github.pradigunara.staydee

import android.content.Context
import android.content.SharedPreferences
import io.github.pradigunara.staydee.core.Border
import io.github.pradigunara.staydee.core.DetectEdge
import io.github.pradigunara.staydee.core.Interpol
import io.github.pradigunara.staydee.core.OptAlgo
import io.github.pradigunara.staydee.core.Settings
import io.github.pradigunara.staydee.core.X264_PRESETS

/** The max render long edges the UI offers; 0 is full resolution. */
val MAX_EDGE_CHOICES: List<Int> = listOf(0, 1920, 2560, 3840)

/**
 * [Settings] in SharedPreferences. Every read clamps to the valid range or falls back to the
 * default, so a stale or hand-edited file cannot crash the app. Doubles are stored as text so
 * they round-trip exactly.
 */
class SettingsStore(context: Context) {
    private val prefs = context.getSharedPreferences("settings", Context.MODE_PRIVATE)
    private val defaults = Settings()

    fun load(): Settings = Settings(
        shakiness = prefs.getInt(SHAKINESS, defaults.shakiness).coerceIn(1, 10),
        accuracy = prefs.getInt(ACCURACY, defaults.accuracy).coerceIn(1, 15),
        smoothing = prefs.getInt(SMOOTHING, defaults.smoothing).coerceIn(0, 1000),
        optzoom = prefs.getInt(OPTZOOM, defaults.optzoom).coerceIn(0, 2),
        optalgo = prefs.getString(OPTALGO, null)?.let { OptAlgo.fromWire(it) } ?: defaults.optalgo,
        zoomspeed = prefs.double(ZOOMSPEED, defaults.zoomspeed).coerceIn(0.0, 5.0),
        border = prefs.getString(BORDER, null)?.let { Border.fromWire(it) } ?: defaults.border,
        interpol = prefs.getString(INTERPOL, null)?.let { Interpol.fromWire(it) } ?: defaults.interpol,
        sharpen = prefs.getBoolean(SHARPEN, defaults.sharpen),
        crf = prefs.getInt(CRF, defaults.crf).coerceIn(0, 51),
        preset = prefs.getString(PRESET, null)?.takeIf { it in X264_PRESETS } ?: defaults.preset,
        audio = prefs.getBoolean(AUDIO, defaults.audio),
        tripod = prefs.getBoolean(TRIPOD, defaults.tripod),
        stepsize = prefs.getInt(STEPSIZE, defaults.stepsize).coerceIn(1, 32),
        mincontrast = prefs.double(MINCONTRAST, defaults.mincontrast).coerceIn(0.0, 1.0),
        maxshift = prefs.getInt(MAXSHIFT, defaults.maxshift).coerceIn(-1, 500),
        maxangle = prefs.double(MAXANGLE, defaults.maxangle).coerceIn(-1.0, 3.14),
        zoom = prefs.getInt(ZOOM, defaults.zoom).coerceIn(-100, 100),
        maxEdge = prefs.getInt(MAX_EDGE, defaults.maxEdge).takeIf { it in MAX_EDGE_CHOICES } ?: defaults.maxEdge,
        detectEdge = when (val text = prefs.getString(DETECT_EDGE, null)) {
            "off" -> DetectEdge.Off
            null -> defaults.detectEdge
            else -> text.toIntOrNull()?.takeIf { it > 0 }?.let { DetectEdge.Pixels(it) } ?: DetectEdge.Auto
        },
    )

    fun save(s: Settings) {
        prefs.edit().apply {
            putInt(SHAKINESS, s.shakiness)
            putInt(ACCURACY, s.accuracy)
            putInt(SMOOTHING, s.smoothing)
            putInt(OPTZOOM, s.optzoom)
            putString(OPTALGO, s.optalgo.wire)
            putString(ZOOMSPEED, s.zoomspeed.toString())
            putString(BORDER, s.border.wire)
            putString(INTERPOL, s.interpol.wire)
            putBoolean(SHARPEN, s.sharpen)
            putInt(CRF, s.crf)
            putString(PRESET, s.preset)
            putBoolean(AUDIO, s.audio)
            putBoolean(TRIPOD, s.tripod)
            putInt(STEPSIZE, s.stepsize)
            putString(MINCONTRAST, s.mincontrast.toString())
            putInt(MAXSHIFT, s.maxshift)
            putString(MAXANGLE, s.maxangle.toString())
            putInt(ZOOM, s.zoom)
            putInt(MAX_EDGE, s.maxEdge)
            putString(
                DETECT_EDGE,
                when (val edge = s.detectEdge) {
                    DetectEdge.Auto -> "auto"
                    DetectEdge.Off -> "off"
                    is DetectEdge.Pixels -> edge.px.toString()
                },
            )
        }.apply()
    }

    private fun SharedPreferences.double(key: String, default: Double): Double =
        getString(key, null)?.toDoubleOrNull()?.takeIf { it.isFinite() } ?: default

    private companion object {
        const val SHAKINESS = "shakiness"
        const val ACCURACY = "accuracy"
        const val SMOOTHING = "smoothing"
        const val OPTZOOM = "optzoom"
        const val OPTALGO = "optalgo"
        const val ZOOMSPEED = "zoomspeed"
        const val BORDER = "border"
        const val INTERPOL = "interpol"
        const val SHARPEN = "sharpen"
        const val CRF = "crf"
        const val PRESET = "preset"
        const val AUDIO = "audio"
        const val TRIPOD = "tripod"
        const val STEPSIZE = "stepsize"
        const val MINCONTRAST = "mincontrast"
        const val MAXSHIFT = "maxshift"
        const val MAXANGLE = "maxangle"
        const val ZOOM = "zoom"
        const val MAX_EDGE = "max_edge"
        const val DETECT_EDGE = "detect_edge"
    }
}
