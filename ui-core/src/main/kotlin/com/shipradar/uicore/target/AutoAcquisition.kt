package com.shipradar.uicore.target

import com.shipradar.contract.OwnShipData

/**
 * A radar plot offered to the auto-acquisition logic — a candidate not yet under track.
 * @property relativeSpeedKn Relative speed estimate (knots); auto-acquisition is specified for relative
 *           speeds up to 100 kn (A.823 §3.2.1).
 */
data class RadarPlot(
    val id: String,
    val rangeNm: Double,
    val trueBearingDeg: Double,
    val relativeSpeedKn: Double? = null,
)

/**
 * An operator-defined range/bearing sector (A.823 §3.2.1 acquisition area; IEC 62388 / MSC.192(79)
 * §5.27 "automatic acquisition/activation zone"). Bearings are true degrees; the sector runs **clockwise**
 * from [fromBearingDeg] to [toBearingDeg] (wrapping through North if needed).
 *
 * @property suppress If true this is a *suppressed* area (A.823 §3.2.1): plots inside are NOT acquired.
 */
data class AcquisitionZone(
    val innerRangeNm: Double,
    val outerRangeNm: Double,
    val fromBearingDeg: Double,
    val toBearingDeg: Double,
    val suppress: Boolean = false,
) {
    init { require(outerRangeNm >= innerRangeNm) { "outerRangeNm must be >= innerRangeNm" } }

    /** True if a true bearing falls within the clockwise sector [fromBearingDeg]..[toBearingDeg]. */
    fun containsBearing(bearingDeg: Double): Boolean {
        val b = Geometry.normalizeDeg(bearingDeg)
        val from = Geometry.normalizeDeg(fromBearingDeg)
        val span = Geometry.normalizeDeg(toBearingDeg - fromBearingDeg)
        val rel = Geometry.normalizeDeg(b - from)
        return rel <= span
    }

    fun contains(rangeNm: Double, bearingDeg: Double): Boolean =
        rangeNm in innerRangeNm..outerRangeNm && containsBearing(bearingDeg)
}

/** What the auto-acquisition pass decided for one frame. */
data class AcquisitionDecision(
    /** Plots selected for new auto-acquisition this frame (capped by remaining radar-track capacity). */
    val toAcquire: List<RadarPlot>,
    /** Plots that fell in an active zone but were dropped because radar-track capacity is full. */
    val rejectedAtCapacity: List<RadarPlot>,
)

/** Automatic target acquisition (A.823 §3.2; IEC 62388 CAT 1 "Auto acquisition of targets: Yes"). */
interface AutoAcquisition {
    fun select(
        plots: List<RadarPlot>,
        zones: List<AcquisitionZone>,
        currentRadarTrackCount: Int,
        ownShip: OwnShipData,
        category: EquipmentCategory = EquipmentCategory.CAT_1,
    ): AcquisitionDecision
}

/**
 * Zone-based auto-acquisition placeholder: a plot is acquired if it lies inside at least one active
 * (non-suppress) [AcquisitionZone] and inside **no** suppressed zone, subject to the category's radar
 * tracked-target capacity ([EquipmentCategory.radarTracked]). Plots faster than [MAX_REL_SPEED_KN] are
 * skipped (A.823 §3.2.1 covers relative speeds up to 100 kn).
 *
 * TODO(待标准 A.823 §3.2.2 / §3.3.2): the *selection criteria* among eligible plots — which IMO requires
 * be described to the user and which govern real auto-acquisition quality — are not yet defined and need
 * the radar front-end's parameters:
 *   - minimum target size / SNR / plot-quality gate before a plot is acquirable,
 *   - tentative→firm track promotion rule (A.823 §3.3.3 "5 out of 10 scans"),
 *   - priority when more eligible plots than free capacity (nearest? fastest-closing? lowest TCPA?),
 *   - guard-zone vs auto-acquisition-zone distinction (the guard-zone alarm is A.823 §3.5.1).
 * List these in the delivery report; here we acquire all eligible plots up to capacity (nearest-first).
 */
class ZoneAutoAcquisition : AutoAcquisition {

    companion object {
        /** A.823 §3.2.1: acquisition specified for relative speeds up to 100 knots. */
        const val MAX_REL_SPEED_KN: Double = 100.0
    }

    override fun select(
        plots: List<RadarPlot>,
        zones: List<AcquisitionZone>,
        currentRadarTrackCount: Int,
        ownShip: OwnShipData,
        category: EquipmentCategory,
    ): AcquisitionDecision {
        val active = zones.filter { !it.suppress }
        val suppressed = zones.filter { it.suppress }

        val eligible = plots.filter { p ->
            val tooFast = (p.relativeSpeedKn ?: 0.0) > MAX_REL_SPEED_KN
            val inActive = active.any { it.contains(p.rangeNm, p.trueBearingDeg) }
            val inSuppressed = suppressed.any { it.contains(p.rangeNm, p.trueBearingDeg) }
            !tooFast && inActive && !inSuppressed
        }.sortedBy { it.rangeNm } // nearest-first; see TODO for the standard selection criteria

        val free = (category.radarTracked - currentRadarTrackCount).coerceAtLeast(0)
        return AcquisitionDecision(
            toAcquire = eligible.take(free),
            rejectedAtCapacity = eligible.drop(free),
        )
    }
}
