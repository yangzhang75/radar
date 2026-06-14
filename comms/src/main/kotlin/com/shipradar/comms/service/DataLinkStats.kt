package com.shipradar.comms.service

import com.shipradar.comms.iec450.Iec450DiscardCounters
import com.shipradar.comms.sync.SeqStats
import com.shipradar.contract.LinkState

/** 单通道收包统计:累计包数 + 最后到包时刻(ms,0=从未到包)。 */
data class ChannelStat(val packets: Long, val lastMs: Long) {
    /** 距最后到包的毫秒数(从未到包返回 null)。 */
    fun ageMs(nowMs: Long): Long? = if (lastMs == 0L) null else (nowMs - lastMs).coerceAtLeast(0)
}

/**
 * 数据链路监视快照 —— LIVE 诊断界面据此显示"收到没/解对没"。非契约的一部分,纯诊断。
 * 涵盖握手状态、各数据接口通道收包/时延、回波序列完整性、61162-450 丢弃明细。
 */
data class DataLinkStats(
    val nowMs: Long,
    val linkState: LinkState,
    val echo: ChannelStat,
    val echoB: ChannelStat,
    val status: ChannelStat,
    val target: ChannelStat,
    val iec450: ChannelStat,
    val seq: SeqStats,
    val discards: Iec450DiscardCounters,
    val aisDeferred: Long,
) {
    /** 任一通道在 [staleMs] 内有到包即视为"有数据流入"。 */
    fun anyData(staleMs: Long = 5_000): Boolean =
        listOf(echo, echoB, status, target, iec450).any { (it.ageMs(nowMs) ?: Long.MAX_VALUE) < staleMs }
}
