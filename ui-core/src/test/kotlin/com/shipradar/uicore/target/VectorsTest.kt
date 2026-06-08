package com.shipradar.uicore.target

import com.shipradar.contract.OwnShipData
import com.shipradar.contract.TargetSource
import com.shipradar.contract.TargetStatus
import com.shipradar.contract.TrackedTarget
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/** True/relative vector endpoint geometry per A.823 §3.4.6 (time-adjustable vectors). */
class VectorsTest {

    private val tol = 1e-9

    private val own = OwnShipData(headingDeg = 0.0, cogDeg = 0.0, sogKn = 10.0)
    // Target 5 NM dead ahead (relPos (0,5)), true course 090 / 12 kn.
    private val tgt = TrackedTarget(
        id = "t", source = TargetSource.RADAR_TT, rangeNm = 5.0, bearingDeg = 0.0, trueBearing = true,
        courseDeg = 90.0, speedKn = 12.0, status = TargetStatus.TRACKED,
    )

    @Test fun trueVector_endpoint() {
        // 6 min = 0.1 h. true vel (12,0) -> end (0,5)+(1.2,0).
        val v = TargetVectors.trueVector(own, tgt, vectorTimeMin = 6.0)!!
        assertEquals(0.0, v.startNm.x, tol); assertEquals(5.0, v.startNm.y, tol)
        assertEquals(1.2, v.endNm.x, tol); assertEquals(5.0, v.endNm.y, tol)
        assertEquals(1.2, v.deltaNm.norm(), tol) // 12 kn * 0.1 h
        assertEquals(true, v.trueMode)
    }

    @Test fun relativeVector_endpoint() {
        // rel vel = target(12,0) - own(0,10) = (12,-10). 6 min -> end (0,5)+(1.2,-1).
        val v = TargetVectors.relativeVector(own, tgt, vectorTimeMin = 6.0)!!
        assertEquals(1.2, v.endNm.x, tol); assertEquals(4.0, v.endNm.y, tol)
        assertEquals(false, v.trueMode)
    }

    @Test fun zeroVectorTime_isStartPoint() {
        val v = TargetVectors.trueVector(own, tgt, vectorTimeMin = 0.0)!!
        assertEquals(v.startNm.x, v.endNm.x, tol)
        assertEquals(v.startNm.y, v.endNm.y, tol)
    }

    @Test fun missingTargetMotion_null() {
        val noMotion = tgt.copy(courseDeg = null, speedKn = null)
        assertNull(TargetVectors.trueVector(own, noMotion, 6.0))
        assertNull(TargetVectors.relativeVector(own, noMotion, 6.0))
    }
}
