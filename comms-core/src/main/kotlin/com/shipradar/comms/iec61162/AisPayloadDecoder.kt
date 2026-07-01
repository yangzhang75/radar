package com.shipradar.comms.iec61162

/**
 * Decodes the encapsulated data field of VDM/VDO sentences.
 *
 * The encapsulated field is six-bit-armoured per IEC 61162-1 ED6 §7.3.4 (encapsulation sentences)
 * and §8.2 (Table 5, six-bit code). The de-armoured bitstream is an ITU-R M.1371 radio message;
 * its internal layout (message type, MMSI, position, COG/SOG, heading, …) is defined by
 * **ITU-R M.1371-5 §3.3**, not by 61162-1.
 *
 * Implemented (MMSI / lat / lon / COG / SOG / heading — the navigationally-mappable fields):
 *  - Types 1, 2, 3  — Class A position report (ITU-R M.1371-5 §3.3, Message 1/2/3)
 *  - Type 18        — Class B position report (ITU-R M.1371-5 §3.3, Message 18)
 *  - Type 19        — Extended Class B position report (Message 19): the position block shares the
 *                     exact bit layout of Message 18 (bits 0-132), so it decodes through the same path.
 *
 * Deferred (framework + TODO) — see [messageType] / [decodePositionReport] (returns null):
 *  - **Static/voyage data: type 5 (Class A) and type 24 (Class B A/B parts)** — name, call sign,
 *    IMO number, dimensions, draught, destination. NOT decoded because the frozen contract
 *    ([com.shipradar.contract.TrackedTarget]) has no static-attribute fields to carry them; decoding
 *    them needs a contract extension (flagged in the W4-F delivery report).
 *    TODO(待标准: ITU-R M.1371-5 §3.3 Message 5/24) + contract静态字段.
 *  - type 21 (aids-to-navigation), type 27 (long-range position — different layout),
 *    addressed/binary messages, and multi-slot communication-state fields.
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

    /** The ITU-R M.1371-5 message type (bits 0-5), or null if the bitstream is too short. */
    fun messageType(reader: BitReader): Int? = if (reader.size < 6) null else reader.uint(0, 6)

    /**
     * Decode a Class A (1/2/3) or Class B (18/19) position report from a de-armoured payload.
     * Returns null for any other message type (deferred) or a too-short bitstream.
     */
    fun decodePositionReport(reader: BitReader): AisPositionFields? {
        if (reader.size < 38) return null
        val type = reader.uint(0, 6)
        return when (type) {
            1, 2, 3 -> decodeClassA(reader, type)
            // Message 18 (Class B) and Message 19 (Extended Class B) share the position-block layout.
            18, 19 -> decodeClassB(reader, type)
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
            navStatus = null, // not present in Class B (ITU-R M.1371-5 §3.3, Message 18/19)
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

    /**
     * Decode AIS static/voyage data — **Message 5** (Class A static & voyage) and **Message 24** (Class B
     * static, part A = name, part B = callsign/type). Returns null for other types or a too-short stream.
     * (ITU-R M.1371-5 §3.3 Messages 5/24.)
     */
    fun decodeStatic(reader: BitReader): AisStaticFields? {
        if (reader.size < 40) return null
        val type = reader.uint(0, 6)
        val mmsi = reader.uint(8, 30).toLong()
        return when (type) {
            5 -> {
                if (reader.size < 240) return null // through ship-type
                AisStaticFields(
                    messageType = 5, mmsi = mmsi,
                    name = reader.text(112, 20), callsign = reader.text(70, 7),
                    shipType = reader.uint(232, 8), imo = reader.uint(40, 30),
                )
            }
            24 -> when (reader.uint(38, 2)) { // part number
                0 -> { // part A — ship name only
                    if (reader.size < 160) return null
                    AisStaticFields(24, mmsi, name = reader.text(40, 20), callsign = null, shipType = null, imo = null)
                }
                1 -> { // part B — ship type + call sign
                    if (reader.size < 132) return null
                    AisStaticFields(24, mmsi, name = null, callsign = reader.text(90, 7), shipType = reader.uint(40, 8), imo = null)
                }
                else -> null
            }
            else -> null
        }
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

    /**
     * AIS 6-bit ASCII text of [chars] characters from [start] (ITU-R M.1371-5 §3.3.5). Values 0..31 map to
     * '@'..'_' and 32..63 to ' '..'?'; '@' (0) is the padding char, so the string is cut at the first '@'
     * and trailing spaces trimmed. Returns null when blank.
     */
    fun text(start: Int, chars: Int): String? {
        val sb = StringBuilder()
        var i = start
        repeat(chars) {
            if (i + 6 > size) return@repeat
            val v = uint(i, 6)
            sb.append(if (v < 32) (v + 64).toChar() else v.toChar())
            i += 6
        }
        return sb.toString().substringBefore('@').trim().ifBlank { null }
    }
}

/** Static / voyage fields from AIS Message 5 or 24 (ITU-R M.1371-5 §3.3). */
internal data class AisStaticFields(
    val messageType: Int,
    val mmsi: Long,
    val name: String?,
    val callsign: String?,
    val shipType: Int?,
    val imo: Int?,
)
