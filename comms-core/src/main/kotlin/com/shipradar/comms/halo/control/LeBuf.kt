package com.shipradar.comms.halo.control

/**
 * Little-endian byte builder for HALO control frames.
 *
 * Multi-byte integer parameters are little-endian on the wire (协议文档 §雷达控制 强调"小端").
 * The 2-byte opcode itself is written in document order (high byte first), e.g. 0x03C1 -> 03 C1,
 * matching every worked example in the doc ("00C1 01", "03C1 10B2 0100", "06C1 ...").
 */
internal class LeBuf(initialCapacity: Int = 32) {
    private val out = ArrayList<Byte>(initialCapacity)

    /** 2-byte opcode, high byte first (document order). */
    fun opcode(op: Int) = apply {
        out.add(((op ushr 8) and 0xFF).toByte())
        out.add((op and 0xFF).toByte())
    }

    fun u8(v: Int) = apply { out.add((v and 0xFF).toByte()) }

    fun u16(v: Int) = apply {
        out.add((v and 0xFF).toByte())
        out.add(((v ushr 8) and 0xFF).toByte())
    }

    fun u32(v: Int) = apply {
        out.add((v and 0xFF).toByte())
        out.add(((v ushr 8) and 0xFF).toByte())
        out.add(((v ushr 16) and 0xFF).toByte())
        out.add(((v ushr 24) and 0xFF).toByte())
    }

    fun bytes(b: ByteArray) = apply { b.forEach { out.add(it) } }

    /** Append [n] zero bytes (advanced 05CB/00CB frames carry 12 bytes of trailing padding). */
    fun zeros(n: Int) = apply { repeat(n) { out.add(0) } }

    fun build(): ByteArray = out.toByteArray()
}
