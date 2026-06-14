package com.shipradar.app.trial

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.shipradar.contract.OwnShipData
import com.shipradar.contract.SensorKind
import com.shipradar.contract.TargetSource
import com.shipradar.contract.TargetStatus
import com.shipradar.contract.TrackedTarget
import com.shipradar.uicore.target.DangerCriteria
import kotlin.math.roundToInt

/**
 * 试操船面板(Trial Maneuver)—— W6-A,Compose。
 *
 * 操作员假设本船改向/改速(可带延迟),系统在**不影响真实跟踪**的前提下重算各目标的 CPA/TCPA 与相对
 * 运动矢量,辅助避碰(IMO A.823(19) §3.7 / IEC 62388 §11 / GB 11711-2002 §4.2.7)。
 *
 * 渲染极薄:全部解算在已单测的纯逻辑 [TrialManeuverEvaluator](委托 ui-core 认证算法)。本组件只负责:
 *  - 试操参数输入(航向/航速/延迟 + 试操开关);
 *  - 试操开启时显示**醒目常驻 "TRIAL 试操" 标识**(A.823 §3.7.1:试操态须有明确指示,不得与真实态混淆);
 *  - 试操前/后对比表(危险用红色),试操关闭时不显示任何试操结果以免混淆。
 */
@Composable
fun TrialManeuverPanel(
    ownShip: OwnShipData,
    targets: List<TrackedTarget>,
    modifier: Modifier = Modifier,
    criteria: DangerCriteria = DangerCriteria(),
) {
    // 默认试操态 = 当前本船态(航向优先 COG,其次罗经;航速取 SOG)。仅作初值,操作员可改。
    var trialOn by remember { mutableStateOf(false) }
    var courseDeg by remember {
        mutableStateOf((ownShip.cogDeg ?: ownShip.headingDeg ?: 0.0).toFloat())
    }
    var speedKn by remember { mutableStateOf((ownShip.sogKn ?: 0.0).toFloat()) }
    var delayMin by remember { mutableStateOf(0f) }

    Column(
        modifier
            .background(PANEL_BG)
            .padding(10.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        // ── 常驻试操标识 ──────────────────────────────────────────────
        TrialBanner(trialOn)

        // ── 参数输入 ──────────────────────────────────────────────────
        ParamSlider("试操航向 CRS", "${courseDeg.roundToInt()}°", courseDeg, 0f..359f) { courseDeg = it }
        ParamSlider("试操航速 SPD", fmt1(speedKn) + " kn", speedKn, 0f..40f) { speedKn = it }
        ParamSlider("延迟 DELAY", "${delayMin.roundToInt()} min", delayMin, 0f..30f) { delayMin = it }

        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("试操 TRIAL", color = LABEL, fontSize = 12.sp, fontWeight = FontWeight.Bold)
            Box(Modifier.width(8.dp))
            Switch(checked = trialOn, onCheckedChange = { trialOn = it })
        }

        // ── 结果表(仅试操开启时显示,避免与真实态混淆)──────────────
        if (trialOn) {
            val rows = TrialManeuverEvaluator.evaluate(
                ownShip = ownShip,
                targets = targets,
                params = TrialManeuverParams(
                    trialCourseDeg = courseDeg.toDouble(),
                    trialSpeedKn = speedKn.toDouble(),
                    delayMin = delayMin.toDouble(),
                ),
                criteria = criteria,
            )
            ResultTable(rows)
        } else {
            Text(
                "试操关闭 — 显示为真实态。打开开关以预览改向/改速效果。",
                color = LABEL, fontSize = 11.sp,
            )
        }
    }
}

@Composable
private fun TrialBanner(active: Boolean) {
    val bg = if (active) TRIAL_RED else BANNER_IDLE
    val txt = if (active) "⚠ TRIAL 试操中 — 模拟态,非真实跟踪" else "TRIAL 试操(关闭)"
    Box(
        Modifier
            .fillMaxWidth()
            .background(bg)
            .padding(horizontal = 10.dp, vertical = 6.dp),
    ) {
        Text(
            txt,
            color = Color(0xFF101418).takeIf { active } ?: LABEL,
            fontSize = 13.sp,
            fontWeight = FontWeight.Black,
            fontFamily = FontFamily.Monospace,
        )
    }
}

@Composable
private fun ParamSlider(
    label: String,
    valueText: String,
    value: Float,
    range: ClosedFloatingPointRange<Float>,
    onChange: (Float) -> Unit,
) {
    Column {
        Row(
            Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Text(label, color = LABEL, fontSize = 11.sp, fontWeight = FontWeight.Medium)
            Text(
                valueText,
                color = VALUE,
                fontSize = 12.sp,
                fontWeight = FontWeight.Bold,
                fontFamily = FontFamily.Monospace,
            )
        }
        Slider(value = value, onValueChange = onChange, valueRange = range)
    }
}

@Composable
private fun ResultTable(rows: List<TrialComparisonRow>) {
    Column(
        Modifier
            .fillMaxWidth()
            .verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        // 表头(列宽与 ResultRow 对齐)
        Row(Modifier.fillMaxWidth().padding(vertical = 2.dp)) {
            HeaderCell("目标", 86.dp)
            HeaderCell("CPA 当前→试操", 150.dp)
            HeaderCell("TCPA 当前→试操", 130.dp)
            HeaderCell("相对矢量", 90.dp)
        }
        if (rows.isEmpty()) {
            Text("无可解算目标。", color = LABEL, fontSize = 11.sp)
        }
        for (r in rows) ResultRow(r)
    }
}

@Composable
private fun HeaderCell(text: String, width: androidx.compose.ui.unit.Dp) {
    Text(
        text,
        color = LABEL,
        fontSize = 9.sp,
        fontWeight = FontWeight.Bold,
        modifier = Modifier.width(width),
    )
}

@Composable
private fun ResultRow(r: TrialComparisonRow) {
    val trialColor = if (r.trialDangerous) TRIAL_RED else SAFE_GREEN
    Row(
        Modifier
            .fillMaxWidth()
            .background(if (r.trialDangerous) Color(0x33FF5252) else Color(0x11FFFFFF))
            .padding(horizontal = 4.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.width(86.dp)) {
            Text(r.targetId, color = VALUE, fontSize = 12.sp, fontWeight = FontWeight.Bold, fontFamily = FontFamily.Monospace)
            Text(sourceTag(r.source), color = LABEL, fontSize = 8.sp)
        }
        // CPA 当前 → 试操
        Column(Modifier.width(150.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    fmtCpa(r.liveCpaNm),
                    color = if (r.liveDangerous) TRIAL_RED else VALUE,
                    fontSize = 12.sp, fontFamily = FontFamily.Monospace,
                )
                Text("  ${trendArrow(r.cpaTrend)} ", color = trendColor(r.cpaTrend), fontSize = 12.sp, fontWeight = FontWeight.Bold)
                Text(
                    fmtCpa(r.trialCpaNm),
                    color = trialColor,
                    fontSize = 13.sp, fontWeight = FontWeight.Bold, fontFamily = FontFamily.Monospace,
                )
            }
        }
        // TCPA 当前 → 试操
        Column(Modifier.width(130.dp)) {
            Row {
                Text(fmtTcpa(r.liveTcpaSec), color = VALUE, fontSize = 12.sp, fontFamily = FontFamily.Monospace)
                Text("  →  ", color = LABEL, fontSize = 12.sp)
                Text(fmtTcpa(r.trialTcpaSec), color = trialColor, fontSize = 13.sp, fontWeight = FontWeight.Bold, fontFamily = FontFamily.Monospace)
            }
        }
        // 试操后相对运动矢量
        Text(
            fmtRelVector(r.trialRelCourseDeg, r.trialRelSpeedKn),
            color = VALUE, fontSize = 11.sp, fontFamily = FontFamily.Monospace,
        )
    }
}

// ── 格式化 ────────────────────────────────────────────────────────────

private fun fmt1(v: Float): String = "%.1f".format(v)

private fun fmtCpa(nm: Double?): String = if (nm == null) "—" else "%.2f".format(nm)

/** TCPA 秒 → "MM:SS";负值(已过 CPA)前缀 "−";null(无相对运动)→ "—"。 */
private fun fmtTcpa(sec: Double?): String {
    if (sec == null) return "—"
    val neg = sec < 0
    val total = kotlin.math.abs(sec).roundToInt()
    val m = total / 60
    val s = total % 60
    return (if (neg) "−" else "") + "%d:%02d".format(m, s)
}

private fun fmtRelVector(courseDeg: Double?, speedKn: Double): String =
    if (courseDeg == null) "—" else "${courseDeg.roundToInt()}°/${"%.1f".format(speedKn)}kn"

private fun trendArrow(t: TrialComparisonRow.Trend): String = when (t) {
    TrialComparisonRow.Trend.SAFER -> "↑"      // CPA 变大 = 更安全
    TrialComparisonRow.Trend.WORSE -> "↓"      // CPA 变小 = 更危险
    TrialComparisonRow.Trend.UNCHANGED -> "→"
    TrialComparisonRow.Trend.UNKNOWN -> "→"
}

private fun trendColor(t: TrialComparisonRow.Trend): Color = when (t) {
    TrialComparisonRow.Trend.SAFER -> SAFE_GREEN
    TrialComparisonRow.Trend.WORSE -> TRIAL_RED
    else -> LABEL
}

private fun sourceTag(s: TargetSource): String = when (s) {
    TargetSource.RADAR_TT -> "TT"
    TargetSource.AIS_ACTIVE -> "AIS"
    TargetSource.AIS_SLEEPING -> "AIS·zzz"
}

// ── 配色(自包含;夜航暗背景,与 DataBar 同色系)─────────────────────
private val PANEL_BG = Color(0xFF0B1418)
private val LABEL = Color(0xFF7FA6B3)
private val VALUE = Color(0xFFE6F2F5)
private val TRIAL_RED = Color(0xFFFF5252)
private val SAFE_GREEN = Color(0xFF4CD07A)
private val BANNER_IDLE = Color(0xFF13242B)

// ── 预览(自包含假目标)────────────────────────────────────────────────
@Preview(widthDp = 420, heightDp = 560, showBackground = true)
@Composable
private fun TrialManeuverPanelPreview() {
    val ownShip = OwnShipData(
        headingDeg = 0.0, headingTrue = true, cogDeg = 0.0, sogKn = 10.0,
        sourceValidity = mapOf(
            SensorKind.HEADING to true, SensorKind.COG_SOG to true,
        ),
    )
    val targets = listOf(
        // 正前方对遇,当前 CPA≈0(危险);右转可化解。
        TrackedTarget(
            id = "TT01", source = TargetSource.RADAR_TT, rangeNm = 6.0, bearingDeg = 0.0,
            trueBearing = true, courseDeg = 180.0, speedKn = 10.0,
            cpaNm = 0.0, tcpaSec = 1080.0, status = TargetStatus.TRACKED, dangerous = true,
        ),
        // 右舷正横静止目标,当前安全;若右转反而变危险。
        TrackedTarget(
            id = "AIS7", source = TargetSource.AIS_ACTIVE, rangeNm = 6.0, bearingDeg = 90.0,
            trueBearing = true, courseDeg = 0.0, speedKn = 0.0,
            cpaNm = 6.0, tcpaSec = 0.0, status = TargetStatus.TRACKED, dangerous = false,
        ),
    )
    TrialManeuverPanel(ownShip = ownShip, targets = targets)
}
