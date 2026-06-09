package com.shipradar.app.target

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.withFrameMillis
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.TextMeasurer
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.sp
import com.shipradar.contract.OwnShipData
import com.shipradar.contract.TrackedTarget
import com.shipradar.uicore.color.ColorMapper
import com.shipradar.uicore.ppi.PpiOrientation
import com.shipradar.uicore.ppi.PpiProjection
import com.shipradar.uicore.ppi.ScreenPoint
import com.shipradar.uicore.target.OverlayColors
import com.shipradar.uicore.target.OverlayConfig
import com.shipradar.uicore.target.OverlayProjector
import com.shipradar.uicore.target.SymbolShape
import com.shipradar.uicore.target.TargetScene
import com.shipradar.uicore.target.TargetSymbol
import com.shipradar.uicore.target.TargetTrailStore
import kotlinx.coroutines.flow.StateFlow
import kotlin.math.cos
import kotlin.math.min
import kotlin.math.sin

/**
 * T2.3r — Target/track overlay drawn **on top of** the PPI (T2.1r `com.shipradar.app.ppi`). Renders the
 * radar TT + AIS symbols, true/relative vectors, past-position trails, and the CPA/TCPA dangerous-target
 * red highlight, consuming the live [com.shipradar.contract.RadarDataBus] streams. This Composable is a
 * thin draw layer: all geometry/symbology/colour/LOD lives in the pure, unit-tested
 * `com.shipradar.uicore.target.OverlayProjector`; here we only translate its [TargetScene] into Canvas calls.
 *
 * Self-contained per the wave-3 contract (RadarScreen is orchestrator-owned). It sizes the PPI projection
 * to its own bounds, so it composes correctly over the PPI render surface filling the same Box.
 *
 * Symbology per IEC 62288 Ed.2 Annex A / MSC.191(79): circle = tracked radar target, triangle (oriented
 * to heading/COG) = AIS, red + flashing = dangerous (§5.6.3 / §4.7.2.1), broken ring = acquiring, cross =
 * lost, box = selected. Exact glyph dimensions are delegated to Annex A graphics — see delivery report.
 */
@Composable
fun TargetOverlay(
    targets: StateFlow<List<TrackedTarget>>,
    ownShip: StateFlow<OwnShipData>,
    rangeScaleNm: Double,
    modifier: Modifier = Modifier.fillMaxSize(),
    orientation: PpiOrientation = PpiOrientation.HEAD_UP,
    config: OverlayConfig = OverlayConfig(),
) {
    val targetList by targets.collectAsState()
    val ship by ownShip.collectAsState()

    // Accumulate past positions across emissions (A.823 §3.3.5). One-frame lag is acceptable for trails.
    val trailStore = remember(config.maxTrailPoints) { TargetTrailStore(config.maxTrailPoints) }
    LaunchedEffect(targetList, ship) { trailStore.record(targetList, ship) }

    val blink by rememberDangerBlink()
    val textMeasurer = rememberTextMeasurer()

    Canvas(modifier) {
        // Use the SHARED operational radius (reserves the bearing-scale margin) so targets align with
        // PpiView's range rings — NOT min(size)/2, which drew edge targets outside the circle.
        val radius = com.shipradar.app.ppi.PpiLayout.operationalRadiusPx(size.width, size.height, density).toDouble()
        if (radius <= 0.0) return@Canvas
        val projection = PpiProjection.create(
            center = ScreenPoint(size.width / 2.0, size.height / 2.0),
            radiusPx = radius,
            orientation = orientation,
            headingDeg = ship.headingDeg,
            courseDeg = ship.cogDeg,
        )
        val scene: TargetScene = OverlayProjector.project(
            targets = targetList,
            ownShip = ship,
            projection = projection,
            scaleNm = rangeScaleNm,
            config = config,
            trails = trailStore.snapshot(),
        )
        drawScene(scene, blink, textMeasurer, config.palette)
    }
}

private fun DrawScope.drawScene(scene: TargetScene, blinkOn: Boolean, tm: TextMeasurer, palette: ColorMapper.Palette) {
    // Order: trails (background) -> vectors -> symbols -> labels (foreground).
    for (tr in scene.trails) {
        val c = Color(tr.argb)
        for (p in tr.points) drawCircle(c, radius = 2.0f, center = p.offset())
    }
    for (v in scene.vectors) {
        drawLine(Color(v.argb), v.from.offset(), v.to.offset(), strokeWidth = 2.0f)
    }
    for (s in scene.symbols) drawSymbol(s, blinkOn)
    for (lb in scene.labels) {
        val style = TextStyle(color = Color(lb.argb), fontSize = 11.sp)
        // anchor slightly up-right of the symbol so the glyph stays clear.
        drawText(tm, lb.lines.joinToString("\n"), topLeft = Offset(lb.anchor.x.toFloat() + 8f, lb.anchor.y.toFloat() - 18f), style = style)
    }
}

private fun DrawScope.drawSymbol(s: TargetSymbol, blinkOn: Boolean) {
    val color = Color(s.argb)
    val r = s.radiusPx.toFloat()
    val center = s.at.offset()
    val stroke = if (s.dangerous) 3.0f else 1.6f

    when (s.shape) {
        SymbolShape.CIRCLE -> {
            if (s.acquiring) {
                // Broken ring for a target in acquisition state (IEC 62288 §5.6.3).
                drawCircle(color, r, center, style = Stroke(width = stroke, pathEffect = PathEffect.dashPathEffect(floatArrayOf(6f, 6f))))
            } else {
                drawCircle(color, r, center, style = Stroke(width = stroke))
            }
        }
        SymbolShape.TRIANGLE -> {
            val path = trianglePath(center, r, s.orientationScreenDeg)
            if (s.filled) drawPath(path, color) else drawPath(path, color, style = Stroke(width = stroke))
        }
    }

    // Dangerous: red is set in [s.argb]; add a flashing emphasis ring until acknowledged (§5.6.3).
    if (s.dangerous && blinkOn) drawCircle(Color(OverlayColors.DANGER), r + 4f, center, style = Stroke(width = 2.0f))

    // Lost target: overdraw a cross (IEC 62288 §5.6.4).
    if (s.lost) {
        val d = r * 0.8f
        drawLine(color, Offset(center.x - d, center.y - d), Offset(center.x + d, center.y + d), strokeWidth = 1.6f)
        drawLine(color, Offset(center.x - d, center.y + d), Offset(center.x + d, center.y - d), strokeWidth = 1.6f)
    }

    // Selected target: a surrounding box (IEC 62288 def 3.40).
    if (s.selected) {
        val b = r + 5f
        drawRectStroke(center.x - b, center.y - b, 2 * b, 2 * b, color)
    }
}

/** Isosceles triangle pointing along [orientationScreenDeg] (screen angle cw from up); apex-up if null. */
private fun trianglePath(center: Offset, r: Float, orientationScreenDeg: Double?): Path {
    val apex = orientationScreenDeg ?: 0.0
    fun pt(angleDeg: Double, radius: Float): Offset {
        val a = Math.toRadians(angleDeg)
        return Offset(center.x + radius * sin(a).toFloat(), center.y - radius * cos(a).toFloat())
    }
    return Path().apply {
        val a = pt(apex, r * 1.3f)            // apex (points to heading/COG)
        val b = pt(apex + 140.0, r)
        val c = pt(apex - 140.0, r)
        moveTo(a.x, a.y); lineTo(b.x, b.y); lineTo(c.x, c.y); close()
    }
}

private fun DrawScope.drawRectStroke(x: Float, y: Float, w: Float, h: Float, color: Color) {
    drawLine(color, Offset(x, y), Offset(x + w, y), strokeWidth = 1.4f)
    drawLine(color, Offset(x + w, y), Offset(x + w, y + h), strokeWidth = 1.4f)
    drawLine(color, Offset(x + w, y + h), Offset(x, y + h), strokeWidth = 1.4f)
    drawLine(color, Offset(x, y + h), Offset(x, y), strokeWidth = 1.4f)
}

private fun ScreenPoint.offset(): Offset = Offset(x.toFloat(), y.toFloat())

/** ~2 Hz blink flag for dangerous-target emphasis, driven by the frame clock (compose-runtime only). */
@Composable
private fun rememberDangerBlink(periodMs: Long = 500L): State<Boolean> {
    val state = remember { mutableStateOf(true) }
    LaunchedEffect(Unit) {
        var last = 0L
        while (true) {
            withFrameMillis { now ->
                if (last == 0L) last = now
                if (now - last >= periodMs) { state.value = !state.value; last = now }
            }
        }
    }
    return state
}
