package com.shipradar.comms.halo.status

import com.shipradar.contract.GuardZoneAlarmType
import com.shipradar.contract.GuardZoneStatus
import com.shipradar.contract.RadarPowerState
import com.shipradar.contract.SeaMode
import com.shipradar.contract.SeaState
import com.shipradar.util.Angles
import kotlin.math.roundToInt

/**
 * T1.3b — parses HALO status-channel bytes (236.6.7.9:6679) into [RadarStatusUpdate]s.
 *
 * Layout per 雷达天线端协议文档-HALO.docx §雷达状态. Compliance: HALO-03. Every message starts with a
 * 2-byte header in document order (high byte first), e.g. 01C4 -> 01 C4; the payload that follows is
 * little-endian. Field widths are taken from the doc field tables (see field-by-field comments).
 *
 * Known doc ambiguities flagged to 张建/协议:
 *  - 量程 in 02C4 is documented "以厘米表达" (cm) -> we divide by 100 to get metres. The 03C1 control
 *    command instead uses decimetres (see HaloControlEncoder). Vendor confirmation required.
 *  - 海况/STC曲线 ordering differs between commands and status: 0BC1 command is 0平静/1普通/2恶劣,
 *    but 08C4 status STC曲线 is 0普通/1平静/2恶劣. We map 08C4 explicitly (0->MODERATE,1->CALM,2->ROUGH).
 */
object HaloStatusParser {

    // Status headers (2-byte, high byte first like control opcodes).
    const val MODE = 0x01C4
    const val SETUP = 0x02C4
    const val EXT_SETUP = 0x08C4
    const val PERFORMANCE = 0x03C4
    const val CONFIG = 0x04C4
    const val ALARM = 0x00C6
    const val ERROR = 0x10C6
    const val ADVANCED = 0x07C4

    fun parseStatus(bytes: ByteArray): RadarStatusUpdate {
        require(bytes.size >= 2) { "HALO status message too short: ${bytes.size} bytes (need >= 2 for header)" }
        val header = ((bytes[0].toInt() and 0xFF) shl 8) or (bytes[1].toInt() and 0xFF)
        val r = LeReader(bytes, start = 2)
        return when (header) {
            MODE      -> parseMode(r)
            SETUP     -> parseSetup(r)
            EXT_SETUP -> parseExtSetup(r)
            ALARM     -> parseAlarm(r)
            ERROR     -> parseError(r)
            else      -> RadarStatusUpdate.Unknown(header, bytes)
        }
    }

    /** 模式状态 [01C4]: uint32 状态, uint32 定时发射状态, uint32 预热时间(s), uint32 定时计数(s). */
    private fun parseMode(r: LeReader): RadarStatusUpdate.Mode {
        r.require(16)
        val state = r.u32().toInt()
        val timed = r.u32() != 0L
        val warmup = r.u32().toInt()
        @Suppress("UNUSED_VARIABLE") val timedCount = r.u32().toInt()
        return RadarStatusUpdate.Mode(
            powerState = powerStateOf(state),
            timedTransmit = timed,
            warmupRemainSec = warmup,
            timedCountSec = timedCount,
        )
    }

    /** 设置状态 [02C4]. Field widths from doc table (4=uint32, 2=uint16, 1=uint8). */
    private fun parseSetup(r: LeReader): RadarStatusUpdate.Setup {
        r.require(97)
        val rangeCm = r.u32()                 // 量程 (以厘米表达)
        r.skip(2)                              // 预留 uint16
        val gainAuto = r.u32() == 1L           // 增益类型 0手动/1自动
        val gain = r.u8()                      // 增益值 0..255
        val seaMode = seaModeOf(r.u32().toInt()) // 海浪方式 0手动/1港湾/2近海
        val seaLevel = r.u8()                  // 手动海浪调节值
        r.skip(4)                              // 雨雪方式 (只能手动)
        val rainLevel = r.u8()                 // 雨雪调节值
        r.skip(4)                              // FTC控制预留
        val ftcLevel = r.u8()                  // FTC水平
        r.skip(4)                              // Tune type (脉冲雷达)
        r.skip(1)                              // Coarse tune type
        r.skip(1)                              // Fine tune
        val ir = r.u32().toInt()               // 干扰抑制 IR 0..3
        val targetExpansion = r.u32() != 0L    // 目标扩展 0关/1开
        val targetBoost = r.u32().toInt()      // 目标加速 0..2
        r.skip(4)                              // 脉冲宽度类型
        r.skip(4)                              // 脉冲宽度水平
        val sensitivity = r.u8()               // 报警灵敏度
        val gz1enabled = r.u8() != 0           // 报警圈1状态 0关/1开
        val gz2enabled = r.u8() != 0           // 报警圈2状态
        // 报警圈1几何
        val gz1ref = r.u32().toInt()           // 方位参考 0相对本船/1真北
        val gz1start = r.u32().toInt()         // 起点量程 (m)
        val gz1end = r.u32().toInt()           // 终点量程 (m)
        val gz1bearing = r.u16()               // 中心方位 (0.1°)
        val gz1width = r.u16()                 // 宽度 (0.1°, 3600=360°)
        // 报警圈2几何
        val gz2ref = r.u32().toInt()
        val gz2start = r.u32().toInt()
        val gz2end = r.u32().toInt()
        val gz2bearing = r.u16()
        val gz2width = r.u16()
        // 报警类型 + 是否触发
        val gz1type = guardAlarmTypeOf(r.u32().toInt()) // 0离开/1闯入/2both
        val gz1trig = r.u8() != 0
        val gz2type = guardAlarmTypeOf(r.u32().toInt())
        val gz2trig = r.u8() != 0

        val zones = listOf(
            GuardZoneStatus(
                zone = 0, enabled = gz1enabled, trueBearing = gz1ref == 1,
                startRangeMeters = gz1start, endRangeMeters = gz1end,
                bearingDeg = Angles.tenthsDegToDeg(gz1bearing), widthDeg = gz1width / 10.0,
                alarmType = gz1type, triggered = gz1trig,
            ),
            GuardZoneStatus(
                zone = 1, enabled = gz2enabled, trueBearing = gz2ref == 1,
                startRangeMeters = gz2start, endRangeMeters = gz2end,
                bearingDeg = Angles.tenthsDegToDeg(gz2bearing), widthDeg = gz2width / 10.0,
                alarmType = gz2type, triggered = gz2trig,
            ),
        )
        return RadarStatusUpdate.Setup(
            rangeMeters = (rangeCm / 100.0).roundToInt(), // cm -> m
            gainAuto = gainAuto, gain = gain,
            seaMode = seaMode, seaLevel = seaLevel,
            rainLevel = rainLevel, ftcLevel = ftcLevel,
            interferenceRejection = ir,
            targetExpansion = targetExpansion, targetBoost = targetBoost,
            guardSensitivity = sensitivity,
            guardZones = zones,
        )
    }

    /** 扩展设置状态 [08C4]. */
    private fun parseExtSetup(r: LeReader): RadarStatusUpdate.ExtSetup {
        r.require(16)
        val stcCurve = r.u8()                  // STC曲线 0普通/1平静/2恶劣
        val localIr = r.u8()                   // 本地IR 0..3
        val fastScan = r.u8()                  // 快速扫描模式 0关/1:36rpm/2:48rpm
        val sidelobeAuto = r.u32() == 0L       // 旁瓣方式 0自动/1手动
        val sidelobeLevel = r.u8()             // 手动旁瓣值 0..255
        val rpmX10 = r.u16()                   // 旋转速度 (rpmX10)
        val noiseRejection = r.u8()            // 干扰抑制 0..2
        val targetSeparation = r.u8()          // 目标分离 0..3
        r.skip(4)                              // 预留
        return RadarStatusUpdate.ExtSetup(
            seaState = seaStateOf08C4(stcCurve),
            localInterferenceRejection = localIr,
            fastScanMode = fastScan,
            sidelobeAuto = sidelobeAuto,
            sidelobeLevel = sidelobeLevel,
            rpmX10 = rpmX10,
            noiseRejection = noiseRejection,
            targetSeparation = targetSeparation,
        )
    }

    /** 报警状态 [00C6]: uint8 报警圈, uint32 报警类型, uint32 报警状态. */
    private fun parseAlarm(r: LeReader): RadarStatusUpdate.Alarm {
        r.require(9)
        val zone = r.u8()
        val type = guardAlarmTypeOf(r.u32().toInt())
        val activation = when (r.u32().toInt()) {
            0x1 -> HaloAlarmActivation.ACTIVE
            0x2 -> HaloAlarmActivation.INACTIVE
            0x3 -> HaloAlarmActivation.CANCELLED
            else -> HaloAlarmActivation.UNKNOWN
        }
        return RadarStatusUpdate.Alarm(zone, type, activation)
    }

    /** 雷达错误信息 [10C6]: uint32 错误类型, uint32 相关信息, uint32 更多信息. */
    private fun parseError(r: LeReader): RadarStatusUpdate.RadarError {
        r.require(12)
        val error = HaloError.fromCode(r.u32())
        val related = r.u32()
        val more = r.u32()
        return RadarStatusUpdate.RadarError(error, related, more)
    }

    // --- field mappers ---

    private fun powerStateOf(v: Int): RadarPowerState = when (v) {
        0 -> RadarPowerState.OFF
        1 -> RadarPowerState.STANDBY
        2 -> RadarPowerState.TRANSMIT
        3 -> RadarPowerState.WARMUP
        4 -> RadarPowerState.NO_SCANNER
        5 -> RadarPowerState.DETECTING_SCANNER
        else -> RadarPowerState.OFF
    }

    private fun seaModeOf(v: Int): SeaMode = when (v) {
        0 -> SeaMode.MANUAL
        1 -> SeaMode.HARBOUR
        2 -> SeaMode.OFFSHORE
        else -> SeaMode.MANUAL
    }

    private fun guardAlarmTypeOf(v: Int): GuardZoneAlarmType = when (v) {
        0 -> GuardZoneAlarmType.LEAVING
        1 -> GuardZoneAlarmType.ENTERING
        2 -> GuardZoneAlarmType.BOTH
        else -> GuardZoneAlarmType.LEAVING
    }

    /** 08C4 STC曲线: 0普通/1平静/2恶劣 -> SeaState (declared CALM, MODERATE, ROUGH). */
    private fun seaStateOf08C4(v: Int): SeaState = when (v) {
        0 -> SeaState.MODERATE // 普通
        1 -> SeaState.CALM     // 平静
        2 -> SeaState.ROUGH    // 恶劣
        else -> SeaState.MODERATE
    }
}
