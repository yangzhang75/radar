package com.shipradar.app.chart

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipPath
import androidx.compose.ui.platform.LocalContext
import com.shipradar.uicore.ppi.PpiOrientation
import com.shipradar.uicore.ppi.PpiProjection
import com.shipradar.uicore.ppi.ScreenPoint
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.roundToInt

/**
 * 海图/底图叠加:**填充陆地(真实 Natural Earth 多边形)+ 海岸线 + 经纬网格**,按本船位置/量程/方位
 * 投影到 PPI,裁剪到操作圆内。陆地填充使其像真海图(非细线)。**真实地理数据,非认证 S-57 ENC。**
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
    val land = remember { ChartData.land(ctx.assets) }
    val coast = remember { ChartData.coastlines(ctx.assets) }
    val proj = PpiProjection.create(
        ScreenPoint(center.x.toDouble(), center.y.toDouble()), radiusPx.toDouble(), orientation, headingDeg, courseDeg,
    )
    val cosLat = cos(Math.toRadians(ownLat))

    // 投影一个经纬点到屏幕(陆地填充用:不按量程裁剪,靠圆裁剪;rangeFraction 可 >1)。
    fun raw(lat: Double, lon: Double): Offset {
        val dLat = lat - ownLat
        val dLon = (lon - ownLon) * cosLat
        val distNm = 60.0 * hypot(dLat, dLon)
        val trueBrg = Math.toDegrees(atan2(dLon, dLat))
        val bowRel = (((trueBrg - (headingDeg ?: 0.0)) % 360) + 360) % 360
        val sp = proj.polarToScreen(bowRel, if (rangeScaleNm > 0) distNm / rangeScaleNm else 0.0)
        return Offset(sp.x.toFloat(), sp.y.toFloat())
    }
    // 线条用:超量程返回 Unspecified 以断开。
    fun culled(lat: Double, lon: Double): Offset {
        val dLat = lat - ownLat; val dLon = (lon - ownLon) * cosLat
        if (60.0 * hypot(dLat, dLon) > rangeScaleNm * 1.02) return Offset.Unspecified
        return raw(lat, lon)
    }

    Canvas(modifier.fillMaxSize()) {
        val circle = Path().apply { addOval(Rect(center.x - radiusPx, center.y - radiusPx, center.x + radiusPx, center.y + radiusPx)) }
        // 陆地填充 + 海岸线描边,裁剪在操作圆内。
        clipPath(circle) {
            for (poly in land) {
                if (poly.size < 3) continue
                val path = Path()
                val p0 = raw(poly[0][0], poly[0][1]); path.moveTo(p0.x, p0.y)
                for (k in 1 until poly.size) { val p = raw(poly[k][0], poly[k][1]); path.lineTo(p.x, p.y) }
                path.close()
                drawPath(path, LAND_FILL)
                drawPath(path, COAST, style = Stroke(width = 1.6f))
            }
        }
        // 经纬网格
        drawGraticule(ownLat, ownLon, rangeScaleNm, ::culled)
        // 海岸线细节(land.json 之外的细线)
        for (line in coast) drawPolyline(line, ::culled)
    }
}

private val LAND_FILL = Color(0xFF3A4A2E) // 暗橄榄陆地(雷达暗背景上不刺眼)
private val COAST = Color(0xFFC8B07A)     // 海岸线(陆地棕黄)
private val GRID = Color(0x55A9C7D6)      // 经纬网格

private fun gridStepDeg(rangeNm: Double): Double = when {
    rangeNm <= 1.5 -> 0.02; rangeNm <= 6.0 -> 0.05; rangeNm <= 24.0 -> 0.2; else -> 0.5
}

private fun DrawScope.drawGraticule(ownLat: Double, ownLon: Double, rangeNm: Double, p: (Double, Double) -> Offset) {
    val span = rangeNm / 60.0 * 1.1
    val step = gridStepDeg(rangeNm)
    fun snap(v: Double) = (v / step).roundToInt() * step
    var lat = snap(ownLat - span)
    while (lat <= ownLat + span) {
        val pts = ArrayList<Offset>(); var lon = ownLon - span
        while (lon <= ownLon + span) { pts.add(p(lat, lon)); lon += step / 4 }
        strokePath(pts, GRID, 1f); lat += step
    }
    var lon = snap(ownLon - span)
    while (lon <= ownLon + span) {
        val pts = ArrayList<Offset>(); var la = ownLat - span
        while (la <= ownLat + span) { pts.add(p(la, lon)); la += step / 4 }
        strokePath(pts, GRID, 1f); lon += step
    }
}

private fun DrawScope.drawPolyline(line: List<DoubleArray>, p: (Double, Double) -> Offset) =
    strokePath(line.map { p(it[0], it[1]) }, COAST, 1.6f)

private fun DrawScope.strokePath(pts: List<Offset>, color: Color, width: Float) {
    var prev: Offset? = null
    for (pt in pts) {
        if (pt == Offset.Unspecified) { prev = null; continue }
        prev?.let { drawLine(color, it, pt, strokeWidth = width) }
        prev = pt
    }
}
