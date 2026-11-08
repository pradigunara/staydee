package io.github.pradigunara.staydee.pipeline

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import io.github.pradigunara.staydee.core.ColorTransfer
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.File

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

/** Video codecs the bundled ffmpeg can decode (vendor/ffmpeg/BUILDINFO.md); notably no AV1. */
private val DECODABLE_VIDEO = setOf("h264", "hevc", "mpeg4", "h263", "vp8", "vp9", "ffv1", "rawvideo")

/** Probe [uri] with the bundled ffprobe. Throws [IllegalStateException] with a readable reason. */
suspend fun probe(context: Context, uri: Uri): Probe = withContext(Dispatchers.IO) {
    // IO: opening the Uri, and copying it when the provider can't seek, must stay off the main thread
    val scratch = File(context.cacheDir, "probe").apply { mkdirs() }
    val json = InputSource.open(context, uri, scratch).use { input -> probeJson(context, input) }
    scratch.deleteRecursively()
    parseProbe(json, displayName(context, uri))
}

/** ffprobe's JSON for [input]; the pipeline reuses its already opened source. */
internal suspend fun probeJson(context: Context, input: InputSource): JSONObject {
    val result = runTool(
        NativeTools.ffprobe(context),
        listOf("-v", "error", "-print_format", "json", "-show_format", "-show_streams", input.arg),
        stdin = input,
        collectStdout = true,
    )
    check(result.exitCode == 0) { "not a readable video: ${result.stderrTail.trim().lines().lastOrNull().orEmpty()}" }
    return JSONObject(result.stdout)
}

internal fun parseProbe(json: JSONObject, displayName: String): Probe {
    val streams = json.optJSONArray("streams")
    val all = (0 until (streams?.length() ?: 0)).map { streams!!.getJSONObject(it) }
    val video = checkNotNull(all.firstOrNull { it.optString("codec_type") == "video" }) { "no video stream" }
    val codec = video.optString("codec_name")
    check(codec in DECODABLE_VIDEO) { "${codec.uppercase()} video is not supported" }
    val format = json.optJSONObject("format")
    return Probe(
        width = video.optInt("width").also { check(it > 0) { "video width unknown" } },
        height = video.optInt("height").also { check(it > 0) { "video height unknown" } },
        durationSeconds = format?.optString("duration")?.toDoubleOrNull()?.takeIf { it > 0 },
        transfer = ColorTransfer.fromFfprobe(video.optString("color_transfer").takeIf { it.isNotEmpty() }),
        hasAudio = all.any { it.optString("codec_type") == "audio" },
        creationTimeTag = format?.optJSONObject("tags")?.optString("creation_time")?.takeIf { it.isNotEmpty() },
        displayName = displayName,
    )
}

internal fun displayName(context: Context, uri: Uri): String =
    context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { c ->
        if (c.moveToFirst()) c.getString(0) else null
    } ?: uri.lastPathSegment?.substringAfterLast('/') ?: "video.mp4"
