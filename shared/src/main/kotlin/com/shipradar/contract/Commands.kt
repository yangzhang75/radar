package com.shipradar.contract

/**
 * Control commands the UI sends downstream. comms encodes these into HALO control-channel bytes
 * (236.6.7.10:6680). Opcodes per 雷达天线端协议文档-HALO.docx §雷达控制 (see com.shipradar.constants.HaloOpcodes).
 * UI never sees raw opcodes — it only constructs these typed commands.
 */
sealed interface RadarCommand {
    data class Power(val on: Boolean) : RadarCommand                       // 00C1
    data class Transmit(val on: Boolean) : RadarCommand                    // 01C1
    data class Rotate(val on: Boolean) : RadarCommand                      // 02C1
    data class TimedTransmit(val on: Boolean) : RadarCommand               // 0CC1
    data class TimedTransmitSetup(val transmitSec: Int, val standbySec: Int) : RadarCommand // B0C1
    /** range in metres, min 15. */
    data class SetRange(val meters: Int) : RadarCommand                    // 03C1
    data class Gain(val auto: Boolean, val level: Int) : RadarCommand      // 06C1 type 0
    data class Sea(val mode: SeaMode, val level: Int) : RadarCommand       // 06C1 type 2
    data class Sidelobe(val auto: Boolean, val level: Int) : RadarCommand  // 06C1 type 5
    data class Rain(val level: Int) : RadarCommand                         // 06C1 type 4 (manual only)
    data class Ftc(val level: Int) : RadarCommand                          // 07C1
    /** 0 = off .. 3 = max. */
    data class InterferenceRejection(val level: Int) : RadarCommand        // 08C1
    data class LocalInterferenceRejection(val level: Int) : RadarCommand   // 0EC1 (0..3)
    data class NoiseRejection(val level: Int) : RadarCommand               // 21C1 (0..2)
    data class TargetSeparation(val level: Int) : RadarCommand             // 22C1 (0..2)
    data class TargetExpansion(val on: Boolean) : RadarCommand             // 09C1
    data class TargetBoost(val level: Int) : RadarCommand                  // 0AC1 (0..2)
    data class FastScan(val on: Boolean) : RadarCommand                    // 0FC1
    data class SeaStateCmd(val state: SeaState) : RadarCommand             // 0BC1
    /** 10..36 rpm. */
    data class SetRpm(val rpm: Int) : RadarCommand                         // 05CB
    data object Watchdog : RadarCommand                                    // A1C1 (~8s, comms-driven)
    data class Query(val kind: QueryKind) : RadarCommand                   // 01C2..05C2/08C2

    // Guard zones (报警圈) — 90C1 sub-commands
    data class GuardZoneEnable(val zone: Int, val on: Boolean) : RadarCommand                  // 90C1 0100
    data class GuardZoneSetup(
        val zone: Int, val startRangeMeters: Int, val endRangeMeters: Int,
        val bearingDeg: Double, val widthDeg: Double,
    ) : RadarCommand                                                                           // 90C1 0200
    data class GuardZoneAlarmMode(val zone: Int, val type: GuardZoneAlarmType) : RadarCommand  // 90C1 0400
    data class GuardZoneSensitivity(val level: Int) : RadarCommand                             // 90C1 0300

    // Install corrections (persisted by radar; not needed every boot)
    data class PlacementAngle(val angleDeg: Int) : RadarCommand            // 40C1
    data class BearingAlignment(val tenthsDeg: Int) : RadarCommand         // 05C1 (0..3599)
    data class AntennaHeight(val mm: Int) : RadarCommand                   // 30C1
}

enum class QueryKind { ALL, MODE, SETUP, ADVANCED_SETUP, PERFORMANCE, CONFIG }

/** Target tracking control on the tracking-control channel (236.6.7.20:6690). */
sealed interface TrackCommand {
    data class Acquire(val rangeNm: Double, val bearingDeg: Double) : TrackCommand
    data class Cancel(val targetId: String) : TrackCommand
}
