package io.github.pradigunara.staydee.pipeline

import android.content.Context
import android.content.Intent
import androidx.core.content.ContextCompat
import io.github.pradigunara.staydee.service.StabilizeService
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * The one job the app runs at a time. The UI reads [state] and calls [start]/[cancel];
 * [StabilizeService] does the work and is the only writer of [state].
 */
object JobRepository {
    private val mutableState = MutableStateFlow<JobState>(JobState.Idle)
    val state: StateFlow<JobState> = mutableState.asStateFlow()

    /** Request waiting for the service to pick it up (same process, so no parceling). */
    @Volatile
    internal var pending: StabilizeRequest? = null
        private set

    /** Start [request] in the foreground service. Ignored while a job is running. */
    fun start(context: Context, request: StabilizeRequest) {
        if (state.value is JobState.Running) return
        pending = request
        ContextCompat.startForegroundService(context, Intent(context, StabilizeService::class.java))
    }

    /** Cancel the running job: the current ffmpeg session and any pass not yet started. */
    fun cancel(context: Context) {
        if (state.value !is JobState.Running) return
        context.startService(Intent(context, StabilizeService::class.java).setAction(StabilizeService.ACTION_CANCEL))
    }

    /** Dismiss a finished, failed or cancelled job. */
    fun acknowledge() {
        if (state.value !is JobState.Running) mutableState.value = JobState.Idle
    }

    internal fun takePending(): StabilizeRequest? = pending.also { pending = null }

    internal fun update(next: JobState) {
        mutableState.value = next
    }
}
