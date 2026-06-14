package com.shipradar.app.bite

import com.shipradar.contract.LinkState
import com.shipradar.contract.OwnShipData
import com.shipradar.comms.service.ChannelStat
import com.shipradar.comms.service.DataLinkStats
import com.shipradar.comms.iec450.Iec450DiscardCounters
import com.shipradar.comms.sync.SeqStats
import kotlin.test.Test
import kotlin.test.assertEquals

/** W6-C — BITE 整体健康判定(纯函数)+ 通道活跃度 + 映射的单元测试。 */
class BiteReportTest {

    private fun activeChannels() = listOf(ChannelHealth("ECHO", 100, ageMs = 50))

    private fun report(
        link: LinkState = LinkState.CONNECTED,
        channels: List<ChannelHealth> = activeChannels(),
        hdg: Boolean = true, posn: Boolean = true, sog: Boolean = true,
    ) = BiteReport(link, channels, hdg, posn, sog)

    // ---- overall(): FAULT / DEGRADED / OK ----

    @Test fun noLink_isFault() {
        assertEquals(OverallHealth.FAULT, report(link = LinkState.DISCONNECTED).overall())
    }

    @Test fun allGood_isOk() {
        assertEquals(OverallHealth.OK, report().overall())
    }

    @Test fun linkUp_butSensorInvalid_isDegraded() {
        assertEquals(OverallHealth.DEGRADED, report(hdg = false).overall())
        assertEquals(OverallHealth.DEGRADED, report(posn = false).overall())
        assertEquals(OverallHealth.DEGRADED, report(sog = false).overall())
    }

    @Test fun negotiating_isDegraded_notFault() {
        assertEquals(OverallHealth.DEGRADED, report(link = LinkState.NEGOTIATING).overall())
    }

    @Test fun linkSelfReportedDegraded_isDegraded() {
        assertEquals(OverallHealth.DEGRADED, report(link = LinkState.DEGRADED).overall())
    }

    @Test fun connectedButNoChannelActive_isDegraded() {
        val silentOrDead = listOf(
            ChannelHealth("ECHO", 10, ageMs = 9_000),  // 静默
            ChannelHealth("TARGET", 0, ageMs = null),   // 无数据
        )
        assertEquals(OverallHealth.DEGRADED, report(channels = silentOrDead).overall())
    }

    @Test fun disconnectedDominatesEvenWithValidSensors() {
        // 无链路即 FAULT,即使传感器都有效。
        assertEquals(OverallHealth.FAULT, report(link = LinkState.DISCONNECTED).overall())
    }

    @Test fun emptyChannels_notTreatedAsDegraded() {
        // 无通道信息时不强制降级(仅评估链路+传感器)。
        assertEquals(OverallHealth.OK, report(channels = emptyList()).overall())
    }

    // ---- ChannelHealth.activity(): 绿/黄/红 ----

    @Test fun channelActivity_greenYellowRed() {
        assertEquals(ChannelActivity.ACTIVE, ChannelHealth("c", 1, ageMs = 100).activity())
        assertEquals(ChannelActivity.SILENT, ChannelHealth("c", 1, ageMs = 10_000).activity())
        assertEquals(ChannelActivity.NO_DATA, ChannelHealth("c", 0, ageMs = null).activity())
    }

    @Test fun channelActivity_thresholdBoundary() {
        assertEquals(ChannelActivity.ACTIVE, ChannelHealth("c", 1, ageMs = 3_000).activity(3_000))
        assertEquals(ChannelActivity.SILENT, ChannelHealth("c", 1, ageMs = 3_001).activity(3_000))
    }

    @Test fun sensorsAllValid_flag() {
        assertEquals(true, report().sensorsAllValid)
        assertEquals(false, report(sog = false).sensorsAllValid)
    }

    // ---- BiteMapping.from(DataLinkStats, OwnShipData) ----

    @Test fun mapping_fromDataLinkStats_buildsChannelsAndSensors() {
        val now = 1_000_000L
        val stats = DataLinkStats(
            nowMs = now,
            linkState = LinkState.CONNECTED,
            echo = ChannelStat(packets = 500, lastMs = now - 100),   // 活跃
            echoB = ChannelStat(packets = 498, lastMs = now - 120),
            status = ChannelStat(packets = 12, lastMs = now - 9_000), // 静默
            target = ChannelStat(packets = 0, lastMs = 0),            // 从未到包 -> ageMs null
            iec450 = ChannelStat(packets = 80, lastMs = now - 200),
            seq = SeqStats(received = 0, inOrder = 0, gapEvents = 0, missing = 0, recovered = 0, duplicates = 0, reordered = 0, resyncs = 0),
            discards = Iec450DiscardCounters(),
            aisDeferred = 0,
        )
        val ownShip = OwnShipData(headingDeg = 12.0, latitude = 30.0, longitude = 121.0, sogKn = null)
        val r = BiteMapping.from(stats, ownShip, lastBiteMillis = now)

        assertEquals(5, r.channels.size)
        assertEquals(LinkState.CONNECTED, r.linkState)
        assertEquals(ChannelActivity.ACTIVE, r.channels.first { it.name == "ECHO" }.activity())
        assertEquals(ChannelActivity.SILENT, r.channels.first { it.name == "STATUS" }.activity())
        assertEquals(ChannelActivity.NO_DATA, r.channels.first { it.name == "TARGET" }.activity())
        assertEquals(true, r.headingValid)
        assertEquals(true, r.positionValid)
        assertEquals(false, r.sogValid)            // sogKn == null
        assertEquals(OverallHealth.DEGRADED, r.overall()) // SOG 无效 -> 降级
    }
}
