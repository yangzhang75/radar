package com.shipradar.uicore.target

import com.shipradar.contract.OwnShipData
import com.shipradar.contract.TargetSource
import com.shipradar.contract.TargetStatus
import com.shipradar.contract.TrackedTarget
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertTrue

/** Curved trial manoeuvre with own-ship turn/accel dynamics (#3, A.823 §3.7.2). */
class CurvedTrialManeuverTest {

    // own ship steaming due north at 15 kn.
    private val own = OwnShipData(headingDeg = 0.0, cogDeg = 0.0, sogKn = 15.0)
    // target dead ahead 6 NM, coming south at 15 kn → head-on collision course.
    private val target = TrackedTarget(
        id = "T1", source = TargetSource.RADAR_TT, rangeNm = 6.0, bearingDeg = 0.0,
        trueBearing = true, courseDeg = 180.0, speedKn = 15.0, status = TargetStatus.TRACKED,
    )
    // hard turn to starboard (course 090), keep speed.
    private val request = TrialManeuverRequest(newCourseDeg = 90.0, newSpeedKn = 15.0)
    private val criteria = DangerCriteria()

    private fun cpa(sim: TrialManeuverSimulator) =
        sim.simulate(own, listOf(target), request, criteria).outcomes.single().trialCpaNm

    @Test
    fun `curved manoeuvre yields a tighter CPA than the instant model (turn takes time)`() {
        val instant = cpa(InstantTrialManeuverSimulator())
        val curved = cpa(CurvedTrialManeuverSimulator(ShipDynamics(rateOfTurnDegPerSec = 3.0, accelKnPerSec = 0.05)))
        assertTrue(instant.isFinite() && curved.isFinite())
        assertTrue(curved < instant, "curved CPA ($curved) must be smaller than instant ($instant) — the ship closes while turning")
    }

    @Test
    fun `a very fast turn approaches the instant result`() {
        val instant = cpa(InstantTrialManeuverSimulator())
        val fast = cpa(CurvedTrialManeuverSimulator(ShipDynamics(rateOfTurnDegPerSec = 90.0, accelKnPerSec = 10.0), stepSec = 1.0))
        assertTrue(abs(fast - instant) < 0.5, "near-instant turn: curved ($fast) ≈ instant ($instant)")
    }

    @Test
    fun `produces an outcome for the tracked target`() {
        val r = CurvedTrialManeuverSimulator().simulate(own, listOf(target), request, criteria)
        assertTrue(r.outcomes.size == 1 && r.outcomes.single().targetId == "T1")
        assertTrue(r.outcomes.single().trialCpaNm >= 0.0)
    }
}
