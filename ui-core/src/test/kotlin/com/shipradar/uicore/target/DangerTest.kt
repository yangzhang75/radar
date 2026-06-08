package com.shipradar.uicore.target

import com.shipradar.contract.OwnShipData
import com.shipradar.contract.TargetSource
import com.shipradar.contract.TargetStatus
import com.shipradar.contract.TrackedTarget
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** Dangerous-target classification per A.823 §3.5.2 CPA/TCPA limits (drives alarm 3044). */
class DangerTest {

    private fun sol(cpa: Double, tcpa: Double?, relSpeed: Double = 10.0) =
        CpaSolution(cpaNm = cpa, tcpaSec = tcpa, relativeSpeedKn = relSpeed, relativeCourseDeg = 0.0)

    private val def = DangerCriteria() // safeCpa 2.0 NM, safeTcpa 720 s

    @Test fun within_both_limits_isDangerous() {
        assertTrue(DangerClassifier.isDangerous(sol(0.5, 300.0), def))
    }

    @Test fun cpa_beyond_limit_notDangerous() {
        assertFalse(DangerClassifier.isDangerous(sol(3.0, 300.0), def))
    }

    @Test fun tcpa_beyond_limit_notDangerous() {
        assertFalse(DangerClassifier.isDangerous(sol(0.5, 1500.0), def))
    }

    @Test fun cpa_already_passed_notDangerous() {
        assertFalse(DangerClassifier.isDangerous(sol(0.5, -100.0), def))
    }

    @Test fun noRelativeMotion_insideCpa_isDangerous() {
        assertTrue(DangerClassifier.isDangerous(sol(1.0, null, relSpeed = 0.0), def))
    }

    @Test fun noRelativeMotion_outsideCpa_notDangerous() {
        assertFalse(DangerClassifier.isDangerous(sol(3.0, null, relSpeed = 0.0), def))
    }

    @Test fun operatorConfigurableLimits_respected() {
        val s = sol(0.5, 1000.0)
        assertFalse(DangerClassifier.isDangerous(s, DangerCriteria(safeCpaNm = 2.0, safeTcpaSec = 720.0)))
        assertTrue(DangerClassifier.isDangerous(s, DangerCriteria(safeCpaNm = 2.0, safeTcpaSec = 1800.0)))
    }

    @Test fun evaluate_setsContractFlag() {
        // Own 000/10, target 8 NM dead ahead reciprocal 10 kn -> CPA 0, TCPA 24 min.
        val own = OwnShipData(headingDeg = 0.0, cogDeg = 0.0, sogKn = 10.0)
        val tgt = TrackedTarget(
            id = "t", source = TargetSource.RADAR_TT, rangeNm = 8.0, bearingDeg = 0.0, trueBearing = true,
            courseDeg = 180.0, speedKn = 10.0, status = TargetStatus.TRACKED,
        )
        // Default 12-min TCPA limit -> 24-min TCPA is not yet dangerous...
        assertFalse(DangerClassifier.evaluate(own, tgt).dangerous)
        // ...but with a 30-min limit it is, and CPA/TCPA fields are populated either way.
        val evaluated = DangerClassifier.evaluate(own, tgt, DangerCriteria(safeTcpaSec = 1800.0))
        assertTrue(evaluated.dangerous)
        assertEquals(0.0, evaluated.cpaNm!!, 1e-6)
        assertEquals(1440.0, evaluated.tcpaSec!!, 1e-6)
    }

    @Test fun evaluate_missingMotion_notDangerous() {
        val own = OwnShipData(headingDeg = 0.0, cogDeg = 0.0, sogKn = 10.0)
        val tgt = TrackedTarget(
            id = "t", source = TargetSource.AIS_SLEEPING, rangeNm = 8.0, bearingDeg = 0.0, trueBearing = true,
            status = TargetStatus.TRACKED,
        )
        assertFalse(DangerClassifier.evaluate(own, tgt).dangerous)
    }
}
