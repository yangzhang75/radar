package com.shipradar.comms.halo.control

import com.shipradar.constants.HaloOpcodes
import com.shipradar.contract.QueryKind
import com.shipradar.contract.RadarCommand
import com.shipradar.contract.SeaMode
import com.shipradar.contract.TrackCommand
import com.shipradar.util.Angles
import kotlin.math.roundToInt

/**
 * T1.3a — encodes typed [RadarCommand]s into HALO control-channel bytes (236.6.7.10:6680).
 *
 * Wire format per 雷达天线端协议文档-HALO.docx §雷达控制 / §雷达高级控制. Compliance: HALO-02.
 * Opcodes come from [HaloOpcodes]; Q12 floats from [com.shipradar.util.HaloFixedPoint].
 *
 * Endianness: integer parameters are little-endian; the 2-byte opcode is written high byte first
 * (document order). Verified against every worked example in the doc — see HaloControlEncoderTest.
 *
 * Known doc ambiguities flagged to 张建/协议 (do NOT silently re-interpret for type cert):
 *  - 03C1 量程 single: doc text says "单位m" but the 6nm worked example "03C1 10B2 0100" decodes to
 *    111120 = 11112 m * 10, i.e. the wire field is decimetres (0.1 m). We encode metres*10 to match
 *    the example. By contrast 02C4 status reports range in cm (*100). Units are inconsistent across
 *    the doc — vendor confirmation required.
 *  - 90C1 0400 报警方式: this command's text lists 0进入/1离开, but every status message (02C4, 00C6)
 *    and the task spec use 0离开/1进入. We follow the status convention (= GuardZoneAlarmType.ordinal:
 *    LEAVING=0, ENTERING=1, BOTH=2). Vendor confirmation required.
 *  - 看门狗: §雷达控制 header says A1C1; §消息发送规则 says A0C1. We use A1C1 (= HaloOpcodes.WATCHDOG).
 */
object HaloControlEncoder {

    /** Minimum range in metres (协议文档 §调整量程 03C1: "最少为15m"). */
    const val MIN_RANGE_METERS = 15

    /** 03C1 wire scale: the field is decimetres (see class doc), so metres * 10. */
    private const val RANGE_METERS_TO_WIRE = 10

    fun encode(cmd: RadarCommand): ByteArray = when (cmd) {
        // --- power / transmit / rotation (bool, 1-byte state) ---
        is RadarCommand.Power     -> boolCmd(HaloOpcodes.POWER, cmd.on)
        is RadarCommand.Transmit  -> boolCmd(HaloOpcodes.TRANSMIT, cmd.on)
        is RadarCommand.Rotate    -> boolCmd(HaloOpcodes.ROTATE, cmd.on)
        is RadarCommand.TimedTransmit -> boolCmd(HaloOpcodes.TIMED_TRANSMIT, cmd.on)
        is RadarCommand.TimedTransmitSetup ->                         // B0C1: uint32 tP_sec, uint32 sP_sec (0..86400)
            LeBuf().opcode(HaloOpcodes.TIMED_TRANSMIT_SETUP)
                .u32(cmd.transmitSec.coerceIn(0, 86_400))
                .u32(cmd.standbySec.coerceIn(0, 86_400))
                .build()

        // --- range (03C1, uint32, decimetres on the wire) ---
        is RadarCommand.SetRange ->
            LeBuf().opcode(HaloOpcodes.SET_RANGE)
                .u32(cmd.meters.coerceAtLeast(MIN_RANGE_METERS) * RANGE_METERS_TO_WIRE)
                .build()

        // --- gain / sea / sidelobe / rain share 06C1 (uint32 type, uint32 mode, uint8 level) ---
        is RadarCommand.Gain     -> gainFamily(HaloOpcodes.TYPE_GAIN, if (cmd.auto) 1 else 0, cmd.level)
        is RadarCommand.Sea      -> gainFamily(HaloOpcodes.TYPE_SEA, cmd.mode.toSeaWire(), cmd.level)
        is RadarCommand.Sidelobe -> gainFamily(HaloOpcodes.TYPE_SIDELOBE, if (cmd.auto) 1 else 0, cmd.level)
        is RadarCommand.Rain     -> gainFamily(HaloOpcodes.TYPE_RAIN, 0 /* 雨雪只能手动 */, cmd.level)

        // --- single uint8 level commands ---
        is RadarCommand.Ftc                       -> u8Cmd(HaloOpcodes.FTC, cmd.level)
        is RadarCommand.InterferenceRejection     -> u8Cmd(HaloOpcodes.INTERFERENCE_REJECTION, cmd.level)
        is RadarCommand.LocalInterferenceRejection -> u8Cmd(HaloOpcodes.LOCAL_INTERFERENCE_REJECTION, cmd.level)
        is RadarCommand.NoiseRejection            -> u8Cmd(HaloOpcodes.NOISE_REJECTION, cmd.level)
        is RadarCommand.TargetSeparation          -> u8Cmd(HaloOpcodes.TARGET_SEPARATION, cmd.level)
        is RadarCommand.TargetBoost               -> u8Cmd(HaloOpcodes.TARGET_BOOST, cmd.level)
        is RadarCommand.TargetExpansion -> boolCmd(HaloOpcodes.TARGET_EXPANSION, cmd.on)
        is RadarCommand.FastScan        -> boolCmd(HaloOpcodes.FAST_SCAN, cmd.on)
        // 0BC1 海水状况: 0 平静 / 1 普通 / 2 恶劣 == SeaState.ordinal (CALM/MODERATE/ROUGH)
        is RadarCommand.SeaStateCmd     -> u8Cmd(HaloOpcodes.SEA_STATE, cmd.state.ordinal)

        // --- rotation speed (05CB advanced frame) ---
        is RadarCommand.SetRpm -> advancedFrame(
            HaloOpcodes.ADVANCED_05CB, SUB_RPM,
            // 24 rpm -> wire 240 (F0); status 08C4 also reports rpmX10 -> wire is rpm * 10
            valueBytes = LeBuf().u32(cmd.rpm.coerceIn(10, 36) * 10).build(),
        )

        // --- watchdog / queries / no-arg ---
        is RadarCommand.Watchdog -> LeBuf().opcode(HaloOpcodes.WATCHDOG).build()
        is RadarCommand.Query    -> LeBuf().opcode(cmd.kind.toOpcode()).build()

        // --- guard zones (90C1 sub-commands) ---
        is RadarCommand.GuardZoneEnable ->
            LeBuf().opcode(HaloOpcodes.GUARD_ZONE).u16(GZ_ENABLE)
                .u8(cmd.zone).u8(if (cmd.on) 1 else 0).build()
        is RadarCommand.GuardZoneSetup ->
            LeBuf().opcode(HaloOpcodes.GUARD_ZONE).u16(GZ_SETUP)
                .u8(cmd.zone)
                .u32(cmd.startRangeMeters)
                .u32(cmd.endRangeMeters)
                .u16(Angles.degToTenthsDeg(cmd.bearingDeg))              // 0..3599 (0.1°)
                .u16((cmd.widthDeg * 10.0).roundToInt().coerceIn(0, 3600)) // 360°=3600
                .build()
        is RadarCommand.GuardZoneSensitivity ->
            LeBuf().opcode(HaloOpcodes.GUARD_ZONE).u16(GZ_SENSITIVITY)
                .u8(cmd.level).build()
        is RadarCommand.GuardZoneAlarmMode ->
            LeBuf().opcode(HaloOpcodes.GUARD_ZONE).u16(GZ_ALARM_MODE)
                .u8(cmd.zone).u8(cmd.type.ordinal).build()

        // --- install corrections (persisted by radar) ---
        is RadarCommand.PlacementAngle   -> LeBuf().opcode(HaloOpcodes.PLACEMENT_ANGLE).u32(cmd.angleDeg).build()
        is RadarCommand.BearingAlignment -> LeBuf().opcode(HaloOpcodes.BEARING_CORRECTION).u16(cmd.tenthsDeg.coerceIn(0, 3599)).build()
        is RadarCommand.AntennaHeight    -> LeBuf().opcode(HaloOpcodes.ANTENNA_HEIGHT).u32(cmd.mm).build()
    }

    /**
     * T1.3 — 目标捕获/取消 on the tracking-control channel (236.6.7.20:6690).
     *
     * The HALO protocol doc lists the endpoint but gives NO byte format for target acquire/cancel.
     * Not fabricating a layout for a type-cert deliverable.
     *
     * TODO(待张建/待协议补充): obtain the 目标控制 message layout, then implement Acquire/Cancel.
     */
    fun encodeTrack(cmd: TrackCommand): ByteArray = when (cmd) {
        is TrackCommand.Acquire,
        is TrackCommand.Cancel ->
            TODO("待张建/待协议补充: HALO 目标控制信道(236.6.7.20:6690)字节格式未在协议文档中定义")
    }

    // --- helpers ---

    private fun boolCmd(opcode: Int, on: Boolean) = LeBuf().opcode(opcode).u8(if (on) 1 else 0).build()
    private fun u8Cmd(opcode: Int, level: Int) = LeBuf().opcode(opcode).u8(level).build()

    private fun gainFamily(type: Int, mode: Int, level: Int) =
        LeBuf().opcode(HaloOpcodes.GAIN_SEA_SIDELOBE)
            .u32(type).u32(mode).u8(level).build()

    /** 05CB / 00CB share a frame: opcode + 4-byte sub + 4-byte value + 12 zero bytes. */
    internal fun advancedFrame(opcode: Int, sub: Int, valueBytes: ByteArray): ByteArray =
        LeBuf().opcode(opcode).u32(sub).bytes(valueBytes).zeros(12).build()

    private fun SeaMode.toSeaWire(): Int = ordinal // 0 手动 / 1 港湾 / 2 近海

    private fun QueryKind.toOpcode(): Int = when (this) {
        QueryKind.ALL            -> HaloOpcodes.QUERY_ALL
        QueryKind.MODE           -> HaloOpcodes.QUERY_MODE
        QueryKind.SETUP          -> HaloOpcodes.QUERY_SETUP
        QueryKind.ADVANCED_SETUP -> HaloOpcodes.QUERY_ADVANCED_SETUP
        QueryKind.PERFORMANCE    -> HaloOpcodes.QUERY_PERFORMANCE
        QueryKind.CONFIG         -> HaloOpcodes.QUERY_CONFIG
    }

    // 05CB sub-command (设置扫描转速 05CB 0300 0000)
    private const val SUB_RPM = 0x03

    // 90C1 guard-zone sub-commands (2-byte little-endian: 0100 / 0200 / 0300 / 0400)
    private const val GZ_ENABLE = 0x01
    private const val GZ_SETUP = 0x02
    private const val GZ_SENSITIVITY = 0x03
    private const val GZ_ALARM_MODE = 0x04
}
