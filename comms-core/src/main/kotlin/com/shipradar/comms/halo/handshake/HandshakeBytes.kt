package com.shipradar.comms.halo.handshake

/**
 * Byte reader for HALO handshake frames. Mixed endianness: the 01B2 reply uses little-endian
 * group framing (prefix/radarId/count) but **big-endian** ports (network order, e.g. 1A16 = 6678),
 * and IPv4 octets in network order. Bounds-checked with clear messages, mirroring the style of the
 * sibling status/control readers.
 */
internal class HandshakeReader(private val b: ByteArray, start: Int = 0) {
    var pos = start
        private set

    val remaining: Int get() = b.size - pos

    fun require(n: Int) {
        require(remaining >= n) {
            "HALO 01B2 truncated: need $n more bytes at offset $pos, have $remaining"
        }
    }

    fun u8(): Int { require(1); return b[pos++].toInt() and 0xFF }

    /** Unsigned 16-bit, little-endian. */
    fun u16le(): Int {
        require(2)
        val v = (b[pos].toInt() and 0xFF) or ((b[pos + 1].toInt() and 0xFF) shl 8)
        pos += 2
        return v
    }

    /** Unsigned 16-bit, big-endian (network order) — used for ports. */
    fun u16be(): Int {
        require(2)
        val v = ((b[pos].toInt() and 0xFF) shl 8) or (b[pos + 1].toInt() and 0xFF)
        pos += 2
        return v
    }

    /** 4 octets as a dotted-quad IPv4 string (network order). */
    fun ipv4(): String {
        require(4)
        val s = "${b[pos].toInt() and 0xFF}.${b[pos + 1].toInt() and 0xFF}." +
            "${b[pos + 2].toInt() and 0xFF}.${b[pos + 3].toInt() and 0xFF}"
        pos += 4
        return s
    }

    /** [n] raw bytes. */
    fun bytes(n: Int): ByteArray {
        require(n)
        val out = b.copyOfRange(pos, pos + n)
        pos += n
        return out
    }

    fun skip(n: Int) { require(n); pos += n }

    /** Peek the next [n] bytes without advancing (no requirement failure; may return fewer). */
    fun peek(n: Int): ByteArray = b.copyOfRange(pos, minOf(pos + n, b.size))
}

/** Little-endian byte builder for the 01B1 request (kept local; opcode is high-byte-first). */
internal class HandshakeBuf {
    private val out = ArrayList<Byte>(16)

    /** 2-byte opcode, high byte first (document order — matches every HALO worked example). */
    fun opcode(op: Int) = apply {
        out.add(((op ushr 8) and 0xFF).toByte())
        out.add((op and 0xFF).toByte())
    }

    fun bytes(b: ByteArray) = apply { b.forEach { out.add(it) } }

    fun build(): ByteArray = out.toByteArray()
}
