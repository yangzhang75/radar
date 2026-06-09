package com.shipradar.app.databar

import com.shipradar.app.control.MotionMode
import com.shipradar.app.control.RadarDisplaySettings
import com.shipradar.app.control.Stabilisation
import com.shipradar.app.control.VectorMode
import com.shipradar.contract.MasterSlave
import com.shipradar.contract.OwnShipData
import com.shipradar.contract.RadarPowerState
import com.shipradar.contract.RadarStatus
import com.shipradar.contract.SeaMode
import com.shipradar.contract.SensorKind
import com.shipradar.uicore.ppi.PpiOrientation
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * ALRM-02 验证：防撞要素永久显示「逐项必显」+ 失效标示 + 格式化 + 条款锚点。
 * 纯 JVM（不触 Compose），随 :app:testDebugUnitTest 运行。
 */
class DataBarModelTest {

    private val allValid = mapOf(
        SensorKind.HEADING to true, SensorKind.POSITION to true,
        SensorKind.COG_SOG to true, SensorKind.RADAR_LINK to true,
    )

    private fun ownShip(validity: Map<SensorKind, Boolean> = allValid) = OwnShipData(
        latitude = 34.4217, longitude = -119.7017,
        headingDeg = 87.0, headingTrue = true, cogDeg = 90.0, sogKn = 12.4,
        sourceValidity = validity,
    )

    private fun status() = RadarStatus(
        powerState = RadarPowerState.TRANSMIT, rangeMeters = 11112, // 6 NM
        gainAuto = false, gain = 142, seaMode = SeaMode.MANUAL, seaLevel = 30, rainLevel = 10,
        masterSlave = MasterSlave.MASTER,
    )

    private fun settings(
        orientation: PpiOrientation = PpiOrientation.NORTH_UP,
        stabilisation: Stabilisation = Stabilisation.SEA,
    ) = RadarDisplaySettings(
        orientation = orientation, motion = MotionMode.TRUE_MOTION,
        vectorMode = VectorMode.RELATIVE, vectorTimeMin = 6, stabilisation = stabilisation,
    )

    private fun build(
        own: OwnShipData = ownShip(),
        st: RadarStatus = status(),
        set: RadarDisplaySettings = settings(),
    ) = DataBarModel.build(own, st, set, own.sourceValidity)

    // ---- 逐项必显（穷举性）：核心合规断言 ----

    @Test fun every_mandatory_item_present_exactly_once() {
        val keys = build().map { it.key }
        // 全部必显项都在
        assertEquals(DataBarModel.mandatoryKeys, keys.toSet(), "缺失永久显示必显项")
        // 且各恰好一次（无重复、无遗漏）
        assertEquals(DataKey.entries.size, keys.size)
        assertEquals(keys.size, keys.toSet().size, "存在重复项")
    }

    @Test fun item_set_is_invariant_under_total_sensor_failure() {
        // 全失效时清单项数/键集不变（仅值与严重度变化）——永久显示不得因失效而消失。
        val deadValidity = mapOf(
            SensorKind.HEADING to false, SensorKind.POSITION to false,
            SensorKind.COG_SOG to false, SensorKind.RADAR_LINK to false,
        )
        val fields = build(own = ownShip(deadValidity))
        assertEquals(DataBarModel.mandatoryKeys, fields.map { it.key }.toSet())
    }

    @Test fun task_required_items_are_covered() {
        // 任务点名要素：增益/抑制/量程/运动/定向/矢量(模式+时间+稳定)/主从/传感器失效/本船航向航速位置。
        val keys = build().map { it.key }.toSet()
        listOf(
            DataKey.GAIN, DataKey.SEA, DataKey.RAIN, DataKey.RANGE_SCALE,
            DataKey.MOTION, DataKey.ORIENTATION, DataKey.VECTOR, DataKey.STABILISATION,
            DataKey.MASTER_SLAVE, DataKey.HEADING, DataKey.SPEED, DataKey.POSITION, DataKey.SCANNER,
        ).forEach { assertTrue(it in keys, "任务必显要素缺失: $it") }
    }

    @Test fun every_field_carries_an_iec62388_clause() {
        build().forEach {
            assertTrue(it.clause.contains("62388"), "缺条款锚点: ${it.key}")
            assertTrue(it.clause.isNotBlank())
        }
    }

    // ---- 失效标示（不得留空/陈旧；须清晰标示 §14.2.2.1 / §16.2.1）----

    private fun field(fields: List<DataField>, key: DataKey) = fields.first { it.key == key }

    @Test fun heading_failure_shows_placeholder_and_fail() {
        val f = build(own = ownShip(allValid + (SensorKind.HEADING to false)))
        val hdg = field(f, DataKey.HEADING)
        assertEquals(FieldSeverity.FAIL, hdg.severity)
        assertEquals("HDG ---", hdg.value)
    }

    @Test fun null_heading_value_is_treated_as_failure() {
        val own = ownShip().copy(headingDeg = null)
        assertEquals(FieldSeverity.FAIL, field(build(own = own), DataKey.HEADING).severity)
    }

    @Test fun position_failure_shows_placeholder_and_fail() {
        val f = field(build(own = ownShip(allValid + (SensorKind.POSITION to false))), DataKey.POSITION)
        assertEquals(FieldSeverity.FAIL, f.severity)
        assertEquals("POS ---", f.value)
    }

    @Test fun radar_link_loss_marks_radar_fields_degraded_and_scanner_fail() {
        val f = build(own = ownShip(allValid + (SensorKind.RADAR_LINK to false)))
        listOf(DataKey.RANGE_SCALE, DataKey.GAIN, DataKey.SEA, DataKey.RAIN, DataKey.MASTER_SLAVE).forEach {
            assertEquals(FieldSeverity.DEGRADED, field(f, it).severity, "$it 应降级")
        }
        val scan = field(f, DataKey.SCANNER)
        assertEquals(FieldSeverity.FAIL, scan.severity)
        assertEquals("LINK LOST", scan.value)
    }

    @Test fun north_up_with_heading_loss_falls_back_to_headup_degraded() {
        // §16.2.2：方位稳定模式航向失效 -> 回落 head-up，永久指示降级。
        val f = field(build(own = ownShip(allValid + (SensorKind.HEADING to false))), DataKey.ORIENTATION)
        assertEquals(FieldSeverity.DEGRADED, f.severity)
        assertTrue(f.value.contains("H UP"), "应标示回落 head-up: ${f.value}")
    }

    @Test fun head_up_unaffected_by_heading_loss() {
        val f = field(build(own = ownShip(allValid + (SensorKind.HEADING to false)), set = settings(orientation = PpiOrientation.HEAD_UP)), DataKey.ORIENTATION)
        assertEquals(FieldSeverity.OK, f.severity)
        assertEquals("H UP", f.value)
    }

    @Test fun ground_stabilisation_without_speed_is_degraded() {
        val f = field(
            build(own = ownShip(allValid + (SensorKind.COG_SOG to false)), set = settings(stabilisation = Stabilisation.GROUND)),
            DataKey.STABILISATION,
        )
        assertEquals(FieldSeverity.DEGRADED, f.severity)
    }

    @Test fun all_ok_when_sensors_valid() {
        // 正常态：除非降级条件，均为 OK。
        val f = build()
        assertEquals(FieldSeverity.OK, field(f, DataKey.HEADING).severity)
        assertEquals(FieldSeverity.OK, field(f, DataKey.POSITION).severity)
        assertEquals(FieldSeverity.OK, field(f, DataKey.RANGE_SCALE).severity)
    }

    // ---- 格式化 ----

    @Test fun range_formats_to_nm() {
        assertEquals("6 NM", DataBarModel.formatRangeNm(11112))   // 6 * 1852
        assertEquals("0.75 NM", DataBarModel.formatRangeNm(1389)) // 0.75 * 1852 = 1389
        assertEquals("24 NM", DataBarModel.formatRangeNm(44448))
    }

    @Test fun heading_shows_true_magnetic_flag() {
        assertEquals("087° T", field(build(), DataKey.HEADING).value)
        val magnetic = ownShip().copy(headingTrue = false)
        assertTrue(field(build(own = magnetic), DataKey.HEADING).value.endsWith(" M"))
    }

    @Test fun vector_shows_mode_and_time() {
        assertEquals("R VECT 6min", field(build(), DataKey.VECTOR).value)
    }

    @Test fun gain_auto_vs_manual() {
        assertEquals("MAN 142", field(build(), DataKey.GAIN).value)
        assertEquals("AUTO", field(build(st = status().copy(gainAuto = true)), DataKey.GAIN).value)
    }

    @Test fun master_slave_reflected() {
        assertEquals("MASTER", field(build(), DataKey.MASTER_SLAVE).value)
        assertEquals("SLAVE", field(build(st = status().copy(masterSlave = MasterSlave.SLAVE)), DataKey.MASTER_SLAVE).value)
    }

    @Test fun lat_lon_hemisphere_formatting() {
        // 南纬西经
        val own = ownShip().copy(latitude = -34.5, longitude = -119.5)
        val v = field(build(own = own), DataKey.POSITION).value
        assertTrue(v.contains("S") && v.contains("W"), "半球标识错误: $v")
    }

    @Test fun groups_follow_annex_i_structure() {
        // 分组覆盖 Annex I 五大组。
        assertEquals(
            setOf(DataGroup.OWN_SHIP, DataGroup.RANGE_MODE, DataGroup.TARGET, DataGroup.RADAR_SIGNAL, DataGroup.RADAR_SYSTEM),
            build().map { it.group }.toSet(),
        )
    }
}
