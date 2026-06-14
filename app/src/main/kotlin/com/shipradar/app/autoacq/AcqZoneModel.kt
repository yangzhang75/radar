package com.shipradar.app.autoacq

import com.shipradar.contract.TargetSource
import com.shipradar.contract.TrackedTarget

/**
 * W8-E — 自动捕获区(auto-acquisition zone) 的 **纯逻辑模型**（无 Compose / 无 Android，可单测）。
 *
 * 依据 IEC 62388 §11.3.7（自动捕获，MSC.192/5.25.3.2）+ §11.7.2（用户定义捕获/激活区）：
 * 操作员画定扇环区域，雷达对**进入该区或在区内检测到的回波自动起始跟踪**（并给新目标告警，§11.7.2.2 b/d）。
 * 这与报警圈(guard zone)语义不同——报警圈是“进/离区报警”，自动捕获区是“自动起跟踪”。本模型与
 * `com.shipradar.app.guardzone` **完全独立**（不 import），扇环命中逻辑自成体系。
 *
 * 一个扇环区 = [内距离, 外距离] NM × [起方位 → 止方位] deg（顺时针扫过）。支持 ≥1 个区。
 */
object AcqZoneModel {

    /** 角度命中容差（度），吸收浮点/步进误差。 */
    private const val ANGLE_EPS = 1e-6

    /**
     * 扇环命中测试（纯几何）：方位/距离是否落在本区内。正确处理跨 360° 边界与整圈。
     *
     * 参系约定：`bearingDeg` 必须与 [AcqZone.trueBearing] 同参系（同为真方位或同为相对船首），由调用方保证。
     * 不看 [AcqZone.enabled]。
     */
    fun contains(zone: AcqZone, bearingDeg: Double, rangeNm: Double): Boolean {
        val inner = minOf(zone.innerRangeNm, zone.outerRangeNm)
        val outer = maxOf(zone.innerRangeNm, zone.outerRangeNm)
        if (rangeNm < inner || rangeNm > outer) return false
        return bearingInSector(bearingDeg, zone.startBearingDeg, zone.endBearingDeg)
    }

    /**
     * 目标是否落在本区内（便捷重载）。仅当目标与区方位参系一致时比较；参系不一致返回 false（无法在缺航向时换算）。
     */
    fun contains(zone: AcqZone, target: TrackedTarget): Boolean =
        target.trueBearing == zone.trueBearing && contains(zone, target.bearingDeg, target.rangeNm)

    /**
     * 自动捕获候选：落在**任一启用区**内、且为**雷达回波**(RADAR_TT)、且尚未跟踪的目标。
     * （§11.3.7 b：进入或在自动捕获区内检测到的雷达目标自动捕获。AIS 目标由 AIS 处理另行激活，不在此。）
     *
     * @param isAcquired 谓词：目标是否已处于跟踪态（由跟踪引擎提供）；默认按 [TrackedTarget.status] 粗判。
     */
    fun autoAcquireCandidates(
        zones: List<AcqZone>,
        targets: List<TrackedTarget>,
        isAcquired: (TrackedTarget) -> Boolean = { it.source != TargetSource.RADAR_TT },
    ): List<TrackedTarget> {
        val active = zones.filter { it.enabled }
        if (active.isEmpty()) return emptyList()
        return targets.filter { t ->
            t.source == TargetSource.RADAR_TT && !isAcquired(t) && active.any { contains(it, t) }
        }
    }

    /** 区扇宽（度，顺时针）。起==止 视为整圈 360°。 */
    fun sweepDeg(zone: AcqZone): Double {
        val w = norm360(zone.endBearingDeg - zone.startBearingDeg)
        return if (w <= ANGLE_EPS) 360.0 else w
    }

    /** 区中心方位（度）。 */
    fun centerBearingDeg(zone: AcqZone): Double = norm360(zone.startBearingDeg + sweepDeg(zone) / 2.0)

    /** 方位是否在扇区 [start → end]（顺时针）内，正确处理跨 360°。起==止 视为整圈（恒真）。 */
    fun bearingInSector(bearingDeg: Double, startDeg: Double, endDeg: Double): Boolean {
        val width = norm360(endDeg - startDeg)
        if (width <= ANGLE_EPS) return true
        val offset = norm360(bearingDeg - startDeg)
        return offset <= width + ANGLE_EPS
    }

    /** 归一化到 [0,360)。 */
    fun norm360(deg: Double): Double {
        var d = deg % 360.0
        if (d < 0) d += 360.0
        return d
    }
}

/**
 * 一个自动捕获区的参数。方位顺时针自 [startBearingDeg] 扫到 [endBearingDeg]（起==止 = 整圈）。
 *
 * @property id 区标识（≥1 个区时区分，0 起）。
 * @property enabled 是否启用。
 * @property innerRangeNm 内距离 NM。
 * @property outerRangeNm 外距离 NM。
 * @property startBearingDeg 起方位（度）。
 * @property endBearingDeg 止方位（度，顺时针在起方位之后）。
 * @property trueBearing 方位参系：true=真方位，false=相对船首（与 [TrackedTarget.trueBearing] 对齐用）。
 */
data class AcqZone(
    val id: Int = 0,
    val enabled: Boolean = false,
    val innerRangeNm: Double = 3.0,
    val outerRangeNm: Double = 4.0,
    val startBearingDeg: Double = 0.0,
    val endBearingDeg: Double = 0.0,
    val trueBearing: Boolean = false,
)
