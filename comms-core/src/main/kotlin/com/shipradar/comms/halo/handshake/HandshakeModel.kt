package com.shipradar.comms.halo.handshake

import com.shipradar.constants.Endpoint

/**
 * T1.1a — typed model of the HALO 握手 (handshake) 允许链接【01B2】 reply.
 *
 * The reply carries the radar serial, the radar's own IP, and the negotiated multicast/unicast
 * address list (协议地址), grouped per physical radar (HALO supports dual-range A/B). Byte layout
 * reverse-engineered from the §握手协议 允许链接 sample — see [HaloHandshake] for the parser and
 * the documented framing. Compliance: HALO-03.
 */

/** Which physical radar a channel/group belongs to (HALO dual-range). */
enum class RadarUnit { A, B, UNKNOWN }

/**
 * Semantic role of a negotiated channel. Derived from the group [EndpointGroup.prefix] +
 * the per-channel role tag (0x10/0x11/0x12 = position within the group). OTHER covers the first
 * two groups (negotiation/service/discovery) whose framing the doc does not label.
 */
enum class ChannelRole { IMAGE, CONTROL, STATUS, TARGET, TRACK_CONTROL, TRACK_STATUS, OTHER }

/** One negotiated channel: its semantic role and the endpoint to bind/send on. */
data class NegotiatedChannel(
    val unit: RadarUnit,
    val role: ChannelRole,
    val endpoint: Endpoint,
    /** Raw role tag as on the wire (0x10/0x11/0x12). Kept for traceability / debugging. */
    val rawRoleTag: Int,
)

/**
 * One group from the 01B2 reply: all channels for one (category, radar) pair.
 * [prefix] is a 2-byte little-endian group category whose exact semantics the doc does not define
 * (TODO(待协议)); observed values: 0x0010 = radar-video group, 0x0012 = ARPA/target group,
 * 0xFFFD / 0x001F = the first two (negotiation/service) groups.
 */
data class EndpointGroup(
    val prefix: Int,
    val radarId: Int,
    val unit: RadarUnit,
    val channels: List<NegotiatedChannel>,
)

/**
 * Fully parsed handshake result: the negotiated link parameters the Foreground Service needs to
 * bind every multicast channel. Also produced (with [manual] = true) by the manual-IP fallback when
 * the handshake itself cannot complete (e.g. 蒲公英 VPN drops the negotiation multicast).
 */
data class RadarLinkInfo(
    /** 10-digit radar 机号 (serial). Trailing nulls stripped. */
    val serial: String,
    /** Radar IP, dotted-quad. */
    val radarIp: String,
    val groups: List<EndpointGroup>,
    /** True when synthesised from a manually-configured IP + default endpoints (no 01B2 parsed). */
    val manual: Boolean = false,
) {
    /** All negotiated channels across every group, flattened. */
    val channels: List<NegotiatedChannel> get() = groups.flatMap { it.channels }

    /** First endpoint matching [unit] + [role], or null if not negotiated. */
    fun endpoint(role: ChannelRole, unit: RadarUnit = RadarUnit.A): Endpoint? =
        channels.firstOrNull { it.unit == unit && it.role == role }?.endpoint

    // Convenience accessors for the primary radar (A).
    val image: Endpoint? get() = endpoint(ChannelRole.IMAGE)
    val control: Endpoint? get() = endpoint(ChannelRole.CONTROL)
    val status: Endpoint? get() = endpoint(ChannelRole.STATUS)
    val target: Endpoint? get() = endpoint(ChannelRole.TARGET)
    val trackControl: Endpoint? get() = endpoint(ChannelRole.TRACK_CONTROL)
    val trackStatus: Endpoint? get() = endpoint(ChannelRole.TRACK_STATUS)
}
