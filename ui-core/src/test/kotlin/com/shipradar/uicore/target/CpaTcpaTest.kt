package com.shipradar.uicore.target

import com.shipradar.contract.OwnShipData
import com.shipradar.contract.TargetSource
import com.shipradar.contract.TargetStatus
import com.shipradar.contract.TrackedTarget
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * CPA/TCPA verification against IMO A.823(19) Appendix 2 operational scenarios and classic textbook
 * geometries (crossing, overtaking, stationary, parallel, past-CPA).
 */
class CpaTcpaTest {

    private val tol = 1e-6

    private fun target(
        range: Double,
        bearing: Double,
        course: Double? = null,
        speed: Double? = null,
        trueBearing: Boolean = true,
        source: TargetSource = TargetSource.RADAR_TT,
        id: String = "t",
    ) = TrackedTarget(
        id = id, source = source, rangeNm = range, bearingDeg = bearing, trueBearing = trueBearing,
        courseDeg = course, speedKn = speed, status = TargetStatus.TRACKED,
    )

    private fun ownShip(course: Double, speed: Double, heading: Double = course) =
        OwnShipData(headingDeg = heading, cogDeg = course, sogKn = speed)

    // ---- A.823 Appendix 2 scenarios (stated as relative course/speed) ----
    // Expected CPA/TCPA derived from the relative-motion geometry:
    //   1: dead-ahead reciprocal, 8 NM @ 20 kn closing  -> CPA 0,   TCPA 24 min
    //   2: crossing abeam-onset, 1 NM, rel course 090   -> CPA 1,   TCPA 0   (at CPA now)
    //   3: 045/225 reciprocal, 8 NM @ 20 kn             -> CPA 0,   TCPA 24 min
    //   4: identical relative motion to 3 (own speed differs only) -> CPA 0, TCPA 24 min

    @Test fun a823_scenario1_headOn() {
        val s = CpaTcpaCalculator.fromRelativeMotion(rangeNm = 8.0, trueBearingDeg = 0.0, relCourseDeg = 180.0, relSpeedKn = 20.0)
        assertEquals(0.0, s.cpaNm, tol)
        assertEquals(1440.0, s.tcpaSec!!, tol)
    }

    @Test fun a823_scenario2_atCpaNow() {
        val s = CpaTcpaCalculator.fromRelativeMotion(rangeNm = 1.0, trueBearingDeg = 0.0, relCourseDeg = 90.0, relSpeedKn = 10.0)
        assertEquals(1.0, s.cpaNm, tol)
        assertEquals(0.0, s.tcpaSec!!, tol)
    }

    @Test fun a823_scenario3_obliqueReciprocal() {
        val s = CpaTcpaCalculator.fromRelativeMotion(rangeNm = 8.0, trueBearingDeg = 45.0, relCourseDeg = 225.0, relSpeedKn = 20.0)
        assertEquals(0.0, s.cpaNm, 1e-9)
        assertEquals(1440.0, s.tcpaSec!!, tol)
    }

    @Test fun a823_scenario4_sameRelativeMotionAsScenario3() {
        // Scenarios 3 & 4 differ only in own-ship speed; relative motion (225/20) is identical -> same CPA/TCPA.
        val s = CpaTcpaCalculator.fromRelativeMotion(rangeNm = 8.0, trueBearingDeg = 45.0, relCourseDeg = 225.0, relSpeedKn = 20.0)
        assertEquals(0.0, s.cpaNm, 1e-9)
        assertEquals(1440.0, s.tcpaSec!!, tol)
    }

    @Test fun a823_scenario1_viaTrueMotionPath() {
        // Cross-check the in-service true-motion path: own 000/10 kn, target true course 180 / 10 kn,
        // 8 NM dead ahead -> relative motion 180/20 -> same CPA 0, TCPA 24 min as scenario 1.
        val s = CpaTcpaCalculator.compute(ownShip(0.0, 10.0), target(8.0, 0.0, course = 180.0, speed = 10.0))!!
        assertEquals(0.0, s.cpaNm, tol)
        assertEquals(1440.0, s.tcpaSec!!, tol)
        assertEquals(20.0, s.relativeSpeedKn, tol)
        assertEquals(180.0, s.relativeCourseDeg!!, tol)
    }

    // ---- Textbook geometries via the true-motion path ----

    @Test fun stationaryTargetDeadAhead_collision() {
        // Own 000/10 kn, target stationary 6 NM dead ahead -> own runs it down: CPA 0, TCPA 36 min.
        val s = CpaTcpaCalculator.compute(ownShip(0.0, 10.0), target(6.0, 0.0, course = 0.0, speed = 0.0))!!
        assertEquals(0.0, s.cpaNm, tol)
        assertEquals(2160.0, s.tcpaSec!!, tol)
    }

    @Test fun overtaking_collision() {
        // Own 000/20 kn overtakes target 000/10 kn 4 NM ahead -> CPA 0, TCPA 24 min.
        val s = CpaTcpaCalculator.compute(ownShip(0.0, 20.0), target(4.0, 0.0, course = 0.0, speed = 10.0))!!
        assertEquals(0.0, s.cpaNm, tol)
        assertEquals(1440.0, s.tcpaSec!!, tol)
    }

    @Test fun parallelEqualSpeed_noRelativeMotion() {
        // Own 000/10 kn, target 2 NM abeam (090) on parallel 000/10 kn course -> no relative motion.
        val s = CpaTcpaCalculator.compute(ownShip(0.0, 10.0), target(2.0, 90.0, course = 0.0, speed = 10.0))!!
        assertNull(s.tcpaSec, "parallel equal-speed: TCPA undefined")
        assertNull(s.relativeCourseDeg)
        assertEquals(2.0, s.cpaNm, tol)
        assertTrue(s.relativeSpeedKn < CpaTcpaCalculator.REL_SPEED_EPSILON_KN)
    }

    @Test fun pastCpa_negativeTcpa() {
        // Target on the quarter at relPos (2,-2) NM, stationary; own 000/10 kn already opening it.
        // bearing = atan2(2,-2) = 135 deg, range = sqrt(8).
        val s = CpaTcpaCalculator.compute(
            ownShip(0.0, 10.0),
            target(Math.sqrt(8.0), 135.0, course = 0.0, speed = 0.0),
        )!!
        assertTrue(s.tcpaSec!! < 0, "CPA already passed -> negative TCPA")
        assertEquals(-720.0, s.tcpaSec!!, tol)
        assertEquals(2.0, s.cpaNm, tol)
    }

    @Test fun relativeBearing_convertedWithHeading() {
        // Target 0.0 NM... use 5 NM at relative bearing 270 with own heading 090 -> true bearing 000 (dead ahead).
        val own = OwnShipData(headingDeg = 90.0, cogDeg = 0.0, sogKn = 10.0)
        val tgt = target(5.0, 270.0, course = 180.0, speed = 10.0, trueBearing = false)
        val s = CpaTcpaCalculator.compute(own, tgt)!!
        assertEquals(0.0, s.cpaNm, tol)
        assertEquals(900.0, s.tcpaSec!!, tol) // 5 NM / 20 kn = 0.25 h = 900 s
    }

    @Test fun missingMotion_returnsNull() {
        assertNull(CpaTcpaCalculator.compute(ownShip(0.0, 10.0), target(5.0, 0.0))) // target has no course/speed
        assertNull(CpaTcpaCalculator.compute(OwnShipData(headingDeg = 0.0), target(5.0, 0.0, course = 180.0, speed = 10.0)))
    }

    @Test fun enrich_populatesContractFields() {
        val t = CpaTcpaCalculator.enrich(ownShip(0.0, 10.0), target(8.0, 0.0, course = 180.0, speed = 10.0))
        assertEquals(0.0, t.cpaNm!!, tol)
        assertEquals(1440.0, t.tcpaSec!!, tol)
    }
}
