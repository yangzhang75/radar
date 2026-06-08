package com.shipradar.halofeed

import com.shipradar.contract.SampleEncoding
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

/**
 * Self-test of the spoke-header bit-field packing. This *predates* and validates the T1.2 parser
 * only insofar as it proves toBytes/fromBytes are exact inverses and the wire bytes are
 * little-endian as the doc requires — it is NOT the T1.2 parser.
 */
class SpokeHeaderTest {

    private fun sample() = SpokeHeader(
        spokeLengthBytes = 536,
        sequenceNumber = 1234,
        sampleEncoding = SampleEncoding.AMPLITUDE,
        nOfSamples = 1024,
        bitsPerSample = 4,
        rangeCellSizeMm = 5426,
        spokeAzimuth = 2048,
        bearingZeroError = false,
        spokeCompass = 1000,
        trueNorth = true,
        compassInvalid = false,
        rangeCellsDiv2 = 512,
    )

    @Test fun headerIsExactly24Bytes() {
        assertEquals(24, sample().toBytes().size)
        assertEquals(24, SpokeHeader.HEADER_BYTES)
    }

    @Test fun roundTripsAllFields() {
        val h = sample()
        val back = SpokeHeader.fromBytes(h.toBytes())
        assertEquals(h, back)
    }

    @Test fun roundTripsBothEncodings() {
        for (enc in SampleEncoding.entries) {
            val h = sample().copy(sampleEncoding = enc)
            assertEquals(enc, SpokeHeader.fromBytes(h.toBytes()).sampleEncoding)
        }
    }

    @Test fun roundTripsFlagsAndMaxValues() {
        val h = SpokeHeader(
            spokeLengthBytes = 4095,       // max 12-bit
            sequenceNumber = 4095,         // max 12-bit
            sampleEncoding = SampleEncoding.DOPPLER,
            nOfSamples = 4094,
            bitsPerSample = 15,            // max 4-bit
            rangeCellSizeMm = 65535,       // max 16-bit
            spokeAzimuth = 8191,           // max 13-bit
            bearingZeroError = true,
            spokeCompass = 16383,          // max 14-bit
            trueNorth = true,
            compassInvalid = true,
            rangeCellsDiv2 = 65535,        // max 16-bit
        )
        assertEquals(h, SpokeHeader.fromBytes(h.toBytes()))
    }

    @Test fun flagsAreIndependent() {
        // Toggle each 1-bit flag in isolation and confirm only it changes.
        val base = sample().copy(bearingZeroError = false, trueNorth = false, compassInvalid = false)
        assertEquals(true, SpokeHeader.fromBytes(base.copy(bearingZeroError = true).toBytes()).bearingZeroError)
        assertEquals(true, SpokeHeader.fromBytes(base.copy(trueNorth = true).toBytes()).trueNorth)
        assertEquals(true, SpokeHeader.fromBytes(base.copy(compassInvalid = true).toBytes()).compassInvalid)
    }

    @Test fun wireBytesAreLittleEndian() {
        // word0 = spokeLength(536=0x218) | sequence(1<<16) ; encoding 0 -> 0x00010218
        val h = sample().copy(
            spokeLengthBytes = 536, sequenceNumber = 1, sampleEncoding = SampleEncoding.AMPLITUDE,
        )
        val b = h.toBytes()
        // little-endian: LSB first
        assertContentEquals(byteArrayOf(0x18, 0x02, 0x01, 0x00), b.copyOfRange(0, 4))
    }

    @Test fun encodingOccupiesBits28to29OfWord0() {
        // DOPPLER(=1) at bit 28 sets byte3 bit4 -> 0x10
        val h = sample().copy(
            spokeLengthBytes = 0, sequenceNumber = 0, sampleEncoding = SampleEncoding.DOPPLER,
        )
        assertContentEquals(byteArrayOf(0x00, 0x00, 0x00, 0x10), h.toBytes().copyOfRange(0, 4))
    }

    @Test fun reservedWordsAreZero() {
        // Bytes 16..23 are the two fully-reserved words; must serialize as zero.
        assertContentEquals(ByteArray(8), sample().toBytes().copyOfRange(16, 24))
    }

    @Test fun rejectsOutOfRangeField() {
        assertFailsWith<IllegalArgumentException> { sample().copy(spokeAzimuth = 8192) } // 13-bit overflow
        assertFailsWith<IllegalArgumentException> { sample().copy(bitsPerSample = 16) }  // 4-bit overflow
        assertFailsWith<IllegalArgumentException> { sample().copy(spokeLengthBytes = -1) }
    }

    @Test fun parsesAtNonZeroOffset() {
        val h = sample()
        val framed = ByteArray(5) + h.toBytes() + ByteArray(3)
        assertEquals(h, SpokeHeader.fromBytes(framed, 5))
    }
}
