package io.github.pradigunara.staydee.ui

import android.net.Uri
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import io.github.pradigunara.staydee.R
import io.github.pradigunara.staydee.core.Settings
import io.github.pradigunara.staydee.pipeline.JobState
import io.github.pradigunara.staydee.pipeline.Probe

/** Everything the main screen shows. Plain data; the screen never reads repositories itself. */
data class MainUi(
    val source: Uri?,
    /** Null while the source is being read, and when reading failed (see [probeError]). */
    val probe: Probe?,
    val probeError: String?,
    /** Estimate for a run with [settings]; null when the duration is unknown. */
    val estimateSeconds: Double?,
    val settings: Settings,
    val job: JobState,
)

/**
 * The single screen: form while idle; progress, result or failure while a job exists.
 * Overlays live inside the [StaydeeScreen] scaffold (plan §13.2).
 */
@Composable
fun MainScreen(
    ui: MainUi,
    onPick: () -> Unit,
    onSettingsChange: (Settings) -> Unit,
    onRun: () -> Unit,
    onCancel: () -> Unit,
    onOpen: () -> Unit,
    onShare: () -> Unit,
    onCompare: () -> Unit,
    onDismiss: () -> Unit,
    onDiagnostics: (() -> Unit)?,
) {
    var showAdvanced by remember { mutableStateOf(false) }
    StaydeeScreen(title = stringResource(R.string.app_name)) { padding ->
        Column(
            Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .padding(bottom = 16.dp),
        ) {
            when (val job = ui.job) {
                JobState.Idle -> IdleForm(
                    ui = ui,
                    onPick = onPick,
                    onSettingsChange = onSettingsChange,
                    onRun = onRun,
                    onAdvanced = { showAdvanced = true },
                    onDiagnostics = onDiagnostics,
                )
                is JobState.Running -> RunningPanel(job, onCancel)
                is JobState.Done -> DonePanel(job, onOpen, onShare, onCompare, onDismiss)
                is JobState.Failed -> ResultMessage(stringResource(R.string.failed_title), job.message, onDismiss)
                is JobState.Cancelled -> ResultMessage(stringResource(R.string.cancelled_title), null, onDismiss)
            }
        }
        AdvancedSheet(
            show = showAdvanced,
            settings = ui.settings,
            onSettingsChange = onSettingsChange,
            onDismiss = { showAdvanced = false },
        )
    }
}

@Composable
private fun IdleForm(
    ui: MainUi,
    onPick: () -> Unit,
    onSettingsChange: (Settings) -> Unit,
    onRun: () -> Unit,
    onAdvanced: () -> Unit,
    onDiagnostics: (() -> Unit)?,
) {
    val context = LocalContext.current
    val s = ui.settings
    SourceCard(ui, onPick)

    Heading(stringResource(R.string.heading_stabilize))
    KnobGroup(Modifier.padding(horizontal = CARD_INSET).fillMaxWidth()) {
        IntKnob(
            title = stringResource(R.string.knob_shakiness),
            value = s.shakiness,
            range = 1..10,
            summary = stringResource(R.string.knob_shakiness_summary),
            onCommit = { onSettingsChange(s.copy(shakiness = it)) },
        )
        IntKnob(
            title = stringResource(R.string.knob_smoothing),
            value = s.smoothing,
            range = 0..1000,
            summary = stringResource(R.string.knob_smoothing_summary),
            onCommit = { onSettingsChange(s.copy(smoothing = it)) },
        )
        IntKnob(
            title = stringResource(R.string.knob_crf),
            value = s.crf,
            range = 0..51,
            summary = stringResource(R.string.knob_crf_summary),
            onCommit = { onSettingsChange(s.copy(crf = it)) },
        )
        ArrowKnob(
            title = stringResource(R.string.advanced_open),
            summary = stringResource(R.string.advanced_summary),
            onClick = onAdvanced,
        )
    }

    val textRow = Modifier.padding(horizontal = TEXT_INSET)
    Column(Modifier.padding(top = 12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        s.reconciled().warnings.forEach { Caption(it, textRow) }
        val estimate = ui.estimateSeconds
        if (estimate != null) {
            Caption(stringResource(R.string.estimate_value, formatSeconds(context, estimate)), textRow)
        } else if (ui.probe != null) {
            Caption(stringResource(R.string.estimate_unknown), textRow)
        }
    }
    ActionColumn {
        PrimaryButton(
            text = stringResource(R.string.run),
            enabled = ui.source != null && ui.probe != null,
            onClick = onRun,
            modifier = Modifier.fillMaxWidth(),
        )
        if (onDiagnostics != null) {
            TextAction(text = stringResource(R.string.diagnostics), onClick = onDiagnostics, modifier = Modifier.fillMaxWidth())
        }
    }
}

/** Buttons below the cards: card-aligned, with a consistent gap between them. */
@Composable
private fun ActionColumn(content: @Composable () -> Unit) {
    Column(
        Modifier.fillMaxWidth().padding(horizontal = CARD_INSET).padding(top = 16.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) { content() }
}

@Composable
private fun SourceCard(ui: MainUi, onPick: () -> Unit) {
    val context = LocalContext.current
    val probe = ui.probe
    Heading(stringResource(R.string.heading_video))
    InfoCard(Modifier.padding(horizontal = CARD_INSET).fillMaxWidth()) {
        when {
            ui.source == null -> BodyText(stringResource(R.string.source_none))
            ui.probeError != null -> BodyText(stringResource(R.string.source_unreadable, ui.probeError))
            probe == null -> {
                BodyText(stringResource(R.string.source_reading))
                ProgressBar(null, Modifier.padding(top = 4.dp))
            }
            else -> {
                BodyText(probe.displayName)
                Caption(stringResource(R.string.source_dimensions, probe.width, probe.height))
                probe.durationSeconds?.let {
                    Caption(stringResource(R.string.source_duration, formatSeconds(context, it)))
                }
                if (probe.transfer.isHdr) Caption(stringResource(R.string.source_hdr))
            }
        }
        SecondaryButton(
            text = stringResource(if (ui.source == null) R.string.choose_video else R.string.choose_other_video),
            onClick = onPick,
            modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
        )
    }
}

@Composable
private fun RunningPanel(job: JobState.Running, onCancel: () -> Unit) {
    val context = LocalContext.current
    Heading(stringResource(R.string.running_title))
    InfoCard(Modifier.padding(horizontal = CARD_INSET).fillMaxWidth()) {
        BodyText(stringResource(stageLabel(job.stage)))
        ProgressBar(job.progress, Modifier.fillMaxWidth().padding(vertical = 8.dp))
        job.estimateSeconds?.let {
            Caption(stringResource(R.string.estimate_value, formatSeconds(context, it)))
        }
    }
    ActionColumn {
        SecondaryButton(text = stringResource(R.string.cancel), onClick = onCancel, modifier = Modifier.fillMaxWidth())
    }
}

@Composable
private fun DonePanel(
    job: JobState.Done,
    onOpen: () -> Unit,
    onShare: () -> Unit,
    onCompare: () -> Unit,
    onDismiss: () -> Unit,
) {
    val context = LocalContext.current
    Heading(stringResource(R.string.done_title))
    InfoCard(Modifier.padding(horizontal = CARD_INSET).fillMaxWidth()) {
        BodyText(job.displayName)
        Caption(stringResource(R.string.done_saved))
        Caption(stringResource(R.string.done_took, formatSeconds(context, job.elapsedSeconds)))
        job.warnings.forEach { Caption(it) }
    }
    ActionColumn {
        PrimaryButton(text = stringResource(R.string.open), enabled = true, onClick = onOpen, modifier = Modifier.fillMaxWidth())
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            SecondaryButton(text = stringResource(R.string.compare), onClick = onCompare, modifier = Modifier.weight(1f))
            SecondaryButton(text = stringResource(R.string.share), onClick = onShare, modifier = Modifier.weight(1f))
        }
        TextAction(text = stringResource(R.string.dismiss), onClick = onDismiss, modifier = Modifier.fillMaxWidth())
    }
}

@Composable
private fun ResultMessage(title: String, detail: String?, onDismiss: () -> Unit) {
    Heading(title)
    if (detail != null) {
        InfoCard(Modifier.padding(horizontal = CARD_INSET).fillMaxWidth()) { BodyText(detail) }
    }
    ActionColumn {
        SecondaryButton(text = stringResource(R.string.dismiss), onClick = onDismiss, modifier = Modifier.fillMaxWidth())
    }
}

private fun stageLabel(stage: JobState.Stage): Int = when (stage) {
    JobState.Stage.PROBING -> R.string.stage_probing
    JobState.Stage.DETECTING -> R.string.stage_detecting
    JobState.Stage.TRANSFORMING -> R.string.stage_transforming
    JobState.Stage.SAVING -> R.string.stage_saving
}
