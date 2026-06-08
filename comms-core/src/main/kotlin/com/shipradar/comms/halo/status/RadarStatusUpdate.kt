package com.shipradar.comms.halo.status

import com.shipradar.contract.GuardZoneAlarmType
import com.shipradar.contract.GuardZoneStatus
import com.shipradar.contract.RadarPowerState
import com.shipradar.contract.RadarStatus
import com.shipradar.contract.SeaMode
import com.shipradar.contract.SeaState

/**
 * T1.3b — a single parsed HALO status message (协议文档 §雷达状态). Compliance: HALO-03.
 *
 * HALO status arrives as PARTIAL messages (01C4 mode / 02C4 setup / 08C4 ext setup / 00C6 alarm /
 * 10C6 error), each carrying only its own fields. The contract [RadarStatus] is a full snapshot, so
 * the sync layer (T1.6) folds successive updates into a running snapshot via [applyTo].
 */
sealed interface RadarStatusUpdate {
    /** Merge this update's fields into [prev], returning the new snapshot. */
    fun applyTo(prev: RadarStatus): RadarStatus

    /** 模式状态 [01C4]. */
    data class Mode(
        val powerState: RadarPowerState,
        val timedTransmit: Boolean,
        /** Seconds from warmup -> standby. */
        val warmupRemainSec: Int,
        /** Timed-transmit countdown (s) before the next transmit<->standby toggle. */
        val timedCountSec: Int,
    ) : RadarStatusUpdate {
        override fun applyTo(prev: RadarStatus): RadarStatus = prev.copy(
            powerState = powerState,
            timedTransmit = timedTransmit,
            warmupRemainSec = if (powerState == RadarPowerState.WARMUP) warmupRemainSec else null,
        )
    }

    /** 设置状态 [02C4]. */
    data class Setup(
        val rangeMeters: Int,
        val gainAuto: Boolean,
        val gain: Int,
        val seaMode: SeaMode,
        val seaLevel: Int,
        val rainLevel: Int,
        val ftcLevel: Int,
        val interferenceRejection: Int,
        val targetExpansion: Boolean,
        val targetBoost: Int,
        /** 报警灵敏度 0..255 (not part of the RadarStatus snapshot; carried for completeness). */
        val guardSensitivity: Int,
        val guardZones: List<GuardZoneStatus>,
    ) : RadarStatusUpdate {
        override fun applyTo(prev: RadarStatus): RadarStatus = prev.copy(
            rangeMeters = rangeMeters,
            gainAuto = gainAuto,
            gain = gain,
            seaMode = seaMode,
            seaLevel = seaLevel,
            rainLevel = rainLevel,
            ftcLevel = ftcLevel,
            interferenceRejection = interferenceRejection,
            targetExpansion = targetExpansion,
            targetBoost = targetBoost,
            guardZones = guardZones,
        )
    }

    /** 扩展设置状态 [08C4]. */
    data class ExtSetup(
        val seaState: SeaState,
        /** 本地IR 0..3. */
        val localInterferenceRejection: Int,
        /** 快速扫描模式: 0 关 / 1 36rpm / 2 48rpm. */
        val fastScanMode: Int,
        val sidelobeAuto: Boolean,
        val sidelobeLevel: Int,
        val rpmX10: Int,
        /** 干扰抑制 0..2 (噪音抑制). */
        val noiseRejection: Int,
        /** 目标分离 0..3. */
        val targetSeparation: Int,
    ) : RadarStatusUpdate {
        override fun applyTo(prev: RadarStatus): RadarStatus = prev.copy(
            seaState = seaState,
            rpmX10 = rpmX10,
        )
    }

    /** 报警状态 [00C6] — a guard-zone alarm event. Surfaced as an AlarmEvent by the alarm layer (T2.8). */
    data class Alarm(
        val zone: Int,
        val type: GuardZoneAlarmType,
        val activation: HaloAlarmActivation,
    ) : RadarStatusUpdate {
        // An event, not steady state: leave the snapshot unchanged.
        override fun applyTo(prev: RadarStatus): RadarStatus = prev
    }

    /** 雷达错误信息 [10C6]. Surfaced as an AlarmEvent by the alarm layer (T2.8). */
    data class RadarError(
        val error: HaloError,
        val relatedInfo: Long,
        val moreInfo: Long,
    ) : RadarStatusUpdate {
        override fun applyTo(prev: RadarStatus): RadarStatus = prev
    }

    /** A status header we don't (yet) parse — kept so the caller can log/ignore rather than crash. */
    data class Unknown(val header: Int, val raw: ByteArray) : RadarStatusUpdate {
        override fun applyTo(prev: RadarStatus): RadarStatus = prev

        override fun equals(other: Any?): Boolean =
            other is Unknown && header == other.header && raw.contentEquals(other.raw)
        override fun hashCode(): Int = 31 * header + raw.contentHashCode()
    }
}

/** 00C6 报警状态: 0x1 激活 / 0x2 未激活 / 0x3 取消. */
enum class HaloAlarmActivation { ACTIVE, INACTIVE, CANCELLED, UNKNOWN }

/**
 * 10C6 雷达错误类型 (协议文档 §雷达错误信息) — the 13 documented fault codes plus UNKNOWN.
 * Raw codes are the eError* enum values from the doc.
 */
enum class HaloError(val code: Long) {
    PERSISTENCE_CORRUPT(0x00000001),
    ZERO_BEARING_FAULT(0x00010001),
    BEARING_PULSE_FAULT(0x00010002),
    MOTOR_NOT_RUNNING(0x00010003),
    COMMS_NOT_ACTIVE(0x00010004),
    MAGNETRON_HEATER_VOLTAGE(0x00010005),
    MODULATION_VOLTAGE(0x00010006),
    TRIGGER_FAULT(0x00010007),
    VIDEO_FAULT(0x00010008),
    FAN_FAULT(0x00010009),
    SCANNER_CONFIG_FAULT(0x0001000A),
    POWER_SUPPLY_TRANSIENT(0x0001000B),
    SCANNER_DETECT_FAIL(0x0001000C),
    UNKNOWN(-1);

    companion object {
        fun fromCode(code: Long): HaloError = entries.firstOrNull { it.code == code } ?: UNKNOWN
    }
}
