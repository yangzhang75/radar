package com.shipradar.comms.halo.target

import com.shipradar.contract.TrackedTarget

/**
 * Entry points for the HALO target-tracking channels. The mapping layer ([TargetMapper]) is fully
 * implemented and tested; the **wire decode** layer is a documented stub because the radar protocol
 * document gives no byte format for these payloads (only the multicast addresses). Once a real-device
 * capture pins the layout, fill in [decodeTargetRecords] / [decodeTrackingState] and the rest works
 * unchanged.
 *
 * Channels (com.shipradar.constants.HaloEndpoints):
 *  - TARGET        236.6.7.18:6688  radar -> sw  tracked targets       -> [parseTargets]
 *  - TRACK_STATUS  236.6.7.19:6689  radar -> sw  tracking state/setup  -> [parseTrackStatus]
 *
 * 合规追溯: HALO.
 */
object TargetParser {

    /**
     * Parse a UDP datagram from the TARGET channel (236.6.7.18:6688) into contract targets.
     *
     * Currently returns an empty list: the wire framing is unknown (see [decodeTargetRecords]). The
     * value lives in the mapping, which is exercised directly in tests via [mapTrackedTargets].
     */
    fun parseTargets(bytes: ByteArray): List<TrackedTarget> =
        mapTrackedTargets(decodeTargetRecords(bytes))

    /**
     * Parse a UDP datagram from the TRACK_STATUS channel (236.6.7.19:6689) into the intermediate
     * tracking-state model. Returns `null` until the wire format is known (see [decodeTrackingState]).
     */
    fun parseTrackStatus(bytes: ByteArray): SdkTrackingState? = decodeTrackingState(bytes)

    /**
     * TODO(待协议: 236.6.7.18 wire format，需真机抓包/SDK源): decode the raw target datagram into
     * [SdkTrackedTarget] records. The 雷达天线端协议文档-HALO.docx lists only the address, not the
     * payload layout.
     *
     * CANDIDATE LAYOUT (hypothesis only — DO NOT trust until confirmed by capture): the Navico SDK
     * 4.0.16 callback struct `tTrackedTarget` is byte-packed (BytePackOn.h => #pragma pack(1)) and
     * little-endian. If the wire mirrors that in-memory struct, one record is the following fields
     * back-to-back (offsets in bytes, total 70):
     *   off  size  field
     *    0    1    targetValid        (u8)
     *    1    1    targetSource        (u8  eTargetSource)
     *    2    4    targetType          (u32 eTargetType)
     *    6    4    targetID            (u32)
     *   10    4    serverTargetID      (i32)
     *   14    4    targetState         (u32 eTargetState)
     *   18    4    acquisitionMask     (u32)
     *   22   16    infoRelative        (4 x u32: distance_m, bearing_ddeg, course_ddeg, speed_dmps)
     *   38   16    infoAbsolute        (4 x u32)
     *   54    1    infoAbsoluteValid   (u8)
     *   55    4    CPA_m               (i32)
     *   59    4    TCPA_s              (i32)
     *   63    1    towardsCPA          (u8)   => 64-byte struct (no trailing pad under pack(1))
     * The real on-wire format very likely differs (framing header, count, target id list, endianness,
     * possibly only deltas). Validate truncated/short datagrams here and DROP malformed records
     * rather than throwing, so a corrupt packet never crashes the receive loop.
     */
    internal fun decodeTargetRecords(@Suppress("UNUSED_PARAMETER") bytes: ByteArray): List<SdkTrackedTarget> {
        // TODO(待协议): no wire layout available; emit nothing until real-device capture.
        return emptyList()
    }

    /**
     * TODO(待协议: 236.6.7.19 wire format，需真机抓包/SDK源): decode the tracking state/setup datagram
     * into [SdkTrackingState]. The SDK splits this across several observer messages
     * (iTargetTrackingStateObserver); the wire framing that multiplexes them on 236.6.7.19 is not
     * documented.
     */
    internal fun decodeTrackingState(@Suppress("UNUSED_PARAMETER") bytes: ByteArray): SdkTrackingState? {
        // TODO(待协议): no wire layout available.
        return null
    }
}
