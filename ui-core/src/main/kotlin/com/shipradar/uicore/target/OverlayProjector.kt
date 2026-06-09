package com.shipradar.uicore.target

import com.shipradar.contract.OwnShipData
import com.shipradar.contract.TargetSource
import com.shipradar.contract.TargetStatus
import com.shipradar.contract.TrackedTarget
import com.shipradar.uicore.ppi.PpiProjection
import com.shipradar.uicore.ppi.RangeModel
import com.shipradar.uicore.ppi.ScreenPoint
import kotlin.math.roundToInt

/**
 * Builds a [TargetScene] from the live target set, own ship and the PPI [PpiProjection] (T2.1 geometry).
 * Pure and platform-independent — the heart of T2.3r, fully unit-testable on the JVM.
 *
 * Reuses `com.shipradar.uicore.target` core logic (T2.3): [TargetVectors] for vector endpoints,
 * [Geometry] for NE↔polar projection, [CapacityMonitor] (CAP-01) for the LOD threshold, and the
 * contract's `dangerous`/`cpaNm`/`tcpaSec` fields (set upstream by the same core) for red highlighting
 * and labels. Symbology/colour follow IEC 62288 Annex A / MSC.191 (see [OverlayScene]).
 *
 * Coordinate handling: [PpiProjection.polarToScreen] takes a **bow-relative** azimuth and a range
 * fraction. A target's true bearing is converted to bow-relative with own-ship heading; NE vector/trail
 * points (true frame, from the core) are converted via their true bearing. Targets beyond the operational
 * circle are culled; a true-bearing target with no heading available is counted [TargetScene.unplaceable].
 */
object OverlayProjector {

    /** Symbol radius (px) as a fraction of the PPI radius, clamped to a sensible pixel range. */
    private const val SYMBOL_RADIUS_FRACTION = 0.018
    private const val SYMBOL_RADIUS_MIN_PX = 4.0
    private const val SYMBOL_RADIUS_MAX_PX = 14.0

    fun project(
        targets: List<TrackedTarget>,
        ownShip: OwnShipData,
        projection: PpiProjection,
        scaleNm: Double,
        config: OverlayConfig = OverlayConfig(),
        trails: Map<String, List<Vec2>> = emptyMap(),
    ): TargetScene {
        require(scaleNm > 0) { "scaleNm must be > 0" }
        val capacity = CapacityMonitor.evaluate(targets)
        val heading = ownShip.headingDeg
        val symbolR = (projection.radiusPx * SYMBOL_RADIUS_FRACTION)
            .coerceIn(SYMBOL_RADIUS_MIN_PX, SYMBOL_RADIUS_MAX_PX)

        // Detail-reduce sleeping AIS when the scene is dense, so ≥240 targets stay fluid.
        val dense = targets.size > config.sleepingDetailThreshold

        val symbols = ArrayList<TargetSymbol>(targets.size)
        val vectors = ArrayList<VectorGraphic>()
        val trailGraphics = ArrayList<TrailGraphic>()
        val labels = ArrayList<TargetLabel>()
        var culled = 0
        var unplaceable = 0

        for (t in targets) {
            val bowRel = bowRelativeAzimuth(t, heading)
            if (bowRel == null) { unplaceable++; continue }
            val frac = RangeModel.rangeNmToFraction(t.rangeNm, scaleNm)
            if (frac > 1.0) { culled++; continue } // outside the operational display circle
            val at = projection.polarToScreen(bowRel, frac)

            val sleeping = t.source == TargetSource.AIS_SLEEPING
            val detail = !(sleeping && dense) // sleeping AIS in a dense scene → bare position glyph
            val dangerous = t.dangerous
            val selected = config.selectedId != null && config.selectedId == t.id

            symbols += TargetSymbol(
                id = t.id,
                at = at,
                shape = shapeFor(t.source),
                argb = if (dangerous) OverlayColors.DANGER else OverlayColors.target(config.palette),
                radiusPx = symbolR,
                filled = t.source == TargetSource.AIS_ACTIVE,
                orientationScreenDeg = orientationScreenDeg(t, heading, projection),
                dangerous = dangerous,
                acquiring = t.status == TargetStatus.ACQUIRING,
                lost = t.status == TargetStatus.LOST,
                selected = selected,
                trialManeuver = t.status == TargetStatus.TEST_MANEUVER,
            )

            if (!detail) continue // sleeping-AIS LOD: skip vector/trail/label

            // Vector (A.823 §3.4.6) — only when the target has motion (sleeping AIS / no course skip).
            if (config.showVectors && !sleeping) {
                val mv = if (config.trueVectors) {
                    TargetVectors.trueVector(ownShip, t, config.vectorTimeMin)
                } else {
                    TargetVectors.relativeVector(ownShip, t, config.vectorTimeMin)
                }
                if (mv != null) {
                    val from = projectNe(mv.startNm, heading, scaleNm, projection)
                    val to = projectNe(mv.endNm, heading, scaleNm, projection)
                    if (from != null && to != null) {
                        vectors += VectorGraphic(t.id, from, to, OverlayColors.vector(config.palette, dangerous), config.trueVectors)
                    }
                }
            }

            // Past-position trail (A.823 §3.3.5).
            if (config.showTrails) {
                val pts = trails[t.id]
                if (pts != null && pts.size >= 2) {
                    val screenPts = pts.mapNotNull { projectNe(it, heading, scaleNm, projection) }
                    if (screenPts.size >= 2) trailGraphics += TrailGraphic(t.id, screenPts, OverlayColors.trail(config.palette))
                }
            }

            // Label: CPA/TCPA for dangerous or selected targets (A.823 §3.6.2.3/.4).
            if (config.showLabels && (dangerous || selected)) {
                labelFor(t)?.let { lines ->
                    labels += TargetLabel(t.id, at, lines, if (dangerous) OverlayColors.DANGER else OverlayColors.selection(config.palette))
                }
            }
        }

        return TargetScene(
            symbols = symbols,
            vectors = vectors,
            trails = trailGraphics,
            labels = labels,
            drawnCount = symbols.size,
            culledOffArea = culled,
            unplaceable = unplaceable,
            capacity = capacity,
        )
    }

    private fun shapeFor(source: TargetSource): SymbolShape = when (source) {
        TargetSource.RADAR_TT -> SymbolShape.CIRCLE
        TargetSource.AIS_ACTIVE, TargetSource.AIS_SLEEPING -> SymbolShape.TRIANGLE
    }

    /** Bow-relative azimuth (deg) for a target, or null if it can't be resolved (true bearing, no heading). */
    private fun bowRelativeAzimuth(t: TrackedTarget, headingDeg: Double?): Double? {
        return if (t.trueBearing) {
            val h = headingDeg ?: return null
            Geometry.normalizeDeg(t.bearingDeg - h)
        } else {
            Geometry.normalizeDeg(t.bearingDeg)
        }
    }

    /** Heading/COG of an AIS triangle as a screen angle (deg cw from up); null if course unknown. */
    private fun orientationScreenDeg(t: TrackedTarget, headingDeg: Double?, projection: PpiProjection): Double? {
        if (t.source == TargetSource.RADAR_TT) return null // circles have no orientation
        val course = t.courseDeg ?: return null
        val h = headingDeg ?: return null // course is true; need heading to get bow-relative
        val bowRel = Geometry.normalizeDeg(course - h)
        return Geometry.normalizeDeg(bowRel + projection.displayRotationDeg)
    }

    /** Project a true-frame NE point (NM, own ship origin) to a screen pixel; null if off-area/unplaceable. */
    private fun projectNe(ne: Vec2, headingDeg: Double?, scaleNm: Double, projection: PpiProjection): ScreenPoint? {
        val h = headingDeg ?: return null
        val rangeNm = ne.norm()
        val trueBrg = Geometry.bearingOf(ne)
        val bowRel = Geometry.normalizeDeg(trueBrg - h)
        val frac = RangeModel.rangeNmToFraction(rangeNm, scaleNm)
        // Vectors/trails may extend slightly past the ring; clamp visually rather than drop the whole line.
        return projection.polarToScreen(bowRel, frac.coerceAtMost(RangeModel.MAX_DISPLAYED_RANGE_FRACTION))
    }

    private fun labelFor(t: TrackedTarget): List<String>? {
        val lines = ArrayList<String>(2)
        t.cpaNm?.let { lines += "CPA ${fmt(it)} NM" }
        t.tcpaSec?.let { lines += "TCPA ${(it / 60.0).let(::fmt)} min" }
        return lines.ifEmpty { null }
    }

    private fun fmt(v: Double): String {
        val tenths = (v * 10.0).roundToInt()
        return "${tenths / 10}.${kotlin.math.abs(tenths % 10)}"
    }
}
