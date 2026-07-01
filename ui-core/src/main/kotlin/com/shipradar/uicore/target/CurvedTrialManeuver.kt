package com.shipradar.uicore.target

import com.shipradar.contract.OwnShipData
import kotlin.math.abs
import kotlin.math.min

/**
 * Own-ship manoeuvring characteristics for the curved trial manoeuvre (A.823 §3.7.2 / §4.2.7.2 — the
 * OPTIONAL part that models the vessel's own dynamics, not fixed by the standard: vessel/installation data).
 *
 * @property rateOfTurnDegPerSec steady rate of turn the vessel achieves for the trial (deg/s).
 * @property accelKnPerSec       rate of speed change toward the trial speed (kn/s).
 */
data class ShipDynamics(
    val rateOfTurnDegPerSec: Double = 3.0,
    val accelKnPerSec: Double = 0.05,
) {
    init { require(rateOfTurnDegPerSec > 0 && accelKnPerSec > 0) { "dynamics must be positive" } }
}

/**
 * Trial manoeuvre that models own-ship **turning and acceleration dynamics** (A.823 §3.7.2, OPTIONAL) —
 * a numerical integration of the curved own-ship trajectory, versus [InstantTrialManeuverSimulator]'s
 * step change. During [TrialManeuverRequest.delaySec] own ship holds present COG/SOG; then it turns toward
 * the trial course at [ShipDynamics.rateOfTurnDegPerSec] and ramps to the trial speed at
 * [ShipDynamics.accelKnPerSec], while each target holds its course/speed. The predicted CPA/TCPA is the
 * minimum own-target range over the integrated trajectory. Pure and deterministic.
 */
class CurvedTrialManeuverSimulator(
    private val dynamics: ShipDynamics = ShipDynamics(),
    private val stepSec: Double = 2.0,
    private val horizonSec: Double = 30.0 * 60.0,
) : TrialManeuverSimulator {

    override fun simulate(
        ownShip: OwnShipData,
        targets: List<com.shipradar.contract.TrackedTarget>,
        request: TrialManeuverRequest,
        criteria: DangerCriteria,
    ): TrialManeuverResult {
        val ownCog = ownShip.cogDeg ?: ownShip.headingDeg
        val ownSog = ownShip.sogKn
        val outcomes = targets.mapNotNull { t ->
            val p0 = Geometry.relativePosition(t, ownShip) ?: return@mapNotNull null
            val tv = Geometry.targetVelocity(t) ?: return@mapNotNull null
            val cog = ownCog ?: return@mapNotNull null
            val sog = ownSog ?: return@mapNotNull null

            var own = Vec2(0.0, 0.0)          // own ship starts at origin (NE plane, NM)
            var course = cog
            var speed = sog
            var minRange = p0.norm()          // current range (t = 0)
            var minT = 0.0
            var tSec = 0.0
            while (tSec < horizonSec) {
                val next = tSec + stepSec
                if (tSec >= request.delaySec) {
                    course = stepAngle(course, request.newCourseDeg, dynamics.rateOfTurnDegPerSec * stepSec)
                    speed = stepScalar(speed, request.newSpeedKn, dynamics.accelKnPerSec * stepSec)
                }
                own += Vec2.ofBearing(course, speed) * (stepSec / 3600.0)  // integrate own position (NM)
                val target = p0 + tv * (next / 3600.0)                     // target holds course/speed
                val range = (target - own).norm()
                if (range < minRange) { minRange = range; minT = next }
                tSec = next
            }
            val dangerous = minRange <= criteria.safeCpaNm && minT <= criteria.safeTcpaSec
            TrialTargetOutcome(
                targetId = t.id,
                trialCpaNm = minRange,
                trialTcpaSec = if (minT > 0.0) minT else null,
                dangerous = dangerous,
            )
        }
        return TrialManeuverResult(request, outcomes)
    }

    /** Move [from] toward [to] by at most [maxStep] degrees, taking the shorter direction. */
    private fun stepAngle(from: Double, to: Double, maxStep: Double): Double {
        val diff = Geometry.normalizeDeg(to - from).let { if (it > 180.0) it - 360.0 else it } // [-180,180]
        val step = diff.coerceIn(-maxStep, maxStep)
        return Geometry.normalizeDeg(from + step)
    }

    /** Move [from] toward [to] by at most [maxStep]. */
    private fun stepScalar(from: Double, to: Double, maxStep: Double): Double {
        val diff = to - from
        return from + (if (abs(diff) <= maxStep) diff else min(maxStep, abs(diff)) * (if (diff >= 0) 1.0 else -1.0))
    }
}
