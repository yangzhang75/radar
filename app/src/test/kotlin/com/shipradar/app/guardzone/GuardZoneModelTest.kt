package com.shipradar.app.guardzone

import com.shipradar.contract.GuardZoneAlarmType
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * W7-A 扇环命中测试 + 几何→命令映射。纯 JVM（随 :app:testDebugUnitTest 运行）。
 */
class GuardZoneModelTest {

    // 普通扇区 zone: 方位 30°→90°, 距离 3..4 NM
    private val sector = GuardZone(zone = 0, enabled = true, innerRangeNm = 3.0, outerRangeNm = 4.0, startBearingDeg = 30.0, endBearingDeg = 90.0)

    // ---- 必备三例：区内 / 区外 / 跨 360° 边界 ----

    @Test fun target_inside_sector_ring_hits() {
        assertTrue(GuardZoneModel.contains(sector, targetBearingDeg = 60.0, targetRangeNm = 3.5))
    }

    @Test fun target_outside_sector_misses() {
        // 方位在扇外
        assertFalse(GuardZoneModel.contains(sector, targetBearingDeg = 120.0, targetRangeNm = 3.5))
        // 方位命中但距离在环外
        assertFalse(GuardZoneModel.contains(sector, targetBearingDeg = 60.0, targetRangeNm = 5.0))
        // 距离命中但方位在环内侧外
        assertFalse(GuardZoneModel.contains(sector, targetBearingDeg = 60.0, targetRangeNm = 2.0))
    }

    @Test fun wraparound_sector_across_360_boundary() {
        // 跨 0°：350°→20°（扇宽 30°）
        val wrap = GuardZone(zone = 1, enabled = true, innerRangeNm = 2.0, outerRangeNm = 5.0, startBearingDeg = 350.0, endBearingDeg = 20.0)
        assertEquals(30.0, GuardZoneModel.sweepDeg(wrap), 1e-9)
        assertTrue(GuardZoneModel.contains(wrap, targetBearingDeg = 5.0, targetRangeNm = 3.0), "5° 应在 350→20 内")
        assertTrue(GuardZoneModel.contains(wrap, targetBearingDeg = 355.0, targetRangeNm = 3.0), "355° 应在内")
        assertTrue(GuardZoneModel.contains(wrap, targetBearingDeg = 360.0, targetRangeNm = 3.0), "360°(=0°) 应在内")
        assertFalse(GuardZoneModel.contains(wrap, targetBearingDeg = 200.0, targetRangeNm = 3.0), "200° 应在外")
        assertFalse(GuardZoneModel.contains(wrap, targetBearingDeg = 21.0, targetRangeNm = 3.0), "21° 刚出界")
    }

    // ---- 边界细节 ----

    @Test fun sector_boundaries_inclusive() {
        assertTrue(GuardZoneModel.contains(sector, 30.0, 3.0))  // 起方位 + 内圈
        assertTrue(GuardZoneModel.contains(sector, 90.0, 4.0))  // 止方位 + 外圈
    }

    @Test fun negative_and_oversized_bearings_normalised() {
        assertTrue(GuardZoneModel.contains(sector, -300.0, 3.5)) // -300° == 60°
        assertTrue(GuardZoneModel.contains(sector, 420.0, 3.5))  // 420° == 60°
    }

    @Test fun full_circle_when_start_equals_end() {
        val ring = GuardZone(zone = 0, enabled = true, innerRangeNm = 1.0, outerRangeNm = 2.0, startBearingDeg = 45.0, endBearingDeg = 45.0)
        assertEquals(360.0, GuardZoneModel.sweepDeg(ring), 1e-9)
        assertTrue(GuardZoneModel.contains(ring, 200.0, 1.5)) // 任意方位
        assertFalse(GuardZoneModel.contains(ring, 200.0, 3.0)) // 仍受距离环约束
    }

    @Test fun inner_outer_swapped_is_tolerated() {
        val swapped = sector.copy(innerRangeNm = 4.0, outerRangeNm = 3.0)
        assertTrue(GuardZoneModel.contains(swapped, 60.0, 3.5))
    }

    // ---- 触发逻辑 ----

    @Test fun triggers_requires_enabled() {
        assertTrue(GuardZoneModel.triggers(sector, 60.0, 3.5))
        assertFalse(GuardZoneModel.triggers(sector.copy(enabled = false), 60.0, 3.5))
    }

    @Test fun zonesHit_returns_only_enabled_matches() {
        val zones = listOf(
            sector,                                   // 命中
            sector.copy(zone = 1, enabled = false),   // 几何命中但未启用
        )
        val hit = GuardZoneModel.zonesHit(zones, 60.0, 3.5)
        assertEquals(listOf(0), hit.map { it.zone })
    }

    // ---- 几何 → GuardZoneSetup 命令映射 ----

    @Test fun toSetupCommand_maps_range_to_meters_and_bearing_to_center_width() {
        val cmd = GuardZoneModel.toSetupCommand(sector)
        assertEquals(0, cmd.zone)
        assertEquals((3.0 * 1852).toInt(), cmd.startRangeMeters)
        assertEquals((4.0 * 1852).toInt(), cmd.endRangeMeters)
        assertEquals(60.0, cmd.bearingDeg, 1e-9)  // 中心 = 30 + 60/2
        assertEquals(60.0, cmd.widthDeg, 1e-9)    // 扇宽 = 90 - 30
    }

    @Test fun toSetupCommand_wraparound_center() {
        val wrap = GuardZone(zone = 1, innerRangeNm = 2.0, outerRangeNm = 5.0, startBearingDeg = 350.0, endBearingDeg = 20.0)
        val cmd = GuardZoneModel.toSetupCommand(wrap)
        assertEquals(30.0, cmd.widthDeg, 1e-9)
        assertEquals(5.0, cmd.bearingDeg, 1e-9)   // 中心 = 350 + 15 = 365 -> 5°
        assertEquals(1, cmd.zone)
    }

    @Test fun alarm_type_default_is_entering() {
        assertEquals(GuardZoneAlarmType.ENTERING, GuardZone(0).alarmType)
    }
}
