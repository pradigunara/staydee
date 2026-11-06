package io.github.pradigunara.staydee.ui

import android.net.Uri
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.common.VideoSize
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.ui.PlayerView
import io.github.pradigunara.staydee.R
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlin.math.abs
import kotlin.math.max

private const val DRIFT_MS = 80L
private const val TICK_MS = 200L

/**
 * Source (muted) and result, side by side for portrait video and stacked for landscape.
 * One transport drives both. The source is the reference clock: the result is nudged back
 * onto it whenever the two drift apart during playback.
 */
@Composable
fun ComparePlayerScreen(source: Uri, result: Uri, onBack: () -> Unit) {
    BackHandler(onBack = onBack)
    val context = LocalContext.current
    val srcPlayer = remember(source) {
        ExoPlayer.Builder(context).build().apply {
            setMediaItem(MediaItem.fromUri(source))
            volume = 0f
            prepare()
        }
    }
    val resPlayer = remember(result) {
        ExoPlayer.Builder(context).build().apply {
            setMediaItem(MediaItem.fromUri(result))
            prepare()
        }
    }

    var playing by remember { mutableStateOf(false) }
    var portrait by remember { mutableStateOf(false) }
    var durationMs by remember { mutableLongStateOf(0L) }
    var positionMs by remember { mutableLongStateOf(0L) }
    var scrub by remember { mutableStateOf<Float?>(null) }

    DisposableEffect(srcPlayer, resPlayer) {
        val listener = object : Player.Listener {
            override fun onIsPlayingChanged(isPlaying: Boolean) {
                playing = isPlaying
            }

            override fun onVideoSizeChanged(videoSize: VideoSize) {
                if (videoSize.width > 0 && videoSize.height > 0) portrait = videoSize.isPortrait()
            }

            override fun onPlaybackStateChanged(playbackState: Int) {
                if (playbackState == Player.STATE_READY) durationMs = max(resPlayer.duration, 0L)
            }
        }
        resPlayer.addListener(listener)
        onDispose {
            resPlayer.removeListener(listener)
            srcPlayer.release()
            resPlayer.release()
        }
    }

    // Pause when the app leaves the foreground; audio would otherwise keep playing.
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    DisposableEffect(lifecycle, srcPlayer, resPlayer) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_STOP) {
                srcPlayer.pause()
                resPlayer.pause()
            }
        }
        lifecycle.addObserver(observer)
        onDispose { lifecycle.removeObserver(observer) }
    }

    LaunchedEffect(srcPlayer, resPlayer) {
        while (isActive) {
            delay(TICK_MS)
            if (scrub == null) {
                val reference = srcPlayer.currentPosition
                if (playing && abs(resPlayer.currentPosition - reference) > DRIFT_MS) {
                    resPlayer.seekTo(reference)
                }
                positionMs = resPlayer.currentPosition
            }
        }
    }

    fun toggle() {
        if (playing) {
            srcPlayer.pause()
            resPlayer.pause()
        } else {
            if (resPlayer.playbackState == Player.STATE_ENDED) {
                srcPlayer.seekTo(0)
                resPlayer.seekTo(0)
            }
            srcPlayer.play()
            resPlayer.play()
        }
    }

    val shown = scrub ?: if (durationMs > 0) (positionMs.toFloat() / durationMs).coerceIn(0f, 1f) else 0f

    StaydeeScreen(title = stringResource(R.string.compare_title)) { padding ->
        Column(Modifier.fillMaxSize().padding(padding)) {
            val originalLabel = stringResource(R.string.compare_original)
            val stabilizedLabel = stringResource(R.string.compare_stabilized)
            if (portrait) {
                Row(Modifier.weight(1f)) {
                    Pane(srcPlayer, originalLabel, Modifier.weight(1f))
                    Pane(resPlayer, stabilizedLabel, Modifier.weight(1f))
                }
            } else {
                Column(Modifier.weight(1f)) {
                    Pane(srcPlayer, originalLabel, Modifier.weight(1f))
                    Pane(resPlayer, stabilizedLabel, Modifier.weight(1f))
                }
            }
            TextAction(
                text = stringResource(if (playing) R.string.compare_pause else R.string.compare_play),
                onClick = { toggle() },
                modifier = Modifier.fillMaxWidth(),
            )
            ScrubSlider(
                value = shown,
                onValueChange = { scrub = it },
                onValueChangeFinished = {
                    val target = ((scrub ?: shown) * durationMs).toLong()
                    srcPlayer.seekTo(target)
                    resPlayer.seekTo(target)
                    positionMs = target
                    scrub = null
                },
                modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp),
            )
        }
    }
}

@Composable
private fun Pane(player: Player, label: String, modifier: Modifier) {
    Column(modifier) {
        Caption(label, Modifier.padding(horizontal = 12.dp, vertical = 4.dp))
        AndroidView(
            factory = { context ->
                PlayerView(context).apply {
                    useController = false
                    this.player = player
                }
            },
            update = { it.player = player },
            modifier = Modifier.fillMaxWidth().weight(1f),
        )
    }
}

private fun VideoSize.isPortrait(): Boolean {
    val rotated = unappliedRotationDegrees == 90 || unappliedRotationDegrees == 270
    return if (rotated) width > height else height > width
}
