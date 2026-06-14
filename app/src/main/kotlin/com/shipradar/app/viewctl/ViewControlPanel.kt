package com.shipradar.app.viewctl

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp

/**
 * Operator controls for off-centre (look-ahead) display and reset.
 *
 * Layout: an off-centre on/off [Switch], a four-direction nudge pad that moves own-ship/CCRP one
 * small step ([ViewControlState.NUDGE_STEP]) per press (clamped to the IEC 62388 §10.4.2.1 limit),
 * and a centre **reset** (回中). The directional pad and reset are disabled while off-centring is
 * off. A status line shows the current offset vs the 66 % limit.
 *
 * Arrow direction = the on-screen direction the own-ship marker moves (+x right, +y down). Pressing
 * ▼ pushes own-ship down to enlarge the view ahead (the look-ahead use-case of §10.4).
 */
@Composable
fun ViewControlPanel(state: ViewControlState, modifier: Modifier = Modifier) {
    val enabled = state.offCenterEnabled
    val step = ViewControlState.NUDGE_STEP
    Column(
        modifier = modifier.padding(12.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("偏心显示", style = MaterialTheme.typography.titleSmall)
            Spacer(Modifier.width(8.dp))
            Switch(checked = enabled, onCheckedChange = { state.setOffCenter(it) })
        }

        // Four-direction nudge pad + centre reset.
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            NudgeButton("▲", enabled) { state.nudge(0f, -step) }
            Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                NudgeButton("◀", enabled) { state.nudge(-step, 0f) }
                OutlinedButton(
                    onClick = { state.reset() },
                    enabled = enabled,
                    modifier = Modifier.size(56.dp),
                    contentPadding = androidx.compose.foundation.layout.PaddingValues(0.dp),
                ) { Text("复位", textAlign = TextAlign.Center) }
                NudgeButton("▶", enabled) { state.nudge(step, 0f) }
            }
            NudgeButton("▼", enabled) { state.nudge(0f, step) }
        }

        val pct = (state.effectiveOffset.magnitude * 100f).toInt()
        Text(
            text = if (state.isOffCenter) "偏移 $pct% / 上限 66%" else "居中",
            style = MaterialTheme.typography.labelMedium,
        )
    }
}

@Composable
private fun NudgeButton(glyph: String, enabled: Boolean, onClick: () -> Unit) {
    Button(
        onClick = onClick,
        enabled = enabled,
        modifier = Modifier.size(56.dp),
        contentPadding = androidx.compose.foundation.layout.PaddingValues(0.dp),
    ) { Text(glyph) }
}

@Preview(name = "ViewControlPanel (off-centred)", showBackground = true, widthDp = 240, heightDp = 280)
@Composable
private fun ViewControlPanelPreview() {
    val state = rememberViewControlState(offCenterEnabled = true, offset = ViewOffset(0.2f, 0.3f))
    MaterialTheme { ViewControlPanel(state) }
}
