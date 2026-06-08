package com.shipradar.contract

/**
 * Radar status echo, parsed from HALO status messages (01C4 mode / 02C4 setup / 08C4 ext setup).
 * Consumed by the data bar (§3.7 永久显示) and control UI (to reflect actual radar state).
 */
data class RadarStatus(
    val powerState: RadarPowerState,
    val timedTransmit: Boolean = false,
    val warmupRemainSec: Int? = null,
    /** Current range in metres (02C4 reports cm; convert in parser). */
    val rangeMeters: Int = 0,
    val gainAuto: Boolean = false,
    /** 0..255, valid in manual gain. */
    val gain: Int = 0,
    val seaMode: SeaMode = SeaMode.MANUAL,
    val seaLevel: Int = 0,
    val rainLevel: Int = 0,
    val ftcLevel: Int = 0,
    /** Interference rejection 0..3. */
    val interferenceRejection: Int = 0,
    val targetExpansion: Boolean = false,
    /** target acceleration / boost 0..2. */
    val targetBoost: Int = 0,
    /** rpm * 10 as reported by 08C4. */
    val rpmX10: Int = 240,
    val seaState: SeaState = SeaState.MODERATE,
    val guardZones: List<GuardZoneStatus> = emptyList(),
    val masterSlave: MasterSlave = MasterSlave.MASTER,
)

enum class RadarPowerState { OFF, STANDBY, TRANSMIT, WARMUP, NO_SCANNER, DETECTING_SCANNER }

/** 06C1 sea mode. HARBOUR/OFFSHORE only apply when auto. */
enum class SeaMode { MANUAL, HARBOUR, OFFSHORE }

/** Extended setup (08C4) sea-state / STC curve: 0 normal, 1 calm, 2 rough. */
enum class SeaState { CALM, MODERATE, ROUGH }

enum class MasterSlave { MASTER, SLAVE }

/** Guard zone (报警圈) echo from setup status (02C4). */
data class GuardZoneStatus(
    val zone: Int,                 // 0 or 1
    val enabled: Boolean,
    val trueBearing: Boolean,      // 0 relative-to-bow, 1 true north
    val startRangeMeters: Int,
    val endRangeMeters: Int,
    /** Centre bearing, 0.1° units as reported; degrees after parse. */
    val bearingDeg: Double,
    /** Sector width in degrees; 360 = full circle. */
    val widthDeg: Double,
    val alarmType: GuardZoneAlarmType,
    val triggered: Boolean,
)

enum class GuardZoneAlarmType { LEAVING, ENTERING, BOTH }
