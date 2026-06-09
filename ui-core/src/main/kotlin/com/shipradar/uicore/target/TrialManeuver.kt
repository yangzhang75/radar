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
 * This satisfies the MANDATORY part of A.823 §3.7 / GB 11711-2002 §4.2.7: simulate the effect on all
 * tracked targets, with/without time delay (§3.7.1/§4.2.7.1 — [TrialManeuverRequest.delaySec]), without
 * interrupting tracking (only copies are propagated), cancellable at any time (§3.7.3/§4.2.7.3 — this is
 * stateless, so "cancel" = simply stop calling it). The trial state is surfaced via
 * [TargetStatus.TEST_MANEUVER]; per GB §4.2.7.1 / Appendix B symbol 10 the screen indication is the
 * letter **"T"** (rendered by the overlay layer).
 *
 * It does **NOT** model own ship's turning dynamics — that part of §3.7.2/§4.2.7.2 is explicitly
 * OPTIONAL ("当试操船技术包含对本船机动特性的模拟时…", "if provided") and needs ship-specific parameters
 * NOT fixed by A.823/GB 11711 (they are vessel/installation data). Documented gap (see delivery report);
 * to add the curved trajectory the integrator must supply:
 *   - rate of turn (deg/s) or the advance/transfer turning-circle for the trial speed,
 *   - acceleration/deceleration to the trial speed,
 *   - whether the trial timer is operator- or auto-stepped.
 * Note A.823 §3.8.4/§4.2.8.4 ("own-ship manoeuvre" = course change of ±45° in 1 min; motion trend within
 * 1 min, predicted motion within 3 min) bounds post-manoeuvre *settling*, which is again a tracking-layer
 * timing property, not part of this analytic projection.
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
