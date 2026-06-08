package com.shipradar.util

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class HaloFixedPointTest {

    @Test fun decodes_positive_example_from_doc() {
        // 98.351 -> 0x0006259E
        assertEquals(98.351, HaloFixedPoint.decodeQ12(0x0006259E), 1e-3)
    }

    @Test fun decodes_negative_example_from_doc() {
        // -0.997 -> 0xFFFFF00C (two's complement)
        assertEquals(-0.997, HaloFixedPoint.decodeQ12(0xFFFFF00C.toInt()), 1e-3)
    }

    @Test fun encode_then_decode_roundtrips() {
        for (v in listOf(0.0, 1.0, -1.0, 98.351, -0.997, 50.5, -100.0, 100.0)) {
            assertEquals(v, HaloFixedPoint.decodeQ12(HaloFixedPoint.encodeQ12(v)), 1e-3)
        }
    }

    @Test fun le_bytes_match_doc_positive() {
        // wire bytes for 98.351 are "9E 25 06 00"
        val bytes = HaloFixedPoint.encodeQ12LeBytes(98.351)
        assertEquals(0x9E.toByte(), bytes[0])
        assertEquals(0x25.toByte(), bytes[1])
        assertEquals(0x06.toByte(), bytes[2])
        assertEquals(0x00.toByte(), bytes[3])
        assertEquals(98.351, HaloFixedPoint.decodeQ12LeBytes(bytes), 1e-3)
    }

    @Test fun le_bytes_match_doc_negative() {
        // wire bytes for -0.997 are "0C F0 FF FF"
        val bytes = HaloFixedPoint.encodeQ12LeBytes(-0.997)
        assertEquals(0x0C.toByte(), bytes[0])
        assertEquals(0xF0.toByte(), bytes[1])
        assertEquals(0xFF.toByte(), bytes[2])
        assertEquals(0xFF.toByte(), bytes[3])
    }

    @Test fun angles_spoke_azimuth_roundtrip() {
        assertEquals(0.0, Angles.rawAzimuthToDeg(0), 1e-9)
        assertEquals(180.0, Angles.rawAzimuthToDeg(2048), 1e-9)
        assertTrue(Angles.rawAzimuthToDeg(5000) <= 360.0) // clamps to 4095
        assertEquals(2048, Angles.degToRawAzimuth(180.0))
    }
}
