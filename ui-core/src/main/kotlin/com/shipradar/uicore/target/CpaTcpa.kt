package com.shipradar.uicore.target

import com.shipradar.contract.OwnShipData
import com.shipradar.contract.TrackedTarget

/**
 * Result of a closest-point-of-approach computation (A.823 §3.6.2.3/.4, GB 11711 corresponding clause).
 *
 * @property cpaNm   Predicted closest range, nautical miles (always >= 0).
 * @property tcpaSec Time to CPA, seconds. Negative => CPA already passed (target opening).
 *                   `null` => no relative motion (relative speed ~0): range is constant, TCPA undefined.
 * @property relativeSpeedKn   Magnitude of the relative-motion vector (knots).
 * @property relativeCourseDeg Direction of relative motion (deg, clockwise from North); `null` if rel speed ~0.
 */
data class CpaSolution(
    val cpaNm: Double,
    val tcpaSec: Double?,
    val relativeSpeedKn: Double,
    val relativeCourseDeg: Double?,
)

/**
 * CPA/TCPA from relative-motion geometry — the ARPA core (IMO A.823(19) §3.6, GB 11711-2002,
 * IEC 62388 §11.4 tracked-target data). Pure functions; no platform calls.
 *
 * Geometry: with the target at relative position `p` (NM, NE plane) moving at relative velocity `v`
 * (knots), the relative position at time `t` (hours) is `p + v*t`. The squared range
 * `|p + v*t|^2` is minimised at `t* = -(p·v)/(v·v)`; then `CPA = |p + v*t*|`.
 * TCPA is reported in seconds (`t* * 3600`).
 */
object CpaTcpaCalculator {

    /**
     * Below this relative speed (knots) the targets are treated as having no relative motion
     * (parallel courses at equal speed, or both effectively stationary relative to each other):
     * range is constant, TCPA is undefined and reported as `null`, CPA equals the present range.
     * A.823 Appendix 1 §17: a relative vector is the predicted *movement* of a target relative to own
     * ship — with no relative movement there is no point of approach to predict.
     */
    const val REL_SPEED_EPSILON_KN: Double = 1.0e-6

    /**
     * CPA/TCPA from an explicit relative position and relative-motion vector.
     * This matches A.823 Appendix 2, whose scenarios are stated as *relative* course and speed.
     *
     * @param relPosNm   Target position relative to own ship (NM, NE plane).
     * @param relVelKn   Relative-motion velocity (knots, NE plane) = target true velocity − own velocity.
     */
    fun fromRelativeMotion(relPosNm: Vec2, relVelKn: Vec2): CpaSolution {
        val relSpeed = relVelKn.norm()
        if (relSpeed < REL_SPEED_EPSILON_KN) {
            // No relative motion: range never changes, no CPA event. CPA = current range.
            return CpaSolution(
                cpaNm = relPosNm.norm(),
                tcpaSec = null,
                relativeSpeedKn = relSpeed,
                relativeCourseDeg = null,
            )
        }
        val tcpaHours = -(relPosNm dot relVelKn) / (relVelKn dot relVelKn)
        val cpaPos = relPosNm + relVelKn * tcpaHours
        return CpaSolution(
            cpaNm = cpaPos.norm(),
            tcpaSec = tcpaHours * 3600.0,
            relativeSpeedKn = relSpeed,
            relativeCourseDeg = Geometry.bearingOf(relVelKn),
        )
    }

    /**
     * Convenience overload taking relative course/speed directly (as A.823 Appendix 2 states them).
     *
     * @param rangeNm        Present range to target (NM).
     * @param trueBearingDeg Present true bearing to target (deg, clockwise from North).
     * @param relCourseDeg   Relative course of the target (deg).
     * @param relSpeedKn     Relative speed of the target (knots).
     */
    fun fromRelativeMotion(
        rangeNm: Double,
        trueBearingDeg: Double,
        relCourseDeg: Double,
        relSpeedKn: Double,
    ): CpaSolution = fromRelativeMotion(
        Vec2.ofBearing(trueBearingDeg, rangeNm),
        Vec2.ofBearing(relCourseDeg, relSpeedKn),
    )

    /**
     * CPA/TCPA from own-ship and target **true** motion (the form used in service, where targets carry
     * true course/speed per A.823 §3.6.2.5/.6 and AIS reports COG/SOG).
     *
     * Relative velocity = target true velocity − own-ship velocity (A.823 Appendix 1 §17/§18).
     *
     * @return null if inputs are insufficient (target bearing unresolved, or either party lacks
     *         course/speed). A stationary target is fully supported — its true velocity is `(0,0)` so the
     *         relative motion is just the reverse of own ship's, which is the classic closing geometry.
     */
    fun compute(ownShip: OwnShipData, target: TrackedTarget): CpaSolution? {
        val relPos = Geometry.relativePosition(target, ownShip) ?: return null
        val ownVel = Geometry.ownVelocity(ownShip) ?: return null
        val tgtVel = Geometry.targetVelocity(target) ?: return null
        return fromRelativeMotion(relPos, tgtVel - ownVel)
    }

    /**
     * Returns a copy of [target] with [TrackedTarget.cpaNm]/[TrackedTarget.tcpaSec] populated from
     * [compute]. If CPA/TCPA can't be computed the fields are left null (the contract's "until computed"
     * state). Does **not** set `dangerous` — that is [DangerClassifier]'s job (A.823 §3.5.2).
     */
    fun enrich(ownShip: OwnShipData, target: TrackedTarget): TrackedTarget {
        val s = compute(ownShip, target) ?: return target
        return target.copy(cpaNm = s.cpaNm, tcpaSec = s.tcpaSec)
    }
}
