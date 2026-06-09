package com.shipradar.app.framework

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * T2.9 — OpenBridge chrome controls: button / toggle / menu / data-bar / alert-bar. Visual target
 * OpenBridge 6.0; colours from [OpenBridge.colors] (IEC 62288 basis in [ObTokens]). These are the
 * shared building blocks the slot workers (control panel, data bar, alarm UI) compose with, so the
 * whole HMI stays visually consistent.
 *
 * Touch-first sizing: controls default to a ≥48 dp target (bridge gloves / ship motion; OpenBridge
 * touch guidance, IEC 62288 §4 ergonomics).
 */

private val ChromeShape = RoundedCornerShape(4.dp)
private val MinTouch = 48.dp

/**
 * Primary chrome button. [selected] paints it with the accent (active) colour — used for latched
 * modes (range/motion/orientation, T2.4/T2.6). Disabled uses the passive foreground.
 */
@Composable
fun ObButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    selected: Boolean = false,
) {
    val c = OpenBridge.colors
    val bg = when {
        selected -> c.accent
        else -> c.chromeElevated
    }
    val fg = when {
        !enabled -> c.foregroundDisabled
        selected -> c.accentForeground
        else -> c.foregroundPrimary
    }
    Box(
        modifier = modifier
            .heightIn(min = MinTouch)
            .background(bg, ChromeShape)
            .border(1.dp, c.chromeBorder, ChromeShape)
            .clickable(enabled = enabled) { onClick() }
            .padding(horizontal = 16.dp, vertical = 10.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(text, color = fg, fontSize = 16.sp, fontWeight = FontWeight.Medium)
    }
}

/** Two-state toggle (e.g. trails on/off). Latched state shows the accent, like [ObButton] selected. */
@Composable
fun ObToggleButton(
    text: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
) = ObButton(
    text = text,
    onClick = { onCheckedChange(!checked) },
    modifier = modifier,
    enabled = enabled,
    selected = checked,
)

/** Vertical menu / control list container — a bordered OpenBridge panel surface. */
@Composable
fun ObMenu(
    modifier: Modifier = Modifier,
    content: @Composable ColumnScope.() -> Unit,
) {
    val c = OpenBridge.colors
    Column(
        modifier = modifier
            .background(c.chromeBackground, ChromeShape)
            .border(1.dp, c.chromeBorder, ChromeShape)
            .padding(4.dp),
        content = content,
    )
}

/** One selectable menu row. */
@Composable
fun ObMenuItem(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    selected: Boolean = false,
    enabled: Boolean = true,
) {
    val c = OpenBridge.colors
    val bg = if (selected) c.accent else Color.Transparent
    val fg = when {
        !enabled -> c.foregroundDisabled
        selected -> c.accentForeground
        else -> c.foregroundPrimary
    }
    Box(
        modifier = modifier
            .fillMaxWidth()
            .heightIn(min = MinTouch)
            .background(bg, ChromeShape)
            .clickable(enabled = enabled) { onClick() }
            .padding(horizontal = 12.dp, vertical = 10.dp),
        contentAlignment = Alignment.CenterStart,
    ) {
        Text(text, color = fg, fontSize = 16.sp)
    }
}

/**
 * Data-bar container — the permanent-display strip (top of [RadarScaffold]). Holds [ObDataField]s.
 * Per IEC 62288 the permanent/data area shows always-visible navigation data (HDG, SOG, range, etc.);
 * its content is owned by the data-bar worker (T2.7), this is only the styled chrome strip.
 */
@Composable
fun ObDataBar(
    modifier: Modifier = Modifier,
    content: @Composable RowScope.() -> Unit,
) {
    val c = OpenBridge.colors
    Row(
        modifier = modifier
            .fillMaxWidth()
            .background(c.chromeBackground)
            .padding(horizontal = 12.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(20.dp),
        content = content,
    )
}

/** A labelled read-out field for the data bar: small passive label over a prominent value. */
@Composable
fun ObDataField(
    label: String,
    value: String,
    modifier: Modifier = Modifier,
) {
    val c = OpenBridge.colors
    Column(modifier = modifier) {
        Text(label, color = c.foregroundSecondary, fontSize = 11.sp)
        Text(value, color = c.foregroundPrimary, fontSize = 18.sp, fontWeight = FontWeight.SemiBold)
    }
}

/**
 * Alert priority colour coding. Order = IMO/IEC alert priority (IEC 62288 §4.7.2.1 red = alarm;
 * IEC 62388 §16.1 / IMO A.1021 alarm > warning > caution). Colours come from [OpenBridge.colors] and
 * are guaranteed mutually distinguishable (§4.7.1.1).
 */
enum class ObAlertPriority { ALARM, WARNING, CAUTION }

@Composable
fun ObAlertPriority.color(): Color = when (this) {
    ObAlertPriority.ALARM -> OpenBridge.colors.alarm
    ObAlertPriority.WARNING -> OpenBridge.colors.warning
    ObAlertPriority.CAUTION -> OpenBridge.colors.caution
}

/**
 * Alert (BAM) bar chrome — the bottom strip of [RadarScaffold]. Shows the highest-priority active
 * alert with its priority colour and an acknowledge affordance. Per IEC 62288 §4.7.4.1 flashing is
 * reserved for *unacknowledged* alerts; the actual flash animation + alert queue logic are owned by
 * the alarm worker (T2.8) — this is the styled container it renders into. [acknowledged] only swaps
 * the static styling here (full-fill when unacknowledged, outlined when acknowledged).
 */
@Composable
fun ObAlertBar(
    priority: ObAlertPriority,
    message: String,
    acknowledged: Boolean,
    onAcknowledge: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val c = OpenBridge.colors
    val priorityColor = priority.color()
    // Unacknowledged: solid priority fill for maximum conspicuity. Acknowledged: dark bar with a
    // priority-coloured marker (no longer demands attention but stays visible).
    val barBg = if (acknowledged) c.chromeBackground else priorityColor
    val textColor = if (acknowledged) c.foregroundPrimary else c.accentForeground
    Row(
        modifier = modifier
            .fillMaxWidth()
            .heightIn(min = MinTouch)
            .background(barBg)
            .border(1.dp, priorityColor)
            .padding(horizontal = 12.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (acknowledged) {
            Box(Modifier.width(8.dp).heightIn(min = 24.dp).background(priorityColor))
            Spacer(Modifier.width(10.dp))
        }
        Text(
            text = priority.name,
            color = textColor,
            fontSize = 13.sp,
            fontWeight = FontWeight.Bold,
        )
        Spacer(Modifier.width(12.dp))
        Text(text = message, color = textColor, fontSize = 16.sp, modifier = Modifier.weight(1f))
        ObButton(text = if (acknowledged) "ACK'D" else "ACK", onClick = onAcknowledge, enabled = !acknowledged)
    }
}
