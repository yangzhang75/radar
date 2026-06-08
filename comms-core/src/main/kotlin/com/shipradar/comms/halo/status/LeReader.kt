package com.shipradar.comms.halo.status

/** Little-endian reader over a HALO status payload. All multi-byte fields are little-endian. */
internal class LeReader(private val b: ByteArray, start: Int = 0) {
    var pos = start
        private set

    fun u8(): Int = (b[pos++].toInt() and 0xFF)

    fun u16(): Int {
        val v = (b[pos].toInt() and 0xFF) or ((b[pos + 1].toInt() and 0xFF) shl 8)
        pos += 2
        return v
    }

    /** uint32 returned as Long to stay unsigned-safe (ranges in cm can exceed Int range semantics). */
    fun u32(): Long {
        val v = (b[pos].toLong() and 0xFF) or
            ((b[pos + 1].toLong() and 0xFF) shl 8) or
            ((b[pos + 2].toLong() and 0xFF) shl 16) or
            ((b[pos + 3].toLong() and 0xFF) shl 24)
        pos += 4
        return v
    }

    fun skip(n: Int) { pos += n }

    fun require(n: Int) {
        require(b.size - pos >= n) { "HALO status payload truncated: need $n more bytes at offset $pos, have ${b.size - pos}" }
    }
}
