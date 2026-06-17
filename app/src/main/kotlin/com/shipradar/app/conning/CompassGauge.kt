package com.shipradar.app.conning

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.shipradar.app.framework.OpenBridge
import kotlin.math.cos
import kotlin.math.min
import kotlin.math.sin

/**
 * OpenBridge `<obc-compass>` re-drawn in Compose Canvas (DISP / conning). Faithful to the published
 * `@oicl/openbridge-webcomponents` compass: a triple-ring watch face with cardinal tickmarks, a solid
 * **HDG** arrow and a hollow **COG** arrow rotating over a north-up dial, a centred own-ship silhouette,
 * and a rate-of-turn dot. Colours come from the OpenBridge instrument tokens ([OpenBridge.colors]) so the
 * gauge tracks day / dusk / night exactly like the JRC RADAR reference (blue by day/dusk, teal at night).
 *
 * Geometry mirrors the component's 512×512 base: ring radii are taken as fractions of that base and
 * scaled to the actual draw size. North-up only (the reference's default `CompassDirection.NorthUp`):
 * the ring is fixed and the arrows rotate, which is the bridge-standard radar orientation.
 *
 * Pure presentation of contract values (HDG/COG/ROT from [com.shipradar.contract.OwnShipData]); no
 * formatting logic beyond rounding for the centre read-out.
 */
@Composable
fun CompassGauge(
    headingDeg: Double?,
    cogDeg: Double?,
    rotDegMin: Double?,
    modifier: Modifier = Modifier,
) {
    val c = OpenBridge.colors
    val measurer = rememberTextMeasurer()
    val labelStyle = TextStyle(
        color = c.instrumentRegularSecondary,
        fontSize = 13.sp,
        fontWeight = FontWeight.Bold,
        fontFamily = FontFamily.Default,
    )
    val readoutStyle = TextStyle(
        color = c.instrumentEnhancedPrimary,
        fontSize = 22.sp,
        fontWeight = FontWeight.Bold,
        fontFamily = FontFamily.Monospace,
    )
    val readoutLabelStyle = TextStyle(
        color = c.instrumentRegularSecondary,
        fontSize = 10.sp,
        fontWeight = FontWeight.Medium,
        fontFamily = FontFamily.Default,
    )

    Box(modifier.fillMaxWidth().aspectRatio(1f), contentAlignment = Alignment.Center) {
        Canvas(Modifier.fillMaxWidth().aspectRatio(1f)) {
            val cx = size.width / 2f
            val cy = size.height / 2f
            // 512-base radii projected onto the actual size (leave a small margin for labels).
            val base = min(size.width, size.height) / 2f
            val rOuter = base * 0.96f      // outer scale ring
            val rScaleIn = base * 0.84f    // inner edge of the scale band
            val rFace = base * 0.80f       // dial face
            val rArrowTip = base * 0.30f   // HDG/COG arrow tip (toward centre)
            val rArrowBase = base * 0.78f  // arrow base near the rim

            // --- dial face + triple ring (frame tokens) ---
            drawCircle(c.instrumentFrameSecondary, radius = rFace, center = Offset(cx, cy))
            drawCircle(c.instrumentFrameTertiary, radius = rOuter, center = Offset(cx, cy), style = Stroke(width = base * 0.012f))
            drawCircle(c.instrumentFrameTertiary, radius = rScaleIn, center = Offset(cx, cy), style = Stroke(width = base * 0.008f))

            // --- tickmarks every 10°, longer every 30°, longest at the four cardinals ---
            for (deg in 0 until 360 step 10) {
                val cardinal = deg % 90 == 0
                val medium = deg % 30 == 0
                val len = when {
                    cardinal -> base * 0.12f
                    medium -> base * 0.085f
                    else -> base * 0.05f
                }
                val w = if (cardinal) base * 0.016f else base * 0.008f
                val col = if (cardinal) c.instrumentRegularSecondary else c.instrumentFrameTertiary
                val p1 = polar(cx, cy, rOuter, deg.toDouble())
                val p2 = polar(cx, cy, rOuter - len, deg.toDouble())
                drawLine(col, p1, p2, strokeWidth = w)
            }

            // --- COG arrow (hollow) under HDG ---
            cogDeg?.let { drawArrow(it, rArrowTip, rArrowBase, base, c.instrumentEnhancedSecondary, filled = false) }
            // --- HDG arrow (solid) ---
            headingDeg?.let { drawArrow(it, rArrowTip, rArrowBase, base, c.instrumentEnhancedSecondary, filled = true) }

            // --- rate-of-turn dot on the inner ring (right = starboard turn) ---
            rotDegMin?.let { rot ->
                val clamped = rot.coerceIn(-30.0, 30.0)
                val ang = (headingDeg ?: 0.0) + clamped // dot offset from heading by ROT magnitude
                val p = polar(cx, cy, rScaleIn - base * 0.06f, ang)
                drawCircle(c.instrumentEnhancedPrimary, radius = base * 0.03f, center = p)
            }

            // --- own-ship silhouette at centre, rotated to heading (OB vessel image transform) ---
            val ship = Path().apply {
                moveTo(cx, cy - base * 0.18f)
                lineTo(cx + base * 0.08f, cy + base * 0.14f)
                lineTo(cx, cy + base * 0.06f)
                lineTo(cx - base * 0.08f, cy + base * 0.14f)
                close()
            }
            rotate(degrees = (headingDeg ?: 0.0).toFloat(), pivot = Offset(cx, cy)) {
                drawPath(ship, c.instrumentRegularSecondary)
            }

            // --- cardinal labels N/E/S/W just inside the scale band ---
            val labels = listOf(0 to "N", 90 to "E", 180 to "S", 270 to "W")
            for ((deg, txt) in labels) {
                val layout = measurer.measure(txt, labelStyle)
                val p = polar(cx, cy, rScaleIn - base * 0.13f, deg.toDouble())
                drawText(layout, topLeft = Offset(p.x - layout.size.width / 2f, p.y - layout.size.height / 2f))
            }

            // --- centre HDG read-out (big enhanced numerals + label) ---
            val hdgTxt = headingDeg?.let { "%03d".format(((it % 360 + 360) % 360).toInt()) } ?: "---"
            val ro = measurer.measure("$hdgTxt°", readoutStyle)
            drawText(ro, topLeft = Offset(cx - ro.size.width / 2f, cy + base * 0.30f))
            val rl = measurer.measure("HDG", readoutLabelStyle)
            drawText(rl, topLeft = Offset(cx - rl.size.width / 2f, cy + base * 0.30f + ro.size.height))
        }
    }
}

/** Point on the dial at compass bearing [deg] (0° = up/north, clockwise) at radius [r] from (cx,cy). */
private fun polar(cx: Float, cy: Float, r: Float, deg: Double): Offset {
    val rad = Math.toRadians(deg)
    return Offset(cx + r * sin(rad).toFloat(), cy - r * cos(rad).toFloat())
}

/**
 * Draw an HDG/COG arrow: a slim chevron whose tip sits at [rTip] from centre and whose base spans the rim
 * at [rBase], rotated to compass bearing [deg]. [filled] = solid (HDG); otherwise a 2px outline (COG).
 */
private fun DrawScope.drawArrow(
    deg: Double,
    rTip: Float,
    rBase: Float,
    base: Float,
    color: Color,
    filled: Boolean,
) {
    val cx = size.width / 2f
    val cy = size.height / 2f
    val halfWidthDeg = 3.5 // half angular width of the arrow base
    val tip = polar(cx, cy, rTip, deg)
    val left = polar(cx, cy, rBase, deg - halfWidthDeg)
    val right = polar(cx, cy, rBase, deg + halfWidthDeg)
    val notch = polar(cx, cy, rBase - base * 0.10f, deg) // inward notch at the base centre
    val path = Path().apply {
        moveTo(tip.x, tip.y)
        lineTo(right.x, right.y)
        lineTo(notch.x, notch.y)
        lineTo(left.x, left.y)
        close()
    }
    if (filled) drawPath(path, color)
    else drawPath(path, color, style = Stroke(width = base * 0.014f))
}
