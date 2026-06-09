package com.shipradar.halofeed

import com.shipradar.contract.RadarPowerState
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals

class StatusPacketTest {

    private fun u32le(b: ByteArray, o: Int) =
        (b[o].toLong() and 0xFF) or ((b[o + 1].toLong() and 0xFF) shl 8) or
            ((b[o + 2].toLong() and 0xFF) shl 16) or ((b[o + 3].toLong() and 0xFF) shl 24)

    @Test fun modePacketLayout() {
        val p = StatusPacket.mode(RadarPowerState.TRANSMIT, timedTransmit = true, warmupRemainSec = 42, timedCountSec = 7)
        assertEquals(18, p.size) // 2-byte opcode + 4×uint32
        assertContentEquals(byteArrayOf(0x01, 0xC4.toByte()), p.copyOfRange(0, 2))
        assertEquals(2L, u32le(p, 2))   // TRANSMIT = code 2
        assertEquals(1L, u32le(p, 6))   // timedTransmit
        assertEquals(42L, u32le(p, 10)) // warmup
        assertEquals(7L, u32le(p, 14))  // timed count
    }

    @Test fun powerCodesMatchDoc() {
        // Doc §模式状态: 0 关闭 / 1 待机 / 2 发射 / 3 预热 / 4 没有扫描机 / 5 检测扫描机
        assertEquals(0, StatusPacket.powerCode(RadarPowerState.OFF))
        assertEquals(1, StatusPacket.powerCode(RadarPowerState.STANDBY))
        assertEquals(2, StatusPacket.powerCode(RadarPowerState.TRANSMIT))
        assertEquals(3, StatusPacket.powerCode(RadarPowerState.WARMUP))
        assertEquals(4, StatusPacket.powerCode(RadarPowerState.NO_SCANNER))
        assertEquals(5, StatusPacket.powerCode(RadarPowerState.DETECTING_SCANNER))
    }
}
