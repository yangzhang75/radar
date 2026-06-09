package com.shipradar.app.alarm

import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.shipradar.app.alarm.AlarmPresentation.AlarmRow
import com.shipradar.app.alarm.AlarmPresentation.AlarmUiState
import com.shipradar.contract.AlarmEvent
import com.shipradar.contract.AlarmPriority
import com.shipradar.contract.AlarmState

/**
 * BAM alarm HMI (T2.8). Three public composables, all driven by the immutable
 * [AlarmPresentation.AlarmUiState] and forwarding operator actions through a [RadarAlarmController]:
 *
 *  - [AlarmBar]   — the always-visible top banner showing the single most urgent alert;
 *  - [AlarmList]  — the scrollable list of every active alert;
 *  - [AlarmPanel] — bar + list composed together (the slot the orchestrator wires into RadarScreen).
 *
 * Visual behaviour follows **IEC 62923-1** via [AlarmPresentation]: an unacknowledged active alert
 * flashes (Annex G); colour codes the priority (IEC 62288 §4.7.2.1); acknowledge / silence buttons
 * appear only when the state machine would accept them.
 */

private fun argb(packed: Int): Color = Color(packed)

/** Flashing alpha for unacknowledged alerts; steady 1f otherwise. ~1 Hz, never fully dark (stays legible). */
@Composable
private fun flashAlpha(flashing: Boolean): Float {
    if (!flashing) return 1f
    val transition = rememberInfiniteTransition(label = "alarm-flash")
    return transition.animateFloat(
        initialValue = 1f,
        targetValue = 0.3f,
        animationSpec = infiniteRepeatable(tween(500), RepeatMode.Reverse),
        label = "alarm-flash-alpha",
    ).value
}

/** Top alarm banner: highlights [AlarmUiState.bar]; shows a calm "no alarms" state when empty. */
@Composable
fun AlarmBar(
    uiState: AlarmUiState,
    controller: RadarAlarmController,
    modifier: Modifier = Modifier,
) {
    val row = uiState.bar
    if (row == null) {
        Surface(modifier.fillMaxWidth().height(56.dp), color = MaterialTheme.colorScheme.surfaceVariant) {
            Box(Modifier.padding(horizontal = 16.dp), contentAlignment = Alignment.CenterStart) {
                Text("No active alarms", color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        return
    }

    val fill = argb(row.colorArgb)
    val onFill = argb(AlarmColors.ON_PRIORITY)
    Surface(modifier.fillMaxWidth().height(56.dp), color = fill) {
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Column(Modifier.weight(1f).alpha(flashAlpha(row.flashing))) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    if (row.sounding) Text("🔊", color = onFill) // 🔊 audible
                    Text(
                        "${priorityLabel(row.priority)} · ${row.stateLabel}",
                        color = onFill, fontWeight = FontWeight.Bold, fontSize = 12.sp,
                    )
                    if (uiState.activeCount > 1) {
                        Text("+${uiState.activeCount - 1} more", color = onFill, fontSize = 12.sp)
                    }
                }
                Text(
                    row.title, color = onFill, fontWeight = FontWeight.SemiBold,
                    maxLines = 1, overflow = TextOverflow.Ellipsis,
                )
            }
            AlarmActions(row, controller, onColor = onFill)
        }
    }
}

/** Scrollable list of all active alerts, most urgent first. */
@Composable
fun AlarmList(
    uiState: AlarmUiState,
    controller: RadarAlarmController,
    modifier: Modifier = Modifier,
) {
    LazyColumn(modifier.fillMaxWidth()) {
        items(uiState.rows, key = { it.event.identifier to it.event.utcMillis }) { row ->
            AlarmRowItem(row, controller)
            HorizontalDivider()
        }
    }
}

/** Bar + list — the self-contained alarm surface wired into the HMI by the orchestrator. */
@Composable
fun AlarmPanel(
    uiState: AlarmUiState,
    controller: RadarAlarmController,
    modifier: Modifier = Modifier,
) {
    Column(modifier.fillMaxWidth()) {
        AlarmBar(uiState, controller)
        AlarmList(uiState, controller, Modifier.weight(1f))
    }
}

@Composable
private fun AlarmRowItem(row: AlarmRow, controller: RadarAlarmController) {
    val acked = !AlarmPresentation.isUnacknowledged(row.state)
    Row(
        Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        // Priority colour swatch (dimmed once acknowledged/resolved).
        Box(
            Modifier.width(6.dp).height(40.dp).background(
                if (acked) argb(AlarmColors.ACKNOWLEDGED_DIM) else argb(row.colorArgb)
            )
        )
        Column(Modifier.weight(1f).alpha(flashAlpha(row.flashing))) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("#${row.event.identifier}", fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Text("${priorityLabel(row.priority)} · ${row.stateLabel}", fontSize = 11.sp, fontWeight = FontWeight.Bold)
                if (row.sounding) Text("🔊", fontSize = 11.sp)
            }
            Text(row.title, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
            row.detail?.let { Text(it, fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 2, overflow = TextOverflow.Ellipsis) }
        }
        AlarmActions(row, controller, onColor = MaterialTheme.colorScheme.onPrimary)
    }
}

/** The ack / silence / transfer buttons, each shown only when the state machine accepts that action. */
@Composable
private fun AlarmActions(row: AlarmRow, controller: RadarAlarmController, onColor: Color) {
    val id = row.event.identifier
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        if (row.silenceable) {
            ActionButton("SILENCE") { controller.dispatch(AlarmAction.Silence(id)) }
        }
        if (row.acknowledgeable) {
            ActionButton("ACK") { controller.dispatch(AlarmAction.Acknowledge(id)) }
        }
        if (row.transferable) {
            ActionButton("TRANSFER") { controller.dispatch(AlarmAction.TransferResponsibility(id)) }
        }
    }
}

@Composable
private fun ActionButton(label: String, onClick: () -> Unit) {
    Button(
        onClick = onClick,
        colors = ButtonDefaults.buttonColors(),
        contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 12.dp, vertical = 4.dp),
    ) { Text(label, fontSize = 12.sp, fontWeight = FontWeight.Bold) }
}

private fun priorityLabel(p: AlarmPriority): String = when (p) {
    AlarmPriority.EMERGENCY_ALARM -> "EMERGENCY"
    AlarmPriority.ALARM -> "ALARM"
    AlarmPriority.WARNING -> "WARNING"
    AlarmPriority.CAUTION -> "CAUTION"
}

// --- Previews (fake data so the surface develops independently of the T1.1 service) -------------

private fun sample(id: Int, priority: AlarmPriority, state: AlarmState, text: String, t: Long) =
    AlarmEvent(identifier = id, priority = priority, state = state, text = text, source = "RADAR1", utcMillis = t)

private val previewState = AlarmPresentation.uiStateOf(
    listOf(
        sample(3044, AlarmPriority.ALARM, AlarmState.ACTIVE_UNACK, "CPA/TCPA — target 12", 1_000),
        sample(3052, AlarmPriority.WARNING, AlarmState.ACTIVE_SILENCED, "Lost target — target 7", 900),
        sample(3015, AlarmPriority.WARNING, AlarmState.ACTIVE_ACK, "Lost input — GPS", 800),
        sample(3043, AlarmPriority.CAUTION, AlarmState.ACTIVE, "Target store near limit", 700),
    )
)

@Preview(name = "Alarm bar", widthDp = 720)
@Composable
private fun PreviewAlarmBar() {
    MaterialTheme { AlarmBar(previewState, NoopAlarmController) }
}

@Preview(name = "Alarm panel", widthDp = 720, heightDp = 360)
@Composable
private fun PreviewAlarmPanel() {
    MaterialTheme { AlarmPanel(previewState, NoopAlarmController) }
}

@Preview(name = "No alarms", widthDp = 720)
@Composable
private fun PreviewEmpty() {
    MaterialTheme { AlarmBar(AlarmPresentation.uiStateOf(emptyList()), NoopAlarmController) }
}
