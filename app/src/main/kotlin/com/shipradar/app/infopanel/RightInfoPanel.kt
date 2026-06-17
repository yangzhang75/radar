package com.shipradar.app.infopanel

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.foundation.layout.Arrangement
import com.shipradar.app.conning.CompassGauge
import com.shipradar.app.conning.InstrumentField
import com.shipradar.app.control.RadarDisplaySettings
import com.shipradar.app.framework.OpenBridge
import com.shipradar.contract.OwnShipData
import com.shipradar.contract.TargetSource
import com.shipradar.contract.TrackedTarget
import kotlin.math.abs

/**
 * Right-hand information column — standard IMO CAT 1 layout area ③ (cf. Furuno FAR-2xx8 §1.4):
 * own-ship read-outs, selected/most-dangerous target data (BRG/RNG/CPA/TCPA/COG/SOG), TT/AIS
 * settings and a collision-danger banner. Read-only presentation of contract data.
 */
@Composable
fun RightInfoPanel(
    ownShip: OwnShipData,
    targets: List<TrackedTarget>,
    display: RadarDisplaySettings,
    selected: TrackedTarget? = null,
    simulated: Boolean = true,
    onToggleSource: () -> Unit = {},
    dualRange: Boolean = false,
    onToggleDual: () -> Unit = {},
    modifier: Modifier = Modifier,
) {
    val tt = targets.count { it.source == TargetSource.RADAR_TT }
    val aisActive = targets.count { it.source == TargetSource.AIS_ACTIVE }
    val aisSleeping = targets.count { it.source == TargetSource.AIS_SLEEPING }
    val danger = targets.filter { it.dangerous }
    val shown = selected ?: danger.firstOrNull() ?: targets.firstOrNull()

    Column(
        modifier
            .width(248.dp)
            .fillMaxHeight()
            .background(OpenBridge.colors.chromeBackground)
            .verticalScroll(rememberScrollState())
            .padding(8.dp),
    ) {
        // SIM / LIVE 数据源切换(模拟作为功能)。SIM 时整行琥珀高亮,与 PPI 横幅呼应。
        Row(
            Modifier
                .fillMaxWidth()
                .background(if (simulated) Color(0xCCB36B00) else Color(0xFF14323A))
                .clickable { onToggleSource() }
                .padding(horizontal = 8.dp, vertical = 6.dp),
        ) {
            Text(
                if (simulated) "● SIMULATION 模拟" else "● LIVE 实时",
                color = if (simulated) Color(0xFFFFF1D6) else Color(0xFF7FE6A0),
                fontWeight = FontWeight.Bold,
                fontSize = 12.sp,
            )
            Text("  ⇄ 点击切换", color = Color(0xFFB8D2DA), fontSize = 10.sp, modifier = Modifier.padding(start = 6.dp))
        }
        // 双量程 (HALO Radar B) 单/双画面切换。
        Row(
            Modifier
                .fillMaxWidth()
                .padding(top = 2.dp)
                .background(if (dualRange) Color(0xFF14463A) else Color(0xFF14323A))
                .clickable { onToggleDual() }
                .padding(horizontal = 8.dp, vertical = 6.dp),
        ) {
            Text(
                if (dualRange) "▌▌ 双量程 DUAL" else "▌ 单量程 SINGLE",
                color = if (dualRange) Color(0xFF7FE6A0) else Color(0xFFB8D2DA),
                fontWeight = FontWeight.Bold,
                fontSize = 12.sp,
            )
            Text("  键 D", color = Color(0xFF7FA6B3), fontSize = 10.sp, modifier = Modifier.padding(start = 6.dp))
        }
        // OpenBridge conning 罗经盘(HDG/COG 箭头 + 转速点),对齐 JRC RADAR 实机右侧仪表。
        Section("COMPASS") {
            CompassGauge(
                headingDeg = ownShip.headingDeg,
                cogDeg = ownShip.cogDeg,
                rotDegMin = ownShip.rotDegMin,
                modifier = Modifier.padding(vertical = 4.dp),
            )
            // 读数瓦片(OpenBridge instrument-field):COG / SOG / ROT。
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                InstrumentField("COG", ownShip.cogDeg, "°T", fractionDigits = 1, maxDigits = 3)
                InstrumentField("SOG", ownShip.sogKn, "kn", fractionDigits = 1, maxDigits = 2)
                InstrumentField("ROT", ownShip.rotDegMin, "°/m", fractionDigits = 0, maxDigits = 3)
            }
        }
        Section("OWN SHIP") {
            Field("HDG", ownShip.headingDeg?.let { deg(it) + if (ownShip.headingTrue) " T" else " M" })
            Field("POSN", latLon(ownShip.latitude, ownShip.longitude))
        }
        Section(if (shown != null) "TARGET ${shown.id}${danger.isNotEmpty().ifTrue(" ⚠")}" else "TARGET") {
            if (shown == null) {
                Text("No target selected", color = OpenBridge.colors.foregroundSecondary, fontSize = 11.sp)
            } else {
                Field("BRG", deg(shown.bearingDeg) + if (shown.trueBearing) " T" else " R")
                Field("RNG", "%.2f NM".format(shown.rangeNm))
                Field("COG", shown.courseDeg?.let { deg(it) })
                Field("SOG", shown.speedKn?.let { "%.1f kn".format(it) })
                Field("CPA", shown.cpaNm?.let { "%.2f NM".format(it) }, danger = shown.dangerous)
                Field("TCPA", shown.tcpaSec?.let { mmss(it) }, danger = shown.dangerous)
            }
        }
        Section("TT / AIS") {
            Field("RADAR TT", tt.toString())
            Field("AIS ACT", aisActive.toString())
            Field("AIS SLEEP", aisSleeping.toString())
            Field("VECTOR", "${display.vectorTimeMin} min ${display.vectorMode.name.lowercase()}")
            Field("MOTION", if (display.motion.name == "TRUE") "TM" else "RM")
            Field("RANGE", "%.2f NM".format(display.rangeScaleNm))
        }
        if (danger.isNotEmpty()) {
            Row(
                Modifier.fillMaxWidth().padding(top = 8.dp).background(Color(0xFF7A1F1F)).padding(6.dp),
            ) {
                Text("DANGER OF COLLISION", color = Color(0xFFFFE0E0), fontWeight = FontWeight.Bold, fontSize = 12.sp)
            }
        }
    }
}

@Composable
private fun Section(title: String, content: @Composable () -> Unit) {
    Text(
        title,
        color = OpenBridge.colors.accent,
        fontSize = 10.sp,
        fontWeight = FontWeight.Bold,
        modifier = Modifier.padding(top = 10.dp, bottom = 2.dp),
    )
    content()
}

@Composable
private fun Field(label: String, value: String?, danger: Boolean = false) {
    Row(Modifier.fillMaxWidth().padding(vertical = 1.dp)) {
        Text(label, color = OpenBridge.colors.foregroundSecondary, fontSize = 11.sp, modifier = Modifier.width(74.dp))
        Text(
            value ?: "---",
            color = if (danger) OpenBridge.colors.alarm else OpenBridge.colors.foregroundPrimary,
            fontSize = 13.sp,
            fontWeight = FontWeight.Bold,
            fontFamily = FontFamily.Monospace,
        )
    }
}

private fun deg(d: Double): String = "%05.1f°".format((d % 360 + 360) % 360)
private fun mmss(sec: Double): String {
    val s = abs(sec).toInt(); return "%s%02d:%02d".format(if (sec < 0) "-" else "", s / 60, s % 60)
}
private fun latLon(lat: Double?, lon: Double?): String? {
    if (lat == null || lon == null) return null
    fun fmt(v: Double, pos: String, neg: String): String {
        val h = if (v >= 0) pos else neg; val a = abs(v); val d = a.toInt(); val m = (a - d) * 60
        return "%d°%05.2f'%s".format(d, m, h)
    }
    return fmt(lat, "N", "S") + " " + fmt(lon, "E", "W")
}
private fun Boolean.ifTrue(s: String) = if (this) s else ""
