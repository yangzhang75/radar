package com.shipradar.app.input

import com.shipradar.contract.TrackedTarget
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.sin

/**
 * Snaps the EBL/VRM measurement tools onto the nearest tracked target — a standard radar measurement aid
 * (lay the EBL/VRM roughly on a contact, snap to read its exact bearing/range). Pure geometry over the
 * contract [TrackedTarget] list; the controller binds it to a gesture/key.
 *
 * Works in the true-bearing NE plane; only true-bearing targets are considered (radar tracks and AIS are
 * true-referenced). The caller converts an EBL's relative bearing to true (using own heading) before/after.
 */
object TargetSnap {

    private fun polar(trueBearingDeg: Double, rangeNm: Double): Pair<Double, Double> {
        val rad = Math.toRadians(trueBearingDeg)
        return rangeNm * sin(rad) to rangeNm * cos(rad)
    }

    /**
     * The target nearest to the polar reference point ([refTrueBearingDeg], [refRangeNm]) within [gateNm],
     * or null if none qualifies. Distance is the straight-line NE-plane separation in nautical miles.
     */
    fun nearestTo(
        targets: List<TrackedTarget>,
        refTrueBearingDeg: Double,
        refRangeNm: Double,
        gateNm: Double = 1.0,
    ): TrackedTarget? {
        val (rx, ry) = polar(refTrueBearingDeg, refRangeNm)
        return targets.asSequence()
            .filter { it.trueBearing }
            .map { t ->
                val (tx, ty) = polar(t.bearingDeg, t.rangeNm)
                t to hypot(tx - rx, ty - ry)
            }
            .filter { it.second <= gateNm }
            .minByOrNull { it.second }
            ?.first
    }
}
