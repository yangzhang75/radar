package com.shipradar.comms.halo.target

import com.shipradar.contract.TargetSource
import com.shipradar.contract.TargetStatus
import com.shipradar.contract.TrackedTarget

/**
 * Pure mapping from the SDK-shaped intermediate model ([SdkTrackedTarget]) to the frozen
 * `com.shipradar.contract.TrackedTarget`. This is the part we CAN verify today without the real
 * wire format: unit conversions, enum/state mapping, validity/drop rules. The (unknown) wire decode
 * is isolated in [TargetParser] so that once a real-device capture pins the byte layout, only the
 * decoder needs to be filled in — this mapping stays unchanged.
 *
 * 合规追溯: HALO.
 */

/** Metres per nautical mile (exact, per IEC/SI definition). */
const val METERS_PER_NM: Double = 1852.0

/** Knots per metre-per-second (1 m/s = 3600/1852 kn). */
const val KNOTS_PER_MPS: Double = 3600.0 / METERS_PER_NM

/**
 * Map an SDK target record to a contract [TrackedTarget], or `null` if the record should be dropped
 * (not a displayable tracked target).
 *
 * Drop rules:
 *  - `targetValid == 0`  -> invalid record.
 *  - state is a failure/sentinel (ACQUIRE_FAILURE / FAIL_ACQUIRE_MAX / FAIL_ACQUIRE_POS / BAD_STATE)
 *    -> these are acquire-rejections (serverTargetId negative), not targets to plot.
 *
 * Kinematics source: prefer the absolute (true-north) info when [SdkTrackedTarget.infoAbsoluteValid]
 * is set (-> `trueBearing = true`); otherwise fall back to the own-ship-relative info
 * (-> `trueBearing = false`). This matches the contract's single bearing + `trueBearing` flag.
 */
fun SdkTrackedTarget.toContract(): TrackedTarget? {
    if (targetValid == 0) return null
    val status = targetState.toContractStatusOrNull() ?: return null

    val useAbsolute = infoAbsoluteValid != 0
    val info = if (useAbsolute) infoAbsolute else infoRelative

    val dangerous = targetState == SdkTargetState.DANGEROUS ||
        (acquisitionMask and SdkTrackedTarget.ACQUISITION_MASK_DANGEROUS) != 0L

    // serverTargetId is the stable radar-assigned id; fall back to the client id if unset/invalid.
    val id = if (serverTargetId >= 0) serverTargetId.toString() else targetId.toString()

    return TrackedTarget(
        id = id,
        source = TargetSource.RADAR_TT,
        rangeNm = info.distanceM / METERS_PER_NM,
        bearingDeg = info.bearingDdeg / 10.0,
        trueBearing = useAbsolute,
        latitude = null,  // SDK delivers polar (range/bearing) only; lat/lon needs own-ship fusion (out of scope here)
        longitude = null,
        courseDeg = info.courseDdeg / 10.0,
        speedKn = (info.speedDmps / 10.0) * KNOTS_PER_MPS,
        cpaNm = cpaM / METERS_PER_NM,
        tcpaSec = tcpaS.toDouble(),
        status = status,
        dangerous = dangerous,
    )
}

/**
 * Map an SDK [SdkTargetState] onto the contract [TargetStatus], or `null` for failure/sentinel
 * states that should not be plotted.
 *
 * Contract gaps (see delivery report): the contract has no distinct OUT_OF_RANGE status (folded into
 * LOST here) and a contract-only TEST_MANEUVER with no SDK equivalent.
 */
fun SdkTargetState.toContractStatusOrNull(): TargetStatus? = when (this) {
    SdkTargetState.ACQUIRING -> TargetStatus.ACQUIRING
    SdkTargetState.SAFE -> TargetStatus.TRACKED
    SdkTargetState.DANGEROUS -> TargetStatus.TRACKED
    SdkTargetState.LOST -> TargetStatus.LOST
    SdkTargetState.OUT_OF_RANGE -> TargetStatus.LOST       // contract has no OUT_OF_RANGE -> treat as not-plottable
    SdkTargetState.LOST_OUT_OF_RANGE -> TargetStatus.LOST
    SdkTargetState.ACQUIRE_FAILURE,
    SdkTargetState.FAIL_ACQUIRE_MAX,
    SdkTargetState.FAIL_ACQUIRE_POS,
    SdkTargetState.BAD_STATE -> null
}

/** Map a batch of SDK records, dropping any that fail [toContract]'s validity rules. */
fun mapTrackedTargets(records: List<SdkTrackedTarget>): List<TrackedTarget> =
    records.mapNotNull { it.toContract() }
