package com.shipradar.app.autoacq

import com.shipradar.contract.TargetSource
import com.shipradar.contract.TargetStatus
import com.shipradar.contract.TrackedTarget
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** W8-E 自动捕获区命中测试 + 候选筛选。纯 JVM。 */
class AcqZoneModelTest {

    private val zone = AcqZone(id = 0, enabled = true, innerRangeNm = 3.0, outerRangeNm = 4.0, startBearingDeg = 20.0, endBearingDeg = 110.0)

    private fun radar(bearing: Double, range: Double, true_: Boolean = false, status: TargetStatus = TargetStatus.TRACKED) =
        TrackedTarget(id = "T", source = TargetSource.RADAR_TT, rangeNm = range, bearingDeg = bearing, trueBearing = true_, status = status)

    // ---- 必备三例：区内 / 区外 / 跨 360° ----

    @Test fun target_inside_hits() {
        assertTrue(AcqZoneModel.contains(zone, bearingDeg = 60.0, rangeNm = 3.5))
    }

    @Test fun target_outside_misses() {
        assertFalse(AcqZoneModel.contains(zone, 200.0, 3.5))  // 方位外
        assertFalse(AcqZoneModel.contains(zone, 60.0, 5.0))   // 距离外(远)
        assertFalse(AcqZoneModel.contains(zone, 60.0, 1.0))   // 距离外(近)
    }

    @Test fun wraparound_across_360() {
        val wrap = AcqZone(id = 1, enabled = true, innerRangeNm = 2.0, outerRangeNm = 5.0, startBearingDeg = 350.0, endBearingDeg = 30.0)
        assertEquals(40.0, AcqZoneModel.sweepDeg(wrap), 1e-9)
        assertTrue(AcqZoneModel.contains(wrap, 10.0, 3.0))    // 跨 0°
        assertTrue(AcqZoneModel.contains(wrap, 355.0, 3.0))
        assertTrue(AcqZoneModel.contains(wrap, 0.0, 3.0))
        assertFalse(AcqZoneModel.contains(wrap, 180.0, 3.0))
        assertFalse(AcqZoneModel.contains(wrap, 31.0, 3.0))   // 刚出界
    }

    // ---- 边界 / 整圈 / 参系 ----

    @Test fun boundaries_inclusive() {
        assertTrue(AcqZoneModel.contains(zone, 20.0, 3.0))
        assertTrue(AcqZoneModel.contains(zone, 110.0, 4.0))
    }

    @Test fun full_circle_when_start_equals_end() {
        val ring = AcqZone(enabled = true, innerRangeNm = 1.0, outerRangeNm = 2.0, startBearingDeg = 90.0, endBearingDeg = 90.0)
        assertEquals(360.0, AcqZoneModel.sweepDeg(ring), 1e-9)
        assertTrue(AcqZoneModel.contains(ring, 270.0, 1.5))
        assertFalse(AcqZoneModel.contains(ring, 270.0, 3.0)) // 距离仍约束
    }

    @Test fun negative_oversized_bearings_normalised() {
        assertTrue(AcqZoneModel.contains(zone, -300.0, 3.5)) // == 60°
        assertTrue(AcqZoneModel.contains(zone, 420.0, 3.5))  // == 60°
    }

    @Test fun target_overload_requires_matching_bearing_frame() {
        // zone 相对船首；目标相对 -> 命中
        assertTrue(AcqZoneModel.contains(zone, radar(60.0, 3.5, true_ = false)))
        // 目标为真方位（参系不一致）-> 不命中（缺航向无法换算）
        assertFalse(AcqZoneModel.contains(zone, radar(60.0, 3.5, true_ = true)))
    }

    // ---- 自动捕获候选 ----

    @Test fun candidates_only_untracked_radar_in_enabled_zone() {
        val zones = listOf(zone, AcqZone(id = 1, enabled = false, innerRangeNm = 0.0, outerRangeNm = 99.0))
        val targets = listOf(
            radar(60.0, 3.5),                                   // 区内雷达 -> 候选
            radar(200.0, 3.5),                                  // 区外 -> 否
            TrackedTarget("A", TargetSource.AIS_ACTIVE, 3.5, 60.0, false, status = TargetStatus.TRACKED), // AIS -> 否
        )
        val cands = AcqZoneModel.autoAcquireCandidates(zones, targets) { false } // 视为均未跟踪
        assertEquals(listOf("T"), cands.map { it.id })
    }

    @Test fun candidates_empty_when_no_zone_enabled() {
        val cands = AcqZoneModel.autoAcquireCandidates(listOf(zone.copy(enabled = false)), listOf(radar(60.0, 3.5))) { false }
        assertTrue(cands.isEmpty())
    }

    @Test fun already_acquired_excluded() {
        val cands = AcqZoneModel.autoAcquireCandidates(listOf(zone), listOf(radar(60.0, 3.5))) { true } // 全部已跟踪
        assertTrue(cands.isEmpty())
    }
}
