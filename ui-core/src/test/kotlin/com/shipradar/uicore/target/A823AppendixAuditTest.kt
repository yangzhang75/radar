package com.shipradar.uicore.target

import com.shipradar.contract.OwnShipData
import com.shipradar.contract.TargetSource
import com.shipradar.contract.TargetStatus
import com.shipradar.contract.TrackedTarget
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.math.sqrt

/**
 * W4-D certification audit — numeric assertions against the **four operational scenarios** that are
 * IDENTICAL in IMO A.823(19) Appendix 2 and GB 11711-2002 Appendix C:
 *
 * | scenario | own course/speed | target range | target brg | rel course | rel speed |
 * |    1     |     000° / 10 kn |      8 NM     |    000°    |    180°    |   20 kn   |
 * |    2     |     000° / 10 kn |      1 NM     |    000°    |    090°    |   10 kn   |
 * |    3     |     000° /  5 kn |      8 NM     |    045°    |    225°    |   20 kn   |
 * |    4     |     000° / 25 kn |      8 NM     |    045°    |    225°    |   20 kn   |
 *
 * For each, the audit pins the exact relative-motion CPA/TCPA AND the calculated **true course / true
 * speed** (A.823 §3.6.2.5/.6, GB §4.2.6.2 e)/f); the §3.8.3 / §4.2.8.3 accuracy table lists these as
 * required outputs). Scenarios 3 & 4 share identical relative motion (own speed differs only), so their
 * CPA/TCPA are equal while their true motion differs — a discriminating check of the relative↔true math.
 *
 * Scope note (§3.8.5 / §4.2.8.5): these are EXACT analytic results. The §3.8 accuracy budget bounds the
 * *tracking/estimation* error under the Appendix 3/D sensor noise — a separate filter layer, not this
 * geometry. ui-core.target adds zero error, so the ARPA-math contribution is negligible by construction.
 */
class A823AppendixAuditTest {

    private val tol = 1e-6
    private val sqrt200 = sqrt(200.0)        // 14.142135623…
    private val sqrt283 = sqrt(283.578644)   // 16.83980…  (scenario 3 true speed)
    private val sqrt317 = sqrt(317.893218)   // 17.82956…  (scenario 4 true speed)

    private fun own(courseDeg: Double, speedKn: Double) =
        OwnShipData(headingDeg = courseDeg, cogDeg = courseDeg, sogKn = speedKn)

    // ---------- CPA / TCPA (A.823 §3.6.2.3/.4, GB §4.2.6.2 c)/d) ----------

    @Test fun scenario1_cpaTcpa() {
        val s = CpaTcpaCalculator.fromRelativeMotion(8.0, 0.0, 180.0, 20.0)
        assertEquals(0.0, s.cpaNm, tol); assertEquals(1440.0, s.tcpaSec!!, tol)
    }

    @Test fun scenario2_cpaTcpa() {
        val s = CpaTcpaCalculator.fromRelativeMotion(1.0, 0.0, 90.0, 10.0)
        assertEquals(1.0, s.cpaNm, tol); assertEquals(0.0, s.tcpaSec!!, tol)
    }

    @Test fun scenario3_cpaTcpa() {
        val s = CpaTcpaCalculator.fromRelativeMotion(8.0, 45.0, 225.0, 20.0)
        assertEquals(0.0, s.cpaNm, 1e-9); assertEquals(1440.0, s.tcpaSec!!, tol)
    }

    @Test fun scenario4_sameRelativeCpaTcpaAsScenario3() {
        val s = CpaTcpaCalculator.fromRelativeMotion(8.0, 45.0, 225.0, 20.0)
        assertEquals(0.0, s.cpaNm, 1e-9); assertEquals(1440.0, s.tcpaSec!!, tol)
    }

    // ---------- calculated TRUE course / TRUE speed (A.823 §3.6.2.5/.6, GB §4.2.6.2 e)/f) ----------

    @Test fun scenario1_trueMotion() {
        val m = TargetMotion.trueFromRelative(own(0.0, 10.0), relCourseDeg = 180.0, relSpeedKn = 20.0)!!
        assertEquals(10.0, m.speedKn, tol)
        assertEquals(180.0, m.courseDeg!!, tol)
    }

    @Test fun scenario2_trueMotion() {
        val m = TargetMotion.trueFromRelative(own(0.0, 10.0), 90.0, 10.0)!!
        assertEquals(sqrt200, m.speedKn, tol)   // 14.142… kn
        assertEquals(45.0, m.courseDeg!!, tol)
    }

    @Test fun scenario3_trueMotion() {
        val m = TargetMotion.trueFromRelative(own(0.0, 5.0), 225.0, 20.0)!!
        assertEquals(sqrt283, m.speedKn, 1e-4)        // 16.8398 kn
        assertEquals(237.121, m.courseDeg!!, 0.01)    // SW-ish true course
    }

    @Test fun scenario4_trueMotion_differsFromScenario3() {
        val m = TargetMotion.trueFromRelative(own(0.0, 25.0), 225.0, 20.0)!!
        assertEquals(sqrt317, m.speedKn, 1e-4)        // 17.8296 kn
        assertEquals(307.516, m.courseDeg!!, 0.01)    // NW-ish true course (own 25 kn dominates)
    }

    @Test fun relativeToTrue_roundTrips() {
        // true -> relative -> true returns the original true motion (scenario 2).
        val ownVel = Vec2.ofBearing(0.0, 10.0)
        val trueVel = Vec2.ofBearing(45.0, sqrt200)
        val rel = TargetMotion.relativeFromTrue(ownVel, trueVel)
        assertEquals(90.0, rel.courseDeg!!, tol); assertEquals(10.0, rel.speedKn, tol)
        val back = TargetMotion.trueFromRelative(ownVel, Vec2.ofBearing(rel.courseDeg!!, rel.speedKn))
        assertEquals(45.0, back.courseDeg!!, tol); assertEquals(sqrt200, back.speedKn, tol)
    }

    // ---------- negative-TCPA convention (GB §4.2.6.2 d): "TCPA shown negative once CPA passed") ----------

    @Test fun tcpaNegativeOncePassed_gbClause() {
        // Target on the quarter, opening; CPA was 18 min ago.
        val s = CpaTcpaCalculator.fromRelativeMotion(Vec2(2.0, -2.0), Vec2(0.0, -10.0))
        assertTrue(s.tcpaSec!! < 0.0, "GB §4.2.6.2 d: TCPA must be negative after CPA is passed")
    }

    // ---------- §3.8.5/§4.2.8.5: ARPA math adds zero error (true-motion path matches relative) ----------

    @Test fun trueMotionPath_matchesRelativeScenarios_exactly() {
        // Scenario 1 fed as own + target TRUE motion must reproduce the relative-motion CPA/TCPA exactly.
        val t = TrackedTarget(
            id = "s1", source = TargetSource.RADAR_TT, rangeNm = 8.0, bearingDeg = 0.0, trueBearing = true,
            courseDeg = 180.0, speedKn = 10.0, status = TargetStatus.TRACKED,
        )
        val s = CpaTcpaCalculator.compute(own(0.0, 10.0), t)!!
        assertEquals(0.0, s.cpaNm, tol); assertEquals(1440.0, s.tcpaSec!!, tol)
    }

    // ---------- Trial manoeuvre (A.823 §3.7, GB §4.2.7): exact post-manoeuvre CPA ----------

    @Test fun trialStarboardTurn_opensCpa_scenario1() {
        // Live scenario 1 is a collision (CPA 0). Trial: turn to 090° at 10 kn, no delay.
        // rel vel after = target(0,-10) - own(10,0) = (-10,-10); from relPos (0,8):
        //   TCPA = 80/200 h = 0.4 h = 1440 s; CPA = |(-4,4)| = sqrt(32) NM.
        val own = own(0.0, 10.0)
        val t = TrackedTarget(
            id = "s1", source = TargetSource.RADAR_TT, rangeNm = 8.0, bearingDeg = 0.0, trueBearing = true,
            courseDeg = 180.0, speedKn = 10.0, status = TargetStatus.TRACKED,
        )
        val outcome = InstantTrialManeuverSimulator()
            .simulate(own, listOf(t), TrialManeuverRequest(newCourseDeg = 90.0, newSpeedKn = 10.0))
            .outcomes.single()
        assertEquals(sqrt(32.0), outcome.trialCpaNm, tol)   // 5.65685 NM
        assertEquals(1440.0, outcome.trialTcpaSec!!, tol)
    }
}
