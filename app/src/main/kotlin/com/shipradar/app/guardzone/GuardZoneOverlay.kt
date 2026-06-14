package com.shipradar.app.guardzone

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.shipradar.contract.GuardZoneAlarmType
import com.shipradar.uicore.ppi.PpiOrientation
import com.shipradar.uicore.ppi.RangeModel
import com.shipradar.uicore.ppi.resolveDisplayRotationDeg
import kotlin.math.min

/**
 * W7-A — 报警圈/捕获区在 PPI 上的 **半透明扇环叠加**（自包含 Canvas，不依赖任何现有 overlay）。
 *
 * 区边界须清晰标示（IEC 62388 §11.7.2.2 / §11.3.7.1）。每个区画成内/外半径之间、起→止方位之间的扇环：
 * 半透明填充 + 较亮描边。启用的区更醒目，未启用的区淡显（虚线感由低透明度表达）。
 *
 * 参系：屏幕角 = 方位 + displayRotation（见 ui-core [resolveDisplayRotationDeg]）。
 *  - 区为相对船首方位时，bowRel = 方位；为真方位时 bowRel = 方位 − 航向。
 *  - 方位稳定模式(NORTH_UP/COURSE_UP)需航向；本叠加把 [headingDeg]/[courseDeg] 设为 **可选**：
 *    缺失或解析失败时回落 HEAD_UP（rotation=0），与渲染层在航向丢失时的回落行为一致。
 *
 * 必备入参与任务签名一致：[center]、[radiusPx]、[rangeScaleNm]、[orientation]、[zones]。
 */
@Composable
fun GuardZoneOverlay(
    center: Offset,
    radiusPx: Float,
    rangeScaleNm: Double,
    orientation: PpiOrientation,
    zones: List<GuardZone>,
    modifier: Modifier = Modifier,
    headingDeg: Double? = null,
    courseDeg: Double? = null,
) {
    // 方位稳定模式缺航向时回落 head-up（rotation=0），不抛异常。
    val rotation = runCatching { resolveDisplayRotationDeg(orientation, headingDeg, courseDeg) }.getOrDefault(0.0)

    Canvas(modifier.fillMaxSize()) {
        zones.forEach { zone -> drawZone(zone, center, radiusPx, rangeScaleNm, rotation, headingDeg) }
    }
}

private fun DrawScope.drawZone(
    zone: GuardZone,
    center: Offset,
    radiusPx: Float,
    rangeScaleNm: Double,
    rotationDeg: Double,
    headingDeg: Double?,
) {
    val innerPx = (RangeModel.rangeNmToFraction(minOf(zone.innerRangeNm, zone.outerRangeNm), rangeScaleNm) * radiusPx).toFloat()
    val outerPx = (RangeModel.rangeNmToFraction(maxOf(zone.innerRangeNm, zone.outerRangeNm), rangeScaleNm) * radiusPx).toFloat()
    if (outerPx <= 0f || outerPx <= innerPx) return

    val sweep = GuardZoneModel.sweepDeg(zone)
    // 起方位 → 屏幕角（Compose arc：0°=3点钟、顺时针；屏幕角 0=正上，故 compose = screenAngle − 90）。
    val startScreen = bearingToScreenAngle(zone.startBearingDeg, zone.trueBearing, rotationDeg, headingDeg)
    val startCompose = (startScreen - 90.0).toFloat()
    // 整圈时 arcTo 360 会退化，夹到 359.9 以保证可绘制（视觉上仍为整环）。
    val sweepF = min(sweep, 359.9).toFloat()

    val baseHue = if (zone.zone == 0) Color(0xFFFF9800) else Color(0xFF26C6DA) // 区0琥珀 / 区1青
    val fillAlpha = if (zone.enabled) 0.18f else 0.06f
    val edgeAlpha = if (zone.enabled) 0.95f else 0.35f
    val edgeWidth = if (zone.enabled) 2.5f else 1.5f

    val path = annularSectorPath(center, innerPx, outerPx, startCompose, sweepF)
    drawPath(path, baseHue.copy(alpha = fillAlpha))
    drawPath(path, baseHue.copy(alpha = edgeAlpha), style = Stroke(width = edgeWidth))
}

/** 区方位 → 屏幕角（度，0=正上、顺时针）。真方位先减航向得船首相对，再加显示旋转。 */
private fun bearingToScreenAngle(bearingDeg: Double, trueBearing: Boolean, rotationDeg: Double, headingDeg: Double?): Double {
    val bowRel = if (trueBearing) bearingDeg - (headingDeg ?: 0.0) else bearingDeg
    return GuardZoneModel.norm360(bowRel + rotationDeg)
}

/** 扇环路径：外弧顺扫 + 内弧逆扫回，闭合。 */
private fun annularSectorPath(center: Offset, innerR: Float, outerR: Float, startComposeDeg: Float, sweepDeg: Float): Path {
    val outerRect = Rect(center, outerR)
    val innerRect = Rect(center, innerR)
    return Path().apply {
        arcTo(outerRect, startComposeDeg, sweepDeg, forceMoveTo = true)
        arcTo(innerRect, startComposeDeg + sweepDeg, -sweepDeg, forceMoveTo = false)
        close()
    }
}

@Preview(showBackground = true, widthDp = 320, heightDp = 320)
@Composable
private fun GuardZoneOverlayPreview() {
    val side = 320.dp
    androidx.compose.foundation.layout.Box(Modifier.size(side).background(Color(0xFF06121A))) {
        GuardZoneOverlay(
            center = Offset(320f, 320f),
            radiusPx = 300f,
            rangeScaleNm = 6.0,
            orientation = PpiOrientation.HEAD_UP,
            zones = listOf(
                GuardZone(zone = 0, enabled = true, innerRangeNm = 3.0, outerRangeNm = 4.0, startBearingDeg = 30.0, endBearingDeg = 90.0,
                    alarmType = GuardZoneAlarmType.ENTERING),
                GuardZone(zone = 1, enabled = true, innerRangeNm = 1.5, outerRangeNm = 2.5, startBearingDeg = 340.0, endBearingDeg = 20.0,
                    alarmType = GuardZoneAlarmType.BOTH),
            ),
        )
        // 几个目标点示意（区内/区外）。
        Canvas(Modifier.fillMaxSize()) {
            val c = Offset(size.width / 2, size.height / 2)
            val r = min(size.width, size.height) / 2 * 0.95f
            fun dot(bearingDeg: Double, rangeNm: Double, color: Color) {
                val frac = RangeModel.rangeNmToFraction(rangeNm, 6.0).toFloat()
                val a = Math.toRadians(bearingDeg - 90.0)
                drawCircle(color, radius = 5f, center = Offset(c.x + r * frac * kotlin.math.cos(a).toFloat(), c.y + r * frac * kotlin.math.sin(a).toFloat()))
            }
            dot(60.0, 3.5, Color(0xFFFF5252)) // 命中区0
            dot(0.0, 2.0, Color(0xFFFF5252))  // 命中区1(跨0°)
            dot(180.0, 3.5, Color(0xFF80D8FF)) // 区外
        }
    }
}
