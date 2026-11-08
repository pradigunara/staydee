package io.github.pradigunara.staydee

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.ViewModel
import io.github.pradigunara.staydee.core.QualityLevel
import io.github.pradigunara.staydee.core.SMOOTHING_SECONDS_DEFAULT
import io.github.pradigunara.staydee.core.Settings

/** The max render long edges the UI offers; 0 is full resolution. */
val MAX_EDGE_CHOICES: List<Int> = listOf(0, 1920, 2560, 3840)

/**
 * The knobs while the app is open. Deliberately not persisted: every launch starts from the
 * defaults, so one clip's tuning doesn't silently carry over to the next video (owner decision,
 * plan §15.7). A ViewModel keeps them across rotation within the session.
 */
class EditorState : ViewModel() {
    var settings by mutableStateOf(Settings())

    /** Main-screen smoothing in seconds; converted to frames of the clip when a job starts. */
    var smoothingSeconds by mutableStateOf(SMOOTHING_SECONDS_DEFAULT)

    /** Main-screen quality; the CRF it means follows the output resolution (core `autoCrf`). */
    var quality by mutableStateOf(QualityLevel.BALANCED)

    /** Exact CRF set in Advanced; null = automatic from [quality]. */
    var crfOverride by mutableStateOf<Int?>(null)
}
