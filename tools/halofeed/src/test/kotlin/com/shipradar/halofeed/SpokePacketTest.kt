package com.shipradar.halofeed

import com.shipradar.contract.SampleEncoding
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class SpokePacketTest {

    @Test fun preambleIsEightBytes() {
        assertEquals(8, SpokePacket.FRAME_PREAMBLE.size)
    }

    @Test fun defaultPacketWithinMtu() {
        val cfg = FeedConfig()
        assertTrue(cfg.packetBytes <= FeedConfig.MAX_PACKET_BYTES, "packet ${cfg.packetBytes}B")
        // sanity: 8 + 2 × (24 + 512) = 1080
        assertEquals(1080, cfg.packetBytes)
    }

    @Test fun typicalSpokeIs536Bytes() {
        // Matches the doc's worked example: 24-byte header + 1024×4-bit = 536.
        val spoke = SpokeSource(FeedConfig()).spokeAt(0, 0)
        assertEquals(536, spoke.header.spokeLengthBytes)
        assertEquals(536, spoke.toBytes().size)
    }

    @Test fun packetCanBeWalkedBySpokeLength() {
        // A parser should iterate spokes using spokeLength_bytes against the datagram length.
        val cfg = FeedConfig(spokesPerPacket = 2)
        val src = SpokeSource(cfg)
        val spokes = listOf(src.spokeAt(10, 100), src.spokeAt(11, 101))
        val packet = SpokePacket.build(spokes)

        // preamble emitted verbatim
        assertContentEquals(SpokePacket.FRAME_PREAMBLE, packet.copyOfRange(0, 8))

        var pos = SpokePacket.FRAME_PREAMBLE.size
        val recovered = buildList {
            while (pos < packet.size) {
                val h = SpokeHeader.fromBytes(packet, pos)
                add(h)
                pos += h.spokeLengthBytes
            }
        }
        assertEquals(packet.size, pos) // consumed exactly
        assertEquals(2, recovered.size)
        assertEquals(10, recovered[0].spokeAzimuth)
        assertEquals(100, recovered[0].sequenceNumber)
        assertEquals(11, recovered[1].spokeAzimuth)
        assertEquals(101, recovered[1].sequenceNumber)
    }

    @Test fun dopplerConfigProducesDopplerEncoding() {
        val spoke = SpokeSource(FeedConfig(doppler = true)).spokeAt(0, 0)
        assertEquals(SampleEncoding.DOPPLER, spoke.header.sampleEncoding)
    }
}
