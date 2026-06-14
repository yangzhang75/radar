package com.shipradar.app.autoacq

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
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp

/**
 * W8-E — 自动捕获区设置面板（IEC 62388 §11.3.7：用户定义自动捕获区边界）。
 *
 * **状态 hoisted**：区列表由编排者持有（[zones] + [onZonesChange]），以便同一份状态既驱动本面板编辑，
 * 又供 [AcqZoneOverlay] 实时绘制。控件：每区 启用开关 + 内/外距离 + 起/止方位 步进。
 */
@Composable
fun AcqZoneSetupPanel(
    zones: List<AcqZone>,
    onZonesChange: (List<AcqZone>) -> Unit,
    modifier: Modifier = Modifier,
) {
    fun update(index: Int, transform: (AcqZone) -> AcqZone) {
        onZonesChange(zones.toMutableList().also { it[index] = transform(it[index]) })
    }

    Column(
        modifier
            .width(360.dp)
            .verticalScroll(rememberScrollState())
            .padding(12.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Text("自动捕获区", fontWeight = FontWeight.Bold, style = MaterialTheme.typography.titleMedium)
        Text(
            "IEC 62388 §11.3.7 — 进入该区的雷达回波自动起始跟踪",
            style = MaterialTheme.typography.bodySmall,
        )

        zones.forEachIndexed { i, zone ->
            HorizontalDivider()
            ZoneEditor(
                zone = zone,
                onEnabledChange = { on -> update(i) { it.copy(enabled = on) } },
                onChange = { updated -> update(i) { updated } },
            )
        }
    }
}

@Composable
private fun ZoneEditor(
    zone: AcqZone,
    onEnabledChange: (Boolean) -> Unit,
    onChange: (AcqZone) -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
            Text("捕获区 ${zone.id}", fontWeight = FontWeight.Bold)
            Switch(checked = zone.enabled, onCheckedChange = onEnabledChange)
        }
        Stepper("内距离", "%.2f NM".format(zone.innerRangeNm)) { d ->
            onChange(zone.copy(innerRangeNm = (zone.innerRangeNm + d * 0.1).coerceIn(0.0, 96.0)))
        }
        Stepper("外距离", "%.2f NM".format(zone.outerRangeNm)) { d ->
            onChange(zone.copy(outerRangeNm = (zone.outerRangeNm + d * 0.1).coerceIn(0.0, 96.0)))
        }
        Stepper("起方位", "%03.0f°".format(zone.startBearingDeg)) { d ->
            onChange(zone.copy(startBearingDeg = AcqZoneModel.norm360(zone.startBearingDeg + d * 5.0)))
        }
        Stepper("止方位", "%03.0f°".format(zone.endBearingDeg)) { d ->
            onChange(zone.copy(endBearingDeg = AcqZoneModel.norm360(zone.endBearingDeg + d * 5.0)))
        }
    }
}

/** 简易 ±步进控件（避开实验性 API）。 */
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

@Preview(showBackground = true, widthDp = 380, heightDp = 520)
@Composable
private fun AcqZoneSetupPanelPreview() {
    AcqZoneSetupPanel(
        zones = listOf(
            AcqZone(id = 0, enabled = true, innerRangeNm = 3.0, outerRangeNm = 4.0, startBearingDeg = 20.0, endBearingDeg = 110.0),
            AcqZone(id = 1, enabled = false),
        ),
        onZonesChange = {},
    )
}
