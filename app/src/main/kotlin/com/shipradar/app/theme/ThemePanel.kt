package com.shipradar.app.theme

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.layout.height
import kotlin.math.roundToInt

/**
 * W6-B — Day / Dusk / Night theme selector + brilliance (back-light) slider.
 *
 * Self-contained and stateless (hoist [mode] / [brilliance]; emit changes via the callbacks). It styles
 * itself from [radarPalette] at the *current* mode and brilliance so the panel always previews the
 * condition the operator is selecting — including the §7.2.1 night dimming. The orchestrator decides
 * where to place it (e.g. a settings drawer) and how to apply the result globally; see [ThemeState].
 *
 * Certification notes: the three modes realise IEC 62288 §4.4.1.1 Table 1 ambient conditions; the
 * continuous slider realises the §7.2.1 brightness range "sufficient to maintain dark adaptation".
 *
 * @param mode current ambient mode.
 * @param brilliance current back-light level, 0..1.
 * @param onModeChange invoked when the operator taps a mode.
 * @param onBrillianceChange invoked continuously as the operator drags the slider (already 0..1).
 */
@Composable
fun ThemePanel(
    mode: ThemeMode,
    brilliance: Float,
    onModeChange: (ThemeMode) -> Unit,
    onBrillianceChange: (Float) -> Unit,
    modifier: Modifier = Modifier,
) {
    val palette = radarPalette(mode).atBrilliance(brilliance)
    Surface(
        modifier = modifier,
        color = Color(palette.surface),
        contentColor = Color(palette.textPrimary),
        shape = RoundedCornerShape(8.dp),
        border = BorderStroke(1.dp, Color(palette.border)),
    ) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text(
                "显示主题 / Brilliance",
                color = Color(palette.textPrimary),
                fontWeight = FontWeight.SemiBold,
            )

            // --- Mode selector: Day / Dusk / Night ---
            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                ModeChip("昼 DAY", mode == ThemeMode.DAY, palette, Modifier.weight(1f)) {
                    onModeChange(ThemeMode.DAY)
                }
                ModeChip("黄昏 DUSK", mode == ThemeMode.DUSK, palette, Modifier.weight(1f)) {
                    onModeChange(ThemeMode.DUSK)
                }
                ModeChip("夜 NIGHT", mode == ThemeMode.NIGHT, palette, Modifier.weight(1f)) {
                    onModeChange(ThemeMode.NIGHT)
                }
            }

            // --- Brilliance slider ---
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("亮度", color = Color(palette.textSecondary))
                Slider(
                    value = brilliance,
                    onValueChange = onBrillianceChange,
                    valueRange = 0f..1f,
                    modifier = Modifier.weight(1f).padding(horizontal = 12.dp),
                    colors = SliderDefaults.colors(
                        thumbColor = Color(palette.accent),
                        activeTrackColor = Color(palette.accent),
                        inactiveTrackColor = Color(palette.border),
                    ),
                )
                Text(
                    "${(brilliance * 100).roundToInt()}%",
                    color = Color(palette.textPrimary),
                    modifier = Modifier.width(44.dp),
                )
            }
        }
    }
}

/** One selectable mode button, styled from the live [palette] (accent border + fill when selected). */
@Composable
private fun ModeChip(
    label: String,
    selected: Boolean,
    palette: RadarPalette,
    modifier: Modifier = Modifier,
    onClick: () -> Unit,
) {
    val bg = if (selected) Color(palette.accent) else Color(palette.surfaceElevated)
    val fg = if (selected) Color(palette.accentText) else Color(palette.textPrimary)
    Surface(
        modifier = modifier
            .height(44.dp)
            .clip(RoundedCornerShape(6.dp))
            .selectable(selected = selected, role = Role.RadioButton, onClick = onClick),
        color = bg,
        border = BorderStroke(1.dp, Color(palette.border)),
        shape = RoundedCornerShape(6.dp),
    ) {
        Row(
            Modifier.fillMaxWidth().padding(8.dp),
            horizontalArrangement = Arrangement.Center,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(label, color = fg, fontWeight = if (selected) FontWeight.Bold else FontWeight.Normal)
        }
    }
}

// --- Previews: the same panel under all three ambient conditions (§4.4.1.1 Table 1). ---

@Preview(name = "DAY", showBackground = true, backgroundColor = 0xFF000A14)
@Composable
private fun ThemePanelDayPreview() {
    Column(Modifier.background(Color(radarPalette(ThemeMode.DAY).background)).padding(8.dp)) {
        ThemePanel(ThemeMode.DAY, 1.0f, {}, {})
    }
}

@Preview(name = "DUSK", showBackground = true, backgroundColor = 0xFF00060D)
@Composable
private fun ThemePanelDuskPreview() {
    Column(Modifier.background(Color(radarPalette(ThemeMode.DUSK).background)).padding(8.dp)) {
        ThemePanel(ThemeMode.DUSK, 0.6f, {}, {})
    }
}

@Preview(name = "NIGHT", showBackground = true, backgroundColor = 0xFF030000)
@Composable
private fun ThemePanelNightPreview() {
    Column(Modifier.background(Color(radarPalette(ThemeMode.NIGHT).background)).padding(8.dp)) {
        ThemePanel(ThemeMode.NIGHT, 0.35f, {}, {})
    }
}
