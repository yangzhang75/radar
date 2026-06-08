package com.shipradar.comms.sync

import com.shipradar.contract.EchoSpoke
import com.shipradar.contract.SampleEncoding
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class SpokeRingTest {

    private fun spoke(azDeg: Double, seq: Int = 0) = EchoSpoke(
        azimuthDeg = azDeg, headingDeg = null, trueNorth = true,
        rangeCellSizeMm = 1, rangeCellsDiv2 = 1, samples = ByteArray(0),
        encoding = SampleEncoding.AMPLITUDE, sequenceNumber = seq, bearingZeroError = false,
    )

    @Test fun slot_mapping_wraps_360_to_zero() {
        val ring = SpokeRing(slots = 360)
        assertEquals(0, ring.slotOf(0.0))
        assertEquals(90, ring.slotOf(90.0))
        assertEquals(0, ring.slotOf(360.0))   // 360 == 0
        assertEquals(359, ring.slotOf(359.5))
        assertEquals(10, ring.slotOf(370.0))  // 370 -> 10
    }

    @Test fun full_sweep_then_wrap_emits_complete_revolution() {
        val ring = SpokeRing(slots = 360)
        var completed: RevolutionSnapshot? = null
        for (deg in 0 until 360) {
            val r = ring.offer(spoke(deg.toDouble()))
            if (r != null) completed = r
        }
        assertNull(completed, "no wrap yet within first sweep")
        // crossing back over 0 completes the revolution
        completed = ring.offer(spoke(0.0))
        assertNotNull(completed)
        assertEquals(360, completed.filledSlots)
        assertEquals(1.0, completed.coverage)
        assertEquals(0, completed.largestGapSlots)
    }

    @Test fun partial_sweep_reports_coverage_and_largest_gap() {
        val ring = SpokeRing(slots = 360)
        // fill 0..179 only, then wrap
        for (deg in 0 until 180) ring.offer(spoke(deg.toDouble()))
        val snap = ring.offer(spoke(0.0))!!
        assertEquals(180, snap.filledSlots)
        assertEquals(0.5, snap.coverage)
        // the empty half is the largest gap (180 slots = 180 deg)
        assertEquals(180, snap.largestGapSlots)
        assertEquals(180.0, snap.largestGapDeg)
    }

    @Test fun later_spoke_overwrites_same_azimuth_slot() {
        val ring = SpokeRing(slots = 360)
        ring.offer(spoke(45.0, seq = 1))
        ring.offer(spoke(45.3, seq = 2)) // same slot (45)
        val snap = ring.snapshot()
        assertEquals(1, snap.filledSlots)
        assertEquals(2, snap.spokes[45]!!.sequenceNumber) // latest wins
    }

    @Test fun scattered_gaps_find_largest_contiguous_hole() {
        val ring = SpokeRing(slots = 360)
        // fill everything except 100..119 (a 20-slot hole) and 200 (1-slot hole)
        for (deg in 0 until 360) {
            if (deg in 100..119) continue
            if (deg == 200) continue
            ring.offer(spoke(deg.toDouble()))
        }
        val snap = ring.snapshot()
        assertEquals(360 - 21, snap.filledSlots)
        assertEquals(20, snap.largestGapSlots)
    }

    @Test fun coverage_grows_as_spokes_arrive() {
        val ring = SpokeRing(slots = 360)
        assertEquals(0.0, ring.coverage())
        for (deg in 0 until 90) ring.offer(spoke(deg.toDouble())) // 90 of 360 slots
        assertEquals(0.25, ring.coverage())
    }
}
