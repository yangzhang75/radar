package com.shipradar.comms.halo.handshake

import com.shipradar.constants.Endpoint
import com.shipradar.constants.HaloEndpoints
import com.shipradar.constants.HaloOpcodes

/**
 * T1.1a — pure HALO 握手 (handshake) codec: build the 请求链接【01B1】 request and parse the
 * 允许链接【01B2】 reply. No sockets, no Android — the Foreground Service (wave 2) sends 01B1 on the
 * negotiation channel ([HaloEndpoints.NEGOTIATION] = 236.6.7.4:6768) and feeds the received 01B2
 * bytes to [parseLinkAllow]. Compliance: HALO-03.
 *
 * 01B2 payload layout (reverse-engineered from the §握手协议 允许链接 byte sample; the 220-byte
 * sample parses with zero leftover bytes and all pads zero):
 *
 *   serial[16]   ASCII digits + null padding -> 10-digit 机号
 *   radarIP[4]   dotted quad, network order
 *   hdr[4]       0101 0600  (undocumented group-section header — TODO(待协议))
 *   group* until end:
 *     prefix[2 LE]    group category (TODO(待协议): 0x0010 video, 0x0012 ARPA, 0xFFFD/0x001F first two)
 *     radarId[2 LE]   0x0120 = radar A, 0x0220 = radar B
 *     count[2 LE]     channel count
 *     channel[10]×count:
 *       roleTag[1]    0x10/0x11/0x12 = position within group
 *       pad[3]        00 00 00
 *       ip[4]         dotted quad
 *       port[2 BE]    big-endian (e.g. 1A16 = 6678)
 *
 * Known doc gaps flagged to 张建/协议 (NOT silently invented for type cert):
 *  - 01B1 请求 carries no documented payload; we send the opcode only. Any client-info payload is
 *    TODO(待协议).
 *  - The 4-byte hdr (0101 0600) and the 2-byte group prefix are not labelled in the doc; their
 *    structure is inferred from the single sample. Channel role mapping keys off prefix + role tag.
 *  - 自定义 section: serial / command header / protocol addresses are customizable; the parser
 *    therefore accepts a custom reply opcode and never hardcodes the negotiated addresses.
 */
object HaloHandshake {

    /** Where 01B1 is sent / 01B2 is received (single-radar default). */
    val NEGOTIATION_ENDPOINT: Endpoint = HaloEndpoints.NEGOTIATION

    private const val SERIAL_LEN = 16
    private const val HEADER_LEN = 4          // 0101 0600
    private const val GROUP_HEADER_LEN = 6    // prefix[2] + radarId[2] + count[2]
    private const val CHANNEL_LEN = 10        // roleTag[1] + pad[3] + ip[4] + port[2]

    private const val RADAR_ID_A = 0x0120
    private const val RADAR_ID_B = 0x0220

    private const val PREFIX_VIDEO = 0x0010   // {image, control, status}
    private const val PREFIX_ARPA = 0x0012    // {target, trackControl, trackStatus}

    private const val ROLE_TAG_BASE = 0x10    // tags run 0x10, 0x11, 0x12 ...

    /**
     * Build the 请求链接【01B1】 request. The doc specifies no payload, so this is the 2-byte opcode
     * in document order (`01 B1`). [opcode] is overridable per the 自定义 (custom command header)
     * support. Any future client-info payload is TODO(待协议).
     */
    fun buildLinkRequest(opcode: Int = HaloOpcodes.LINK_REQUEST): ByteArray =
        HandshakeBuf().opcode(opcode).build()

    /**
     * Parse a 允许链接【01B2】 reply into [RadarLinkInfo].
     *
     * @param packet either the full datagram (leading 2-byte opcode, e.g. `01 B2`, auto-detected
     *   and skipped) or the payload starting at the serial. Detection is safe because the serial's
     *   first byte is an ASCII digit (0x30–0x39), never the opcode high byte.
     * @param expectedOpcode the reply opcode to detect/skip (overridable for 自定义 command headers).
     */
    fun parseLinkAllow(packet: ByteArray, expectedOpcode: Int = HaloOpcodes.LINK_ALLOW): RadarLinkInfo {
        require(packet.isNotEmpty()) { "HALO 01B2 empty packet" }
        val r = HandshakeReader(packet)

        // Skip the opcode header if present (document order: high byte, low byte).
        val head = r.peek(2)
        if (head.size == 2 &&
            (head[0].toInt() and 0xFF) == ((expectedOpcode ushr 8) and 0xFF) &&
            (head[1].toInt() and 0xFF) == (expectedOpcode and 0xFF)
        ) {
            r.skip(2)
        }

        val serial = decodeSerial(r.bytes(SERIAL_LEN))
        val radarIp = r.ipv4()
        r.skip(HEADER_LEN) // 0101 0600 — undocumented, skipped

        val groups = ArrayList<EndpointGroup>()
        while (r.remaining >= GROUP_HEADER_LEN) {
            val prefix = r.u16le()
            val radarId = r.u16le()
            val count = r.u16le()
            val unit = radarUnitOf(radarId)
            val channels = ArrayList<NegotiatedChannel>(count)
            for (i in 0 until count) {
                val roleTag = r.u8()
                r.skip(3) // pad 00 00 00
                val ip = r.ipv4()
                val port = r.u16be()
                channels += NegotiatedChannel(
                    unit = unit,
                    role = roleOf(prefix, roleTag),
                    endpoint = Endpoint(ip, port),
                    rawRoleTag = roleTag,
                )
            }
            groups += EndpointGroup(prefix, radarId, unit, channels)
        }
        return RadarLinkInfo(serial = serial, radarIp = radarIp, groups = groups)
    }

    /**
     * Manual-IP fallback (蒲公英 VPN 组播失败兜底): skip the handshake and synthesise a
     * [RadarLinkInfo] from a manually-configured radar IP plus the default single-radar
     * [HaloEndpoints]. [serial] is unknown without a handshake; defaults to empty.
     */
    fun manualFallback(radarIp: String, serial: String = ""): RadarLinkInfo {
        fun ch(role: ChannelRole, tag: Int, ep: Endpoint) =
            NegotiatedChannel(RadarUnit.A, role, ep, tag)
        val video = EndpointGroup(
            prefix = PREFIX_VIDEO, radarId = RADAR_ID_A, unit = RadarUnit.A,
            channels = listOf(
                ch(ChannelRole.IMAGE, 0x10, HaloEndpoints.IMAGE),
                ch(ChannelRole.CONTROL, 0x11, HaloEndpoints.CONTROL),
                ch(ChannelRole.STATUS, 0x12, HaloEndpoints.STATUS),
            ),
        )
        val arpa = EndpointGroup(
            prefix = PREFIX_ARPA, radarId = RADAR_ID_A, unit = RadarUnit.A,
            channels = listOf(
                ch(ChannelRole.TARGET, 0x10, HaloEndpoints.TARGET),
                ch(ChannelRole.TRACK_CONTROL, 0x11, HaloEndpoints.TRACK_CONTROL),
                ch(ChannelRole.TRACK_STATUS, 0x12, HaloEndpoints.TRACK_STATUS),
            ),
        )
        return RadarLinkInfo(serial = serial, radarIp = radarIp, groups = listOf(video, arpa), manual = true)
    }

    // --- internals ---

    /** Serial is ASCII digits padded with nulls; strip at the first null and trim. */
    private fun decodeSerial(raw: ByteArray): String {
        val end = raw.indexOf(0).let { if (it < 0) raw.size else it }
        return String(raw, 0, end, Charsets.US_ASCII).trim()
    }

    private fun radarUnitOf(radarId: Int): RadarUnit = when (radarId) {
        RADAR_ID_A -> RadarUnit.A
        RADAR_ID_B -> RadarUnit.B
        else -> RadarUnit.UNKNOWN
    }

    /** Map (group prefix, role tag) to a semantic channel role. Position = roleTag - 0x10. */
    private fun roleOf(prefix: Int, roleTag: Int): ChannelRole {
        val idx = roleTag - ROLE_TAG_BASE
        val table = when (prefix) {
            PREFIX_VIDEO -> listOf(ChannelRole.IMAGE, ChannelRole.CONTROL, ChannelRole.STATUS)
            PREFIX_ARPA -> listOf(ChannelRole.TARGET, ChannelRole.TRACK_CONTROL, ChannelRole.TRACK_STATUS)
            else -> emptyList()
        }
        return table.getOrElse(idx) { ChannelRole.OTHER }
    }

    private fun ByteArray.indexOf(b: Byte): Int {
        for (i in indices) if (this[i] == b) return i
        return -1
    }
}
