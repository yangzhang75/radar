package com.shipradar.app.bite

import com.shipradar.comms.service.ChannelStat
import com.shipradar.comms.service.DataLinkStats
import com.shipradar.contract.OwnShipData

/**
 * 把 LIVE 诊断快照 [DataLinkStats] + 导航状态 [OwnShipData] 映射成纯展示用的 [BiteReport]。
 *
 * 这是编排者注入数据的推荐入口:`BitePanel(BiteMapping.from(dataLinkStats, ownShip, lastBiteMillis))`。
 * 不连任何 socket —— 入参由 comms 前台服务侧采集后传入。若编排者偏好自定义映射,本对象可整体删除,
 * [BiteReport] 不依赖它。
 */
object BiteMapping {

    /**
     * @param lastBiteMillis 上次 BITE 自检时刻(由界面在 RUN BITE 回调里记录后回传);未自检传 null。
     */
    fun from(stats: DataLinkStats, ownShip: OwnShipData, lastBiteMillis: Long? = null): BiteReport {
        fun ch(name: String, c: ChannelStat) = ChannelHealth(name, c.packets, c.ageMs(stats.nowMs))
        return BiteReport(
            linkState = stats.linkState,
            channels = listOf(
                ch("ECHO", stats.echo),
                ch("ECHO-B", stats.echoB),
                ch("STATUS", stats.status),
                ch("TARGET", stats.target),
                ch("IEC450", stats.iec450),
            ),
            // 传感器有效性 = OwnShipData 对应字段非 null(IEC 62388 §6 传感器有效性指示)。
            // 如需更精细,可改用 ownShip.sourceValidity[SensorKind.*]。
            headingValid = ownShip.headingDeg != null,
            positionValid = ownShip.latitude != null && ownShip.longitude != null,
            sogValid = ownShip.sogKn != null,
            lastBiteMillis = lastBiteMillis,
        )
    }
}
