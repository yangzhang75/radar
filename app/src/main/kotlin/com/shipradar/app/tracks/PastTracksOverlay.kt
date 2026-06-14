package com.shipradar.app.tracks

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.DrawScope
import com.shipradar.contract.OwnShipData
import com.shipradar.uicore.ppi.PpiOrientation
import com.shipradar.uicore.ppi.PpiProjection
import com.shipradar.uicore.ppi.ScreenPoint

/** Default faint past-position dot — neutral light grey, distinct from target symbols, visible on the
 * dark radar background (IEC 62388 §11.2.2.2 j → IEC 62288 §4.5.1). Override per day/dusk/night via
 * [PastTracksOverlay]'s `dotColor` (palette switching is the framework's job, not this overlay's). */
val DEFAULT_TRACK_DOT: Color = Color(0xFFB6C2CC)

/**
 * W7-C — past-tracks (past-position) Canvas overlay for the PPI. Self-contained: it consumes a
 * [TrackHistory.snapshot] and the current PPI viewport + own ship, and plots each target's equally
 * time-spaced past positions as faint dots. Independent of the existing TargetOverlay's trails.
 *
 * Projection reuses the (read-only) [PpiProjection] from `ui-core` — same conventions as every other
 * PPI graphic (0 = bow, clockwise; +x right, +y down). Marks are north-referenced and re-projected
 * each frame against the current heading, so they stay azimuth-stabilised in NORTH_UP/COURSE_UP.
 *
 * @param snapshot per-target marks (oldest → newest), from [TrackHistory.snapshot].
 * @param ownShip current own-ship state (heading/course drive the orientation projection).
 * @param center CCRP screen position (px).
 * @param radiusPx operational-area radius (px) at which [rangeScaleNm] maps to range fraction 1.0.
 * @param rangeScaleNm selected range scale (NM); marks beyond it are clipped.
 * @param orientation PPI orientation; falls back to HEAD_UP if a stabilised mode lacks heading/course
 *   (IEC 62388 §10.4.4.1).
 * @param dotColor base dot colour (alpha is modulated per mark age).
 * @param dotRadiusPx dot radius (px).
 */
@Composable
fun PastTracksOverlay(
    snapshot: Map<String, List<TrackPoint>>,
    ownShip: OwnShipData,
    center: Offset,
    radiusPx: Float,
    rangeScaleNm: Double,
    orientation: PpiOrientation,
    modifier: Modifier = Modifier,
    dotColor: Color = DEFAULT_TRACK_DOT,
    dotRadiusPx: Float = 2.5f,
) {
    if (snapshot.isEmpty() || radiusPx <= 0f || rangeScaleNm <= 0.0) return
    Canvas(modifier.fillMaxSize()) {
        drawPastTracks(snapshot, ownShip, center, radiusPx, rangeScaleNm, orientation, dotColor, dotRadiusPx)
    }
}

/** The plotting math, factored out of the [Canvas] lambda. */
internal fun DrawScope.drawPastTracks(
    snapshot: Map<String, List<TrackPoint>>,
    ownShip: OwnShipData,
    center: Offset,
    radiusPx: Float,
    rangeScaleNm: Double,
    orientation: PpiOrientation,
    dotColor: Color,
    dotRadiusPx: Float,
) {
    val eff = effectiveOrientation(orientation, ownShip)
    val proj = PpiProjection.create(
        center = ScreenPoint(center.x.toDouble(), center.y.toDouble()),
        radiusPx = radiusPx.toDouble(),
        orientation = eff,
        headingDeg = ownShip.headingDeg,
        courseDeg = ownShip.cogDeg,
    )
    val headingRef = ownShip.headingDeg ?: 0.0
    for ((_, points) in snapshot) {
        val n = points.size
        points.forEachIndexed { i, p ->
            val frac = p.rangeNm / rangeScaleNm
            if (frac > 1.0) return@forEachIndexed // outside operational area — clip
            val bowRel = p.trueBearingDeg - headingRef
            val sp = proj.polarToScreen(bowRel, frac)
            // Oldest faintest → newest fullest: aids reading direction of motion while staying
            // distinguishable from the live target symbol (§11.2.2.2 j / IEC 62288 §4.5.1).
            val alpha = if (n <= 1) 0.85f else (0.35f + 0.5f * i / (n - 1)).coerceIn(0f, 1f)
            drawCircle(
                color = dotColor.copy(alpha = alpha),
                radius = dotRadiusPx,
                center = Offset(sp.x.toFloat(), sp.y.toFloat()),
            )
        }
    }
}

/** A stabilised orientation falls back to HEAD_UP when its required sensor data is missing
 * (IEC 62388 §10.4.4.1 — head-up is the fallback on heading/course loss). */
internal fun effectiveOrientation(orientation: PpiOrientation, ownShip: OwnShipData): PpiOrientation = when {
    orientation == PpiOrientation.NORTH_UP && ownShip.headingDeg == null -> PpiOrientation.HEAD_UP
    orientation == PpiOrientation.COURSE_UP &&
        (ownShip.headingDeg == null || ownShip.cogDeg == null) -> PpiOrientation.HEAD_UP
    else -> orientation
}
