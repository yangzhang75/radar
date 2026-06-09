package com.shipradar.halofeed

import com.shipradar.contract.RadarPowerState

/**
 * HALO 模式状态【01C4】 builder (radar -> sw, channel 236.6.7.9:6679). Sent every ~2 s per doc §状态信息.
 *
 * Doc §模式状态【01C4】 gives four 32-bit fields: 状态 | 定时发射状态 | 预热时间 | 定时计数.
 * The status channel multiplexes 01C4/02C4/04C4/03C4/08C4, so messages must be self-identifying;
 * we therefore prepend the 2-byte command word (01 C4, document byte order, matching the
 * com.shipradar.constants.HaloOpcodes convention) ahead of the payload.
 *
 * TODO(待协议): the doc does not show the on-wire command header for status messages explicitly, nor
 * confirm field widths (we use uint32 LE as drawn: each field is one "0000 0000" row). Confirm the
 * 01C4 header presence/width/byte-order and field widths against the radar/SDK before trusting on
 * real hardware. Only 01C4 (mode) is emitted here; 02C4/08C4 setup are richer and left as TODO.
 */
object StatusPacket {
    /** 2-byte command word for 模式状态, document byte order. */
    val MODE_OPCODE = byteArrayOf(0x01, 0xC4.toByte())

    /** RadarPowerState ordinals already match the doc codes 0..5 (OFF..DETECTING_SCANNER). */
    fun powerCode(s: RadarPowerState): Int = s.ordinal

    /** Build a 01C4 mode-status datagram payload. */
    fun mode(
        power: RadarPowerState,
        timedTransmit: Boolean = false,
        warmupRemainSec: Int = 0,
        timedCountSec: Int = 0,
    ): ByteArray {
        val out = ByteArray(MODE_OPCODE.size + 16)
        MODE_OPCODE.copyInto(out, 0)
        putUInt32Le(out, 2, powerCode(power).toLong())
        putUInt32Le(out, 6, if (timedTransmit) 1 else 0)
        putUInt32Le(out, 10, warmupRemainSec.toLong())
        putUInt32Le(out, 14, timedCountSec.toLong())
        return out
    }

    private fun putUInt32Le(out: ByteArray, off: Int, w: Long) {
        out[off] = (w and 0xFF).toByte()
        out[off + 1] = ((w ushr 8) and 0xFF).toByte()
        out[off + 2] = ((w ushr 16) and 0xFF).toByte()
        out[off + 3] = ((w ushr 24) and 0xFF).toByte()
    }
}
