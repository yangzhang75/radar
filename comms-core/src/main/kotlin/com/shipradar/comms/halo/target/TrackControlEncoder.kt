package com.shipradar.comms.halo.target

import com.shipradar.contract.TrackCommand

/**
 * Track-control command modeling for the TRACK_CONTROL channel (236.6.7.20:6690).
 *
 * Same shape as the read path: we map the frozen `com.shipradar.contract.TrackCommand` onto an
 * SDK-faithful intent ([SdkTrackCommand], mirroring tTargetTrackingClient::Acquire / Cancel /
 * CancelAll), which IS testable today; the final wire serialization is a TODO because the radar
 * document gives no byte format for this channel either.
 *
 * Note: the contract `TrackCommand` is intentionally minimal (Acquire(rangeNm, bearingDeg),
 * Cancel(targetId)). The SDK Acquire additionally needs a client target id and a bearing type
 * (relative vs absolute/true), and offers CancelAll — see the delivery report's contract-gap list.
 * Those extra inputs are passed here as explicit parameters with documented defaults.
 *
 * 合规追溯: HALO.
 */

/** Bearing reference for an acquire, matching SDK `eBearingType`. */
enum class SdkBearingType(val raw: Int) {
    RELATIVE(0),  // relative to own-ship heading
    ABSOLUTE(1);  // true-north referenced
}

/** SDK-faithful track-control intent (1:1 with tTargetTrackingClient command methods). */
sealed interface SdkTrackCommand {
    /** Acquire: SDK `Acquire(id, range_m, bearing_deg, bearingType)`. range metres, bearing whole degrees. */
    data class Acquire(
        val id: Long,
        val rangeM: Long,
        val bearingDeg: Int,
        val bearingType: SdkBearingType,
    ) : SdkTrackCommand

    /** Cancel one target: SDK `Cancel(serverID)`. */
    data class Cancel(val serverId: Long) : SdkTrackCommand

    /** Cancel all targets: SDK `CancelAll()`. */
    data object CancelAll : SdkTrackCommand
}

object TrackControlEncoder {

    /**
     * Lower a contract [TrackCommand] to the SDK intent, or `null` if it cannot be represented
     * (e.g. a Cancel whose targetId is not a numeric server id).
     *
     * @param clientId    client-assigned target id for Acquire (contract omits it). Defaults to 0.
     * @param bearingType how to interpret the contract bearing; contract `bearingDeg` is documented
     *                    as true-north in the UI, so default ABSOLUTE.
     */
    fun toSdkCommand(
        command: TrackCommand,
        clientId: Long = 0,
        bearingType: SdkBearingType = SdkBearingType.ABSOLUTE,
    ): SdkTrackCommand? = when (command) {
        is TrackCommand.Acquire -> SdkTrackCommand.Acquire(
            id = clientId,
            rangeM = Math.round(command.rangeNm * METERS_PER_NM),
            // SDK Acquire takes whole-degree bearing (uint16); round to nearest and normalize to 0..359.
            bearingDeg = ((Math.round(command.bearingDeg).toInt() % 360) + 360) % 360,
            bearingType = bearingType,
        )
        is TrackCommand.Cancel ->
            command.targetId.toLongOrNull()?.let { SdkTrackCommand.Cancel(it) }
    }

    /**
     * TODO(待协议: 236.6.7.20 wire format，需真机抓包/SDK源): serialize an [SdkTrackCommand] into the
     * TRACK_CONTROL datagram bytes. The document gives only the address; the opcode/field layout for
     * acquire/cancel is unknown. (Overlaps with T1.3a control-command encoding — final ownership to
     * be decided by the orchestrator; modeled here to keep the target path self-contained.)
     */
    fun encode(@Suppress("UNUSED_PARAMETER") command: SdkTrackCommand): ByteArray {
        TODO("待协议: 236.6.7.20 track-control wire format unknown; needs real-device capture / SDK source")
    }
}
