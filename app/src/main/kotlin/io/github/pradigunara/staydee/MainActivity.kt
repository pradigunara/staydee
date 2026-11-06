package io.github.pradigunara.staydee

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.padding
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.arthenica.ffmpegkit.FFmpegKitConfig
import io.github.pradigunara.staydee.ui.StaydeeScreen
import io.github.pradigunara.staydee.ui.StaydeeTheme
import top.yukonga.miuix.kmp.basic.Text

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        val ffmpeg = "FFmpeg ${FFmpegKitConfig.getFFmpegVersion()} · FFmpegKit ${FFmpegKitConfig.getVersion()}"
        setContent {
            StaydeeTheme {
                StaydeeScreen(title = getString(R.string.app_name)) { padding ->
                    Text(ffmpeg, modifier = Modifier.padding(padding).padding(16.dp))
                }
            }
        }
    }
}
