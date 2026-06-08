package com.shipradar.comms.halo.control

import com.shipradar.contract.GuardZoneAlarmType
import com.shipradar.contract.QueryKind
import com.shipradar.contract.RadarCommand
import com.shipradar.contract.SeaMode
import com.shipradar.contract.SeaState
import com.shipradar.contract.TrackCommand
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/** Byte-for-byte verification of the control encoder against 雷达天线端协议文档-HALO.docx worked examples. */
class HaloControlEncoderTest {

    private fun hex(s: String): ByteArray =
        s.replace(" ", "").chunked(2).map { it.toInt(16).toByte() }.toByteArray()

    private fun assertBytes(expectedHex: String, actual: ByteArray) =
        assertEquals(hex(expectedHex).toList(), actual.toList())

    // ---- DOC HARD ASSERTIONS ----

    @Test fun setRange_6nm_matches_doc_example() {
        // 6 nm = 11112 m. Doc: "03C1 10B2 0100" (= 111120 = metres * 10, decimetre wire field).
        assertBytes("03C1 10B2 0100", HaloControlEncoder.encode(RadarCommand.SetRange(11112)))
    }

    @Test fun sea_offshore_level100_matches_doc_example() {
        // Doc: 设置海浪方式近海, 值100 -> "06C1 0200 0000 0200 0000 64"
        assertBytes(
            "06C1 0200 0000 0200 0000 64",
            HaloControlEncoder.encode(RadarCommand.Sea(SeaMode.OFFSHORE, 100)),
        )
    }

    @Test fun minSnr_matches_doc_example() {
        // Doc: 设置最小信噪比 98.351 -> "00CB F100 0000 9E25 0600 0000 0000 0000 0000 0000 0000"
        assertBytes(
            "00CB F1000000 9E250600 000000000000000000000000",
            HaloAdvancedControl.encodeMinSnr(98.351),
        )
    }

    @Test fun setRpm_24_matches_doc_example() {
        // Doc: 设置转速为24rpm -> "05CB 0300 0000 F000 0000 0000 0000 0000 0000 0000 0000"
        assertBytes(
            "05CB 03000000 F0000000 000000000000000000000000",
            HaloControlEncoder.encode(RadarCommand.SetRpm(24)),
        )
    }

    @Test fun power_on_matches_doc_example() {
        // Doc: 打开电源 -> "00C1 01"
        assertBytes("00C1 01", HaloControlEncoder.encode(RadarCommand.Power(true)))
        assertBytes("00C1 00", HaloControlEncoder.encode(RadarCommand.Power(false)))
    }

    // ---- bool / transmit / rotation ----

    @Test fun bool_commands() {
        assertBytes("01C1 01", HaloControlEncoder.encode(RadarCommand.Transmit(true)))
        assertBytes("02C1 00", HaloControlEncoder.encode(RadarCommand.Rotate(false)))
        assertBytes("0CC1 01", HaloControlEncoder.encode(RadarCommand.TimedTransmit(true)))
        assertBytes("09C1 01", HaloControlEncoder.encode(RadarCommand.TargetExpansion(true)))
        assertBytes("0FC1 01", HaloControlEncoder.encode(RadarCommand.FastScan(true)))
    }

    @Test fun timedTransmitSetup_two_uint32() {
        assertBytes(
            "B0C1 2C010000 58020000", // 300 s transmit, 600 s standby
            HaloControlEncoder.encode(RadarCommand.TimedTransmitSetup(300, 600)),
        )
    }

    @Test fun setRange_enforces_minimum_15m() {
        // 5 m coerced up to 15 m -> wire 150 = 0x96
        assertBytes("03C1 96000000", HaloControlEncoder.encode(RadarCommand.SetRange(5)))
    }

    // ---- 06C1 family ----

    @Test fun gain_manual_and_auto() {
        assertBytes("06C1 00000000 00000000 80", HaloControlEncoder.encode(RadarCommand.Gain(auto = false, level = 128)))
        assertBytes("06C1 00000000 01000000 00", HaloControlEncoder.encode(RadarCommand.Gain(auto = true, level = 0)))
    }

    @Test fun sea_modes_map_to_ordinal() {
        assertBytes("06C1 02000000 00000000 0A", HaloControlEncoder.encode(RadarCommand.Sea(SeaMode.MANUAL, 10)))
        assertBytes("06C1 02000000 01000000 0A", HaloControlEncoder.encode(RadarCommand.Sea(SeaMode.HARBOUR, 10)))
    }

    @Test fun sidelobe_and_rain() {
        assertBytes("06C1 05000000 01000000 14", HaloControlEncoder.encode(RadarCommand.Sidelobe(auto = true, level = 20)))
        // rain is manual only -> mode 0
        assertBytes("06C1 04000000 00000000 32", HaloControlEncoder.encode(RadarCommand.Rain(level = 50)))
    }

    // ---- single uint8 ----

    @Test fun single_uint8_commands() {
        assertBytes("07C1 FF", HaloControlEncoder.encode(RadarCommand.Ftc(255)))
        assertBytes("08C1 03", HaloControlEncoder.encode(RadarCommand.InterferenceRejection(3)))
        assertBytes("0EC1 02", HaloControlEncoder.encode(RadarCommand.LocalInterferenceRejection(2)))
        assertBytes("21C1 02", HaloControlEncoder.encode(RadarCommand.NoiseRejection(2)))
        assertBytes("22C1 01", HaloControlEncoder.encode(RadarCommand.TargetSeparation(1)))
        assertBytes("0AC1 02", HaloControlEncoder.encode(RadarCommand.TargetBoost(2)))
    }

    @Test fun seaState_command_uses_ordinal() {
        // 0BC1 海水状况: 0 平静 / 1 普通 / 2 恶劣 == SeaState.ordinal
        assertBytes("0BC1 00", HaloControlEncoder.encode(RadarCommand.SeaStateCmd(SeaState.CALM)))
        assertBytes("0BC1 01", HaloControlEncoder.encode(RadarCommand.SeaStateCmd(SeaState.MODERATE)))
        assertBytes("0BC1 02", HaloControlEncoder.encode(RadarCommand.SeaStateCmd(SeaState.ROUGH)))
    }

    // ---- watchdog / queries ----

    @Test fun watchdog_is_opcode_only() {
        assertBytes("A1C1", HaloControlEncoder.encode(RadarCommand.Watchdog))
    }

    @Test fun queries_map_to_opcodes() {
        assertBytes("01C2", HaloControlEncoder.encode(RadarCommand.Query(QueryKind.ALL)))
        assertBytes("02C2", HaloControlEncoder.encode(RadarCommand.Query(QueryKind.MODE)))
        assertBytes("03C2", HaloControlEncoder.encode(RadarCommand.Query(QueryKind.SETUP)))
        assertBytes("08C2", HaloControlEncoder.encode(RadarCommand.Query(QueryKind.ADVANCED_SETUP)))
        assertBytes("04C2", HaloControlEncoder.encode(RadarCommand.Query(QueryKind.PERFORMANCE)))
        assertBytes("05C2", HaloControlEncoder.encode(RadarCommand.Query(QueryKind.CONFIG)))
    }

    // ---- guard zones (90C1) ----

    @Test fun guardZone_enable() {
        assertBytes("90C1 0100 01 01", HaloControlEncoder.encode(RadarCommand.GuardZoneEnable(zone = 1, on = true)))
        assertBytes("90C1 0100 00 00", HaloControlEncoder.encode(RadarCommand.GuardZoneEnable(zone = 0, on = false)))
    }

    @Test fun guardZone_setup_ranges_and_angles() {
        // zone 0, start 100 m, end 2000 m, bearing 90.0° (=900 0.1°), width 360° (=3600 0.1°)
        assertBytes(
            "90C1 0200 00 64000000 D0070000 8403 100E",
            HaloControlEncoder.encode(
                RadarCommand.GuardZoneSetup(
                    zone = 0, startRangeMeters = 100, endRangeMeters = 2000,
                    bearingDeg = 90.0, widthDeg = 360.0,
                ),
            ),
        )
    }

    @Test fun guardZone_sensitivity_and_alarm_mode() {
        assertBytes("90C1 0300 80", HaloControlEncoder.encode(RadarCommand.GuardZoneSensitivity(128)))
        // alarm mode follows the status convention (LEAVING=0, ENTERING=1, BOTH=2)
        assertBytes("90C1 0400 01 00", HaloControlEncoder.encode(RadarCommand.GuardZoneAlarmMode(1, GuardZoneAlarmType.LEAVING)))
        assertBytes("90C1 0400 00 01", HaloControlEncoder.encode(RadarCommand.GuardZoneAlarmMode(0, GuardZoneAlarmType.ENTERING)))
        assertBytes("90C1 0400 00 02", HaloControlEncoder.encode(RadarCommand.GuardZoneAlarmMode(0, GuardZoneAlarmType.BOTH)))
    }

    // ---- install corrections ----

    @Test fun install_corrections() {
        assertBytes("40C1 2D000000", HaloControlEncoder.encode(RadarCommand.PlacementAngle(45)))
        assertBytes("05C1 8403", HaloControlEncoder.encode(RadarCommand.BearingAlignment(900))) // 90.0°
        assertBytes("30C1 D0070000", HaloControlEncoder.encode(RadarCommand.AntennaHeight(2000))) // mm
    }

    // ---- advanced 00CB family ----

    @Test fun advanced_float_subcommands_distinct() {
        assertBytes("00CB 0B000000 00000000 000000000000000000000000", HaloAdvancedControl.encodeVideoAperture(0.0))
        assertBytes("00CB F8000000 00100000 000000000000000000000000", HaloAdvancedControl.encodeRangeStcCut(1.0))
        assertBytes("00CB FA000000 00100000 000000000000000000000000", HaloAdvancedControl.encodeRainStcRate(1.0))
    }

    @Test fun advanced_negative_q12_example() {
        // -0.997 -> Q12 LE "0C F0 FF FF" (doc §雷达高级控制 negative example)
        assertBytes("00CB F1000000 0CF0FFFF 000000000000000000000000", HaloAdvancedControl.encodeMinSnr(-0.997))
    }

    // ---- track commands are not yet specified by the protocol ----

    @Test fun track_commands_throw_until_protocol_supplied() {
        assertFailsWith<NotImplementedError> { HaloControlEncoder.encodeTrack(TrackCommand.Cancel("T1")) }
        assertFailsWith<NotImplementedError> { HaloControlEncoder.encodeTrack(TrackCommand.Acquire(2.0, 45.0)) }
    }

    @Test fun rpm_is_clamped_to_valid_range() {
        // 100 rpm clamped to 36 -> wire 360 = 0x0168
        val bytes = HaloControlEncoder.encode(RadarCommand.SetRpm(100))
        assertBytes("05CB 03000000 68010000 000000000000000000000000", bytes)
        // 5 rpm clamped to 10 -> wire 100 = 0x64
        assertTrue(HaloControlEncoder.encode(RadarCommand.SetRpm(5))[6] == 0x64.toByte())
    }
}
