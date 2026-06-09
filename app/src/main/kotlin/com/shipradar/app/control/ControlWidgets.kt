package com.shipradar.app.control

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import kotlin.math.roundToInt

/** A titled card grouping one functional block of controls. */
@Composable
fun ControlSection(
    title: String,
    modifier: Modifier = Modifier,
    trailing: String? = null,
    content: @Composable ColumnScope.() -> Unit,
) {
    Card(modifier = modifier.fillMaxWidth().padding(vertical = 4.dp)) {
        Column(Modifier.padding(12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(title, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold)
                if (trailing != null) {
                    Spacer(Modifier.width(8.dp))
                    Text(
                        trailing,
                        modifier = Modifier.fillMaxWidth(),
                        textAlign = TextAlign.End,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.primary,
                    )
                }
            }
            Spacer(Modifier.padding(top = 6.dp))
            content()
        }
    }
}

/**
 * Integer level slider that reflects the radar-reported [value] but does not flood the control
 * channel: it tracks the drag locally and only invokes [onCommit] when the gesture finishes.
 * Re-syncs to [value] whenever the radar reports a new setting.
 */
@Composable
fun RadarLevelSlider(
    label: String,
    value: Int,
    valueRange: IntRange,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    valueSuffix: String = "",
    onCommit: (Int) -> Unit,
) {
    var pos by remember { mutableFloatStateOf(value.toFloat()) }
    LaunchedEffect(value) { pos = value.toFloat() }
    Column(modifier.fillMaxWidth()) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(label, style = MaterialTheme.typography.labelLarge)
            Text(
                "${pos.roundToInt()}$valueSuffix",
                modifier = Modifier.fillMaxWidth(),
                textAlign = TextAlign.End,
                style = MaterialTheme.typography.labelLarge,
                fontWeight = FontWeight.Bold,
            )
        }
        Slider(
            value = pos.coerceIn(valueRange.first.toFloat(), valueRange.last.toFloat()),
            onValueChange = { pos = it },
            onValueChangeFinished = { onCommit(pos.roundToInt()) },
            valueRange = valueRange.first.toFloat()..valueRange.last.toFloat(),
            enabled = enabled,
        )
    }
}

/**
 * A horizontal single-choice selector (segmented-button equivalent built from stable button APIs,
 * avoiding the experimental SegmentedButton). The selected option is filled; others are outlined.
 */
@Composable
fun <T> SegmentedSelector(
    options: List<Pair<T, String>>,
    selected: T,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    onSelect: (T) -> Unit,
) {
    Row(modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        options.forEach { (optionValue, text) ->
            val isSelected = optionValue == selected
            if (isSelected) {
                Button(
                    onClick = { onSelect(optionValue) },
                    enabled = enabled,
                    modifier = Modifier.weight(1f),
                ) { Text(text, maxLines = 1) }
            } else {
                OutlinedButton(
                    onClick = { onSelect(optionValue) },
                    enabled = enabled,
                    modifier = Modifier.weight(1f),
                    colors = ButtonDefaults.outlinedButtonColors(),
                ) { Text(text, maxLines = 1) }
            }
        }
    }
}

/** A small label + stepper (− value +) for discrete integer settings. */
@Composable
fun Stepper(
    label: String,
    value: Int,
    range: IntRange,
    modifier: Modifier = Modifier,
    step: Int = 1,
    enabled: Boolean = true,
    display: (Int) -> String = { it.toString() },
    onChange: (Int) -> Unit,
) {
    Row(modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Text(label, style = MaterialTheme.typography.labelLarge)
        Spacer(Modifier.fillMaxWidth().weight(1f))
        OutlinedButton(
            onClick = { onChange((value - step).coerceIn(range.first, range.last)) },
            enabled = enabled && value > range.first,
        ) { Text("−") } // minus sign
        Text(
            display(value),
            modifier = Modifier.width(64.dp),
            textAlign = TextAlign.Center,
            fontWeight = FontWeight.Bold,
        )
        OutlinedButton(
            onClick = { onChange((value + step).coerceIn(range.first, range.last)) },
            enabled = enabled && value < range.last,
        ) { Text("+") }
    }
}
