package com.shipradar.comms.halo.status

import com.shipradar.contract.GuardZoneAlarmType
import com.shipradar.contract.RadarPowerState
import com.shipradar.contract.RadarStatus
import com.shipradar.contract.SeaMode
import com.shipradar.contract.SeaState
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/** Round-trip verification of the status parser against constructed §雷达状态 messages. */
class HaloStatusParserTest {

    /** Little-endian builder mirroring the doc field widths, for constructing test messages. */
    private class Buf {
        val out = ArrayList<Byte>()
        fun hdr(v: Int) = apply { out.add(((v ushr 8) and 0xFF).toByte()); out.add((v and 0xFF).toByte()) }
        fun u8(v: Int) = apply { out.add((v and 0xFF).toByte()) }
        fun u16(v: Int) = apply { out.add((v and 0xFF).toByte()); out.add(((v ushr 8) and 0xFF).toByte()) }
        fun u32(v: Long) = apply {
            out.add((v and 0xFF).toByte()); out.add(((v ushr 8) and 0xFF).toByte())
            out.add(((v ushr 16) and 0xFF).toByte()); out.add(((v ushr 24) and 0xFF).toByte())
        }
        fun u32(v: Int) = u32(v.toLong())
        fun build() = out.toByteArray()
    }

    // ---- 01C4 mode ----

    @Test fun parses_mode_status() {
        val msg = Buf().hdr(0x01C4)
            .u32(3)   // 状态 = 预热 (WARMUP)
            .u32(1)   // 定时发射状态 = 开
            .u32(42)  // 预热时间 42 s
            .u32(7)   // 定时计数 7 s
            .build()
        val u = HaloStatusParser.parseStatus(msg) as RadarStatusUpdate.Mode
        assertEquals(RadarPowerState.WARMUP, u.powerState)
        assertTrue(u.timedTransmit)
        assertEquals(42, u.warmupRemainSec)
        assertEquals(7, u.timedCountSec)

        val merged = u.applyTo(RadarStatus(powerState = RadarPowerState.OFF))
        assertEquals(RadarPowerState.WARMUP, merged.powerState)
        assertEquals(42, merged.warmupRemainSec)
    }

    @Test fun mode_power_states_cover_all_codes() {
        val expected = listOf(
            RadarPowerState.OFF, RadarPowerState.STANDBY, RadarPowerState.TRANSMIT,
            RadarPowerState.WARMUP, RadarPowerState.NO_SCANNER, RadarPowerState.DETECTING_SCANNER,
        )
        expected.forEachIndexed { code, state ->
            val msg = Buf().hdr(0x01C4).u32(code).u32(0).u32(0).u32(0).build()
            assertEquals(state, (HaloStatusParser.parseStatus(msg) as RadarStatusUpdate.Mode).powerState)
        }
    }

    // ---- 02C4 setup ----

    private fun setupMessage(): ByteArray = Buf().hdr(0x02C4)
        .u32(1_111_200) // 量程 1,111,200 cm = 11112 m (6 nm)
        .u16(0)         // 预留
        .u32(0)         // 增益类型 手动
        .u8(200)        // 增益值
        .u32(2)         // 海浪方式 近海
        .u8(100)        // 海浪值
        .u32(0)         // 雨雪方式
        .u8(30)         // 雨雪值
        .u32(0)         // FTC控制预留
        .u8(15)         // FTC水平
        .u32(0)         // Tune type
        .u8(0)          // Coarse tune
        .u8(0)          // Fine tune
        .u32(3)         // 干扰抑制 IR
        .u32(1)         // 目标扩展 开
        .u32(2)         // 目标加速
        .u32(0)         // 脉冲宽度类型
        .u32(0)         // 脉冲宽度水平
        .u8(128)        // 报警灵敏度
        .u8(1)          // 报警圈1状态 开
        .u8(0)          // 报警圈2状态 关
        .u32(0)         // 圈1方位参考 相对本船
        .u32(100)       // 圈1起点 100 m
        .u32(2000)      // 圈1终点 2000 m
        .u16(900)       // 圈1中心方位 90.0°
        .u16(3600)      // 圈1宽度 360°
        .u32(1)         // 圈2方位参考 真北
        .u32(500)       // 圈2起点
        .u32(1500)      // 圈2终点
        .u16(1800)      // 圈2中心方位 180.0°
        .u16(150)       // 圈2宽度 15.0°
        .u32(0)         // 圈1报警类型 离开
        .u8(1)          // 圈1触发
        .u32(2)         // 圈2报警类型 both
        .u8(0)          // 圈2触发
        .build()

    @Test fun parses_setup_status_scalars() {
        val u = HaloStatusParser.parseStatus(setupMessage()) as RadarStatusUpdate.Setup
        assertEquals(11112, u.rangeMeters) // cm -> m
        assertEquals(false, u.gainAuto)
        assertEquals(200, u.gain)
        assertEquals(SeaMode.OFFSHORE, u.seaMode)
        assertEquals(100, u.seaLevel)
        assertEquals(30, u.rainLevel)
        assertEquals(15, u.ftcLevel)
        assertEquals(3, u.interferenceRejection)
        assertTrue(u.targetExpansion)
        assertEquals(2, u.targetBoost)
        assertEquals(128, u.guardSensitivity)
    }

    @Test fun parses_setup_guard_zones() {
        val u = HaloStatusParser.parseStatus(setupMessage()) as RadarStatusUpdate.Setup
        assertEquals(2, u.guardZones.size)
        val z0 = u.guardZones[0]
        assertEquals(0, z0.zone)
        assertTrue(z0.enabled)
        assertEquals(false, z0.trueBearing)
        assertEquals(100, z0.startRangeMeters)
        assertEquals(2000, z0.endRangeMeters)
        assertEquals(90.0, z0.bearingDeg, 1e-9)   // 900 * 0.1°
        assertEquals(360.0, z0.widthDeg, 1e-9)    // 3600 * 0.1°
        assertEquals(GuardZoneAlarmType.LEAVING, z0.alarmType)
        assertTrue(z0.triggered)

        val z1 = u.guardZones[1]
        assertEquals(1, z1.zone)
        assertEquals(false, z1.enabled)
        assertTrue(z1.trueBearing)
        assertEquals(180.0, z1.bearingDeg, 1e-9)
        assertEquals(15.0, z1.widthDeg, 1e-9)
        assertEquals(GuardZoneAlarmType.BOTH, z1.alarmType)
        assertEquals(false, z1.triggered)
    }

    @Test fun setup_applyTo_merges_into_snapshot() {
        val u = HaloStatusParser.parseStatus(setupMessage()) as RadarStatusUpdate.Setup
        val merged = u.applyTo(RadarStatus(powerState = RadarPowerState.TRANSMIT))
        assertEquals(RadarPowerState.TRANSMIT, merged.powerState) // preserved
        assertEquals(11112, merged.rangeMeters)
        assertEquals(SeaMode.OFFSHORE, merged.seaMode)
        assertEquals(2, merged.guardZones.size)
    }

    // ---- 08C4 ext setup ----

    @Test fun parses_ext_setup_status() {
        val msg = Buf().hdr(0x08C4)
            .u8(2)    // STC曲线 恶劣 -> ROUGH
            .u8(3)    // 本地IR
            .u8(1)    // 快速扫描 36rpm
            .u32(1)   // 旁瓣方式 手动
            .u8(77)   // 手动旁瓣值
            .u16(240) // 旋转速度 rpmX10 -> 24 rpm
            .u8(2)    // 干扰抑制
            .u8(3)    // 目标分离
            .u32(0)   // 预留
            .build()
        val u = HaloStatusParser.parseStatus(msg) as RadarStatusUpdate.ExtSetup
        assertEquals(SeaState.ROUGH, u.seaState)
        assertEquals(3, u.localInterferenceRejection)
        assertEquals(1, u.fastScanMode)
        assertEquals(false, u.sidelobeAuto)
        assertEquals(77, u.sidelobeLevel)
        assertEquals(240, u.rpmX10)
        assertEquals(2, u.noiseRejection)
        assertEquals(3, u.targetSeparation)
    }

    @Test fun ext_setup_seastate_mapping_differs_from_command_order() {
        // 08C4 STC曲线: 0普通->MODERATE, 1平静->CALM, 2恶劣->ROUGH
        fun seaStateAt(v: Int) =
            (HaloStatusParser.parseStatus(
                Buf().hdr(0x08C4).u8(v).u8(0).u8(0).u32(0).u8(0).u16(240).u8(0).u8(0).u32(0).build(),
            ) as RadarStatusUpdate.ExtSetup).seaState
        assertEquals(SeaState.MODERATE, seaStateAt(0))
        assertEquals(SeaState.CALM, seaStateAt(1))
        assertEquals(SeaState.ROUGH, seaStateAt(2))
    }

    // ---- 00C6 alarm ----

    @Test fun parses_alarm_status() {
        val msg = Buf().hdr(0x00C6).u8(1).u32(1).u32(0x1).build()
        val u = HaloStatusParser.parseStatus(msg) as RadarStatusUpdate.Alarm
        assertEquals(1, u.zone)
        assertEquals(GuardZoneAlarmType.ENTERING, u.type) // 1 = 闯入
        assertEquals(HaloAlarmActivation.ACTIVE, u.activation)
    }

    // ---- 10C6 radar error ----

    @Test fun parses_radar_error_codes() {
        val msg = Buf().hdr(0x10C6).u32(0x00010003).u32(5).u32(9).build()
        val u = HaloStatusParser.parseStatus(msg) as RadarStatusUpdate.RadarError
        assertEquals(HaloError.MOTOR_NOT_RUNNING, u.error)
        assertEquals(5L, u.relatedInfo)
        assertEquals(9L, u.moreInfo)
    }

    @Test fun all_thirteen_error_codes_resolve() {
        assertEquals(13, HaloError.entries.size - 1) // minus UNKNOWN
        HaloError.entries.filter { it != HaloError.UNKNOWN }.forEach {
            assertEquals(it, HaloError.fromCode(it.code))
        }
        assertEquals(HaloError.UNKNOWN, HaloError.fromCode(0xDEAD))
    }

    // ---- robustness ----

    @Test fun unknown_header_yields_Unknown_without_crashing() {
        val msg = Buf().hdr(0x99C9).u32(0).build()
        val u = HaloStatusParser.parseStatus(msg)
        assertTrue(u is RadarStatusUpdate.Unknown)
        assertEquals(RadarPowerState.OFF, u.applyTo(RadarStatus(powerState = RadarPowerState.OFF)).powerState)
    }

    @Test fun truncated_message_throws() {
        assertFailsWith<IllegalArgumentException> { HaloStatusParser.parseStatus(byteArrayOf(0x01)) }
        // 01C4 header but no payload
        assertFailsWith<IllegalArgumentException> { HaloStatusParser.parseStatus(byteArrayOf(0x01, 0xC4.toByte())) }
    }
}
