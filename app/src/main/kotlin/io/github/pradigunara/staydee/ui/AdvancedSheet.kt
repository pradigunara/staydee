package io.github.pradigunara.staydee.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringArrayResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import io.github.pradigunara.staydee.MAX_EDGE_CHOICES
import io.github.pradigunara.staydee.R
import io.github.pradigunara.staydee.core.Border
import io.github.pradigunara.staydee.core.DetectEdge
import io.github.pradigunara.staydee.core.Interpol
import io.github.pradigunara.staydee.core.OptAlgo
import io.github.pradigunara.staydee.core.Settings
import io.github.pradigunara.staydee.core.X264_PRESETS
import top.yukonga.miuix.kmp.overlay.OverlayBottomSheet

/** Everything in [Settings] that is not a primary knob, in a bottom sheet. */
@Composable
fun AdvancedSheet(
    show: Boolean,
    settings: Settings,
    onSettingsChange: (Settings) -> Unit,
    onDismiss: () -> Unit,
) {
    val off = stringResource(R.string.value_off)
    OverlayBottomSheet(
        show = show,
        title = stringResource(R.string.advanced_title),
        onDismissRequest = onDismiss,
    ) {
        Column(
            Modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 12.dp, vertical = 8.dp),
        ) {
            IntKnob(
                title = stringResource(R.string.knob_accuracy),
                value = settings.accuracy,
                range = 1..15,
                summary = stringResource(R.string.knob_accuracy_summary),
                onCommit = { onSettingsChange(settings.copy(accuracy = it)) },
            )
            IntKnob(
                title = stringResource(R.string.knob_stepsize),
                value = settings.stepsize,
                range = 1..32,
                onCommit = { onSettingsChange(settings.copy(stepsize = it)) },
            )
            FloatKnob(
                title = stringResource(R.string.knob_mincontrast),
                value = settings.mincontrast,
                range = 0.0..1.0,
                decimals = 2,
                onCommit = { onSettingsChange(settings.copy(mincontrast = it)) },
            )
            IntKnob(
                title = stringResource(R.string.knob_zoom),
                value = settings.zoom,
                range = -100..100,
                onCommit = { onSettingsChange(settings.copy(zoom = it)) },
            )
            IntKnob(
                title = stringResource(R.string.knob_maxshift),
                value = settings.maxshift,
                range = -1..500,
                keyPoint = -1,
                valueText = { if (it < 0) off else it.toString() },
                onCommit = { onSettingsChange(settings.copy(maxshift = it)) },
            )
            FloatKnob(
                title = stringResource(R.string.knob_maxangle),
                value = settings.maxangle,
                range = -1.0..3.14,
                decimals = 2,
                keyPoint = -1.0,
                valueText = { if (it < 0) off else String.format(java.util.Locale.ROOT, "%.2f", it) },
                onCommit = { onSettingsChange(settings.copy(maxangle = it)) },
            )
            FloatKnob(
                title = stringResource(R.string.knob_zoomspeed),
                value = settings.zoomspeed,
                range = 0.0..5.0,
                decimals = 2,
                onCommit = { onSettingsChange(settings.copy(zoomspeed = it)) },
            )
            ChoiceKnob(
                title = stringResource(R.string.knob_optzoom),
                options = stringArrayResource(R.array.optzoom_options).toList(),
                selected = settings.optzoom,
                onSelect = { onSettingsChange(settings.copy(optzoom = it)) },
            )
            ChoiceKnob(
                title = stringResource(R.string.knob_optalgo),
                options = stringArrayResource(R.array.optalgo_options).toList(),
                selected = OptAlgo.entries.indexOf(settings.optalgo),
                onSelect = { onSettingsChange(settings.copy(optalgo = OptAlgo.entries[it])) },
            )
            ChoiceKnob(
                title = stringResource(R.string.knob_border),
                options = stringArrayResource(R.array.border_options).toList(),
                selected = Border.entries.indexOf(settings.border),
                onSelect = { onSettingsChange(settings.copy(border = Border.entries[it])) },
            )
            ChoiceKnob(
                title = stringResource(R.string.knob_interpol),
                options = stringArrayResource(R.array.interpol_options).toList(),
                selected = Interpol.entries.indexOf(settings.interpol),
                onSelect = { onSettingsChange(settings.copy(interpol = Interpol.entries[it])) },
            )
            ChoiceKnob(
                title = stringResource(R.string.knob_detect_edge),
                options = stringArrayResource(R.array.detect_edge_options).toList(),
                selected = if (settings.detectEdge == DetectEdge.Off) 1 else 0,
                onSelect = { onSettingsChange(settings.copy(detectEdge = if (it == 1) DetectEdge.Off else DetectEdge.Auto)) },
            )
            ChoiceKnob(
                title = stringResource(R.string.knob_max_edge),
                options = stringArrayResource(R.array.max_edge_options).toList(),
                selected = MAX_EDGE_CHOICES.indexOf(settings.maxEdge).coerceAtLeast(0),
                onSelect = { onSettingsChange(settings.copy(maxEdge = MAX_EDGE_CHOICES[it])) },
            )
            SwitchKnob(
                title = stringResource(R.string.knob_sharpen),
                checked = settings.sharpen,
                summary = stringResource(R.string.knob_sharpen_summary),
                onCheckedChange = { onSettingsChange(settings.copy(sharpen = it)) },
            )
            SwitchKnob(
                title = stringResource(R.string.knob_tripod),
                checked = settings.tripod,
                summary = stringResource(R.string.knob_tripod_summary),
                onCheckedChange = { onSettingsChange(settings.copy(tripod = it)) },
            )
            SwitchKnob(
                title = stringResource(R.string.knob_audio),
                checked = settings.audio,
                summary = stringResource(R.string.knob_audio_summary),
                onCheckedChange = { onSettingsChange(settings.copy(audio = it)) },
            )
            ChoiceKnob(
                title = stringResource(R.string.knob_preset),
                options = X264_PRESETS,
                selected = X264_PRESETS.indexOf(settings.preset),
                summary = stringResource(R.string.knob_preset_summary),
                onSelect = { onSettingsChange(settings.copy(preset = X264_PRESETS[it])) },
            )
        }
    }
}
