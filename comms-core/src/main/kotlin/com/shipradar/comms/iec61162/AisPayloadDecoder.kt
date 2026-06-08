package com.shipradar.comms.iec61162

/**
 * Decodes the encapsulated data field of VDM/VDO sentences.
 *
 * The encapsulated field is six-bit-armoured per IEC 61162-1 ED6 §7.3.4 (encapsulation sentences)
 * and §8.2 (Table 5, six-bit code). The de-armoured bitstream is an ITU-R M.1371 radio message;
 * its internal layout (message type, MMSI, position, COG/SOG, heading, …) is defined by
 * **ITU-R M.1371-5 §3.3**, not by 61162-1.
 *
 * Implemented now (T1.5 mandatory scope — MMSI / lat / lon / COG / SOG / heading):
 *  - Types 1, 2, 3  — Class A position report (ITU-R M.1371-5 §3.3, Message 1/2/3)
 *  - Type 18        — Class B position report (ITU-R M.1371-5 §3.3, Message 18)
 *
 * Deferred (framework + TODO): type 5 static/voyage data, type 19 (Class B extended), type 24
 * (static data report), type 21 (aids-to-navigation), addressed/binary messages, and multi-slot
 * communication-state fields. See [decodePositionReport] return value (null) for unhandled types.
 */
internal object AisPayloadDecoder {

    /** Sentinel raw values meaning "not available" per ITU-R M.1371-5 §3.3. */
    private const val LON_NA = 0x6791AC0   // 181° in 1/10000 min
    private const val LAT_NA = 0x3412140   // 91° in 1/10000 min
    private const val SOG_NA = 1023        // 0.1-knot units
    private const val COG_NA = 3600        // 0.1° units
    private const val HDG_NA = 511         // degrees
    private const val ROT_NA = -128        // signed 8-bit sentinel

    /**
     * De-armour a six-bit ASCII payload (§8.2 Table 5). Each character carries 6 bits:
     * `v = ch - 48; if (v > 40) v -= 8` giving 0..63. [fillBits] padding bits are removed
     * from the tail (§7.3.4.2). Returns null on any out-of-range character or invalid fill count.
     */
    fun unarmor(payload: String, fillBits: Int): BitReader? {
        if (fillBits !in 0..5) return null
        val bits = BooleanArray(payload.length * 6)
        var n = 0
        for (ch in payload) {
            var v = ch.code - 48
            if (v > 40) v -= 8
            if (v < 0 || v > 63) return null
            for (b in 5 downTo 0) {
                bits[n++] = (v ushr b) and 1 == 1
            }
        }
        val valid = bits.size - fillBits
        if (valid < 0) return null
        return BitReader(bits, valid)
    }

    /**
     * Decode a Class A (1/2/3) or Class B (18) position report from a de-armoured payload.
     * Returns null for any other message type (deferred) or a too-short bitstream.
     */
    fun decodePositionReport(reader: BitReader): AisPositionFields? {
        if (reader.size < 38) return null
        val type = reader.uint(0, 6)
        return when (type) {
            1, 2, 3 -> decodeClassA(reader, type)
            18 -> decodeClassB(reader, type)
            else -> null
        }
    }

    private fun decodeClassA(r: BitReader, type: Int): AisPositionFields? {
        if (r.size < 137) return null
        return AisPositionFields(
            messageType = type,
            mmsi = r.uint(8, 30).toLong(),
            navStatus = r.uint(38, 4),
            rotDegMin = decodeRot(r.int(42, 8)),
            sogKn = decodeSog(r.uint(50, 10)),
            longitude = decodeLon(r.int(61, 28)),
            latitude = decodeLat(r.int(89, 27)),
            cogDeg = decodeCog(r.uint(116, 12)),
            headingDeg = decodeHeading(r.uint(128, 9)),
        )
    }

    private fun decodeClassB(r: BitReader, type: Int): AisPositionFields? {
        if (r.size < 133) return null
        return AisPositionFields(
            messageType = type,
            mmsi = r.uint(8, 30).toLong(),
            navStatus = null, // not present in Class B (ITU-R M.1371-5 §3.3, Message 18)
            rotDegMin = null,
            sogKn = decodeSog(r.uint(46, 10)),
            longitude = decodeLon(r.int(57, 28)),
            latitude = decodeLat(r.int(85, 27)),
            cogDeg = decodeCog(r.uint(112, 12)),
            headingDeg = decodeHeading(r.uint(124, 9)),
        )
    }

    private fun decodeLat(raw: Int): Double? = if (raw == LAT_NA) null else raw / 600000.0
    private fun decodeLon(raw: Int): Double? = if (raw == LON_NA) null else raw / 600000.0
    private fun decodeSog(raw: Int): Double? = if (raw == SOG_NA) null else raw / 10.0
    private fun decodeCog(raw: Int): Double? = if (raw == COG_NA) null else raw / 10.0
    private fun decodeHeading(raw: Int): Double? = if (raw == HDG_NA) null else raw.toDouble()

    /** ROT: signed 8-bit, transmitted as 4.733·sqrt(°/min); we keep the raw-derived °/min estimate. */
    private fun decodeRot(raw: Int): Double? {
        if (raw == ROT_NA) return null // not available
        // ITU-R M.1371-5: value = 4.733 * sqrt(rate). Invert to °/min, preserving sign.
        val v = raw.toDouble() / 4.733
        return Math.copySign(v * v, raw.toDouble())
    }
}

/** Geographic fields extracted from an AIS position report (ITU-R M.1371-5 §3.3). */
internal data class AisPositionFields(
    val messageType: Int,
    val mmsi: Long,
    val navStatus: Int?,
    val rotDegMin: Double?,
    val sogKn: Double?,
    val longitude: Double?,
    val latitude: Double?,
    val cogDeg: Double?,
    val headingDeg: Double?,
)

/** MSB-first bit cursor over a de-armoured AIS payload. [size] excludes fill bits. */
internal class BitReader(private val bits: BooleanArray, val size: Int) {
    /** Unsigned integer from [start] for [len] bits (MSB first). */
    fun uint(start: Int, len: Int): Int {
        var v = 0
        for (i in 0 until len) {
            v = (v shl 1) or (if (bits[start + i]) 1 else 0)
        }
        return v
    }

    /** Two's-complement signed integer from [start] for [len] bits. */
    fun int(start: Int, len: Int): Int {
        val u = uint(start, len)
        val sign = 1 shl (len - 1)
        return if (u and sign != 0) u - (1 shl len) else u
    }
}
