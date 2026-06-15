package com.shipradar.app.chart

import android.graphics.BitmapFactory
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.clipPath
import androidx.compose.ui.graphics.drawscope.withTransform
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import com.shipradar.uicore.ppi.PpiOrientation
import com.shipradar.uicore.ppi.PpiProjection
import com.shipradar.uicore.ppi.ScreenPoint
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.roundToInt

/** 一张 OSM 瓦片 + 其地理范围。 */
private class Tile(val bmp: ImageBitmap, val latN: Double, val latS: Double, val lonW: Double, val lonE: Double) {
    val cLat get() = (latN + latS) / 2; val cLon get() = (lonW + lonE) / 2
}

/**
 * 海图/底图叠加 —— **真实 OpenStreetMap 光栅瓦片**(assets/tiles/),按本船位置/量程/方位投影、旋转、
 * 缩放、裁剪到操作圆内,叠经纬网格。这是**网上下载的真实地图**(OSM 公开瓦片)。
 * 注:OSM 一般底图,非认证 S-57 ENC;接入 S-57 后换数据源即可。
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
    val tiles = remember { loadTiles(ctx) }
    val proj = PpiProjection.create(
        ScreenPoint(center.x.toDouble(), center.y.toDouble()), radiusPx.toDouble(), orientation, headingDeg, courseDeg,
    )
    val cosLat = cos(Math.toRadians(ownLat))
    val pxPerNm = radiusPx / rangeScaleNm.toFloat()

    fun raw(lat: Double, lon: Double): Offset {
        val dLat = lat - ownLat; val dLon = (lon - ownLon) * cosLat
        val distNm = 60.0 * hypot(dLat, dLon)
        val bowRel = ((((Math.toDegrees(atan2(dLon, dLat))) - (headingDeg ?: 0.0)) % 360) + 360) % 360
        val sp = proj.polarToScreen(bowRel, distNm / rangeScaleNm)
        return Offset(sp.x.toFloat(), sp.y.toFloat())
    }
    // 屏上"正北方向"角度(度,从屏幕上方顺时针):由本船正北一小段投影得到 → 瓦片图按此旋转对齐。
    val nVec = raw(ownLat + 0.02, ownLon).let { it - raw(ownLat, ownLon) }
    val northAngleDeg = Math.toDegrees(atan2(nVec.x.toDouble(), -nVec.y.toDouble())).toFloat()

    Canvas(modifier.fillMaxSize()) {
        val circle = Path().apply { addOval(Rect(center.x - radiusPx, center.y - radiusPx, center.x + radiusPx, center.y + radiusPx)) }
        clipPath(circle) {
            for (t in tiles) {
                val c = raw(t.cLat, t.cLon)
                if (hypot((c.x - center.x).toDouble(), (c.y - center.y).toDouble()) > radiusPx * 2.0) continue // 远的跳过
                val wNm = (t.lonE - t.lonW) * cosLat * 60.0
                val hNm = (t.latN - t.latS) * 60.0
                val w = (wNm.toFloat() * pxPerNm * 1.02f) // 略放大消缝
                val h = (hNm.toFloat() * pxPerNm * 1.02f)
                if (w < 1f || h < 1f) continue
                withTransform({
                    translate(c.x, c.y)
                    rotate(northAngleDeg, pivot = Offset.Zero)
                }) {
                    drawImage(
                        image = t.bmp,
                        dstOffset = IntOffset((-w / 2f).roundToInt(), (-h / 2f).roundToInt()),
                        dstSize = IntSize(w.roundToInt(), h.roundToInt()),
                    )
                }
            }
        }
        drawGraticule(ownLat, ownLon, rangeScaleNm) { lat, lon ->
            val dLat = lat - ownLat; val dLon = (lon - ownLon) * cosLat
            if (60.0 * hypot(dLat, dLon) > rangeScaleNm * 1.02) Offset.Unspecified else raw(lat, lon)
        }
    }
}

// ---- 瓦片加载(z10 网格,assets/tiles/<x>_<y>.png)----
private const val Z = 10
private val XS = 858..860
private val YS = 420..422
private fun tileLonW(x: Int) = x.toDouble() / (1 shl Z) * 360.0 - 180.0
private fun tileLatN(y: Int) = Math.toDegrees(atan2(Math.sinh(Math.PI * (1 - 2.0 * y / (1 shl Z))), 1.0))

private fun loadTiles(ctx: android.content.Context): List<Tile> = runCatching {
    val out = ArrayList<Tile>()
    for (x in XS) for (y in YS) {
        val bmp = ctx.assets.open("tiles/${x}_${y}.png").use { BitmapFactory.decodeStream(it) } ?: continue
        out.add(Tile(bmp.asImageBitmap(), tileLatN(y), tileLatN(y + 1), tileLonW(x), tileLonW(x + 1)))
    }
    out
}.getOrDefault(emptyList())

private val GRID = Color(0x55A9C7D6)
private fun gridStepDeg(r: Double) = when { r <= 1.5 -> 0.02; r <= 6.0 -> 0.05; r <= 24.0 -> 0.2; else -> 0.5 }

private fun DrawScope.drawGraticule(ownLat: Double, ownLon: Double, rangeNm: Double, p: (Double, Double) -> Offset) {
    val span = rangeNm / 60.0 * 1.1
    val step = gridStepDeg(rangeNm)
    fun snap(v: Double) = (v / step).roundToInt() * step
    var lat = snap(ownLat - span)
    while (lat <= ownLat + span) {
        val pts = ArrayList<Offset>(); var lon = ownLon - span
        while (lon <= ownLon + span) { pts.add(p(lat, lon)); lon += step / 4 }; stroke(pts); lat += step
    }
    var lon = snap(ownLon - span)
    while (lon <= ownLon + span) {
        val pts = ArrayList<Offset>(); var la = ownLat - span
        while (la <= ownLat + span) { pts.add(p(la, lon)); la += step / 4 }; stroke(pts); lon += step
    }
}

private fun DrawScope.stroke(pts: List<Offset>) {
    var prev: Offset? = null
    for (pt in pts) { if (pt == Offset.Unspecified) { prev = null; continue }; prev?.let { drawLine(GRID, it, pt, 1f) }; prev = pt }
}

// atan2(sinh, 1) 用 Math.atan(Math.sinh) 等价但避免额外 import；这里用 atan2 形式上面已导入。
