package com.shipradar.app.chart

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import com.shipradar.uicore.ppi.PpiOrientation
import com.shipradar.uicore.ppi.PpiProjection
import com.shipradar.uicore.ppi.ScreenPoint
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.roundToInt

/**
 * 海图叠加(底图):经纬网格 + 海岸线,按本船位置/量程/方位投影到 PPI。
 *
 * 投影:每个经纬点 → 相对本船的真距离(NM)+真方位 → [PpiProjection.polarToScreen](艏向相对方位,
 * 由投影按定向旋转)。平面近似(雷达量程内足够)。**演示海图数据见 [ChartData];真 S-57 待采购。**
 * 随偏心:由调用方把 center 传入(= 视图中心 + 偏心偏移)。
 */
@Composable
fun ChartOverlay(
    ownLat: Double?,
    ownLon: Double?,
    headingDeg: Double?,
    courseDeg: Double?,
    rangeScaleNm: Double,
    orientation: PpiOrientation,
    center: Offset,
    radiusPx: Float,
    modifier: Modifier = Modifier,
) {
    if (ownLat == null || ownLon == null || radiusPx <= 0f || rangeScaleNm <= 0.0) return
    val ctx = LocalContext.current
    val coastlines = remember { ChartData.coastlines(ctx.assets) } // 真实海岸线(Natural Earth)
    val proj = PpiProjection.create(
        ScreenPoint(center.x.toDouble(), center.y.toDouble()), radiusPx.toDouble(), orientation, headingDeg, courseDeg,
    )
    val cosLat = cos(Math.toRadians(ownLat))

    fun screenOf(lat: Double, lon: Double): Offset? {
        val dLat = lat - ownLat
        val dLon = (lon - ownLon) * cosLat
        val distNm = 60.0 * hypot(dLat, dLon)
        if (distNm > rangeScaleNm * 1.02) return null // 超量程裁剪
        val trueBrg = Math.toDegrees(atan2(dLon, dLat))
        val bowRel = trueBrg - (headingDeg ?: 0.0)
        val sp = proj.polarToScreen(((bowRel % 360) + 360) % 360, distNm / rangeScaleNm)
        return Offset(sp.x.toFloat(), sp.y.toFloat())
    }

    Canvas(modifier.fillMaxSize()) {
        drawGraticule(ownLat, ownLon, rangeScaleNm, ::screenOf)
        for (line in coastlines) drawPolyline(line, ::screenOf)
    }
}

/** 网格步长(度):按量程自适应,经纬线不过密。 */
private fun gridStepDeg(rangeNm: Double): Double = when {
    rangeNm <= 1.5 -> 0.02
    rangeNm <= 6.0 -> 0.05
    rangeNm <= 24.0 -> 0.2
    else -> 0.5
}

private fun DrawScope.drawGraticule(
    ownLat: Double, ownLon: Double, rangeNm: Double, screenOf: (Double, Double) -> Offset?,
) {
    val spanDeg = rangeNm / 60.0 * 1.1
    val step = gridStepDeg(rangeNm)
    val color = Color(0x66A9C7D6) // 蓝灰网格(略亮可见)
    fun snap(v: Double) = (v / step).roundToInt() * step
    // 纬线(沿经度方向采样)
    var lat = snap(ownLat - spanDeg)
    while (lat <= ownLat + spanDeg) {
        val pts = ArrayList<Offset>()
        var lon = ownLon - spanDeg
        while (lon <= ownLon + spanDeg) { pts.add(screenOf(lat, lon) ?: Offset.Unspecified); lon += step / 4 }
        strokePath(pts, color, 1f)
        lat += step
    }
    // 经线(沿纬度方向采样)
    var lon = snap(ownLon - spanDeg)
    while (lon <= ownLon + spanDeg) {
        val pts = ArrayList<Offset>()
        var la = ownLat - spanDeg
        while (la <= ownLat + spanDeg) { pts.add(screenOf(la, lon) ?: Offset.Unspecified); la += step / 4 }
        strokePath(pts, color, 1f)
        lon += step
    }
}

private fun DrawScope.drawPolyline(line: List<DoubleArray>, screenOf: (Double, Double) -> Offset?) {
    val pts = line.map { screenOf(it[0], it[1]) ?: Offset.Unspecified }
    strokePath(pts, Color(0xCCB8946A), 2.2f) // 海岸线(陆地棕黄)
}

/** 画一串点为折线;遇到 Unspecified(超量程)断开,不连跨屏假线。 */
private fun DrawScope.strokePath(pts: List<Offset>, color: Color, width: Float) {
    var prev: Offset? = null
    for (p in pts) {
        if (p == Offset.Unspecified) { prev = null; continue }
        val pr = prev
        if (pr != null) drawLine(color, pr, p, strokeWidth = width)
        prev = p
    }
}
