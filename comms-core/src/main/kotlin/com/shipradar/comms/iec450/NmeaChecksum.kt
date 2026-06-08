package com.shipradar.comms.iec450

/**
 * 8-bit "Exclusive OR" hexadecimal checksum (`*hh`) shared by IEC 61162-1 sentences and by
 * IEC 61162-450 ED3 TAG blocks (Annex B.4). This is a pure transport-layer **integrity** check —
 * it never interprets sentence fields (field parsing belongs to T1.5 `iec61162`).
 *
 * Per IEC 61162-450 ED3 §B.4 the TAG-block checksum is the XOR of every character between (but not
 * including) the opening `\` and the `*` checksum delimiter. The sentence checksum (IEC 61162-1) is
 * the XOR of every character between (but not including) the starting `$`/`!` and the `*`.
 */
internal object NmeaChecksum {

    /** XOR of `s[start until endExclusive]`, masked to 8 bits. */
    fun xor(s: CharSequence, start: Int, endExclusive: Int): Int {
        var acc = 0
        for (i in start until endExclusive) acc = acc xor s[i].code
        return acc and 0xFF
    }

    /**
     * Parse exactly two hex digits (`[0-9A-Fa-f]{2}`) into 0..255, or `null` if malformed.
     * IEC 61162-450 §B.3 specifies upper-case `[0-9A-F]`; lower case is accepted leniently because
     * real-world talkers emit it and rejecting it would drop otherwise-valid frames (the integrity
     * value is unaffected by case). See delivery report.
     */
    fun parseHex2(h: CharSequence): Int? {
        if (h.length != 2) return null
        val hi = Character.digit(h[0], 16)
        val lo = Character.digit(h[1], 16)
        if (hi < 0 || lo < 0) return null
        return (hi shl 4) or lo
    }
}
