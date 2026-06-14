package com.shipradar.halofeed

import com.shipradar.comms.halo.image.SpokeParser
import com.shipradar.contract.SampleEncoding
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals

/**
 * 端到端互证:**halofeed 生成的真线缆字节**(含 8 字节包帧头,SpokePacket.FRAME_PREAMBLE)
 * ↔ **comms-core 的 SpokeParser 解析**。两块由不同来源/不同人实现,互相验证——这正是之前缺失、
 * 因而漏掉"DemoFeed 与解析器一起省略帧头"那个 bug 的测试。现在帧头若处理不当,本测试立即失败。
 */
class SpokeRoundTripTest {

    private fun spoke(az: Int, seq: Int, levels: ByteArray): Spoke {
        val data = Samples.packNibbles(levels)
        val h = SpokeHeader(
            spokeLengthBytes = SpokeHeader.HEADER_BYTES + data.size,
            sequenceNumber = seq,
            sampleEncoding = SampleEncoding.AMPLITUDE,
            nOfSamples = levels.size,
            bitsPerSample = 4,
            rangeCellSizeMm = 1500,
            spokeAzimuth = az,
            bearingZeroError = false,
            spokeCompass = 0,
            trueNorth = false,
            compassInvalid = true,
            rangeCellsDiv2 = 4,
        )
        return Spoke(h, data)
    }

    @Test fun halofeed_packet_decodes_through_comms_core_parser() {
        val l0 = ByteArray(8) { (it % 16).toByte() }
        val l1 = ByteArray(8) { ((it + 3) % 16).toByte() }
        // SpokePacket.build 会前置 8 字节帧头(真线缆格式)。
        val pkt = SpokePacket.build(listOf(spoke(az = 0, seq = 11, levels = l0), spoke(az = 2048, seq = 12, levels = l1)))

        val decoded = SpokeParser.parse(pkt)

        assertEquals(2, decoded.size, "含帧头的 halofeed 包应被解析器跳头并解出 2 根辐条")
        assertEquals(0.0, decoded[0].azimuthDeg, 1e-9)     // spokeAzimuth 0 -> 0°
        assertEquals(180.0, decoded[1].azimuthDeg, 1e-9)   // spokeAzimuth 2048 -> 180°
        assertEquals(11, decoded[0].sequenceNumber)
        assertEquals(12, decoded[1].sequenceNumber)
        assertContentEquals(l0, decoded[0].samples)
        assertContentEquals(l1, decoded[1].samples)
    }
}
