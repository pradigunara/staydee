package io.github.pradigunara.staydee.ui

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import top.yukonga.miuix.kmp.basic.Card
import top.yukonga.miuix.kmp.basic.InfiniteProgressIndicator
import top.yukonga.miuix.kmp.basic.SmallTitle
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.basic.TextButton

/** Plain state in, lambdas out: no Miuix types leak to callers (plan §13.3). */
data class DiagnosticsState(
    val report: String = "",
    val log: String = "",
    val running: Boolean = false,
)

@Composable
fun DiagnosticsScreen(
    title: String,
    state: DiagnosticsState,
    onRepeatability: () -> Unit,
    onCancel: () -> Unit,
    onShare: () -> Unit,
    onExportTrf: () -> Unit,
) {
    StaydeeScreen(title = title) { padding ->
        Column(
            Modifier.padding(padding).padding(horizontal = 12.dp).verticalScroll(rememberScrollState()),
        ) {
            SmallTitle("M0 static checks")
            MonoCard(state.report.ifEmpty { "collecting…" })

            SmallTitle("M0 repeatability")
            Text(
                "Pick a 4K clip with at least 60 frames. Runs 5 transform-only passes from one fixed trf " +
                    "at 1920 and at the clip's own long edge, and compares framemd5 + PSNR.",
                modifier = Modifier.padding(horizontal = 12.dp),
            )
            if (state.running) {
                InfiniteProgressIndicator(Modifier.padding(12.dp))
                TextButton("Cancel", onClick = onCancel, modifier = Modifier.fillMaxWidth().padding(12.dp))
            } else {
                TextButton("Pick clip and run", onClick = onRepeatability, modifier = Modifier.fillMaxWidth().padding(12.dp))
            }
            SmallTitle("Last job's transforms")
            Text(
                "Copies last-detect.trf, last-pass2.trf and last-trf.json from the last stabilization " +
                    "to Download/Staydee, for comparing against the reference CLI.",
                modifier = Modifier.padding(horizontal = 12.dp),
            )
            TextButton("Export trf to Downloads", onClick = onExportTrf, modifier = Modifier.fillMaxWidth().padding(12.dp))
            if (state.log.isNotEmpty()) {
                MonoCard(state.log)
                TextButton("Share results", onClick = onShare, enabled = !state.running, modifier = Modifier.fillMaxWidth().padding(12.dp))
            }
        }
    }
}

@Composable
private fun MonoCard(text: String) {
    Card(Modifier.fillMaxWidth().padding(vertical = 6.dp)) {
        SelectionContainer {
            Text(
                text,
                modifier = Modifier.horizontalScroll(rememberScrollState()).padding(12.dp),
                fontFamily = FontFamily.Monospace,
                fontSize = 11.sp,
            )
        }
    }
}
