package com.shipradar.halofeed

import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals

class SamplesTest {

    @Test fun lowIndexGoesToLowNibble() {
        // sample[0]=0x0A (low nibble), sample[1]=0x0B (high nibble) -> byte 0xBA
        val packed = Samples.packNibbles(byteArrayOf(0x0A, 0x0B))
        assertEquals(1, packed.size)
        assertEquals(0xBA, packed[0].toInt() and 0xFF)
    }

    @Test fun packLengthIsCeilHalf() {
        assertEquals(512, Samples.packNibbles(ByteArray(1024)).size)
        assertEquals(3, Samples.packNibbles(ByteArray(5)).size) // odd count -> ceil
    }

    @Test fun roundTripsAllLevels() {
        // 0..15 repeated across a full spoke.
        val levels = ByteArray(1024) { ((it % 16)).toByte() }
        val packed = Samples.packNibbles(levels)
        val back = Samples.unpackNibbles(packed, 1024)
        assertContentEquals(levels, back)
    }

    @Test fun clampsToFourBits() {
        // Only the low nibble survives (values are meant to be 0..15).
        val packed = Samples.packNibbles(byteArrayOf(0xFF.toByte(), 0x10))
        assertEquals(0x0F, Samples.unpackNibbles(packed, 2)[0].toInt())
        assertEquals(0x00, Samples.unpackNibbles(packed, 2)[1].toInt())
    }
}
