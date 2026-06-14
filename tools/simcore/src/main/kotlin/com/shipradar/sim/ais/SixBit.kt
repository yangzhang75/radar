package com.shipradar.sim.ais

/**
 * W8-B — six-bit ASCII armoring / de-armoring and the MSB-first bit cursor used to build and read AIS
 * radio messages. Pure JVM, no dependencies.
 *
 * The armoring (binary payload ⇄ printable ASCII) and the `!AIVDM` envelope are defined by
 * **IEC 61162-1 ED6 §7.3.4 (encapsulation) + §8.2 Table 5 (six-bit code)** — i.e. NMEA, not AIS. The
 * de-armoured bitstream is an **ITU-R M.1371 radio message** whose internal field layout is handled by
 * [AisEncoder].
 *
 * Two distinct six-bit alphabets are involved and must not be confused:
 *  - **payload armoring** ([armor] / [deArmor]): packs an arbitrary bitstream into characters; the
 *    char⇄value map is `v<40 → v+48`, else `v+56` (§8.2 Table 5). Inverse: `v=ch-48; if(v>40) v-=8`.
 *  - **six-bit text** ([encodeChar6] / [decodeChar6]): how *string* fields (name/callsign/destination)
 *    inside the message store characters — `@A-Z[\]^_` → 0..31, space..`?` → 32..63 (ITU-R M.1371-5).
 */
object SixBit {

    // ---- payload armoring (IEC 61162-1 §8.2 Table 5) ----

    /**
     * Armor [bits] into a printable six-bit payload string. The bitstream is zero-padded at the tail to
     * a multiple of 6; the number of pad bits is returned as the AIVDM **fill bits** (0..5).
     */
    fun armor(bits: BooleanArray): ArmoredPayload {
        val fill = (6 - bits.size % 6) % 6
        val total = bits.size + fill
        val sb = StringBuilder(total / 6)
        var i = 0
        while (i < total) {
            var v = 0
            for (b in 0 until 6) {
                v = (v shl 1) or (if (i + b < bits.size && bits[i + b]) 1 else 0)
            }
            // value -> char: 0..39 -> '0'..'W' (+48), 40..63 -> '`'.. (+56)
            sb.append((if (v < 40) v + 48 else v + 56).toChar())
            i += 6
        }
        return ArmoredPayload(sb.toString(), fill)
    }

    /**
     * De-armor a [payload] back to its bitstream, dropping [fillBits] padding bits from the tail.
     * Returns null on an out-of-range character or invalid fill count (the inverse of [armor]).
     */
    fun deArmor(payload: String, fillBits: Int): BooleanArray? {
        if (fillBits !in 0..5) return null
        val bits = BooleanArray(payload.length * 6)
        var n = 0
        for (ch in payload) {
            var v = ch.code - 48
            if (v > 40) v -= 8
            if (v < 0 || v > 63) return null
            for (b in 5 downTo 0) bits[n++] = (v ushr b) and 1 == 1
        }
        val valid = bits.size - fillBits
        if (valid < 0) return null
        return bits.copyOf(valid)
    }

    // ---- six-bit text (ITU-R M.1371-5 §3.3, default character set) ----

    /** ASCII char → six-bit text value (0..63). Lower-case is folded up; unsupported → `@` (0). */
    fun encodeChar6(c: Char): Int = when (val code = c.uppercaseChar().code) {
        in 64..95 -> code - 64   // @ A..Z [ \ ] ^ _
        in 32..63 -> code        // space ! " # … 0..9 : ; < = > ?
        else -> 0                // unsupported -> '@'
    }

    /** Six-bit text value (0..63) → ASCII char (inverse of [encodeChar6]). */
    fun decodeChar6(v: Int): Char = if (v in 0..31) (v + 64).toChar() else v.toChar()

    /** Decode a six-bit text run of [chars] characters, trimming the `@`/space padding AIS uses. */
    fun decodeText(reader: BitReader, start: Int, chars: Int): String {
        val sb = StringBuilder(chars)
        for (i in 0 until chars) sb.append(decodeChar6(reader.uint(start + i * 6, 6)))
        return sb.toString().trimEnd('@', ' ')
    }
}

/** An armored payload plus its AIVDM fill-bit count. */
data class ArmoredPayload(val payload: String, val fillBits: Int)

/** MSB-first bit accumulator for assembling an AIS message. */
class BitWriter {
    private val bits = ArrayList<Boolean>(192)
    val size: Int get() = bits.size

    /** Append the low [len] bits of unsigned [value], MSB first. */
    fun uint(value: Long, len: Int): BitWriter {
        for (i in len - 1 downTo 0) bits.add((value ushr i) and 1L == 1L)
        return this
    }

    fun uint(value: Int, len: Int): BitWriter = uint(value.toLong() and 0xFFFFFFFFL, len)

    /** Append [value] as a [len]-bit two's-complement signed field. */
    fun int(value: Int, len: Int): BitWriter {
        val mask = if (len >= 64) -1L else (1L shl len) - 1L
        return uint(value.toLong() and mask, len)
    }

    /** Append [text] as exactly [chars] six-bit text characters (truncated / `@`-padded). */
    fun text(text: String, chars: Int): BitWriter {
        for (i in 0 until chars) uint((if (i < text.length) SixBit.encodeChar6(text[i]) else 0).toLong(), 6)
        return this
    }

    fun toBooleanArray(): BooleanArray = BooleanArray(bits.size) { bits[it] }
}

/** MSB-first bit cursor over a de-armoured AIS payload. */
class BitReader(private val bits: BooleanArray) {
    val size: Int get() = bits.size

    /** Unsigned integer from [start] for [len] bits (MSB first). */
    fun uint(start: Int, len: Int): Int {
        var v = 0
        for (i in 0 until len) v = (v shl 1) or (if (bits[start + i]) 1 else 0)
        return v
    }

    /** Two's-complement signed integer from [start] for [len] bits. */
    fun int(start: Int, len: Int): Int {
        val u = uint(start, len)
        val sign = 1 shl (len - 1)
        return if (u and sign != 0) u - (1 shl len) else u
    }
}
