package io.github.pradigunara.staydee

import android.Manifest
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts.PickVisualMedia
import androidx.activity.result.contract.ActivityResultContracts.RequestPermission
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.core.content.ContextCompat
import androidx.core.content.IntentCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.pradigunara.staydee.core.Settings
import io.github.pradigunara.staydee.core.detectGeometry
import io.github.pradigunara.staydee.core.estimateSeconds
import io.github.pradigunara.staydee.pipeline.JobRepository
import io.github.pradigunara.staydee.pipeline.JobState
import io.github.pradigunara.staydee.pipeline.Probe
import io.github.pradigunara.staydee.pipeline.StabilizeRequest
import io.github.pradigunara.staydee.pipeline.probe
import io.github.pradigunara.staydee.ui.ComparePlayerScreen
import io.github.pradigunara.staydee.ui.MainScreen
import io.github.pradigunara.staydee.ui.MainUi
import io.github.pradigunara.staydee.ui.StaydeeTheme

/** Entry point: pick or share one video, run one stabilization, then open, share or compare the result. */
class MainActivity : ComponentActivity() {
    private val settingsStore by lazy { SettingsStore(this) }

    /** A video handed over by ACTION_SEND, waiting for the composition to take it. */
    private var incoming by mutableStateOf<Uri?>(null)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        if (savedInstanceState == null) {
            incoming = sharedVideo(intent)
            CrashLog.offer(this)
        }
        setContent {
            StaydeeTheme {
                App(
                    incoming = incoming,
                    onIncomingConsumed = { incoming = null },
                    settingsStore = settingsStore,
                )
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        sharedVideo(intent)?.let { incoming = it }
    }

    private fun sharedVideo(intent: Intent?): Uri? =
        if (intent?.action == Intent.ACTION_SEND) {
            IntentCompat.getParcelableExtra(intent, Intent.EXTRA_STREAM, Uri::class.java)
        } else {
            null
        }
}

private enum class Screen { MAIN, COMPARE, DIAGNOSTICS }

@Composable
private fun App(incoming: Uri?, onIncomingConsumed: () -> Unit, settingsStore: SettingsStore) {
    val context = LocalContext.current
    val job by JobRepository.state.collectAsStateWithLifecycle()
    var screen by rememberSaveable { mutableStateOf(Screen.MAIN) }
    var source by rememberSaveable { mutableStateOf<Uri?>(null) }
    var settings by remember { mutableStateOf(settingsStore.load()) }

    LaunchedEffect(incoming) {
        val uri = incoming ?: return@LaunchedEffect
        source = uri
        screen = Screen.MAIN
        onIncomingConsumed()
    }

    val probeResult by produceState<Result<Probe>?>(initialValue = null, source) {
        value = null
        val uri = source ?: return@produceState
        value = runCatching { probe(context, uri) }
    }

    val picker = rememberLauncherForActivityResult(PickVisualMedia()) { uri ->
        if (uri != null) source = uri
    }
    val notifications = rememberLauncherForActivityResult(RequestPermission()) { }

    val done = job as? JobState.Done
    val probed = probeResult?.getOrNull()
    val ui = MainUi(
        source = source,
        probe = probed,
        probeError = probeResult?.exceptionOrNull()?.let { it.message ?: it.toString() },
        estimateSeconds = probed?.let { estimateFor(it, settings) },
        settings = settings,
        job = job,
    )

    val onRun: () -> Unit = {
        source?.let { src ->
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
                ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) !=
                PackageManager.PERMISSION_GRANTED
            ) {
                // The foreground service runs without the permission; only its notification is hidden.
                notifications.launch(Manifest.permission.POST_NOTIFICATIONS)
            }
            JobRepository.start(context, StabilizeRequest(src, settings.reconciled().settings))
        }
    }
    val openDiagnostics: () -> Unit = { screen = Screen.DIAGNOSTICS }

    when {
        screen == Screen.COMPARE && done != null -> ComparePlayerScreen(
            source = done.request.src,
            result = done.result,
            onBack = { screen = Screen.MAIN },
        )
        screen == Screen.DIAGNOSTICS -> {
            BackHandler { screen = Screen.MAIN }
            DiagnosticsHost()
        }
        else -> MainScreen(
            ui = ui,
            onPick = { picker.launch(PickVisualMediaRequest(PickVisualMedia.VideoOnly)) },
            onSettingsChange = {
                settings = it
                settingsStore.save(it)
            },
            onRun = onRun,
            onCancel = { JobRepository.cancel(context) },
            onOpen = { done?.let { openVideo(context, it.result) } },
            onShare = { done?.let { shareVideo(context, it.result) } },
            onCompare = { screen = Screen.COMPARE },
            onDismiss = { JobRepository.acknowledge() },
            onDiagnostics = if (BuildConfig.DEBUG) openDiagnostics else null,
        )
    }
}

/** Model estimate for a run of [p] with [s]; null when the container does not report a duration. */
private fun estimateFor(p: Probe, s: Settings): Double? {
    val duration = p.durationSeconds ?: return null
    val renderLong = if (s.maxEdge in 1 until p.longEdge) s.maxEdge else p.longEdge
    val detectLong = detectGeometry(renderLong, s.detectEdge).target
    return estimateSeconds(duration, renderLong, detectLong)
}

private fun openVideo(context: Context, uri: Uri) {
    val intent = Intent(Intent.ACTION_VIEW)
        .setDataAndType(uri, "video/*")
        .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    try {
        context.startActivity(intent)
    } catch (e: ActivityNotFoundException) {
        Toast.makeText(context, R.string.no_app_for_video, Toast.LENGTH_SHORT).show()
    }
}

private fun shareVideo(context: Context, uri: Uri) {
    val send = Intent(Intent.ACTION_SEND)
        .setType("video/*")
        .putExtra(Intent.EXTRA_STREAM, uri)
        .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    context.startActivity(Intent.createChooser(send, context.getString(R.string.share_chooser)))
}
