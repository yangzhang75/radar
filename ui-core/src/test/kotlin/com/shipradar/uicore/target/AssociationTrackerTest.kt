package com.shipradar.uicore.target

import com.shipradar.contract.OwnShipData
import com.shipradar.contract.TargetSource
import com.shipradar.contract.TargetStatus
import com.shipradar.contract.TrackedTarget
import kotlin.test.Test
import kotlin.test.assertEquals

/** IEC 62388 §11.8.2 radar↔AIS association hysteresis (#2). */
class AssociationTrackerTest {

    private val own = OwnShipData(headingDeg = 0.0, cogDeg = 0.0, sogKn = 0.0)
    private val gate = AssociationGate(positionNm = 0.5, hysteresis = 1.5) // disassociation gate = 0.75 NM

    private fun radar(id: String, rangeNm: Double) = TrackedTarget(
        id = id, source = TargetSource.RADAR_TT, rangeNm = rangeNm, bearingDeg = 0.0,
        trueBearing = true, status = TargetStatus.TRACKED, // no course/speed → position-only gate
    )
    private fun ais(id: String, rangeNm: Double) = TrackedTarget(
        id = id, source = TargetSource.AIS_ACTIVE, rangeNm = rangeNm, bearingDeg = 0.0,
        trueBearing = true, courseDeg = 0.0, speedKn = 5.0, status = TargetStatus.TRACKED,
    )

    /** Radar at 3.0 NG due N, AIS [sep] NM further out on the same bearing → NE separation = sep. */
    private fun scene(sep: Double) = listOf(radar("R1", 3.0), ais("A1", 3.0 + sep))

    @Test
    fun `hysteresis holds an associated pair across the gate boundary`() {
        val tracker = AssociationTracker()
        // frame 1: sep 0.3 < 0.5 tight gate → associate → one fused symbol (AIS kept).
        assertEquals(1, tracker.fuse(scene(0.3), own, gate).fused.size)
        // frame 2: sep 0.6 — beyond the tight gate but inside the 0.75 disassociation gate → HELD together.
        assertEquals(1, tracker.fuse(scene(0.6), own, gate).fused.size)
        // frame 3: sep 0.8 > 0.75 → finally splits into two symbols.
        assertEquals(2, tracker.fuse(scene(0.8), own, gate).fused.size)
    }

    @Test
    fun `without a prior association the wider gate does not apply`() {
        val tracker = AssociationTracker()
        // first ever frame at sep 0.6 (> tight 0.5, < wide 0.75): no prior pair → NOT associated → two symbols.
        assertEquals(2, tracker.fuse(scene(0.6), own, gate).fused.size)
    }

    @Test
    fun `reset drops the retained associations`() {
        val tracker = AssociationTracker()
        tracker.fuse(scene(0.3), own, gate)   // associate
        tracker.reset()
        // after reset, sep 0.6 is treated as a fresh (unassociated) pair → two symbols.
        assertEquals(2, tracker.fuse(scene(0.6), own, gate).fused.size)
    }
}
