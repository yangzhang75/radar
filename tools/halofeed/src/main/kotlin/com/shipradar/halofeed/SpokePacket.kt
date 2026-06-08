package com.shipradar.halofeed

import com.shipradar.contract.SampleEncoding

/** One spoke ready to serialize: its 24-byte header plus already-nibble-packed sample data. */
data class Spoke(val header: SpokeHeader, val data: ByteArray) {
    fun toBytes(): ByteArray = header.toBytes() + data
}

/**
 * Assembles HALO image-data UDP packets per §辐条(Spoke)分配 ("每个数据包可以包含多个辐条").
 *
 * Packet layout: an 8-byte frame preamble followed by N back-to-back spokes.
 *
 * TODO(待张建): the doc shows the preamble literally as `0100 0000 0020 0002` before `Spoke[32]`
 * but does not name its fields. The `0x20`(=32) likely relates to the Spoke[32] array capacity,
 * not the actual spoke count of a partial packet. We therefore emit the documented bytes verbatim
 * and DO NOT trust them to carry a live count. A robust parser (T1.2) should iterate spokes using
 * each header's spokeLength_bytes against the datagram length, not a preamble count. Confirm the
 * exact preamble semantics with 张建 before relying on them.
 */
object SpokePacket {
    /** Documented 8-byte preamble bytes, in wire order. See class TODO. */
    val FRAME_PREAMBLE = byteArrayOf(0x01, 0x00, 0x00, 0x00, 0x00, 0x20, 0x00, 0x02)

    /** Serialize a frame preamble + the given spokes into one datagram payload. */
    fun build(spokes: List<Spoke>): ByteArray {
        var size = FRAME_PREAMBLE.size
        for (s in spokes) size += s.header.spokeLengthBytes
        val out = ByteArray(size)
        FRAME_PREAMBLE.copyInto(out, 0)
        var pos = FRAME_PREAMBLE.size
        for (s in spokes) {
            val bytes = s.toBytes()
            bytes.copyInto(out, pos)
            pos += bytes.size
        }
        return out
    }
}

/**
 * Turns a [FeedConfig] + [Scene] into spokes. Stateless except for the rolling sequenceNumber,
 * which is supplied by the caller so timing/transport stays in [main].
 */
class SpokeSource(private val cfg: FeedConfig, private val scene: Scene = Scene(cfg.nOfSamples)) {
    private val encoding = if (cfg.doppler) SampleEncoding.DOPPLER else SampleEncoding.AMPLITUDE

    /** Build one spoke at raw [spokeAzimuth] (0..4095) with the given [sequenceNumber] (0..4095). */
    fun spokeAt(spokeAzimuth: Int, sequenceNumber: Int): Spoke {
        val levels = scene.samplesFor(spokeAzimuth, encoding)
        val data = Samples.packNibbles(levels)
        val header = SpokeHeader(
            spokeLengthBytes = SpokeHeader.HEADER_BYTES + data.size,
            sequenceNumber = sequenceNumber and 0x0FFF,
            sampleEncoding = encoding,
            nOfSamples = cfg.nOfSamples,
            bitsPerSample = cfg.bitsPerSample,
            rangeCellSizeMm = cfg.rangeCellSizeMm,
            spokeAzimuth = spokeAzimuth and 0x1FFF,
            bearingZeroError = false,
            spokeCompass = 0,
            trueNorth = false,
            compassInvalid = true, // no heading sensor on the fake feed
            rangeCellsDiv2 = cfg.rangeCellsDiv2,
        )
        return Spoke(header, data)
    }
}
