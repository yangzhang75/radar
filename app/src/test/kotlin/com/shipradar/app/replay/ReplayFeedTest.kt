package com.shipradar.app.replay

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** W8-D — 抓包解析纯函数单测。造一个 8B HALO 帧头 + 536B 辐条的图像包,断言取出正确载荷。 */
class ReplayFeedTest {

    /** 8 字节 HALO 帧头 `01 00 00 00 00 20 00 02`。 */
    private val haloHeader = intArrayOf(0x01, 0x00, 0x00, 0x00, 0x00, 0x20, 0x00, 0x02)

    /** 构造整 IP 报文:20B IP 头 + 8B UDP 头 + (8B 帧头 + spokeLen 字节辐条)。 */
    private fun buildDatagram(dstPort: Int, spokeLen: Int): ByteArray {
        val payloadLen = haloHeader.size + spokeLen
        val dg = ByteArray(20 + 8 + payloadLen)
        dg[0] = 0x45                                   // ihl=5 (20B), ver=4
        dg[9] = 0x11                                   // proto = UDP
        dg[16] = 236.toByte(); dg[17] = 6; dg[18] = 7; dg[19] = 8 // dst ip 236.6.7.8
        dg[22] = ((dstPort shr 8) and 0xFF).toByte()   // UDP dst port hi
        dg[23] = (dstPort and 0xFF).toByte()           // UDP dst port lo
        for (i in haloHeader.indices) dg[28 + i] = haloHeader[i].toByte()
        // 余下辐条字节留 0;给首个辐条字节一个标记便于核对偏移。
        if (spokeLen > 0) dg[28 + haloHeader.size] = 0xAB.toByte()
        return dg
    }

    private fun toCaptureText(dg: ByteArray, dstPort: Int, withAscii: Boolean): String {
        val sb = StringBuilder("(UDP)192.168.0.100:1084->236.6.7.8:$dstPort ,${dg.size} Bytes\n\n")
        dg.toList().chunked(16).forEach { row ->
            val hex = row.joinToString(" ") { "%02X".format(it) }
            if (withAscii) sb.append(hex).append("  ; ").append("·".repeat(row.size)).append('\n')
            else sb.append(hex).append('\n')
        }
        return sb.toString()
    }

    @Test fun parsesImagePacket_payloadLengthAndFirstByte() {
        // 536B 辐条 + 8B 帧头 => 载荷 544B,首字节 0x01。
        val dg = buildDatagram(dstPort = 6678, spokeLen = 536)
        val out = ReplayFeed.parseCapture(toCaptureText(dg, 6678, withAscii = true))
        assertEquals(1, out.size)
        val (ep, payload) = out[0]
        assertEquals(6678, ep.port)
        assertEquals("236.6.7.8", ep.host)
        assertEquals(544, payload.size)                       // 8 + 536
        assertEquals(0x01.toByte(), payload[0])               // HALO 帧头首字节
        assertEquals(0x02.toByte(), payload[7])               // 帧头第 8 字节
        assertEquals(0xAB.toByte(), payload[8])               // 辐条首字节(偏移正确)
    }

    @Test fun stripsAsciiComment_sameAsWithout() {
        val dg = buildDatagram(6678, 536)
        val withC = ReplayFeed.parseCapture(toCaptureText(dg, 6678, withAscii = true))[0].second
        val without = ReplayFeed.parseCapture(toCaptureText(dg, 6678, withAscii = false))[0].second
        assertTrue(withC.contentEquals(without))
        assertEquals(544, withC.size)
    }

    @Test fun multipleBlocks_andBadBlockSkipped() {
        val good = toCaptureText(buildDatagram(6678, 100), 6678, withAscii = true)
        val bad = "(UDP)1.2.3.4:1->5.6.7.8:6679 ,3 Bytes\n\n45 00 11\n" // 太短(<28)→跳过
        val good2 = toCaptureText(buildDatagram(6688, 50), 6688, withAscii = false)
        val out = ReplayFeed.parseCapture(good + bad + good2)
        assertEquals(2, out.size)                              // 坏块被跳过
        assertEquals(6678, out[0].first.port)
        assertEquals(6688, out[1].first.port)
        assertEquals(108, out[0].second.size)                 // 8 + 100
    }

    @Test fun channelMapping() {
        assertEquals(ReplayFeed.ReplayChannel.IMAGE, ReplayFeed.channelOf(6678))
        assertEquals(ReplayFeed.ReplayChannel.IMAGE, ReplayFeed.channelOf(6656))
        assertEquals(ReplayFeed.ReplayChannel.STATUS, ReplayFeed.channelOf(6679))
        assertEquals(ReplayFeed.ReplayChannel.TARGET, ReplayFeed.channelOf(6688))
        assertEquals(ReplayFeed.ReplayChannel.UNKNOWN, ReplayFeed.channelOf(1234))
    }

    @Test fun ipUdpPayload_honoursIhl() {
        // ihl=6 (24B IP 头 + options) -> 载荷从 24+8=32 开始。
        val dg = ByteArray(40)
        dg[0] = 0x46 // ihl=6
        dg[32] = 0x99.toByte()
        val p = ReplayFeed.ipUdpPayload(dg)!!
        assertEquals(8, p.size)
        assertEquals(0x99.toByte(), p[0])
    }

    @Test fun emptyOrGarbage_returnsEmpty() {
        assertTrue(ReplayFeed.parseCapture("").isEmpty())
        assertTrue(ReplayFeed.parseCapture("no udp here\njust text\n").isEmpty())
    }

    @Test fun realAssetSampleShape_parsesOneImagePacket() {
        // 模拟真录像头行(单大图像包),仅放 8B 帧头 + 几字节,验证端口/首字节。
        val dg = buildDatagram(6678, 4)
        val out = ReplayFeed.parseCapture(toCaptureText(dg, 6678, withAscii = true))
        assertEquals(1, out.size)
        assertEquals(ReplayFeed.ReplayChannel.IMAGE, ReplayFeed.channelOf(out[0].first.port))
        assertEquals(0x01.toByte(), out[0].second[0])
    }
}
