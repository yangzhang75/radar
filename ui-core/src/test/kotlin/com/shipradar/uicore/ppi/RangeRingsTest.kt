package com.shipradar.uicore.ppi

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class RangeRingsTest {

    private val eps = 1e-9

    @Test fun six_nm_scale_yields_six_one_nm_rings() {
        val rings = RangeRings.rangeRings(scaleNm = 6.0, radiusPx = 120.0)
        assertEquals(6, rings.size)
        // equally spaced, centred at CCRP, outermost at the operational radius
        assertEquals(20.0, rings.first().radiusPx, eps)
        assertEquals(1.0, rings.first().rangeNm, eps)
        assertEquals(120.0, rings.last().radiusPx, eps)
        assertEquals(6.0, rings.last().rangeNm, eps)
        rings.forEach { assertEquals(1.0, it.separationNm, eps) }
    }

    @Test fun quarter_nm_scale_uses_round_separation() {
        assertEquals(0.05, RangeRings.defaultRingSeparationNm(0.25), eps)
        val rings = RangeRings.rangeRings(scaleNm = 0.25, radiusPx = 100.0)
        assertEquals(5, rings.size)
        assertEquals(0.05, rings.first().rangeNm, eps)
        assertEquals(0.25, rings.last().rangeNm, eps)
    }

    @Test fun every_mandatory_scale_gives_two_to_six_equal_rings() {
        // IEC 62388 §9.11.2.1: typically 2..6 equally spaced rings for NM scales.
        for (scale in RangeModel.mandatoryScalesNm) {
            val rings = RangeRings.rangeRings(scaleNm = scale, radiusPx = 100.0)
            assertTrue(rings.size in 2..6, "scale=$scale gave ${rings.size} rings")
            // equally spaced check: consecutive radius deltas equal
            val deltas = rings.map { it.radiusPx }.zipWithNext { a, b -> b - a }
            deltas.forEach { assertEquals(deltas.first(), it, 1e-6) }
            // outermost ring == operational radius and == scale range
            assertEquals(100.0, rings.last().radiusPx, 1e-6)
            assertEquals(scale, rings.last().rangeNm, 1e-6)
        }
    }

    @Test fun explicit_ring_count_override() {
        val rings = RangeRings.rangeRings(scaleNm = 6.0, radiusPx = 120.0, ringCount = 3)
        assertEquals(3, rings.size)
        assertEquals(2.0, rings.first().separationNm, eps)
        assertEquals(40.0, rings[0].radiusPx, eps)
        assertEquals(120.0, rings[2].radiusPx, eps)
    }
}
