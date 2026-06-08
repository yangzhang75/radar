package com.shipradar.uicore.target

import com.shipradar.contract.OwnShipData
import com.shipradar.contract.TargetStatus
import com.shipradar.contract.TrackedTarget

/**
 * A proposed own-ship avoiding action to evaluate (IMO A.823(19) §3.7, mandatory for IEC 62388 CAT 1).
 *
 * @property newCourseDeg Trial course over ground after the manoeuvre (deg, clockwise from North).
 * @property newSpeedKn   Trial speed over ground after the manoeuvre (knots).
 * @property delaySec     Delay before the manoeuvre starts; own ship holds present motion until then
 *                        (A.823 §3.7.1 "with or without time delay before manoeuvre").
 */
data class TrialManeuverRequest(
    val newCourseDeg: Double,
    val newSpeedKn: Double,
    val delaySec: Double = 0.0,
) {
    init { require(delaySec >= 0) { "delaySec must be >= 0" } }
}

/**
 * Per-target outcome of a trial manoeuvre: the predicted CPA/TCPA if own ship executes the trial action.
 * @property trialCpaNm   Predicted CPA after the manoeuvre (NM).
 * @property trialTcpaSec Time to that CPA measured **from now** (= [TrialManeuverRequest.delaySec] plus the
 *                        time-to-CPA from the manoeuvre instant); null if no relative motion after.
 * @property dangerous    Whether the post-manoeuvre solution still breaches the operator CPA/TCPA limits.
 */
data class TrialTargetOutcome(
    val targetId: String,
    val trialCpaNm: Double,
    val trialTcpaSec: Double?,
    val dangerous: Boolean,
)

/** Result of evaluating a trial manoeuvre across all tracked targets. */
data class TrialManeuverResult(
    val request: TrialManeuverRequest,
    val outcomes: List<TrialTargetOutcome>,
) {
    /** True if any target would still be dangerous after the trial action — i.e. the action is unsafe. */
    val anyStillDangerous: Boolean get() = outcomes.any { it.dangerous }
}

/** Trial-manoeuvre simulator (A.823 §3.7). */
interface TrialManeuverSimulator {
    fun simulate(
        ownShip: OwnShipData,
        targets: List<TrackedTarget>,
        request: TrialManeuverRequest,
        criteria: DangerCriteria = DangerCriteria(),
    ): TrialManeuverResult
}

/**
 * Instantaneous-manoeuvre trial: own ship holds present COG/SOG for [TrialManeuverRequest.delaySec],
 * then **instantly** adopts the trial course/speed; targets are assumed to hold course/speed throughout
 * (A.823 §3.7.1 — the simulation must not interrupt live tracking; the live data is untouched here, only
 * a copy is propagated).
 *
 * This is a correct first-order solution and satisfies the core §3.7 requirement. It does **NOT** model
 * own ship's turning dynamics.
 *
 * TODO(待标准 A.823 §3.7.2): the optional realistic model — "simulation of own ship's manoeuvring
 * characteristics" — needs ship-specific parameters not yet supplied:
 *   - rate of turn (deg/s) or the advance/transfer turning-circle for the trial speed,
 *   - acceleration/deceleration to the trial speed,
 *   - whether the trial symbol/"T" indication and trial timer are operator- or auto-stepped.
 * These are vessel/installation parameters; list them in the delivery report for the integrator to
 * provide, then replace the instantaneous step with the curved trajectory.
 */
class InstantTrialManeuverSimulator : TrialManeuverSimulator {

    override fun simulate(
        ownShip: OwnShipData,
        targets: List<TrackedTarget>,
        request: TrialManeuverRequest,
        criteria: DangerCriteria,
    ): TrialManeuverResult {
        val ownVelNow = Geometry.ownVelocity(ownShip)
        val newOwnVel = Vec2.ofBearing(request.newCourseDeg, request.newSpeedKn)
        val delayHours = request.delaySec / 3600.0

        val outcomes = targets.mapNotNull { t ->
            val relPosNow = Geometry.relativePosition(t, ownShip) ?: return@mapNotNull null
            val tgtVel = Geometry.targetVelocity(t) ?: return@mapNotNull null
            if (ownVelNow == null) return@mapNotNull null

            // Propagate relative position to the manoeuvre instant under present motion.
            val relPosAtManeuver = relPosNow + (tgtVel - ownVelNow) * delayHours
            // After the manoeuvre, relative velocity uses the new own-ship velocity.
            val sol = CpaTcpaCalculator.fromRelativeMotion(relPosAtManeuver, tgtVel - newOwnVel)
            val tcpaFromNow = sol.tcpaSec?.let { it + request.delaySec }
            TrialTargetOutcome(
                targetId = t.id,
                trialCpaNm = sol.cpaNm,
                trialTcpaSec = tcpaFromNow,
                dangerous = DangerClassifier.isDangerous(sol, criteria),
            )
        }
        return TrialManeuverResult(request, outcomes)
    }

    /**
     * Marks targets with [TargetStatus.TEST_MANEUVER] so the render layer can draw the trial ("T") symbol
     * (A.823 §3.7.1: "simulation shall be indicated with the relevant symbol"). The live CPA/TCPA fields
     * are overwritten with the trial values for the trial view only.
     */
    fun applyTrialView(
        ownShip: OwnShipData,
        targets: List<TrackedTarget>,
        request: TrialManeuverRequest,
        criteria: DangerCriteria = DangerCriteria(),
    ): List<TrackedTarget> {
        val byId = simulate(ownShip, targets, request, criteria).outcomes.associateBy { it.targetId }
        return targets.map { t ->
            val o = byId[t.id] ?: return@map t
            t.copy(
                cpaNm = o.trialCpaNm,
                tcpaSec = o.trialTcpaSec,
                dangerous = o.dangerous,
                status = TargetStatus.TEST_MANEUVER,
            )
        }
    }
}
