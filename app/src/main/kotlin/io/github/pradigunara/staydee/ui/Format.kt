package io.github.pradigunara.staydee.ui

import android.content.Context
import io.github.pradigunara.staydee.R
import kotlin.math.roundToLong

/** "42 s" or "2 min 10 s" for a duration in seconds. */
fun formatSeconds(context: Context, seconds: Double): String {
    val total = seconds.roundToLong().coerceAtLeast(0L)
    val minutes = total / 60
    val secs = total % 60
    return if (minutes == 0L) {
        context.getString(R.string.duration_seconds, secs)
    } else {
        context.getString(R.string.duration_minutes_seconds, minutes, secs)
    }
}
