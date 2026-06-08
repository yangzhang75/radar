package com.shipradar.uicore.target

import com.shipradar.contract.TargetSource
import com.shipradar.contract.TargetStatus
import com.shipradar.contract.TrackedTarget
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** Capacity counting + over/near-limit flags per IEC 62388 Table 1 CAT 1 (CAP-01; alarms 3042/3043). */
class CapacityTest {

    private fun gen(source: TargetSource, n: Int, base: String) = (0 until n).map {
        TrackedTarget(
            id = "$base$it", source = source, rangeNm = 5.0, bearingDeg = 0.0, trueBearing = true,
            status = TargetStatus.TRACKED,
        )
    }

    private fun set(radar: Int, aisActive: Int, aisSleep: Int) =
        gen(TargetSource.RADAR_TT, radar, "r") +
            gen(TargetSource.AIS_ACTIVE, aisActive, "a") +
            gen(TargetSource.AIS_SLEEPING, aisSleep, "s")

    @Test fun cat1_limitsAreStandardValues() {
        assertEquals(40, EquipmentCategory.CAT_1.radarTracked)
        assertEquals(40, EquipmentCategory.CAT_1.aisActivated)
        assertEquals(200, EquipmentCategory.CAT_1.aisSleeping)
        assertEquals(240, EquipmentCategory.CAT_1.aisTotal)
    }

    @Test fun exactlyAtCat1Minimums_notOver_butNearLimit() {
        // Inject the full CAT 1 set: 40 radar + 40 active + 200 sleeping = 240 AIS, 280 objects.
        val report = CapacityMonitor.evaluate(set(40, 40, 200))
        assertEquals(40, report.radarTracked.count)
        assertEquals(240, report.aisTotal.count)
        assertFalse(report.anyOverLimit)              // == limit is not "over" (over = strictly greater)
        assertTrue(report.anyNearLimit())             // at 100% of limit -> within 90% caution band -> 3043
    }

    @Test fun over240Targets_flagsOverLimit() {
        // 40 radar, 41 active (over the 40 active limit), 200 sleeping -> AIS total 241 (over 240 too).
        val report = CapacityMonitor.evaluate(set(40, 41, 200))
        assertTrue(report.aisActivated.overLimit)
        assertTrue(report.aisTotal.overLimit)
        assertTrue(report.anyOverLimit)               // -> alarm 3042
        assertFalse(report.anyNearLimit())            // over-limit suppresses the near-limit caution
    }

    @Test fun radarOverLimit() {
        val report = CapacityMonitor.evaluate(set(41, 10, 10))
        assertTrue(report.radarTracked.overLimit)
        assertTrue(report.anyOverLimit)
    }

    @Test fun wellBelow_noFlags() {
        val report = CapacityMonitor.evaluate(set(10, 10, 10))
        assertFalse(report.anyOverLimit)
        assertFalse(report.anyNearLimit())
    }

    @Test fun nearLimitBand_boundary() {
        // 90% of 40 = 36 -> 36 active triggers near-limit, 35 does not.
        assertTrue(CapacityMonitor.evaluate(set(10, 36, 10)).anyNearLimit())
        assertFalse(CapacityMonitor.evaluate(set(10, 35, 10)).anyNearLimit())
    }

    @Test fun emptySet_noFlags() {
        val report = CapacityMonitor.evaluate(emptyList())
        assertFalse(report.anyOverLimit)
        assertFalse(report.anyNearLimit())
        assertEquals(0, report.aisTotal.count)
    }

    @Test fun cat3_lowerLimits() {
        // Same 280-object set against CAT 3 minimums (20/20/100/120) -> heavily over.
        val report = CapacityMonitor.evaluate(set(40, 40, 200), EquipmentCategory.CAT_3)
        assertTrue(report.radarTracked.overLimit)
        assertTrue(report.aisTotal.overLimit)
    }
}
