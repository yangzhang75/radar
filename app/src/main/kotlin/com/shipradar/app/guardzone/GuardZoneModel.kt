package com.shipradar.app.guardzone

import com.shipradar.contract.GuardZoneAlarmType
import com.shipradar.contract.RadarCommand
import kotlin.math.roundToInt

/**
 * W7-A — 报警圈(guard zone) / 捕获区(acquisition zone) 的 **纯数据 + 逻辑模型**（无 Compose / 无 Android，可单测）。
 *
 * 依据 IEC 62388 §11.7（用户定义的捕获/激活/报警区，MSC.192/5.28）+ §11.3.7（自动捕获区，MSC.192/5.25.3.2）：
 * 操作员设定一/两个扇环区，目标进入(或离开/双向)区域即触发报警；区可独立开关、可设灵敏度；区边界须清晰标示
 * （§11.7.2.2、§11.3.7.1）。一个扇环区 = [内距离, 外距离] × [起方位 → 止方位]（顺时针扫过）。
 *
 * 与下行命令的关系（字段以 shared `Commands.kt` 为准，不臆造）：
 *  - 几何 → [RadarCommand.GuardZoneSetup](zone, startRangeMeters, endRangeMeters, bearingDeg=中心, widthDeg=扇宽)
 *  - 开关 → [RadarCommand.GuardZoneEnable]，报警方向 → [RadarCommand.GuardZoneAlarmMode]，灵敏度 → [RadarCommand.GuardZoneSensitivity]
 *
 * 注：HALO `GuardZoneSetup` 命令不含 `trueBearing` 标志，`GuardZoneSensitivity` 不含 `zone`（全局）。本模型保留
 * [GuardZone.trueBearing]（供命中测试/绘制选择参考系）与每区 [GuardZone.sensitivity]（任务要求的区参数），下发时按
 * 协议能力映射——见 [GuardZoneSetupPanel] 的说明与交付报告「疑问」。
 */
object GuardZoneModel {

    /** 海里→米。 */
    const val METERS_PER_NM = 1852.0

    /** 协议支持 2 个区：zone 0 / zone 1。 */
    const val ZONE_COUNT = 2

    /** 灵敏度区间。上限为 UI 暂定值（HALO 90C1/0300 的精确范围待协议确认，见交付报告）。 */
    const val SENSITIVITY_MIN = 0
    const val SENSITIVITY_MAX = 10

    /** 角度命中容差（度），吸收浮点/操作步进误差。 */
    private const val ANGLE_EPS = 1e-6

    /**
     * 扇环命中测试（纯几何）：目标是否落在本区内。
     *
     * 参系约定：`targetBearingDeg` 必须与本区 [GuardZone.trueBearing] 同一参系（同为真方位或同为相对船首方位）——
     * 由调用方保证。本函数只做几何包含，不看 [GuardZone.enabled]、不看报警方向（那是触发逻辑，见 [triggers]）。
     *
     * @param targetBearingDeg 目标方位（度，与区同参系），任意实数，内部归一化到 [0,360)。
     * @param targetRangeNm 目标距离（NM）。
     */
    fun contains(zone: GuardZone, targetBearingDeg: Double, targetRangeNm: Double): Boolean {
        val inner = minOf(zone.innerRangeNm, zone.outerRangeNm)
        val outer = maxOf(zone.innerRangeNm, zone.outerRangeNm)
        if (targetRangeNm < inner || targetRangeNm > outer) return false
        return bearingInSector(targetBearingDeg, zone.startBearingDeg, zone.endBearingDeg)
    }

    /**
     * 报警是否应触发：区已启用 且 目标几何命中。
     * （进入/离开/双向的“跨界”判定需要前后两帧状态，属上层告警引擎；本函数给出“当前在区内”这一必要条件，
     *  对 ENTERING/BOTH 的“在区内即新目标告警”语义已足够——§11.7.2.2 d「进入或在区内即给新目标告警」。）
     */
    fun triggers(zone: GuardZone, targetBearingDeg: Double, targetRangeNm: Double): Boolean =
        zone.enabled && contains(zone, targetBearingDeg, targetRangeNm)

    /** 返回某目标命中的所有启用区（供告警引擎/调试）。 */
    fun zonesHit(zones: List<GuardZone>, targetBearingDeg: Double, targetRangeNm: Double): List<GuardZone> =
        zones.filter { triggers(it, targetBearingDeg, targetRangeNm) }

    /**
     * 区扇宽（度，顺时针自起方位扫到止方位）。起==止 视为整圈 360°。
     */
    fun sweepDeg(zone: GuardZone): Double {
        val w = norm360(zone.endBearingDeg - zone.startBearingDeg)
        return if (w <= ANGLE_EPS) 360.0 else w
    }

    /** 区中心方位（度）= 起方位 + 扇宽/2，归一化。 */
    fun centerBearingDeg(zone: GuardZone): Double = norm360(zone.startBearingDeg + sweepDeg(zone) / 2.0)

    /**
     * 几何 → [RadarCommand.GuardZoneSetup]（90C1/0200）。距离 NM→米取整；方位用中心+扇宽。
     */
    fun toSetupCommand(zone: GuardZone): RadarCommand.GuardZoneSetup = RadarCommand.GuardZoneSetup(
        zone = zone.zone,
        startRangeMeters = (minOf(zone.innerRangeNm, zone.outerRangeNm) * METERS_PER_NM).roundToInt(),
        endRangeMeters = (maxOf(zone.innerRangeNm, zone.outerRangeNm) * METERS_PER_NM).roundToInt(),
        bearingDeg = centerBearingDeg(zone),
        widthDeg = sweepDeg(zone),
    )

    /**
     * 方位是否落在扇区 [start → end]（顺时针）内，正确处理跨 360° 边界。
     * 起==止 视为整圈（恒真）。
     */
    fun bearingInSector(bearingDeg: Double, startDeg: Double, endDeg: Double): Boolean {
        val width = norm360(endDeg - startDeg)
        if (width <= ANGLE_EPS) return true // 整圈
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
 * 一个报警圈/捕获区的参数。zone ∈ {0,1}。方位顺时针自 [startBearingDeg] 扫到 [endBearingDeg]。
 *
 * @property zone 区号 0 或 1。
 * @property enabled 是否启用（独立开关，§11.7：可独立开/关）。
 * @property innerRangeNm 内距离 NM。
 * @property outerRangeNm 外距离 NM。
 * @property startBearingDeg 起方位（度）。
 * @property endBearingDeg 止方位（度，顺时针在起方位之后）。起==止 表示整圈。
 * @property trueBearing 方位参系：true=真方位，false=相对船首。（决定命中测试/绘制所用参系。）
 * @property alarmType 报警方向：进入/离开/双向（[GuardZoneAlarmType]）。
 * @property sensitivity 灵敏度 [GuardZoneModel.SENSITIVITY_MIN]..[GuardZoneModel.SENSITIVITY_MAX]。
 */
data class GuardZone(
    val zone: Int,
    val enabled: Boolean = false,
    val innerRangeNm: Double = 3.0,
    val outerRangeNm: Double = 4.0,
    val startBearingDeg: Double = 0.0,
    val endBearingDeg: Double = 0.0,                  // 起==止 = 整圈
    val trueBearing: Boolean = false,                 // 默认相对船首
    val alarmType: GuardZoneAlarmType = GuardZoneAlarmType.ENTERING,
    val sensitivity: Int = (GuardZoneModel.SENSITIVITY_MIN + GuardZoneModel.SENSITIVITY_MAX) / 2,
)
