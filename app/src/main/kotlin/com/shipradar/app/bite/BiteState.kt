package com.shipradar.app.bite

import com.shipradar.contract.LinkState

/**
 * W6-C 性能监视 / BITE(Built-In Test Equipment)自检 —— 纯数据健康模型。
 *
 * 依据 IEC 62388 §6/§15(集成测试 / 性能监测):雷达须**持续指示**设备健康、传感器有效性、链路状态,
 * 并提供自检手段(BITE)。本模型是平台无关的纯快照,由编排者从 `com.shipradar.comms.service.DataLinkStats`
 * 与 `com.shipradar.contract.OwnShipData` 映射注入(见 [com.shipradar.app.bite.BiteMapping]);
 * [BitePanel] 仅做展示。所有判定为纯函数,可单测,不连任何 socket/comms。
 */

/** 单通道活跃度:绿=活跃、黄=静默(曾到包现已停)、红=无数据(从未到包)。 */
enum class ChannelActivity { ACTIVE, SILENT, NO_DATA }

/** 整体健康:OK / 降级 / 故障。 */
enum class OverallHealth { OK, DEGRADED, FAULT }

/**
 * 单个数据通道的健康快照。
 * @property name    通道名(ECHO / STATUS / TARGET / IEC450 …)。
 * @property packets 累计收包数。
 * @property ageMs   距最后到包的毫秒数;`null` = 从未到包(由 `ChannelStat.ageMs(now)` 提供)。
 */
data class ChannelHealth(
    val name: String,
    val packets: Long,
    val ageMs: Long?,
) {
    /** 活跃度判定:[ageMs] 在 [activeWithinMs] 内=ACTIVE;曾到包但已超时=SILENT;从未到包=NO_DATA。 */
    fun activity(activeWithinMs: Long = ACTIVE_WITHIN_MS): ChannelActivity = when {
        ageMs == null -> ChannelActivity.NO_DATA
        ageMs <= activeWithinMs -> ChannelActivity.ACTIVE
        else -> ChannelActivity.SILENT
    }

    companion object {
        /** 默认"活跃"窗口(ms)。超出即视为静默。可由调用方覆盖。 */
        const val ACTIVE_WITHIN_MS: Long = 3_000L
    }
}

/**
 * 一次性能监视 / BITE 健康快照。
 *
 * @property linkState     HALO 链路状态(契约枚举)。
 * @property channels      各数据接口通道收包健康(编排者从 DataLinkStats 的各 ChannelStat 映射)。
 * @property headingValid  航向(HDG)有效?(来自 OwnShipData.headingDeg != null)。
 * @property positionValid 位置(POSN)有效?(latitude/longitude 均非 null)。
 * @property sogValid      对地航速(SOG)有效?(sogKn != null)。
 * @property lastBiteMillis 上次运行 BITE 自检的时刻(epoch ms);`null` = 尚未自检。
 */
data class BiteReport(
    val linkState: LinkState,
    val channels: List<ChannelHealth>,
    val headingValid: Boolean,
    val positionValid: Boolean,
    val sogValid: Boolean,
    val lastBiteMillis: Long? = null,
) {
    /** 三项导航传感器是否全部有效。 */
    val sensorsAllValid: Boolean get() = headingValid && positionValid && sogValid

    /** 是否至少有一个通道处于活跃。空通道列表视为"未评估"(返回 true,不触发降级)。 */
    fun anyChannelActive(activeWithinMs: Long = ChannelHealth.ACTIVE_WITHIN_MS): Boolean =
        channels.isEmpty() || channels.any { it.activity(activeWithinMs) == ChannelActivity.ACTIVE }

    /**
     * 整体健康判定(纯函数,IEC 62388 §6 性能监测的"健康灯")。
     *  - **FAULT**:链路 DISCONNECTED(无链路)——设备/链路不可用。
     *  - **DEGRADED**:链路在但 ① 协商中/链路自报降级,或 ② 任一传感器无效,或 ③ 有通道但无任何活跃数据流。
     *  - **OK**:链路 CONNECTED 且传感器全有效且有数据流入。
     *
     * 说明:`NEGOTIATING` 归 DEGRADED(链路尚未建立但属正常启动过渡,非硬故障);仅 `DISCONNECTED` 记 FAULT。
     */
    fun overall(activeWithinMs: Long = ChannelHealth.ACTIVE_WITHIN_MS): OverallHealth {
        if (linkState == LinkState.DISCONNECTED) return OverallHealth.FAULT
        val degraded = linkState == LinkState.NEGOTIATING ||
            linkState == LinkState.DEGRADED ||
            !sensorsAllValid ||
            !anyChannelActive(activeWithinMs)
        return if (degraded) OverallHealth.DEGRADED else OverallHealth.OK
    }
}
