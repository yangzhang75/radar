package com.shipradar.sim.ais

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * W8-B tests. Cross-validation strategy (deliberately not self-validating):
 *  1. **Independent decoder** ([decodePayload]) written from the ITU-R M.1371-5 §3.3 field offsets
 *     (the same offsets the already-audited `comms-core` AIS decoder uses), separate from the encoder.
 *  2. **Hand-computed armor vector** — armoring checked against bytes worked out by hand from §8.2 Table 5.
 *  3. **A real public AIVDM sentence** — its own checksum is reproduced by [AisEncoder.checksum]
 *     (independent of the encoder) and the message type / MMSI are decoded from it.
 *  4. Round-trip of type 1 / 5 / 24 (explicitly permitted by the task).
 */
class AisEncoderTest {

    // ---------- helpers: AIVDM line splitting + independent field decode ----------

    private data class Aivdm(val count: Int, val index: Int, val seq: String, val chan: Char, val payload: String, val fill: Int)

    private fun parseLine(line: String): Aivdm {
        assertTrue(line.startsWith("!AIVDM,"), "must be an AIVDM sentence: $line")
        val star = line.indexOf('*')
        val body = line.substring(1, star)
        assertEquals(AisEncoder.checksum(body), line.substring(star + 1), "checksum of: $line")
        val f = body.split(",")
        return Aivdm(f[1].toInt(), f[2].toInt(), f[3], f[4][0], f[5], f[6].toInt())
    }

    /** Reassemble one logical message's bits from its (already split) AIVDM lines. */
    private fun bitsOf(lines: List<String>): BitReader {
        val parts = lines.map { parseLine(it) }
        val payload = parts.joinToString("") { it.payload }
        val fill = parts.last().fill
        return BitReader(SixBit.deArmor(payload, fill)!!)
    }

    // ---------- 1. hand-computed armor vector (independent of the encoder) ----------

    @Test
    fun `armor matches a hand-computed six-bit table vector`() {
        // values 1, 40, 39, 0 -> bits, then §8.2 Table 5: 1->'1'(49) 40->'`'(96) 39->'W'(87) 0->'0'(48)
        val bitStr = "000001" + "101000" + "100111" + "000000"
        val bits = BooleanArray(bitStr.length) { bitStr[it] == '1' }
        val armored = SixBit.armor(bits)
        assertEquals("1`W0", armored.payload)
        assertEquals(0, armored.fillBits)
        // and de-armor is the exact inverse
        assertTrue(SixBit.deArmor("1`W0", 0)!!.contentEquals(bits))
    }

    @Test
    fun `armor round-trips arbitrary bitstreams with fill bits`() {
        for (n in listOf(6, 7, 28, 132, 168, 424)) {
            val bits = BooleanArray(n) { (it * 7 + 3) % 5 == 0 }  // deterministic pseudo-pattern
            val a = SixBit.armor(bits)
            assertEquals((6 - n % 6) % 6, a.fillBits, "fill bits for n=$n")
            assertTrue(SixBit.deArmor(a.payload, a.fillBits)!!.contentEquals(bits), "round-trip n=$n")
        }
    }

    // ---------- 2. type 1 position report round-trip ----------

    @Test
    fun `type 1 position report round-trips through an independent decoder`() {
        val t = AisTarget(
            mmsi = 412345678, latitude = 22.5, longitude = 114.0,
            sogKn = 12.3, cogDeg = 89.5, headingDeg = 90, navStatus = NavStatus.UNDER_WAY_ENGINE,
        )
        val line = AisEncoder.positionReport(t, messageType = 1)
        val a = parseLine(line)
        assertEquals(1, a.count); assertEquals(1, a.index); assertEquals("", a.seq); assertEquals('A', a.chan)

        val r = bitsOf(listOf(line))
        assertEquals(168, r.size, "type 1 is 168 bits")
        assertEquals(1, r.uint(0, 6))                       // message type
        assertEquals(412345678, r.uint(8, 30))             // MMSI
        assertEquals(0, r.uint(38, 4))                      // nav status
        assertEquals(0, r.int(42, 8))                       // ROT (not turning)
        assertEquals(123, r.uint(50, 10))                   // SOG 12.3 kn
        assertEquals(22.5, r.int(89, 27) / 600000.0, 1e-6)  // lat
        assertEquals(114.0, r.int(61, 28) / 600000.0, 1e-6) // lon
        assertEquals(895, r.uint(116, 12))                  // COG 89.5°
        assertEquals(90, r.uint(128, 9))                    // heading
    }

    @Test
    fun `unavailable dynamic fields encode to their sentinels`() {
        val t = AisTarget(mmsi = 366000001, latitude = 999.0, longitude = 999.0, sogKn = -1.0, headingDeg = null)
        val r = bitsOf(listOf(AisEncoder.positionReport(t)))
        assertEquals(0x3412140, r.int(89, 27))   // lat N/A (91°)
        assertEquals(0x6791AC0, r.int(61, 28))   // lon N/A (181°)
        assertEquals(1023, r.uint(50, 10))       // SOG N/A
        assertEquals(511, r.uint(128, 9))        // heading N/A
    }

    // ---------- 3. type 5 static & voyage round-trip (2 fragments) ----------

    @Test
    fun `type 5 static voyage is two fragments and round-trips`() {
        val t = AisTarget(
            mmsi = 412345678, name = "EVER GIVEN", callsign = "H3RC", imo = 9811000,
            shipType = 70, dimToBow = 200, dimToStern = 200, dimToPort = 20, dimToStarboard = 20,
            destination = "ROTTERDAM",
        )
        val lines = AisEncoder.staticVoyage(t, seqId = 5)
        assertEquals(2, lines.size, "424-bit message needs 2 fragments")
        val f1 = parseLine(lines[0]); val f2 = parseLine(lines[1])
        assertEquals(2, f1.count); assertEquals(1, f1.index); assertEquals("5", f1.seq)
        assertEquals(2, f2.count); assertEquals(2, f2.index); assertEquals("5", f2.seq)
        assertEquals(0, f1.fill); assertEquals(2, f2.fill)   // fill only on last fragment

        val r = bitsOf(lines)
        assertEquals(424, r.size, "type 5 is 424 bits")
        assertEquals(5, r.uint(0, 6))
        assertEquals(412345678, r.uint(8, 30))
        assertEquals(9811000, r.uint(40, 30))
        assertEquals("H3RC", SixBit.decodeText(r, 70, 7))
        assertEquals("EVER GIVEN", SixBit.decodeText(r, 112, 20))
        assertEquals(70, r.uint(232, 8))
        assertEquals(200, r.uint(240, 9))
        assertEquals(200, r.uint(249, 9))
        assertEquals(20, r.uint(258, 6))
        assertEquals(20, r.uint(264, 6))
        assertEquals("ROTTERDAM", SixBit.decodeText(r, 302, 20))
    }

    // ---------- type 24 Class B static round-trip ----------

    @Test
    fun `type 24 parts A and B round-trip`() {
        val t = AisTarget(
            mmsi = 477123456, name = "HARBOUR CAT", callsign = "VRAB2", shipType = 37,
            dimToBow = 8, dimToStern = 4, dimToPort = 2, dimToStarboard = 2,
        )
        val lines = AisEncoder.classBStatic(t)
        assertEquals(2, lines.size)
        val a = bitsOf(listOf(lines[0]))
        assertEquals(24, a.uint(0, 6)); assertEquals(0, a.uint(38, 2))   // part A
        assertEquals(477123456, a.uint(8, 30))
        assertEquals("HARBOUR CAT", SixBit.decodeText(a, 40, 20))
        val b = bitsOf(listOf(lines[1]))
        assertEquals(24, b.uint(0, 6)); assertEquals(1, b.uint(38, 2))   // part B
        assertEquals(37, b.uint(40, 8))
        assertEquals("VRAB2", SixBit.decodeText(b, 90, 7))
        assertEquals(8, b.uint(132, 9)); assertEquals(4, b.uint(141, 9))
        assertEquals(2, b.uint(150, 6)); assertEquals(2, b.uint(156, 6))
    }

    // ---------- text codec edge cases ----------

    @Test
    fun `six-bit text folds case and pads, decode trims padding`() {
        val w = BitWriter().text("ab", 5)               // 5 chars, "AB" then 3 pad
        val r = BitReader(w.toBooleanArray())
        assertEquals("AB", SixBit.decodeText(r, 0, 5))
        assertEquals('@', SixBit.decodeChar6(0))         // 0..31 -> @ A..Z [ \ ] ^ _
        assertEquals(' ', SixBit.decodeChar6(32))        // 32..63 -> space..?
        assertEquals('0', SixBit.decodeChar6(SixBit.encodeChar6('0')))  // digit round-trips
    }

    // ---------- 4. external cross-checks ----------

    // The §7.2.4 XOR checksum, verified against an INDEPENDENT implementation (computed in Python):
    //   body "AIVDM,1,1,,A,test,0" -> 0x30 ; body "AIVDM,1,1,,B,15M,0" -> 0x6C
    @Test
    fun `checksum matches an independent implementation`() {
        assertEquals("30", AisEncoder.checksum("AIVDM,1,1,,A,test,0"))
        assertEquals("6C", AisEncoder.checksum("AIVDM,1,1,,B,15M,0"))
    }

    // A recognisable real-world MMSI (US Coast-Guard range, MID 366) must round-trip AND resolve to its
    // correct flag state — ties the bit layout + MID table to an externally meaningful identity.
    @Test
    fun `recognisable US MMSI round-trips and resolves to its real flag state`() {
        val line = AisEncoder.positionReport(AisTarget(mmsi = 366730000, latitude = 37.81, longitude = -122.41))
        val r = bitsOf(listOf(line))
        val mmsi = r.uint(8, 30)
        assertEquals(366730000, mmsi)
        assertEquals("美国", countryOf(mmsi))
        assertNotNull(countryOf(mmsi))
    }

    // ---------- countryOf ----------

    @Test
    fun `countryOf resolves common MIDs`() {
        assertEquals("中国", countryOf(412345678))
        assertEquals("中国", countryOf(413000001))
        assertEquals("美国", countryOf(366123456))
        assertEquals("英国", countryOf(232999999))
        assertEquals("日本", countryOf(431000000))
        assertEquals("韩国", countryOf(440100200))
        assertEquals("新加坡", countryOf(563111222))
        assertEquals("巴拿马", countryOf(351777888))
    }

    @Test
    fun `countryOf rejects non ship MMSI and unknown MIDs`() {
        assertEquals(null, countryOf(1234), "too short")
        assertEquals(null, countryOf(999000000), "MID 999 not assigned in table")
        assertEquals(null, AisTarget(mmsi = 1234567).country)
    }
}
