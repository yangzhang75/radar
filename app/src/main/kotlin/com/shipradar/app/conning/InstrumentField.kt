package com.shipradar.app.conning

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.shipradar.app.framework.OpenBridge

/**
 * OpenBridge `<obc-instrument-field>` re-drawn in Compose — a conning read-out tile: a prominent
 * "enhanced" value with low-opacity zero-padding hints, plus a tag + unit label. Colours come from the
 * OpenBridge instrument tokens so the tile matches the JRC RADAR reference (blue value by day/dusk,
 * teal/amber at night). Formatting (digits / padding / dashes for missing data) mirrors the component.
 */
@Composable
fun InstrumentField(
    tag: String,
    value: Double?,
    unit: String,
    modifier: Modifier = Modifier,
    fractionDigits: Int = 0,
    maxDigits: Int = 3,
) {
    val c = OpenBridge.colors
    val text = formatValue(value, fractionDigits, maxDigits)
    val hint = hintZeros(value, fractionDigits, maxDigits)

    Column(modifier.padding(vertical = 3.dp)) {
        Row(verticalAlignment = Alignment.Bottom) {
            if (hint.isNotEmpty()) {
                Text(
                    hint,
                    color = c.instrumentRegularSecondary.copy(alpha = 0.35f),
                    fontSize = 24.sp,
                    fontWeight = FontWeight.Bold,
                    fontFamily = FontFamily.Monospace,
                )
            }
            Text(
                text,
                color = c.instrumentEnhancedPrimary,
                fontSize = 24.sp,
                fontWeight = FontWeight.Bold,
                fontFamily = FontFamily.Monospace,
            )
        }
        Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(tag, color = c.instrumentRegularSecondary, fontSize = 10.sp, fontWeight = FontWeight.Bold)
            if (unit.isNotEmpty()) {
                Text(unit, color = c.instrumentRegularSecondary.copy(alpha = 0.7f), fontSize = 10.sp)
            }
        }
    }
}

/** Format like the OB component: fixed fraction digits, or a dashed placeholder when value is missing. */
private fun formatValue(value: Double?, fractionDigits: Int, maxDigits: Int): String {
    if (value == null) {
        return if (fractionDigits < 1) "-".repeat(1) else "-".repeat(1) + "." + "-".repeat(fractionDigits)
    }
    return "%.${fractionDigits}f".format(value)
}

/** Leading zero-padding hint string (drawn dimmed), so values right-align like the OB instrument field. */
private fun hintZeros(value: Double?, fractionDigits: Int, maxDigits: Int): String {
    if (value == null || value < 0) return ""
    val shown = "%.${fractionDigits}f".format(value).length
    val n = maxDigits + (if (fractionDigits > 0) fractionDigits + 1 else 0) - shown
    return if (n > 0) "0".repeat(n) else ""
}
