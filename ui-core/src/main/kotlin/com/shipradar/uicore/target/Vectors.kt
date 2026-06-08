package com.shipradar.uicore.target

import com.shipradar.contract.OwnShipData
import com.shipradar.contract.TrackedTarget

/**
 * Motion vector for a target, expressed as planar endpoints (NM, NE plane, own ship at origin).
 *
 * @property startNm Present target position relative to own ship.
 * @property endNm   Predicted position after the chosen vector time (the arrow head).
 * @property trueMode true => true vector (A.823 §3.4.6.1, target's predicted true motion);
 *                    false => relative vector (A.823 Appendix 1 §17, motion relative to own ship).
 */
data class MotionVector(
    val startNm: Vec2,
    val endNm: Vec2,
    val trueMode: Boolean,
) {
    /** Vector delta (head − tail), NM. */
    val deltaNm: Vec2 get() = endNm - startNm
}

/**
 * True/relative target vector geometry (IMO A.823(19) §3.4.6).
 *
 * §3.4.6.1: a vector-only ARPA shall offer **both** true and relative vectors.
 * §3.4.6.3: displayed vectors shall be **time-adjustable** — hence vector time is a parameter.
 * §3.4.6.4: a positive indication of the vector time-scale in use shall be given (UI concern).
 *
 * Both endpoints are in the own-ship-centred NE plane so the render layer (T2.1 PPI) can map them
 * straight to screen with its polar→screen transform.
 */
object TargetVectors {

    /**
     * Common bridge vector-time options (minutes). A.823 §3.4.6.3 requires the time be adjustable but
     * fixes no specific set; these are typical selectable values. TODO(待标准): confirm against the
     * GB 11711 / IEC 62388 acceptance procedure if a mandated set or range exists.
     */
    val SELECTABLE_VECTOR_TIMES_MIN: List<Int> = listOf(1, 2, 3, 6, 12, 15, 30)

    /**
     * **True** vector: predicted true motion over [vectorTimeMin] minutes (A.823 §3.4.6.1).
     * `end = startRelPos + targetTrueVelocity * t`.
     */
    fun trueVector(ownShip: OwnShipData, target: TrackedTarget, vectorTimeMin: Double): MotionVector? {
        require(vectorTimeMin >= 0) { "vectorTimeMin must be >= 0" }
        val start = Geometry.relativePosition(target, ownShip) ?: return null
        val tgtVel = Geometry.targetVelocity(target) ?: return null
        val hours = vectorTimeMin / 60.0
        return MotionVector(start, start + tgtVel * hours, trueMode = true)
    }

    /**
     * **Relative** vector: predicted motion relative to own ship over [vectorTimeMin] minutes
     * (A.823 Appendix 1 §17). `end = startRelPos + (targetTrueVel − ownVel) * t`.
     * The relative vector points along the line of relative approach — its closest pass to the origin
     * is the CPA, which is why a relative vector aimed near own ship signals danger.
     */
    fun relativeVector(ownShip: OwnShipData, target: TrackedTarget, vectorTimeMin: Double): MotionVector? {
        require(vectorTimeMin >= 0) { "vectorTimeMin must be >= 0" }
        val start = Geometry.relativePosition(target, ownShip) ?: return null
        val ownVel = Geometry.ownVelocity(ownShip) ?: return null
        val tgtVel = Geometry.targetVelocity(target) ?: return null
        val hours = vectorTimeMin / 60.0
        return MotionVector(start, start + (tgtVel - ownVel) * hours, trueMode = false)
    }
}
