package com.shipradar.halofeed

import com.shipradar.comms.halo.status.HaloStatusParser
import com.shipradar.comms.halo.status.RadarStatusUpdate
import com.shipradar.contract.RadarPowerState
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * 端到端互证(状态通道):**halofeed StatusPacket 生成 01C4 模式状态字节** ↔ **comms-core HaloStatusParser 解析**。
 * 与 SpokeRoundTripTest 同理:两独立来源互证,避免"自造假数据喂自写解析器"的循环验证。
 */
class StatusRoundTripTest {

    @Test fun mode_status_round_trips_through_parser() {
        val pkt = StatusPacket.mode(
            power = RadarPowerState.TRANSMIT,
            timedTransmit = true,
            warmupRemainSec = 42,
            timedCountSec = 7,
        )
        val update = HaloStatusParser.parseStatus(pkt)
        assertTrue(update is RadarStatusUpdate.Mode, "01C4 应解析为 Mode,实际 $update")
        update as RadarStatusUpdate.Mode
        assertEquals(RadarPowerState.TRANSMIT, update.powerState)
        assertEquals(true, update.timedTransmit)
        assertEquals(42, update.warmupRemainSec)
    }

    @Test fun power_states_round_trip() {
        // 关键电源态各自往返一致(发射/待机/预热/关)。
        for (p in listOf(RadarPowerState.OFF, RadarPowerState.STANDBY, RadarPowerState.WARMUP, RadarPowerState.TRANSMIT)) {
            val u = HaloStatusParser.parseStatus(StatusPacket.mode(power = p)) as RadarStatusUpdate.Mode
            assertEquals(p, u.powerState, "电源态 $p 往返不一致")
        }
    }
}
