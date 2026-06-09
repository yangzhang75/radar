package com.shipradar.app.input

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import com.shipradar.contract.TrackedTarget
import com.shipradar.uicore.ppi.PpiOrientation
import com.shipradar.uicore.ppi.PpiProjection
import com.shipradar.uicore.ppi.RangeModel
import com.shipradar.uicore.ppi.ScreenPoint
import com.shipradar.util.Angles
import kotlin.math.cos
import kotlin.math.sin

/**
 * Renders the interaction graphics owned by this layer: EBL lines, VRM circles, PI lines and the
 * selection marker. These are "navigation tools" which IEC 62388 §9.3.1 requires to be presented
 * with their relevant symbols **according to IEC 62288**, with a numerical readout per active tool.
 *
 * Symbol glyphs / exact colours: IEC 62288 §6 + IHO S-52 presentation library (not in the project
 * standards set yet) — drawn here as functional, distinguishable strokes.
 * TODO(待标准: IEC 62288 §6 导航工具符号 / S-52 颜色) replace with the standard symbol set + palette.
 * The numerical read-outs (range/bearing) live in [InteractionModel] (cursorReadout, VRM.rangeNm,
 * EBL.bearingDeg, PI range/bearing) for the data-bar / dialogue area (T2.7) to display.
 */
@Composable
internal fun InputOverlay(
    model: InteractionModel,
    center: Offset,
    radiusPx: Float,
    orientation: PpiOrientation,
    rangeScaleNm: Double,
    ownHeadingDeg: Double?,
    targets: List<TrackedTarget>,
) {
    Canvas(Modifier.fillMaxSize()) {
        val projection = PpiProjection.create(
            center = ScreenPoint(center.x.toDouble(), center.y.toDouble()),
            radiusPx = radiusPx.toDouble(),
            orientation = orientation,
            headingDeg = ownHeadingDeg,
            courseDeg = null,
        )
        val heading = ownHeadingDeg ?: 0.0

        // VRMs — concentric range circles about the CCRP (§9.5).
        model.vrms.filter { it.enabled }.forEach { vrm ->
            val rPx = RangeModel.rangeNmToPx(vrm.rangeNm, rangeScaleNm, radiusPx.toDouble())
            if (rPx in 0.0..(radiusPx.toDouble())) {
                drawCircle(VRM_COLOR, rPx.toFloat(), center, style = Stroke(width = TOOL_STROKE))
            }
        }

        // EBLs — radial bearing lines from the CCRP to the periphery (§9.6).
        model.ebls.filter { it.enabled }.forEach { ebl ->
            val bowRel = when (ebl.reference) {
                BearingReference.RELATIVE -> ebl.bearingDeg
                BearingReference.TRUE -> ebl.bearingDeg - heading
            }
            val end = projection.polarToScreen(bowRel, 1.0)
            drawLine(EBL_COLOR, center, end.toOffset(), strokeWidth = TOOL_STROKE)
        }

        // PI lines — straight lines at a true bearing, offset perpendicular by range (§9.9).
        if (model.piGroupVisible) {
            model.piLines.filter { it.enabled }.forEach { pi ->
                drawPiLine(projection, pi, heading, rangeScaleNm, radiusPx.toDouble())
            }
        }

        // Selection marker — ring around the picked target (§9.7.3).
        model.selectedTargetId?.let { id ->
            targets.firstOrNull { it.id == id }?.let { t ->
                val bowRel = if (t.trueBearing) t.bearingDeg - heading else t.bearingDeg
                val frac = RangeModel.rangeNmToFraction(t.rangeNm, rangeScaleNm)
                val p = projection.polarToScreen(bowRel, frac).toOffset()
                drawCircle(SELECT_COLOR, SELECT_RADIUS, p, style = Stroke(width = TOOL_STROKE))
            }
        }
    }
}

private fun DrawScope.drawPiLine(
    projection: PpiProjection,
    pi: PiLine,
    heading: Double,
    rangeScaleNm: Double,
    radiusPx: Double,
) {
    val bowRel = pi.bearingDeg - heading
    val dirDeg = Angles.normalizeDeg(bowRel + projection.displayRotationDeg) // screen angle of the line
    val normalDeg = Angles.normalizeDeg(dirDeg + 90.0)
    val offsetPx = RangeModel.rangeNmToPx(pi.rangeNm, rangeScaleNm, radiusPx)
    // Foot of the perpendicular from the CCRP onto the line.
    val foot = projection.screenAngleToPoint(normalDeg, offsetPx).toOffset()
    val dir = Offset(sinDeg(dirDeg), -cosDeg(dirDeg))
    val half = (pi.truncateNm?.let { RangeModel.rangeNmToPx(it, rangeScaleNm, radiusPx) } ?: (radiusPx * 2.0)).toFloat()
    drawLine(PI_COLOR, foot - dir * half, foot + dir * half, strokeWidth = TOOL_STROKE)
}

private fun sinDeg(deg: Double): Float = sin(Math.toRadians(deg)).toFloat()
private fun cosDeg(deg: Double): Float = cos(Math.toRadians(deg)).toFloat()
private fun ScreenPoint.toOffset(): Offset = Offset(x.toFloat(), y.toFloat())

// Functional, distinguishable tool colours — pending the IEC 62288 / S-52 navigation-tool palette.
private val EBL_COLOR = Color(0xFF00E5FF)
private val VRM_COLOR = Color(0xFF00E5FF)
private val PI_COLOR = Color(0xFFFFD54F)
private val SELECT_COLOR = Color(0xFF69F0AE)
private const val TOOL_STROKE = 2f
private const val SELECT_RADIUS = 18f
