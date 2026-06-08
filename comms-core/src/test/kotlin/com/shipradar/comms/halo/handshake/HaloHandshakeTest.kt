package com.shipradar.comms.halo.handshake

import com.shipradar.constants.Endpoint
import com.shipradar.constants.HaloOpcodes
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class HaloHandshakeTest {

    private fun hex(s: String): ByteArray =
        s.replace(" ", "").replace("\n", "").chunked(2).map { it.toInt(16).toByte() }.toByteArray()

    /**
     * Exact 允许链接【01B2】 payload sample from 雷达天线端协议文档-HALO.docx §握手协议 (the "字段"
     * column, document order). 220 bytes: serial + radar IP + 0101 0600 + six groups.
     */
    private val sample01b2 = hex(
        "31333039333032353135000000000000" + // serial "1309302515" + null pad
        "C0A80064" +                          // radar IP 192.168.0.100
        "01010600" +                          // undocumented 4-byte header
        // group1: prefix FFFD, radarId 2001(A), count 2
        "FDFF20010200" + "10000000" + "C0A80064" + "1760" + "11000000" + "EC060716" + "1A26" +
        // group2: prefix 001F, radarId 2001(A), count 2
        "1F0020010200" + "10000000" + "EC060717" + "1A1C" + "11000000" + "EC060718" + "1A1D" +
        // group3: prefix 0010 (video), radarId 2001(A), count 3
        "100020010300" + "10000000" + "EC060708" + "1A16" + "11000000" + "EC06070A" + "1A18" +
            "12000000" + "EC060709" + "1A17" +
        // group4: prefix 0010 (video), radarId 2002(B), count 3
        "100020020300" + "10000000" + "EC06070D" + "1A01" + "11000000" + "EC06070E" + "1A02" +
            "12000000" + "EC06070F" + "1A03" +
        // group5: prefix 0012 (ARPA), radarId 2001(A), count 3
        "120020010300" + "10000000" + "EC060712" + "1A20" + "11000000" + "EC060714" + "1A22" +
            "12000000" + "EC060713" + "1A21" +
        // group6: prefix 0012 (ARPA), radarId 2002(B), count 3
        "120020020300" + "10000000" + "EC06070C" + "1A04" + "11000000" + "EC06070D" + "1A05" +
            "12000000" + "EC06070E" + "1A06"
    )

    @Test
    fun `01b2 sample is the documented 220 bytes`() {
        assertEquals(220, sample01b2.size)
    }

    @Test
    fun `parse 01b2 serial and radar ip`() {
        val info = HaloHandshake.parseLinkAllow(sample01b2)
        assertEquals("1309302515", info.serial)
        assertEquals(10, info.serial.length)
        assertEquals("192.168.0.100", info.radarIp)
        assertEquals(6, info.groups.size)
        assertEquals(false, info.manual)
    }

    @Test
    fun `parse 01b2 radar A video and ARPA endpoints`() {
        val info = HaloHandshake.parseLinkAllow(sample01b2)
        // Ports are big-endian: 0x1A16 = 6678, etc.
        assertEquals(Endpoint("236.6.7.8", 6678), info.image)
        assertEquals(Endpoint("236.6.7.10", 6680), info.control)
        assertEquals(Endpoint("236.6.7.9", 6679), info.status)
        assertEquals(Endpoint("236.6.7.18", 6688), info.target)
        assertEquals(Endpoint("236.6.7.20", 6690), info.trackControl)
        assertEquals(Endpoint("236.6.7.19", 6689), info.trackStatus)
    }

    @Test
    fun `parse 01b2 radar B endpoints resolved separately`() {
        val info = HaloHandshake.parseLinkAllow(sample01b2)
        assertEquals(Endpoint("236.6.7.13", 6657), info.endpoint(ChannelRole.IMAGE, RadarUnit.B))
        assertEquals(Endpoint("236.6.7.14", 6658), info.endpoint(ChannelRole.CONTROL, RadarUnit.B))
        assertEquals(Endpoint("236.6.7.15", 6659), info.endpoint(ChannelRole.STATUS, RadarUnit.B))
        assertEquals(Endpoint("236.6.7.12", 6660), info.endpoint(ChannelRole.TARGET, RadarUnit.B))
    }

    @Test
    fun `parse 01b2 group framing prefixes and radar ids`() {
        val g = HaloHandshake.parseLinkAllow(sample01b2).groups
        assertEquals(listOf(0xFFFD, 0x001F, 0x0010, 0x0010, 0x0012, 0x0012), g.map { it.prefix })
        assertEquals(listOf(0x0120, 0x0120, 0x0120, 0x0220, 0x0120, 0x0220), g.map { it.radarId })
        assertEquals(
            listOf(RadarUnit.A, RadarUnit.A, RadarUnit.A, RadarUnit.B, RadarUnit.A, RadarUnit.B),
            g.map { it.unit },
        )
        assertEquals(listOf(2, 2, 3, 3, 3, 3), g.map { it.channels.size })
    }

    @Test
    fun `first group carries the radar own unicast endpoint`() {
        val g0 = HaloHandshake.parseLinkAllow(sample01b2).groups.first()
        // 0x1760 = 5984
        assertEquals(Endpoint("192.168.0.100", 5984), g0.channels[0].endpoint)
        // unlabeled groups map to OTHER role
        assertTrue(g0.channels.all { it.role == ChannelRole.OTHER })
    }

    @Test
    fun `parse tolerates a leading 01b2 opcode header`() {
        val withOpcode = hex("01B2") + sample01b2
        val info = HaloHandshake.parseLinkAllow(withOpcode)
        assertEquals("1309302515", info.serial)
        assertEquals(Endpoint("236.6.7.8", 6678), info.image)
    }

    @Test
    fun `parse supports custom command header`() {
        val custom = 0x7788
        val pkt = hex("7788") + sample01b2
        val info = HaloHandshake.parseLinkAllow(pkt, expectedOpcode = custom)
        assertEquals("1309302515", info.serial)
    }

    @Test
    fun `build 01b1 request is opcode only in document order`() {
        assertContentEquals(byteArrayOf(0x01, 0xB1.toByte()), HaloHandshake.buildLinkRequest())
    }

    @Test
    fun `build 01b1 honors custom command header`() {
        assertContentEquals(
            byteArrayOf(0x77, 0x88.toByte()),
            HaloHandshake.buildLinkRequest(opcode = 0x7788),
        )
    }

    @Test
    fun `link request opcode matches shared constant`() {
        assertEquals(HaloOpcodes.LINK_REQUEST, 0x01B1)
        assertEquals(HaloOpcodes.LINK_ALLOW, 0x01B2)
    }

    @Test
    fun `truncated 01b2 fails with a clear message`() {
        val ex = assertFailsWith<IllegalArgumentException> {
            HaloHandshake.parseLinkAllow(sample01b2.copyOfRange(0, 30))
        }
        assertTrue(ex.message!!.contains("truncated"), "message was: ${ex.message}")
    }

    @Test
    fun `empty packet rejected`() {
        assertFailsWith<IllegalArgumentException> { HaloHandshake.parseLinkAllow(ByteArray(0)) }
    }

    @Test
    fun `manual fallback uses default endpoints and marks manual`() {
        val info = HaloHandshake.manualFallback("10.0.0.5", serial = "0000000001")
        assertTrue(info.manual)
        assertEquals("10.0.0.5", info.radarIp)
        assertEquals("0000000001", info.serial)
        assertEquals(Endpoint("236.6.7.8", 6678), info.image)
        assertEquals(Endpoint("236.6.7.10", 6680), info.control)
        assertEquals(Endpoint("236.6.7.9", 6679), info.status)
        assertEquals(Endpoint("236.6.7.18", 6688), info.target)
        assertEquals(Endpoint("236.6.7.20", 6690), info.trackControl)
        assertEquals(Endpoint("236.6.7.19", 6689), info.trackStatus)
    }

    @Test
    fun `manual fallback serial defaults to empty`() {
        assertEquals("", HaloHandshake.manualFallback("10.0.0.5").serial)
    }

    @Test
    fun `serial with no null padding decodes fully`() {
        // 16 ASCII chars, no null terminator.
        val raw = "1234567890ABCDEF".toByteArray(Charsets.US_ASCII) +
            hex("C0A80001") + hex("01010600")
        val info = HaloHandshake.parseLinkAllow(raw)
        assertEquals("1234567890ABCDEF", info.serial)
        assertEquals("192.168.0.1", info.radarIp)
        assertTrue(info.groups.isEmpty())
    }
}
