package com.shipradar.uicore.target

import com.shipradar.contract.OwnShipData
import com.shipradar.contract.TrackedTarget

/**
 * A course/speed pair. [courseDeg] is `null` when [speedKn] is ~0 (course is undefined for a body at
 * rest), matching how an ARPA blanks course for a stationary target.
 */
data class CourseSpeed(val speedKn: Double, val courseDeg: Double?)

/**
 * Conversion between a target's **relative** motion (what the radar directly observes from successive
 * range/bearing plots) and its **true** motion (IMO A.823(19) §3.6.2.5/.6 and GB 11711-2002 §4.2.6.2 e)/f):
 * "the ARPA shall display the **calculated true course** and **calculated true speed** of the target").
 *
 * Relationship (A.823 Appendix 1 §6/§17/§18, GB 附录A): the target's true velocity is the vector sum of
 * its relative velocity and own ship's true velocity — `v_true = v_rel + v_own` — and conversely
 * `v_rel = v_true − v_own`. Pure geometry, no platform calls. Sea- vs ground-stabilisation (A.823 §3.6.3 /
 * GB §4.2.6.3) is determined by whether own-ship velocity is water-referenced (log/heading) or
 * ground-referenced (COG/SOG); this layer is agnostic — it uses whatever own-ship velocity it is given.
 */
object TargetMotion {

    /** Target **true** course/speed from its relative motion and own-ship velocity. */
    fun trueFromRelative(ownVelKn: Vec2, relVelKn: Vec2): CourseSpeed = motionOf(relVelKn + ownVelKn)

    /** Target **relative** course/speed from its true velocity and own-ship velocity. */
    fun relativeFromTrue(ownVelKn: Vec2, trueVelKn: Vec2): CourseSpeed = motionOf(trueVelKn - ownVelKn)

    /**
     * Target true course/speed given own ship and the target's relative course/speed (as A.823 Appendix 2
     * / GB Appendix C state the operational scenarios). Returns null if own-ship COG/SOG is unavailable.
     */
    fun trueFromRelative(ownShip: OwnShipData, relCourseDeg: Double, relSpeedKn: Double): CourseSpeed? {
        val ownVel = Geometry.ownVelocity(ownShip) ?: return null
        return trueFromRelative(ownVel, Vec2.ofBearing(relCourseDeg, relSpeedKn))
    }

    /**
     * Target relative course/speed (the radar-observed relative motion) for a target carrying true
     * course/speed. Returns null if own ship or target motion is unavailable.
     */
    fun relativeFromTrue(ownShip: OwnShipData, target: TrackedTarget): CourseSpeed? {
        val ownVel = Geometry.ownVelocity(ownShip) ?: return null
        val tgtVel = Geometry.targetVelocity(target) ?: return null
        return relativeFromTrue(ownVel, tgtVel)
    }

    private fun motionOf(v: Vec2): CourseSpeed {
        val speed = v.norm()
        return if (speed < CpaTcpaCalculator.REL_SPEED_EPSILON_KN) {
            CourseSpeed(0.0, null) // at rest -> course undefined
        } else {
            CourseSpeed(speed, Geometry.bearingOf(v))
        }
    }
}
