package com.shipradar.app.control

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.shipradar.contract.GuardZoneAlarmType
import com.shipradar.contract.GuardZoneStatus
import com.shipradar.contract.RadarCommand
import com.shipradar.contract.RadarController
import com.shipradar.contract.RadarStatus

/**
 * Guard-zone (报警圈) controls — part of T2.6 part ①.
 *
 * Per zone: enable toggle (90C1 0100), alarm trigger mode (90C1 0400), and a read-out of the
 * configured sector geometry reported by the radar (02C4). A single global detection sensitivity
 * (90C1 0300) applies to both zones (协议文档 §设置报警灵敏度 has no zone field). A triggered zone is
 * highlighted (error colour) — the actual BAM alarm presentation is owned by T2.8 (alarm UI).
 *
 * Defining the sector *geometry* (start/end range, bearing, width) is done graphically on the PPI
 * by the interaction layer (T2.5) / via `RadarCommand.GuardZoneSetup`; this panel shows the current
 * geometry and owns enable/mode/sensitivity. Commands use the T1.3 encoder.
 */
@Composable
fun GuardZoneControls(
    status: RadarStatus,
    controller: RadarController,
    modifier: Modifier = Modifier,
) {
    ControlSection(ControlVocabulary.GUARD_ZONES, modifier) {
        status.guardZones.sortedBy { it.zone }.forEach { zone ->
            GuardZoneRow(zone, controller)
            Spacer(Modifier.padding(top = 6.dp))
        }

        // Global detection sensitivity (0..255). The radar does not report it back, so seed at the
        // mid-point; the value is committed on release.
        RadarLevelSlider(
            label = ControlVocabulary.SENSITIVITY,
            value = 128,
            valueRange = 0..255,
            enabled = status.guardZones.any { it.enabled },
            onCommit = { controller.send(RadarCommand.GuardZoneSensitivity(it)) },
        )
    }
}

@Composable
private fun GuardZoneRow(zone: GuardZoneStatus, controller: RadarController) {
    Column(Modifier.fillMaxWidth()) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                "${ControlVocabulary.GUARD_ZONE} ${zone.zone + 1}",
                style = MaterialTheme.typography.labelLarge,
                fontWeight = FontWeight.Bold,
                color = if (zone.triggered) MaterialTheme.colorScheme.error
                else MaterialTheme.colorScheme.onSurface,
            )
            if (zone.triggered) {
                Spacer(Modifier.padding(start = 6.dp))
                Text("● ALARM", color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.labelSmall)
            }
            Spacer(Modifier.fillMaxWidth().weight(1f))
            Switch(
                checked = zone.enabled,
                onCheckedChange = { controller.send(RadarCommand.GuardZoneEnable(zone.zone, it)) },
            )
        }
        // Configured sector geometry (read-only here; set on PPI / via GuardZoneSetup).
        Text(
            geometryText(zone),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.padding(top = 4.dp))
        SegmentedSelector(
            options = listOf(
                GuardZoneAlarmType.ENTERING to ControlVocabulary.ALARM_ON_ENTERING,
                GuardZoneAlarmType.LEAVING to ControlVocabulary.ALARM_ON_LEAVING,
                GuardZoneAlarmType.BOTH to ControlVocabulary.ALARM_ON_BOTH,
            ),
            selected = zone.alarmType,
            enabled = zone.enabled,
            onSelect = { controller.send(RadarCommand.GuardZoneAlarmMode(zone.zone, it)) },
        )
    }
}

private fun geometryText(zone: GuardZoneStatus): String {
    val ref = if (zone.trueBearing) "T" else "REL"
    val start = zone.startRangeMeters
    val end = zone.endRangeMeters
    val bearing = zone.bearingDeg.toInt()
    val width = zone.widthDeg.toInt()
    return "$start–$end m  ·  brg ${bearing}° $ref  ·  width ${width}°"
}

@Preview(showBackground = true, widthDp = 380)
@Composable
private fun GuardZoneControlsPreview() {
    Column(Modifier.padding(8.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        GuardZoneControls(status = PreviewStatus, controller = NoOpController)
    }
}
