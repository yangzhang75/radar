package com.shipradar.comms.service

import com.shipradar.comms.halo.handshake.WatchdogPolicy
import com.shipradar.comms.halo.image.SpokeParser
import com.shipradar.comms.iec450.Iec450Group
import com.shipradar.comms.sync.Backoff
import com.shipradar.comms.sync.ChannelConfig
import com.shipradar.comms.sync.DataChannel
import com.shipradar.constants.DataInterfaceProfile
import com.shipradar.constants.HaloEndpointSet

/**
 * Configuration for the comms engine. Defaults target a single-radar HALO network reached over the
 * 蒲公英 VPN; tune timeouts against the link's 1–3 s jitter and 2 Mbps ceiling.
 *
 * @param manualRadarIp when non-null, used as the manual-IP fallback if the handshake times out (and,
 *   if [skipHandshake], used immediately without attempting 01B1/01B2). 蒲公英 VPN sometimes drops the
 *   negotiation multicast even when unicast to the radar works — that is what this covers.
 * @param skipHandshake go straight to [com.shipradar.comms.halo.handshake.HaloHandshake.manualFallback]
 *   (requires [manualRadarIp]); no 01B1 is sent.
 * @param handshakeTimeoutMs how long to wait for 01B2 before retrying / falling back.
 * @param handshakeRetryDelayMs delay between handshake attempts when no fallback IP is configured.
 * @param watchdog HALO A1C1 cadence/threshold policy (~8 s send).
 * @param tickIntervalMs cadence of the liveness tick that drives staleness/reconnect/3002 decisions.
 * @param iec450Groups which 61162-450 transmission groups to join.
 * @param channelConfigs per-channel staleness/backoff policy for the link supervisor.
 * @param profile 数据接口 profile(模拟/实际)。仅作标识与默认端点来源。
 * @param endpoints 数据接口端点整套(按 [profile] 选取);引擎据此 join/收发,不再引用全局常量。
 */
data class CommsConfig(
    val manualRadarIp: String? = null,
    val skipHandshake: Boolean = false,
    val handshakeTimeoutMs: Long = 5_000,
    val handshakeRetryDelayMs: Long = 3_000,
    val watchdog: WatchdogPolicy = WatchdogPolicy(),
    val tickIntervalMs: Long = 1_000,
    val iec450Groups: List<Iec450Group> = listOf(
        Iec450Group.TGTD, Iec450Group.SATD, Iec450Group.NAVD, Iec450Group.BAM1, Iec450Group.CAM1,
    ),
    val channelConfigs: Map<DataChannel, ChannelConfig> = defaultChannelConfigs(),
    /** Echo SharedFlow buffer depth; overflow drops the oldest spoke (the spec's echo drop policy). */
    val echoBufferCapacity: Int = 4096,
    val profile: DataInterfaceProfile = DataInterfaceProfile.ACTUAL,
    val endpoints: HaloEndpointSet = HaloEndpointSet.actual(),
    /**
     * HALO `rangeCellSize` field → millimetres conversion (default [SpokeParser.RANGE_UNIT_MM]). Real
     * captures suggest the field is decimetres ([SpokeParser.RANGE_UNIT_DM]); kept at mm until the vendor
     * confirms (see SpokeParser / haloprobe real-data finding). Applied to the live HALO image path.
     */
    val rangeUnitToMm: Int = SpokeParser.RANGE_UNIT_MM,
) {
    companion object {
        /** 实际雷达数据接口(法定 236.6.7.x 端口)。 */
        fun actual(manualRadarIp: String? = null): CommsConfig =
            CommsConfig(
                manualRadarIp = manualRadarIp,
                profile = DataInterfaceProfile.ACTUAL,
                endpoints = HaloEndpointSet.actual(),
            )

        /** 模拟数据接口(端口整体 +1000,与实际隔离;供主机侧 HALO 模拟器经同一数据服务承接)。 */
        fun simulation(): CommsConfig =
            CommsConfig(
                // 模拟无需握手协商,直接绑定模拟端口收发。
                skipHandshake = true,
                profile = DataInterfaceProfile.SIMULATION,
                endpoints = HaloEndpointSet.simulation(),
            )

        /**
         * Staleness/backoff per channel. Echo streams continuously so a short timeout is fine; status
         * is ~2 s periodic; targets/own-ship are slower. Grace windows allow for VPN connect latency.
         */
        fun defaultChannelConfigs(): Map<DataChannel, ChannelConfig> = mapOf(
            DataChannel.ECHO to ChannelConfig(stalenessTimeoutMs = 4_000, initialGraceMs = 10_000),
            DataChannel.STATUS to ChannelConfig(stalenessTimeoutMs = 6_000, initialGraceMs = 12_000),
            DataChannel.TARGET to ChannelConfig(stalenessTimeoutMs = 12_000, initialGraceMs = 20_000),
            DataChannel.OWN_SHIP to ChannelConfig(
                stalenessTimeoutMs = 6_000, initialGraceMs = 15_000, backoff = Backoff(firstDelayMs = 2_000),
            ),
        )
    }
}
