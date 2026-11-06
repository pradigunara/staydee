package io.github.pradigunara.staydee.pipeline

import android.content.Context
import android.net.Uri
import android.provider.MediaStore
import android.webkit.MimeTypeMap
import com.arthenica.ffmpegkit.FFmpegKit
import com.arthenica.ffmpegkit.FFmpegKitConfig
import com.arthenica.ffmpegkit.FFmpegSessionCompleteCallback
import com.arthenica.ffmpegkit.ReturnCode
import com.arthenica.ffmpegkit.StatisticsCallback
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
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import java.io.File
import java.time.ZoneId
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

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

private const val LOG_TAIL_CHARS = 1500

/**
 * Probe, detect (pass 1), rescale the transforms, render (pass 2). Pure runner: no service or UI
 * knowledge. Cancelling the calling coroutine cancels the running ffmpeg session.
 *
 * [onPlan] gets the model estimate in seconds once the source is probed (null when the duration is
 * unknown). [onStage] gets the stage and overall progress (0..1, null while the duration is unknown);
 * it is called from ffmpeg's statistics thread as well as the caller's.
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
    val source = probe(context, request.src)
    ensureActive()

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
    runFfmpeg(
        // SAF descriptors are single-use, so each session asks for a fresh one.
        detectArgs(FFmpegKitConfig.getSafParameterForRead(context, request.src), settings, detectTrf.path, detectPreScale),
    ) { timeMs ->
        onStage(JobState.Stage.DETECTING, fraction(timeMs)?.let { it * weights.detect.toFloat() })
    }
    ensureActive()

    val detectBytes = detectTrf.readBytes()
    val pass2Bytes = renderTrf(detectBytes, geometry.k)
    pass2Trf.writeBytes(pass2Bytes)

    onStage(JobState.Stage.TRANSFORMING, if (duration != null) weights.detect.toFloat() else null)
    runFfmpeg(
        transformArgs(
            src = FFmpegKitConfig.getSafParameterForRead(context, request.src),
            out = out.path,
            s = settings,
            trf = pass2Trf.path,
            renderScale = renderScale,
            transfer = source.transfer,
            outExt = ext,
            hasAudio = source.hasAudio,
            creationTime = created,
        ),
    ) { timeMs ->
        onStage(
            JobState.Stage.TRANSFORMING,
            fraction(timeMs)?.let { (weights.detect + it * weights.transform).toFloat() },
        )
    }
    ensureActive()
    check(out.length() > 0) { "ffmpeg wrote no output" }

    if (BuildConfig.DEBUG) exportDebugTrf(context, detectBytes, pass2Bytes, geometry.target, renderLong, geometry.k)

    PipelineResult(out, ext, mimeFor(ext), displayName, created, warnings)
}

/**
 * Run one ffmpeg session. [argv] is the `:core` argv, which starts with the literal `ffmpeg`; FFmpegKit
 * takes the arguments only, so that is dropped and `-y` is added. [onTimeMs] receives the output position
 * in milliseconds. Cancelling the coroutine cancels the session.
 */
private suspend fun runFfmpeg(argv: List<String>, onTimeMs: (Double) -> Unit) {
    val args = (listOf("-y") + argv.drop(1)).toTypedArray()
    suspendCancellableCoroutine<Unit> { cont ->
        val session = FFmpegKit.executeWithArgumentsAsync(
            args,
            FFmpegSessionCompleteCallback { finished ->
                val code = finished.returnCode
                when {
                    ReturnCode.isSuccess(code) -> cont.resume(Unit)
                    ReturnCode.isCancel(code) -> cont.cancel()
                    else -> cont.resumeWithException(
                        IllegalStateException(
                            "ffmpeg failed ($code):\n${finished.allLogsAsString.orEmpty().takeLast(LOG_TAIL_CHARS)}",
                        ),
                    )
                }
            },
            null,
            StatisticsCallback { stats -> onTimeMs(stats.time) },
        )
        cont.invokeOnCancellation { FFmpegKit.cancel(session.sessionId) }
    }
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
