package com.shipradar.app.databar

import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.shipradar.contract.MasterSlave
import com.shipradar.contract.OwnShipData
import com.shipradar.contract.RadarPowerState
import com.shipradar.contract.RadarStatus
import com.shipradar.contract.SensorKind
import com.shipradar.uicore.ppi.PpiOrientation

/**
 * T2.7 / ALRM-02 — 防撞要素永久显示数据栏（Compose）。RadarScreen 的 **top 槽**。
 *
 * 设计要点：
 *  - **不得遮挡**：本组件是操作显示区(operational display area, §3.45)之外的专用条带，由 RadarScreen
 *    置于 PPI 上方，绝不覆盖回波（§6326 数据区不得遮蔽雷达图像）。它自身只占一条横向 chrome。
 *  - **逐项必显**：所有项来自 [DataBarModel.build]，固定穷举；本组件不增减项，只着色/排版。
 *  - 失效项以红/琥珀色 + 占位符高亮（§14.2.2.1 / §16.2.1）。
 *
 * 渲染逻辑刻意极薄；合规判定与格式化全在纯逻辑 [DataBarModel]（已单元测试）。
 */
@Composable
fun DataBar(
    ownShip: OwnShipData,
    status: RadarStatus,
    settings: RadarDisplaySettings,
    modifier: Modifier = Modifier,
    sensorValidity: Map<SensorKind, Boolean> = ownShip.sourceValidity,
) {
    val fields = DataBarModel.build(ownShip, status, settings, sensorValidity)
    Row(
        modifier
            .fillMaxWidth()
            .background(Color(0xFF0B1418)) // 暗背景，OpenBridge 夜航调色由 T2.9 框架统一
            .horizontalScroll(rememberScrollState())
            .padding(horizontal = 8.dp, vertical = 4.dp),
        horizontalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        fields.forEach { FieldCell(it) }
    }
}

@Composable
private fun FieldCell(f: DataField) {
    Column {
        Text(
            f.label,
            color = Color(0xFF7FA6B3),
            fontSize = 9.sp,
            fontWeight = FontWeight.Medium,
        )
        Text(
            f.value,
            color = severityColor(f.severity),
            fontSize = 13.sp,
            fontWeight = FontWeight.Bold,
            fontFamily = FontFamily.Monospace,
        )
    }
}

private fun severityColor(s: FieldSeverity): Color = when (s) {
    FieldSeverity.OK -> Color(0xFFE6F2F5)
    FieldSeverity.DEGRADED -> Color(0xFFFFC107) // 琥珀：降级/陈旧
    FieldSeverity.FAIL -> Color(0xFFFF5252)     // 红：失效/无数据
}

@Preview(widthDp = 800, showBackground = true)
@Composable
private fun DataBarPreview() {
    DataBar(
        ownShip = OwnShipData(
            latitude = 34.4217, longitude = -119.7017,
            headingDeg = 87.0, headingTrue = true, cogDeg = 90.0, sogKn = 12.4,
            sourceValidity = mapOf(
                SensorKind.HEADING to true, SensorKind.POSITION to true,
                SensorKind.COG_SOG to true, SensorKind.RADAR_LINK to true,
            ),
        ),
        status = RadarStatus(
            powerState = RadarPowerState.TRANSMIT, rangeMeters = 11112, // 6 NM
            gainAuto = false, gain = 142, seaLevel = 30, rainLevel = 10,
            masterSlave = MasterSlave.MASTER,
        ),
        settings = RadarDisplaySettings(
            orientation = PpiOrientation.NORTH_UP, motionMode = MotionMode.TRUE_MOTION,
            vectorMode = VectorMode.RELATIVE, vectorTimeMin = 6, stabilisation = Stabilisation.GROUND,
        ),
    )
}
