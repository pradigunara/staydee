package io.github.pradigunara.staydee.pipeline

import android.net.Uri
import io.github.pradigunara.staydee.core.Settings

/** One stabilisation request: the source video and the knobs to use. */
data class StabilizeRequest(val src: Uri, val settings: Settings)

/** Where the single running job is. The UI and the notification both render this. */
sealed interface JobState {
    data object Idle : JobState

    enum class Stage { PROBING, DETECTING, TRANSFORMING, SAVING }

    data class Running(
        val request: StabilizeRequest,
        val stage: Stage,
        /** 0..1 over both passes, null while the duration is unknown. */
        val progress: Float?,
        /** Model estimate for the whole job in seconds, null until probed. */
        val estimateSeconds: Double?,
        val startedAtMillis: Long,
    ) : JobState

    data class Done(
        val request: StabilizeRequest,
        /** MediaStore content Uri of the saved result. */
        val result: Uri,
        val displayName: String,
        val elapsedSeconds: Double,
        val warnings: List<String>,
    ) : JobState

    data class Failed(val request: StabilizeRequest?, val message: String) : JobState

    data class Cancelled(val request: StabilizeRequest) : JobState
}
