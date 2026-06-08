package com.shipradar.comms.halo.target

/**
 * Intermediate model of HALO target-tracking data, mirroring the Navico SDK 4.0.16 callback
 * structures (NRPClient/Protocol/TargetTracking.h, struct `tTrackedTarget`).
 *
 * WHY an intermediate model: the radar-side protocol document (雷达天线端协议文档-HALO.docx) lists
 * ONLY the multicast addresses for the target channels (236.6.7.18:6688 目标跟踪数据,
 * 236.6.7.19:6689 跟踪状态信息, 236.6.7.20:6690 跟踪控制) and gives NO wire byte format for the
 * payloads. The SDK headers, however, fully define the *semantics* (fields, units, enum values).
 * So we model the semantics here and decode the (still unknown) wire bytes into this model later.
 * See [TargetParser.decodeTargetRecords] for the wire-decode TODO boundary.
 *
 * Units & ranges below are copied verbatim from the SDK header comments — keep them authoritative.
 * Unsigned SDK fields (uint8/uint16/uint32) are widened to signed Kotlin types large enough to hold
 * the full unsigned range (uint32 -> Long) so the wire decoder never has to deal with sign wrap.
 *
 * 合规追溯: HALO (target tracking channel modeling).
 */

/** SDK `eTargetSource` (uint8). */
enum class SdkTargetSource(val raw: Int) {
    SIMULATED(0),
    REAL(1);

    companion object {
        fun fromRaw(raw: Int): SdkTargetSource? = entries.firstOrNull { it.raw == raw }
    }
}

/** SDK `eTargetType` (uint32). Only Vessel is defined in the SDK. */
enum class SdkTargetType(val raw: Long) {
    VESSEL(0);

    companion object {
        fun fromRaw(raw: Long): SdkTargetType? = entries.firstOrNull { it.raw == raw }
    }
}

/**
 * SDK `eTargetState` (uint32). Note this is a *sparse* enum: values 0..6, then 0x10/0x11, then a
 * sentinel BadState (0xBAD15BAD). Failure/sentinel states are not displayable targets — see
 * [TargetMapper] for the drop rules.
 */
enum class SdkTargetState(val raw: Long) {
    ACQUIRING(0),           // attempting to acquire target
    SAFE(1),                // acquired, not on a collision course
    DANGEROUS(2),           // acquired, may be on a collision course
    LOST(3),                // lost, needs to be canceled and reacquired
    ACQUIRE_FAILURE(4),     // failed to acquire
    OUT_OF_RANGE(5),        // target now out of range
    LOST_OUT_OF_RANGE(6),   // lost due to staying out of range
    FAIL_ACQUIRE_MAX(0x10), // acquire failed: no free target IDs
    FAIL_ACQUIRE_POS(0x11), // acquire failed: invalid position
    BAD_STATE(0xBAD15BADL); // sentinel / corrupt

    companion object {
        fun fromRaw(raw: Long): SdkTargetState? = entries.firstOrNull { it.raw == raw }
    }
}

/** SDK `eHeadingType` (uint32) — compass heading type used by the tracker. */
enum class SdkHeadingType(val raw: Long) {
    NONE(0),
    MAGNETIC(1),
    TRUE(2);

    companion object {
        fun fromRaw(raw: Long): SdkHeadingType? = entries.firstOrNull { it.raw == raw }
    }
}

/**
 * SDK `tTargetInfo` — target kinematics. The SDK carries this twice in each target: once relative
 * to own-ship (`infoRelative`) and once absolute / true-north referenced (`infoAbsolute`).
 *
 * @param distanceM   distance to target, metres (uint32)
 * @param bearingDdeg target bearing, deci-degrees i.e. 10ths of a degree (uint32)
 * @param courseDdeg  target course, deci-degrees (uint32)
 * @param speedDmps   target speed, deci-metres/second i.e. 10ths of a m/s (uint32)
 */
data class SdkTargetInfo(
    val distanceM: Long,
    val bearingDdeg: Long,
    val courseDdeg: Long,
    val speedDmps: Long,
)

/**
 * SDK `tTrackedTarget` — the full per-target record delivered on 236.6.7.18:6688.
 *
 * @param targetValid       0 invalid, 1 valid
 * @param targetSource      simulated vs real
 * @param targetType        vessel (only value defined)
 * @param targetId          client-assigned id (uint32), supplied by us at Acquire time
 * @param serverTargetId    server-assigned id (int32); negative when invalid (failed acquire)
 * @param targetState       acquisition / tracking state
 * @param acquisitionMask   0 = manually acquired; MSB ([ACQUISITION_MASK_DANGEROUS]) = acquired as
 *                          dangerous; low byte ([ACQUISITION_MASK_ZONES]) = auto-acquire zone bitmask
 * @param infoRelative      kinematics relative to own-ship's speed/direction
 * @param infoAbsolute      kinematics independent of own-ship (true-north referenced)
 * @param infoAbsoluteValid whether [infoAbsolute] is populated (0/1)
 * @param cpaM              closest point of approach, metres (int32, signed)
 * @param tcpaS             time to CPA, seconds (int32; negative = CPA already passed)
 * @param towardsCpa        0 = moving away from CPA, 1 = moving towards CPA
 */
data class SdkTrackedTarget(
    val targetValid: Int,
    val targetSource: SdkTargetSource,
    val targetType: SdkTargetType,
    val targetId: Long,
    val serverTargetId: Int,
    val targetState: SdkTargetState,
    val acquisitionMask: Long,
    val infoRelative: SdkTargetInfo,
    val infoAbsolute: SdkTargetInfo,
    val infoAbsoluteValid: Int,
    val cpaM: Int,
    val tcpaS: Int,
    val towardsCpa: Int,
) {
    companion object {
        /** acquisitionMask MSB set => target was acquired as a dangerous target. */
        const val ACQUISITION_MASK_DANGEROUS: Long = 1L shl 31

        /** acquisitionMask low byte => auto-acquire zone bitmask. */
        const val ACQUISITION_MASK_ZONES: Long = 0xFF
    }
}

/**
 * Intermediate model of the tracking *state/setup* channel (236.6.7.19:6689). The SDK splits this
 * into several observer callbacks (iTargetTrackingStateObserver: install, alarm-setup, properties,
 * navigation, zones-enabled, zones). We capture the fields a CAT-1 HMI needs for the target overlay
 * & danger-zone read-back. There is no `com.shipradar.contract` type for tracking state yet — see
 * the delivery report's contract-gap section.
 *
 * @param headingType        SDK `tTargetTrackingProperties.headingType`
 * @param safeZoneDistanceM  SDK `tTargetTrackingAlarmSetup.safeZoneDistance_m` (danger distance, metres)
 * @param safeZoneTimeS      SDK `tTargetTrackingAlarmSetup.safeZoneTime_s` (danger time, seconds)
 * @param minInputRangeDm    SDK `tTTStateInstall.minInputRange_dm` (deci-metres)
 * @param maxInputRangeDm    SDK `tTTStateInstall.maxInputRange_dm` (deci-metres)
 */
data class SdkTrackingState(
    val headingType: SdkHeadingType,
    val safeZoneDistanceM: Long,
    val safeZoneTimeS: Long,
    val minInputRangeDm: Long,
    val maxInputRangeDm: Long,
)
