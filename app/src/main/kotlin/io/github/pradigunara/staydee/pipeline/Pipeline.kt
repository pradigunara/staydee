package io.github.pradigunara.staydee.pipeline

import android.content.Context
import android.net.Uri
import android.provider.MediaStore
import android.webkit.MimeTypeMap
import io.github.pradigunara.staydee.BuildConfig
import io.github.pradigunara.staydee.core.containerFor
import io.github.pradigunara.staydee.core.creationTime
import io.github.pradigunara.staydee.core.detectArgs
import io.github.pradigunara.staydee.core.detectGeometry
import io.github.pradigunara.staydee.core.estimateSeconds
import io.github.pradigunara.staydee.core.localTimestamp
import io.github.pradigunara.staydee.core.outputPath
import io.github.pradigunara.staydee.core.progressWeights
import io.github.pradigunara.staydee.core.renderTrf
import io.github.pradigunara.staydee.core.scaleIfShrinking
import io.github.pradigunara.staydee.core.transformArgs
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import java.io.File
import java.time.ZoneId

/** A finished render waiting in the cache, not yet in the gallery. */
data class PipelineResult(
    val file: File,
    val ext: String,
    val mime: String,
    /** `<stem>-stab.<ext>` */
    val displayName: String,
    /** `YYYY-MM-DDTHH:MM:SS` (source tag or local mtime), or null when neither is known. */
    val creationTime: String?,
    val warnings: List<String>,
)

/** Directory holding every intermediate file of the running job. */
fun jobDir(context: Context): File = File(context.cacheDir, "job")

/**
 * Probe, detect (pass 1), rescale the transforms, render (pass 2) with the bundled ffmpeg. Pure
 * runner: no service or UI knowledge. Cancelling the calling coroutine kills the running process.
 *
 * [onPlan] gets the model estimate in seconds once the source is probed (null when the duration is
 * unknown). [onStage] gets the stage and overall progress (0..1, null while the duration is unknown);
 * it is called from the tool's stdout reader thread as well as the caller's.
 */
suspend fun runPipeline(
    context: Context,
    request: StabilizeRequest,
    onStage: (JobState.Stage, Float?) -> Unit,
    onPlan: (estimateSeconds: Double?) -> Unit,
): PipelineResult = withContext(Dispatchers.IO) {
    val dir = jobDir(context)
    dir.deleteRecursively()
    check(dir.mkdirs()) { "cannot create ${dir.path}" }

    onStage(JobState.Stage.PROBING, null)
    // One opened source for every run: each run reopens it through /proc/self/fd, with its own offset.
    InputSource.open(context, request.src, dir).use { input -> render(context, request, dir, input, onStage, onPlan) }
}

private suspend fun render(
    context: Context,
    request: StabilizeRequest,
    dir: File,
    input: InputSource,
    onStage: (JobState.Stage, Float?) -> Unit,
    onPlan: (estimateSeconds: Double?) -> Unit,
): PipelineResult {
    val source = parseProbe(probeJson(context, input), displayName(context, request.src))
    currentCoroutineContext().ensureActive()

    val warnings = mutableListOf<String>()
    val reconciled = request.settings.reconciled()
    val settings = reconciled.settings
    warnings += reconciled.warnings

    val srcLong = source.longEdge
    val renderLong = if (settings.maxEdge > 0) minOf(srcLong, settings.maxEdge) else srcLong
    val renderScale = scaleIfShrinking(renderLong, srcLong)
    val geometry = detectGeometry(renderLong, settings.detectEdge, binaryTrf = true)
    val detectPreScale = scaleIfShrinking(geometry.target, srcLong)
    val weights = progressWeights(renderLong, geometry.target)
    val duration = source.durationSeconds

    if (geometry.k != 1.0) warnings += "pass 1 ran at ${geometry.reason}; transforms rescaled to match"
    if (source.transfer.isHdr) warnings += "HDR source (${source.transfer}) tone-mapped to SDR BT.709"

    // The output name is <stem>-stab.<ext>; the extension changes when the container can't hold H.264.
    val named = outputPath(source.displayName, null)
    val ext = containerFor(named.substringAfterLast('.')) { warnings += it }
    val displayName = "${named.substringBeforeLast('.')}.$ext"
    val created = creationTime(source.creationTimeTag, sourceMtimeLocal(context, request.src))

    onPlan(duration?.let { estimateSeconds(it, renderLong, geometry.target) })

    val detectTrf = File(dir, "detect.trf")
    val pass2Trf = File(dir, "pass2.trf")
    val out = File(dir, "out.$ext")

    fun fraction(timeMs: Double): Float? = duration?.let { (timeMs / 1000.0 / it).toFloat().coerceIn(0f, 1f) }

    onStage(JobState.Stage.DETECTING, if (duration != null) 0f else null)
    runFfmpeg(context, input, detectArgs(input.arg, settings, detectTrf.path, detectPreScale), "pass 1") { timeMs ->
        onStage(JobState.Stage.DETECTING, fraction(timeMs)?.let { it * weights.detect.toFloat() })
    }
    currentCoroutineContext().ensureActive()

    val detectBytes = detectTrf.readBytes()
    val pass2Bytes = renderTrf(detectBytes, geometry.k)
    pass2Trf.writeBytes(pass2Bytes)

    onStage(JobState.Stage.TRANSFORMING, if (duration != null) weights.detect.toFloat() else null)
    runFfmpeg(
        context,
        input,
        transformArgs(
            src = input.arg,
            out = out.path,
            s = settings,
            trf = pass2Trf.path,
            renderScale = renderScale,
            transfer = source.transfer,
            outExt = ext,
            hasAudio = source.hasAudio,
            creationTime = created,
        ),
        "pass 2",
    ) { timeMs ->
        onStage(
            JobState.Stage.TRANSFORMING,
            fraction(timeMs)?.let { (weights.detect + it * weights.transform).toFloat() },
        )
    }
    currentCoroutineContext().ensureActive()
    check(out.length() > 0) { "ffmpeg wrote no output" }

    if (BuildConfig.DEBUG) exportDebugTrf(context, detectBytes, pass2Bytes, geometry.target, renderLong, geometry.k)

    return PipelineResult(out, ext, mimeFor(ext), displayName, created, warnings)
}

/**
 * Run one ffmpeg pass. [argv] is the `:core` argv, which starts with the literal `ffmpeg`: that is
 * replaced by the bundled binary, and `-y` plus `-progress pipe:1 -nostats` are added, as the
 * reference CLI's `run_stage` does. [onTimeMs] receives the output position in milliseconds.
 */
private suspend fun runFfmpeg(
    context: Context,
    input: InputSource,
    argv: List<String>,
    what: String,
    onTimeMs: (Double) -> Unit,
) {
    val args = listOf("-y", "-progress", "pipe:1", "-nostats") + argv.drop(1)
    runTool(NativeTools.ffmpeg(context), args, stdin = input) { line ->
        // ffmpeg prints out_time_us in microseconds ("N/A" before the first frame)
        if (line.startsWith("out_time_us=")) line.substringAfter('=').toLongOrNull()?.let { onTimeMs(it / 1000.0) }
    }.requireSuccess(what)
}

/** Local `YYYY-MM-DDTHH:MM:SS` of the source's modification time, when MediaStore can tell. */
private fun sourceMtimeLocal(context: Context, uri: Uri): String? {
    val projection = arrayOf(MediaStore.MediaColumns.DATE_MODIFIED, MediaStore.MediaColumns.DATE_TAKEN)
    val epochSeconds = try {
        context.contentResolver.query(uri, projection, null, null, null)?.use { c ->
            if (!c.moveToFirst()) return@use null
            val modified = if (c.isNull(0)) 0L else c.getLong(0) // seconds
            val takenMs = if (c.isNull(1)) 0L else c.getLong(1) // milliseconds
            when {
                modified > 0 -> modified
                takenMs > 0 -> takenMs / 1000
                else -> null
            }
        }
    } catch (_: Exception) {
        null // not a MediaStore uri, or the provider lacks these columns
    }
    return epochSeconds?.let { localTimestamp(it, ZoneId.systemDefault()) }
}

private fun mimeFor(ext: String): String = when (ext) {
    "mp4" -> "video/mp4"
    "mov" -> "video/quicktime"
    "m4v" -> "video/x-m4v"
    "mkv" -> "video/x-matroska"
    else -> MimeTypeMap.getSingleton().getMimeTypeFromExtension(ext)
        ?.takeIf { it.startsWith("video/") } ?: "video/$ext"
}

/** Debug builds: leave the transforms where `adb pull` finds them (M1 byte gate, plan §8). */
private fun exportDebugTrf(
    context: Context,
    detect: ByteArray,
    pass2: ByteArray,
    detectLong: Int,
    renderLong: Int,
    k: Double,
) {
    val dir = context.getExternalFilesDir("debug") ?: return
    dir.mkdirs()
    File(dir, "last-detect.trf").writeBytes(detect)
    File(dir, "last-pass2.trf").writeBytes(pass2)
    File(dir, "last-trf.json").writeText("""{"detect_long_edge":$detectLong,"render_long_edge":$renderLong,"k":$k}""")
}
