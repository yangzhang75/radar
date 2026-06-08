package com.shipradar.uicore.target

import com.shipradar.contract.OwnShipData
import com.shipradar.contract.TargetSource
import com.shipradar.contract.TargetStatus
import com.shipradar.contract.TrackedTarget
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** Trial manoeuvre (A.823 §3.7) and auto-acquisition (A.823 §3.2 / IEC 62388 CAT 1) placeholder logic. */
class TrialAndAcquisitionTest {

    // ---- Trial manoeuvre ----

    private val own = OwnShipData(headingDeg = 0.0, cogDeg = 0.0, sogKn = 10.0)
    // A.823 scenario 1 collision: 8 NM dead ahead, reciprocal 10 kn -> live CPA 0, TCPA 24 min.
    private val collisionTarget = TrackedTarget(
        id = "X", source = TargetSource.RADAR_TT, rangeNm = 8.0, bearingDeg = 0.0, trueBearing = true,
        courseDeg = 180.0, speedKn = 10.0, status = TargetStatus.TRACKED,
    )
    private val sim = InstantTrialManeuverSimulator()
    private val tightLimits = DangerCriteria(safeCpaNm = 2.0, safeTcpaSec = 1800.0) // 30-min lead so live case is dangerous

    @Test fun trialTurn_clearsTheThreat() {
        // Live geometry is dangerous under the 30-min limit...
        assertTrue(DangerClassifier.evaluate(own, collisionTarget, tightLimits).dangerous)
        // ...a 90 deg starboard turn opens the CPA well clear.
        val result = sim.simulate(own, listOf(collisionTarget), TrialManeuverRequest(newCourseDeg = 90.0, newSpeedKn = 10.0), tightLimits)
        val o = result.outcomes.single()
        assertTrue(o.trialCpaNm > 5.0, "trial CPA should open to >5 NM, was ${o.trialCpaNm}")
        assertFalse(o.dangerous)
        assertFalse(result.anyStillDangerous)
    }

    @Test fun trialMaintainCourse_staysDangerous() {
        // Trialling the present course/speed reproduces the live collision (CPA 0).
        val result = sim.simulate(own, listOf(collisionTarget), TrialManeuverRequest(newCourseDeg = 0.0, newSpeedKn = 10.0), tightLimits)
        val o = result.outcomes.single()
        assertEquals(0.0, o.trialCpaNm, 1e-6)
        assertTrue(o.dangerous)
        assertTrue(result.anyStillDangerous)
    }

    @Test fun trialDelay_addsToTcpaFromNow() {
        // With a delay, TCPA-from-now includes the hold time. Delay 600 s before maintaining course.
        val r0 = sim.simulate(own, listOf(collisionTarget), TrialManeuverRequest(0.0, 10.0, delaySec = 0.0)).outcomes.single()
        val r600 = sim.simulate(own, listOf(collisionTarget), TrialManeuverRequest(0.0, 10.0, delaySec = 600.0)).outcomes.single()
        // Holding course then continuing the same course is the same straight-line track: closing rate
        // unchanged, so the CPA event time-from-now is identical regardless of the (no-op) delay.
        assertEquals(r0.trialTcpaSec!!, r600.trialTcpaSec!!, 1e-6)
    }

    @Test fun applyTrialView_marksTestManeuverStatus() {
        val viewed = sim.applyTrialView(own, listOf(collisionTarget), TrialManeuverRequest(90.0, 10.0), tightLimits)
        assertEquals(TargetStatus.TEST_MANEUVER, viewed.single().status)
        assertTrue(viewed.single().cpaNm!! > 5.0)
    }

    // ---- Auto-acquisition ----

    private val acq = ZoneAutoAcquisition()
    private val ownStationary = OwnShipData(headingDeg = 0.0, cogDeg = 0.0, sogKn = 0.0)

    @Test fun plotInsideActiveZone_acquired() {
        val zone = AcquisitionZone(innerRangeNm = 3.0, outerRangeNm = 12.0, fromBearingDeg = 0.0, toBearingDeg = 90.0)
        val plots = listOf(
            RadarPlot("p1", rangeNm = 6.0, trueBearingDeg = 45.0),   // inside
            RadarPlot("p2", rangeNm = 6.0, trueBearingDeg = 200.0),  // wrong bearing
            RadarPlot("p3", rangeNm = 1.0, trueBearingDeg = 45.0),   // too near
        )
        val d = acq.select(plots, listOf(zone), currentRadarTrackCount = 0, ownShip = ownStationary)
        assertEquals(listOf("p1"), d.toAcquire.map { it.id })
    }

    @Test fun suppressedZone_blocksAcquisition() {
        val acquire = AcquisitionZone(3.0, 12.0, 0.0, 90.0)
        val suppress = AcquisitionZone(3.0, 12.0, 40.0, 50.0, suppress = true)
        val plots = listOf(RadarPlot("p1", 6.0, 45.0)) // inside both -> suppressed wins
        val d = acq.select(plots, listOf(acquire, suppress), 0, ownStationary)
        assertTrue(d.toAcquire.isEmpty())
    }

    @Test fun capacityFull_rejects() {
        val zone = AcquisitionZone(3.0, 12.0, 0.0, 90.0)
        val plots = listOf(RadarPlot("p1", 6.0, 45.0))
        val d = acq.select(plots, listOf(zone), currentRadarTrackCount = 40, ownShip = ownStationary) // CAT1 limit 40
        assertTrue(d.toAcquire.isEmpty())
        assertEquals(listOf("p1"), d.rejectedAtCapacity.map { it.id })
    }

    @Test fun overSpeedPlot_skipped() {
        val zone = AcquisitionZone(3.0, 12.0, 0.0, 90.0)
        val plots = listOf(RadarPlot("fast", 6.0, 45.0, relativeSpeedKn = 120.0)) // > 100 kn (A.823 §3.2.1)
        val d = acq.select(plots, listOf(zone), 0, ownStationary)
        assertTrue(d.toAcquire.isEmpty())
    }

    @Test fun bearingSectorWrapsThroughNorth() {
        val zone = AcquisitionZone(3.0, 12.0, fromBearingDeg = 350.0, toBearingDeg = 10.0)
        assertTrue(zone.containsBearing(0.0))
        assertTrue(zone.containsBearing(355.0))
        assertFalse(zone.containsBearing(20.0))
    }

    @Test fun nearestFirstWithinCapacity() {
        val zone = AcquisitionZone(0.0, 12.0, 0.0, 90.0)
        val plots = listOf(
            RadarPlot("far", 9.0, 45.0),
            RadarPlot("near", 4.0, 45.0),
        )
        val d = acq.select(plots, listOf(zone), currentRadarTrackCount = 39, ownShip = ownStationary) // 1 free slot
        assertEquals(listOf("near"), d.toAcquire.map { it.id })
        assertEquals(listOf("far"), d.rejectedAtCapacity.map { it.id })
    }
}
