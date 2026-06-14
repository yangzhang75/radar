package com.shipradar.app.autoacq

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.shipradar.uicore.ppi.PpiOrientation
import com.shipradar.uicore.ppi.RangeModel
import com.shipradar.uicore.ppi.resolveDisplayRotationDeg
import kotlin.math.min

/**
 * W8-E — 自动捕获区在 PPI 上的 **扇环叠加**（自包含 Canvas）。
 *
 * **与报警圈视觉区分**：自动捕获区用 **虚线描边 + 绿色调**（报警圈是实线/琥珀-青）。区边界须清晰标示
 * （IEC 62388 §11.3.7.1 / §11.7.2.2）。
 *
 * 参系：屏幕角 = 方位 + displayRotation（见 ui-core [resolveDisplayRotationDeg]）。真方位区先减航向得船首相对。
 * 方位稳定模式(NORTH_UP/COURSE_UP)需航向；缺失或解析失败回落 HEAD_UP（rotation=0）。
 */
@Composable
fun AcqZoneOverlay(
    center: Offset,
    radiusPx: Float,
    rangeScaleNm: Double,
    orientation: PpiOrientation,
    zones: List<AcqZone>,
    modifier: Modifier = Modifier,
    headingDeg: Double? = null,
    courseDeg: Double? = null,
) {
    val rotation = runCatching { resolveDisplayRotationDeg(orientation, headingDeg, courseDeg) }.getOrDefault(0.0)
    Canvas(modifier.fillMaxSize()) {
        zones.forEach { zone -> drawAcqZone(zone, center, radiusPx, rangeScaleNm, rotation, headingDeg) }
    }
}

private fun DrawScope.drawAcqZone(
    zone: AcqZone,
    center: Offset,
    radiusPx: Float,
    rangeScaleNm: Double,
    rotationDeg: Double,
    headingDeg: Double?,
) {
    val innerPx = (RangeModel.rangeNmToFraction(minOf(zone.innerRangeNm, zone.outerRangeNm), rangeScaleNm) * radiusPx).toFloat()
    val outerPx = (RangeModel.rangeNmToFraction(maxOf(zone.innerRangeNm, zone.outerRangeNm), rangeScaleNm) * radiusPx).toFloat()
    if (outerPx <= 0f || outerPx <= innerPx) return

    val startScreen = bearingToScreenAngle(zone.startBearingDeg, zone.trueBearing, rotationDeg, headingDeg)
    val startCompose = (startScreen - 90.0).toFloat() // Compose arc: 0°=3点钟；屏幕角0=正上 -> compose=screen-90
    val sweepF = min(AcqZoneModel.sweepDeg(zone), 359.9).toFloat()

    // 自动捕获区配色：绿，区别于报警圈。
    val green = Color(0xFF66BB6A)
    val fillAlpha = if (zone.enabled) 0.10f else 0.04f
    val edgeAlpha = if (zone.enabled) 0.95f else 0.40f
    val dashed = PathEffect.dashPathEffect(floatArrayOf(14f, 9f), 0f)

    val path = annularSectorPath(center, innerPx, outerPx, startCompose, sweepF)
    drawPath(path, green.copy(alpha = fillAlpha))
    drawPath(path, green.copy(alpha = edgeAlpha), style = Stroke(width = 2.5f, pathEffect = dashed))
}

/** 区方位 → 屏幕角（度，0=正上、顺时针）。真方位先减航向得船首相对，再加显示旋转。 */
private fun bearingToScreenAngle(bearingDeg: Double, trueBearing: Boolean, rotationDeg: Double, headingDeg: Double?): Double {
    val bowRel = if (trueBearing) bearingDeg - (headingDeg ?: 0.0) else bearingDeg
    return AcqZoneModel.norm360(bowRel + rotationDeg)
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
private fun AcqZoneOverlayPreview() {
    Box(Modifier.size(320.dp).background(Color(0xFF06121A))) {
        AcqZoneOverlay(
            center = Offset(320f, 320f),     // 预览默认 density 下 160.dp*2 ≈ 屏幕中心
            radiusPx = 300f,
            rangeScaleNm = 6.0,
            orientation = PpiOrientation.HEAD_UP,
            zones = listOf(
                AcqZone(id = 0, enabled = true, innerRangeNm = 3.0, outerRangeNm = 4.0, startBearingDeg = 20.0, endBearingDeg = 110.0),
                AcqZone(id = 1, enabled = true, innerRangeNm = 1.5, outerRangeNm = 2.5, startBearingDeg = 340.0, endBearingDeg = 30.0),
            ),
        )
    }
}
