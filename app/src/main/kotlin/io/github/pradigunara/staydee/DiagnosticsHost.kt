package io.github.pradigunara.staydee

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.view.WindowManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts.PickVisualMedia
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import com.arthenica.ffmpegkit.FFmpegKit
import io.github.pradigunara.staydee.core.DETECT_FLOOR
import io.github.pradigunara.staydee.core.Settings
import io.github.pradigunara.staydee.core.detectChain
import io.github.pradigunara.staydee.core.scaleIfShrinking
import io.github.pradigunara.staydee.core.transformChain
import io.github.pradigunara.staydee.diag.DiagnosticsReport
import io.github.pradigunara.staydee.diag.Repeatability
import io.github.pradigunara.staydee.ui.DiagnosticsScreen
import io.github.pradigunara.staydee.ui.DiagnosticsState
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

/** M0 diagnostics (debug builds only): device/AAR report and the transform repeatability check (plan §8). */
@Composable
fun DiagnosticsHost() {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var state by remember { mutableStateOf(DiagnosticsState()) }
    var job by remember { mutableStateOf<Job?>(null) }

    LaunchedEffect(Unit) {
        val report = withContext(Dispatchers.IO) { DiagnosticsReport.build() }
        state = state.copy(report = report)
    }

    val picker = rememberLauncherForActivityResult(PickVisualMedia()) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        job = scope.launch {
            state = state.copy(running = true, log = "")
            val window = (context as Activity).window
            window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
            val append: (String) -> Unit = { line ->
                scope.launch { state = state.copy(log = state.log + line + "\n") }
            }
            try {
                withContext(Dispatchers.IO) { repeatability(context, uri, append) }
            } catch (e: Exception) {
                append("FAILED: ${e.message}")
            } finally {
                window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
                state = state.copy(running = false)
            }
        }
    }

    DiagnosticsScreen(
        title = stringResource(R.string.app_name),
        state = state,
        onRepeatability = { picker.launch(PickVisualMediaRequest(PickVisualMedia.VideoOnly)) },
        onCancel = {
            FFmpegKit.cancel()
            job?.cancel()
        },
        onShare = { share(context, state.report + "\n" + state.log) },
    )
}

private fun repeatability(context: Context, uri: Uri, log: (String) -> Unit) {
    val work = File(context.cacheDir, "m0").apply { deleteRecursively(); mkdirs() }
    // a local copy: SAF descriptors are single-use, and every run re-reads the input
    val src = File(work, "input")
    log("copying input…")
    context.contentResolver.openInputStream(uri)!!.use { input -> src.outputStream().use { input.copyTo(it) } }

    val (longEdge, frames) = MediaMetadataRetriever().run {
        try {
            setDataSource(src.path)
            val w = extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH)!!.toInt()
            val h = extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT)!!.toInt()
            val n = extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_FRAME_COUNT)?.toIntOrNull()
            maxOf(w, h) to n
        } finally {
            release()
        }
    }
    log("input: long edge $longEdge, ${frames ?: "?"} frames")
    if (frames != null && frames < FRAMES) log("WARNING: fewer than $FRAMES frames, result is weak evidence")

    val settings = Settings(sharpen = false) // transform only: no unsharp, no x264
    val edges = listOf(DETECT_FLOOR, longEdge).distinct().filter { it <= longEdge }
    val runner = Repeatability(work, log)
    val results = edges.map { edge ->
        val scale = scaleIfShrinking(edge, longEdge)
        runner.measure(
            src = src.path,
            label = "${edge}p",
            chains = Repeatability.Chains(
                detect = { trf -> detectChain(settings, trf, scale) },
                transform = { trf -> transformChain(settings, trf, scale) },
            ),
            runs = RUNS,
            frames = FRAMES,
        )
    }
    log("")
    log("summary ($RUNS runs, $FRAMES frames each)")
    for (r in results) {
        val verdict = if (r.deterministic) "DETERMINISTIC" else "NOT deterministic"
        log("  ${r.label}: $verdict, ${r.distinctHashes} distinct, ${r.frames} frames hashed")
        for (run in r.runs.drop(1)) log("    run ${run.run}: ${run.framesDiffering} differ, PSNR ${run.psnrAvg}")
    }
    src.delete()
}

private fun share(context: Context, text: String) {
    val send = Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, text)
    context.startActivity(Intent.createChooser(send, "Share diagnostics"))
}

private const val RUNS = 5
private const val FRAMES = 60
