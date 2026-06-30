package com.shipradar.uicore.target

import com.shipradar.contract.OwnShipData
import com.shipradar.contract.TargetStatus
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.sin
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Item 4 — trial manoeuvre (A.823 §3.7) running on targets that were **grown from the radar echo** by the
 * tracker (not AIS/TTM). Proves the full chain: plots → [TrackManager] track (with estimated course/speed)
 * → [InstantTrialManeuverSimulator]. This is exactly what the app does (TrialManeuverPanel(targets =
 * router.targets)), so a green test here guarantees trial-manoeuvre works on live radar tracks.
 */
class TrialOnTrackedTargetTest {

    private val SCAN = 2.5

    private fun plotAt(x: Double, y: Double) = RadarPlot(
        id = "P", rangeNm = hypot(x, y),
        trueBearingDeg = Geometry.normalizeDeg(Math.toDegrees(atan2(x, y))),
        amplitudePeak = 13.0, cellCount = 5,
    )

    /** Track a head-on closing target until confirmed; own ship stationary. */
    private fun trackedHeadOnTarget(): com.shipradar.contract.TrackedTarget {
        val tm = TrackManager()
        val own = OwnShipData(headingDeg = 0.0, cogDeg = 0.0, sogKn = 0.0)
        // target 6 NM dead ahead, closing due south at 15 kn
        var x = 0.0; var y = 6.0
        val course = 180.0; val speed = 15.0
        val vx = speed * sin(Math.toRadians(course)); val vy = speed * cos(Math.toRadians(course))
        var last: com.shipradar.contract.TrackedTarget? = null
        repeat(20) {
            last = tm.update(listOf(plotAt(x, y)), SCAN, own).firstOrNull()
            x += vx * (SCAN / 3600.0); y += vy * (SCAN / 3600.0)
        }
        return last!!
    }

    @Test
    fun `trial manoeuvre evaluates a tracker-produced target`() {
        val track = trackedHeadOnTarget()
        assertEquals(TargetStatus.TRACKED, track.status)
        assertTrue(track.courseDeg != null && track.speedKn != null, "track must carry estimated motion")

        val own = OwnShipData(headingDeg = 0.0, cogDeg = 0.0, sogKn = 12.0)
        val sim = InstantTrialManeuverSimulator()
        val result = sim.simulate(
            ownShip = own,
            targets = listOf(track),
            request = TrialManeuverRequest(newCourseDeg = 90.0, newSpeedKn = 12.0), // hard turn to starboard
        )
        assertEquals(1, result.outcomes.size, "the tracked target must be evaluated")
        val o = result.outcomes.first()
        assertEquals(track.id, o.targetId)
        assertTrue(o.trialCpaNm.isFinite() && o.trialCpaNm >= 0.0, "trial CPA must be computed: ${o.trialCpaNm}")
    }
}
