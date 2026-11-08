package io.github.pradigunara.staydee.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.graphics.Color
import top.yukonga.miuix.kmp.basic.Button
import top.yukonga.miuix.kmp.basic.ButtonDefaults
import top.yukonga.miuix.kmp.basic.Card
import top.yukonga.miuix.kmp.basic.InfiniteProgressIndicator
import top.yukonga.miuix.kmp.basic.LinearProgressIndicator
import top.yukonga.miuix.kmp.basic.Slider
import top.yukonga.miuix.kmp.basic.SmallTitle
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.basic.TextButton
import top.yukonga.miuix.kmp.preference.ArrowPreference
import top.yukonga.miuix.kmp.preference.OverlayDropdownPreference
import top.yukonga.miuix.kmp.preference.SliderPreference
import top.yukonga.miuix.kmp.preference.SwitchPreference
import java.util.Locale
import kotlin.math.pow
import kotlin.math.roundToInt
import kotlin.math.roundToLong

// Thin wrappers over Miuix (plan §13.3). Screens call these with plain values and lambdas.
// Sliders keep a local drag value and commit once on release (plan §13.1).

/** Miuix layout grid: cards sit [CARD_INSET] from the screen edge; text outside cards lines up
 *  with [SmallTitle] and with text inside a card's [CONTENT_PADDING] at [TEXT_INSET]. */
val CARD_INSET = 12.dp
val TEXT_INSET = 28.dp
private val CONTENT_PADDING = 16.dp

/** Integer slider. [keyPoint] snaps to a special value such as the -1 "off" sentinel. */
@Composable
fun IntKnob(
    title: String,
    value: Int,
    range: IntRange,
    onCommit: (Int) -> Unit,
    summary: String? = null,
    keyPoint: Int? = null,
    valueText: (Int) -> String = { it.toString() },
) {
    var drag by remember(value) { mutableFloatStateOf(value.toFloat()) }
    SliderPreference(
        value = drag,
        onValueChange = { drag = it },
        onValueChangeFinished = { onCommit(drag.roundToInt()) },
        title = title,
        summary = summary,
        valueText = valueText(drag.roundToInt()),
        valueRange = range.first.toFloat()..range.last.toFloat(),
        steps = range.last - range.first - 1,
        keyPoints = listOfNotNull(keyPoint?.toFloat()),
    )
}

/** Fractional slider rounded to [decimals] on commit. */
@Composable
fun FloatKnob(
    title: String,
    value: Double,
    range: ClosedFloatingPointRange<Double>,
    decimals: Int,
    onCommit: (Double) -> Unit,
    summary: String? = null,
    keyPoint: Double? = null,
    valueText: (Double) -> String = { String.format(Locale.ROOT, "%.${decimals}f", it) },
) {
    val factor = 10.0.pow(decimals)
    var drag by remember(value) { mutableFloatStateOf(value.toFloat()) }
    SliderPreference(
        value = drag,
        onValueChange = { drag = it },
        onValueChangeFinished = { onCommit((drag * factor).toDouble().roundToLong() / factor) },
        title = title,
        summary = summary,
        valueText = valueText(drag.toDouble()),
        valueRange = range.start.toFloat()..range.endInclusive.toFloat(),
        steps = 0,
        keyPoints = listOfNotNull(keyPoint?.toFloat()),
    )
}

/** Dropdown over [options]; [selected] and [onSelect] use the index. */
@Composable
fun ChoiceKnob(
    title: String,
    options: List<String>,
    selected: Int,
    onSelect: (Int) -> Unit,
    summary: String? = null,
) {
    OverlayDropdownPreference(
        items = options,
        selectedIndex = selected,
        title = title,
        summary = summary,
        onSelectedIndexChange = onSelect,
    )
}

@Composable
fun SwitchKnob(
    title: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    summary: String? = null,
) {
    SwitchPreference(
        checked = checked,
        onCheckedChange = onCheckedChange,
        title = title,
        summary = summary,
    )
}

@Composable
fun ArrowKnob(
    title: String,
    summary: String? = null,
    onClick: () -> Unit,
) {
    ArrowPreference(title = title, summary = summary, onClick = onClick)
}

/** Filled accent button for the screen's main action. */
@Composable
fun PrimaryButton(
    text: String,
    enabled: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    TextButton(text, onClick = onClick, modifier = modifier, enabled = enabled, colors = ButtonDefaults.textButtonColorsPrimary())
}

/** Filled neutral button for secondary actions. */
@Composable
fun SecondaryButton(text: String, onClick: () -> Unit, modifier: Modifier = Modifier) {
    TextButton(text, onClick = onClick, modifier = modifier)
}

/** Low-emphasis action without a fill (dismiss, debug entries). */
@Composable
fun TextAction(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
) {
    TextButton(
        text,
        onClick = onClick,
        modifier = modifier,
        enabled = enabled,
        colors = ButtonDefaults.textButtonColors(color = Color.Transparent, disabledColor = Color.Transparent),
    )
}

/** Scrub bar over 0..1; commit with [onValueChangeFinished]. */
@Composable
fun ScrubSlider(
    value: Float,
    onValueChange: (Float) -> Unit,
    onValueChangeFinished: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Slider(
        value = value,
        onValueChange = onValueChange,
        onValueChangeFinished = onValueChangeFinished,
        modifier = modifier,
        valueRange = 0f..1f,
        steps = 0,
    )
}

/** Determinate bar; indeterminate while [progress] is unknown. */
@Composable
fun ProgressBar(progress: Float?, modifier: Modifier = Modifier) {
    if (progress == null) {
        InfiniteProgressIndicator(modifier)
    } else {
        LinearProgressIndicator(progress = progress, modifier = modifier)
    }
}

/** Card for free content (text, progress); padded so nothing touches the rounded corners. */
@Composable
fun InfoCard(modifier: Modifier = Modifier, content: @Composable ColumnScope.() -> Unit) {
    Card(modifier = modifier, insideMargin = PaddingValues(CONTENT_PADDING)) {
        Column(verticalArrangement = Arrangement.spacedBy(4.dp), content = content)
    }
}

/** Card holding preference rows (knobs); the rows bring their own padding. */
@Composable
fun KnobGroup(modifier: Modifier = Modifier, content: @Composable ColumnScope.() -> Unit) {
    Card(modifier = modifier, content = content)
}

@Composable
fun Heading(text: String, modifier: Modifier = Modifier) {
    SmallTitle(text, modifier = modifier)
}

@Composable
fun BodyText(text: String, modifier: Modifier = Modifier) {
    Text(text = text, modifier = modifier)
}

@Composable
fun Caption(text: String, modifier: Modifier = Modifier) {
    Text(text = text, modifier = modifier, fontSize = 12.sp)
}
