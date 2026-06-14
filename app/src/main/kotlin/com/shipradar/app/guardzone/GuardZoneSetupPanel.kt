package com.shipradar.app.guardzone

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.shipradar.contract.GuardZoneAlarmType
import com.shipradar.contract.RadarCommand
import com.shipradar.contract.RadarController
import com.shipradar.contract.TrackCommand
import kotlin.math.roundToInt

/**
 * W7-A — 报警圈/捕获区图形设置面板（IEC 62388 §11.7：用户可设区的距离与边界、独立开关、报警方向、灵敏度）。
 *
 * 自包含、本地维护两个区的参数状态；任一改动即通过 [RadarController.send] 下发对应命令：
 *  - 开关        → [RadarCommand.GuardZoneEnable]
 *  - 几何(内/外距离, 起/止方位) → [RadarCommand.GuardZoneSetup]（经 [GuardZoneModel.toSetupCommand]）
 *  - 报警方向    → [RadarCommand.GuardZoneAlarmMode]
 *  - 灵敏度(全局)→ [RadarCommand.GuardZoneSensitivity]
 *
 * 注：HALO `GuardZoneSensitivity` 命令无 zone 字段（全局），故灵敏度为面板级单一控件，作用于两区；
 * `GuardZoneSetup` 不含 trueBearing，参系标志仅用于本地命中测试/绘制。详见交付报告。
 */
@Composable
fun GuardZoneSetupPanel(
    controller: RadarController,
    modifier: Modifier = Modifier,
) {
    var zones by remember { mutableStateOf(List(GuardZoneModel.ZONE_COUNT) { GuardZone(zone = it) }) }
    var sensitivity by remember { mutableStateOf(GuardZone(0).sensitivity) }

    fun update(index: Int, transform: (GuardZone) -> GuardZone) {
        zones = zones.toMutableList().also { it[index] = transform(it[index]) }
    }

    Column(
        modifier
            .width(360.dp)
            .verticalScroll(rememberScrollState())
            .padding(12.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Text("报警圈 / 捕获区", fontWeight = FontWeight.Bold, style = MaterialTheme.typography.titleMedium)
        Text(
            "IEC 62388 §11.7 / §11.3.7 — 目标进入(或离开)区域触发报警",
            style = MaterialTheme.typography.bodySmall,
        )

        zones.forEachIndexed { i, zone ->
            HorizontalDivider()
            ZoneEditor(
                zone = zone,
                onEnabledChange = { on ->
                    update(i) { it.copy(enabled = on) }
                    controller.send(RadarCommand.GuardZoneEnable(zone.zone, on))
                },
                onGeometryChange = { updated ->
                    update(i) { updated }
                    controller.send(GuardZoneModel.toSetupCommand(updated))
                },
                onAlarmTypeChange = { type ->
                    update(i) { it.copy(alarmType = type) }
                    controller.send(RadarCommand.GuardZoneAlarmMode(zone.zone, type))
                },
            )
        }

        HorizontalDivider()
        // 灵敏度：HALO 90C1/0300 为全局命令（无 zone），故单一控件。
        Text("灵敏度（全局）：$sensitivity", fontWeight = FontWeight.Medium)
        Slider(
            value = sensitivity.toFloat(),
            onValueChange = { sensitivity = it.roundToInt() },
            onValueChangeFinished = { controller.send(RadarCommand.GuardZoneSensitivity(sensitivity)) },
            valueRange = GuardZoneModel.SENSITIVITY_MIN.toFloat()..GuardZoneModel.SENSITIVITY_MAX.toFloat(),
            steps = (GuardZoneModel.SENSITIVITY_MAX - GuardZoneModel.SENSITIVITY_MIN - 1).coerceAtLeast(0),
        )
    }
}

@Composable
private fun ZoneEditor(
    zone: GuardZone,
    onEnabledChange: (Boolean) -> Unit,
    onGeometryChange: (GuardZone) -> Unit,
    onAlarmTypeChange: (GuardZoneAlarmType) -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
            Text("报警圈 ${zone.zone}", fontWeight = FontWeight.Bold)
            Switch(checked = zone.enabled, onCheckedChange = onEnabledChange)
        }

        Stepper("内距离", "%.2f NM".format(zone.innerRangeNm)) { d ->
            onGeometryChange(zone.copy(innerRangeNm = (zone.innerRangeNm + d * 0.1).coerceIn(0.0, 96.0)))
        }
        Stepper("外距离", "%.2f NM".format(zone.outerRangeNm)) { d ->
            onGeometryChange(zone.copy(outerRangeNm = (zone.outerRangeNm + d * 0.1).coerceIn(0.0, 96.0)))
        }
        Stepper("起方位", "%03.0f°".format(zone.startBearingDeg)) { d ->
            onGeometryChange(zone.copy(startBearingDeg = GuardZoneModel.norm360(zone.startBearingDeg + d * 5.0)))
        }
        Stepper("止方位", "%03.0f°".format(zone.endBearingDeg)) { d ->
            onGeometryChange(zone.copy(endBearingDeg = GuardZoneModel.norm360(zone.endBearingDeg + d * 5.0)))
        }

        Text("报警方向", style = MaterialTheme.typography.bodySmall)
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            AlarmTypeChoice("进入", zone.alarmType == GuardZoneAlarmType.ENTERING) { onAlarmTypeChange(GuardZoneAlarmType.ENTERING) }
            AlarmTypeChoice("离开", zone.alarmType == GuardZoneAlarmType.LEAVING) { onAlarmTypeChange(GuardZoneAlarmType.LEAVING) }
            AlarmTypeChoice("双向", zone.alarmType == GuardZoneAlarmType.BOTH) { onAlarmTypeChange(GuardZoneAlarmType.BOTH) }
        }
    }
}

/** 简易 ±步进控件（避开实验性 API）：左减右加。 */
@Composable
private fun Stepper(label: String, value: String, onStep: (Int) -> Unit) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
        Text(label)
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(onClick = { onStep(-1) }) { Text("−") }
            Text(value, fontFamily = FontFamily.Monospace, fontWeight = FontWeight.Medium)
            OutlinedButton(onClick = { onStep(+1) }) { Text("+") }
        }
    }
}

@Composable
private fun AlarmTypeChoice(label: String, selected: Boolean, onClick: () -> Unit) {
    OutlinedButton(onClick = onClick) {
        Text(if (selected) "● $label" else label, fontWeight = if (selected) FontWeight.Bold else FontWeight.Normal)
    }
}

@Preview(showBackground = true, widthDp = 380, heightDp = 640)
@Composable
private fun GuardZoneSetupPanelPreview() {
    GuardZoneSetupPanel(controller = object : RadarController {
        override fun send(cmd: RadarCommand) {}
        override fun send(cmd: TrackCommand) {}
    })
}
