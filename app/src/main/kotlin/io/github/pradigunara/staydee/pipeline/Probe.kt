package io.github.pradigunara.staydee.pipeline

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import com.arthenica.ffmpegkit.FFmpegKitConfig
import com.arthenica.ffmpegkit.FFprobeKit
import io.github.pradigunara.staydee.core.ColorTransfer
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** What the pipeline and the estimate need to know about a source video. */
data class Probe(
    val width: Int,
    val height: Int,
    /** Seconds; null when the container does not say. */
    val durationSeconds: Double?,
    val transfer: ColorTransfer,
    val hasAudio: Boolean,
    /** `format.tags.creation_time` verbatim, if present. */
    val creationTimeTag: String?,
    /** Source file name as the provider reports it, e.g. `VID_20261107_101500.mp4`. */
    val displayName: String,
) {
    val longEdge: Int get() = maxOf(width, height)
}

/** Probe [uri] with FFprobe over SAF. Throws [IllegalStateException] if it has no video stream. */
suspend fun probe(context: Context, uri: Uri): Probe = withContext(Dispatchers.IO) {
    val session = FFprobeKit.getMediaInformation(FFmpegKitConfig.getSafParameterForRead(context, uri))
    val info = checkNotNull(session.mediaInformation) { "not a readable video: ${session.output?.takeLast(300)}" }
    val streams = info.streams.orEmpty()
    val video = checkNotNull(streams.firstOrNull { it.type == "video" }) { "no video stream" }
    Probe(
        width = checkNotNull(video.width) { "video width unknown" }.toInt(),
        height = checkNotNull(video.height) { "video height unknown" }.toInt(),
        durationSeconds = info.duration?.toDoubleOrNull()?.takeIf { it > 0 },
        transfer = ColorTransfer.fromFfprobe(video.getStringProperty("color_transfer")),
        hasAudio = streams.any { it.type == "audio" },
        creationTimeTag = info.tags?.optString("creation_time")?.takeIf { it.isNotEmpty() },
        displayName = displayName(context, uri),
    )
}

private fun displayName(context: Context, uri: Uri): String =
    context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { c ->
        if (c.moveToFirst()) c.getString(0) else null
    } ?: uri.lastPathSegment?.substringAfterLast('/') ?: "video.mp4"
