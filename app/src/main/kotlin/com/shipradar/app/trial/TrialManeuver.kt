package com.shipradar.app.trial

import com.shipradar.contract.OwnShipData
import com.shipradar.contract.TargetSource
import com.shipradar.contract.TrackedTarget
import com.shipradar.uicore.target.CpaTcpaCalculator
import com.shipradar.uicore.target.DangerCriteria
import com.shipradar.uicore.target.Geometry
import com.shipradar.uicore.target.InstantTrialManeuverSimulator
import com.shipradar.uicore.target.TrialManeuverSimulator
import com.shipradar.uicore.target.TrialManeuverRequest
import com.shipradar.uicore.target.Vec2

/**
 * 试操船(Trial Maneuver)—— W6-A 应用层。
 *
 * 本文件**不重写任何矢量数学**:CPA/TCPA/危险判定全部委托给 ui-core 已认证的
 * [InstantTrialManeuverSimulator](IMO A.823(19) §3.7 / GB 11711-2002 §4.2.7 强制部分)。本层只做两件事:
 *  1. 把面板的操作员单位(度 / 节 / 分钟-延迟)翻译成 ui-core 的 [TrialManeuverRequest](秒延迟);
 *  2. 为避碰画面补出**试操后相对运动矢量**(A.823 Appendix 1 §17 的相对矢量),并把当前实测值与试操值
 *     并排,供操作员对比。相对矢量方向只依赖 `目标真速度 − 试操后本船速度`,与延迟无关(延迟只平移
 *     相对起点,不改变相对速度方向),因此可直接由 ui-core 的 [Geometry]/[Vec2] 原语算出,无需重算几何。
 *
 * **试操绝不影响真实跟踪**:[InstantTrialManeuverSimulator] 只在副本上传播;本层亦只读 [TrackedTarget],
 * 不回写(A.823 §3.7.1 "shall not interrupt the updating of target data")。
 */

/** 操作员设定的试操参数(界面单位)。 */
data class TrialManeuverParams(
    /** 试操航向(度,北顺时针)。 */
    val trialCourseDeg: Double,
    /** 试操航速(节)。 */
    val trialSpeedKn: Double,
    /** 机动延迟(分钟):本船保持当前态直到延迟结束再瞬时改向改速(A.823 §3.7.1 "with or without time delay")。 */
    val delayMin: Double = 0.0,
) {
    init {
        require(delayMin >= 0) { "delayMin must be >= 0" }
        require(trialSpeedKn >= 0) { "trialSpeedKn must be >= 0" }
    }
}

/**
 * 单目标"试操前/后"对比行。
 *
 * @property liveCpaNm/[liveTcpaSec]/[liveDangerous] 当前真实态(直接取自已富集的 [TrackedTarget],未重算)。
 * @property trialCpaNm/[trialTcpaSec]/[trialDangerous] 试操生效后的预测值(来自 [InstantTrialManeuverSimulator])。
 * @property trialRelCourseDeg 试操后相对运动矢量方向(度);相对速度近零时为 null(无可预测的接近点)。
 * @property trialRelSpeedKn   试操后相对速度大小(节)。
 */
data class TrialComparisonRow(
    val targetId: String,
    val source: TargetSource,
    val liveCpaNm: Double?,
    val liveTcpaSec: Double?,
    val liveDangerous: Boolean,
    val trialCpaNm: Double,
    val trialTcpaSec: Double?,
    val trialDangerous: Boolean,
    val trialRelCourseDeg: Double?,
    val trialRelSpeedKn: Double,
) {
    /** CPA 变化趋势(避碰决策的核心信号:试操后 CPA 变大=更安全)。 */
    enum class Trend { SAFER, WORSE, UNCHANGED, UNKNOWN }

    /**
     * 试操后 CPA 相对当前 CPA 的变化方向。[liveCpaNm] 未知时为 [Trend.UNKNOWN];
     * 差值小于 [CPA_TREND_EPSILON_NM] 视作 [Trend.UNCHANGED]。
     */
    val cpaTrend: Trend
        get() {
            val live = liveCpaNm ?: return Trend.UNKNOWN
            val delta = trialCpaNm - live
            return when {
                delta > CPA_TREND_EPSILON_NM -> Trend.SAFER
                delta < -CPA_TREND_EPSILON_NM -> Trend.WORSE
                else -> Trend.UNCHANGED
            }
        }

    companion object {
        /** CPA 趋势判定的死区(NM):小于此差值不报"变化",避免数值抖动。 */
        const val CPA_TREND_EPSILON_NM: Double = 0.01
    }
}

/** 试操评估器:对全部目标重算试操后 CPA/TCPA/相对矢量,并与当前实测值并排。 */
object TrialManeuverEvaluator {

    /**
     * 对 [targets] 评估 [params] 试操方案。返回每个**可解算**目标(方位/航向/航速齐全)的对比行;
     * 无法解算 CPA 的目标(同 [InstantTrialManeuverSimulator] 的 mapNotNull 语义)被剔除。
     *
     * @param criteria 操作员 CPA/TCPA 安全门限(A.823 §3.5.2,无标准强制默认值)。
     * @param simulator 试操模型:默认瞬时机动(§3.7 强制部分);传
     *   [com.shipradar.uicore.target.CurvedTrialManeuverSimulator] 可启用含本船操纵特性的曲线预测(§3.7.2 可选)。
     */
    fun evaluate(
        ownShip: OwnShipData,
        targets: List<TrackedTarget>,
        params: TrialManeuverParams,
        criteria: DangerCriteria = DangerCriteria(),
        simulator: TrialManeuverSimulator = InstantTrialManeuverSimulator(),
    ): List<TrialComparisonRow> {
        val request = TrialManeuverRequest(
            newCourseDeg = params.trialCourseDeg,
            newSpeedKn = params.trialSpeedKn,
            delaySec = params.delayMin * 60.0,
        )
        // 权威 CPA/TCPA/危险判定:委托 ui-core(正确处理延迟、不触碰真实跟踪)。
        val outcomes = simulator.simulate(ownShip, targets, request, criteria)
            .outcomes.associateBy { it.targetId }
        // 试操后本船速度矢量(节,NE 平面)。相对矢量方向与延迟无关,故只需它 + 目标真速度。
        val newOwnVel = Vec2.ofBearing(params.trialCourseDeg, params.trialSpeedKn)

        return targets.mapNotNull { t ->
            val o = outcomes[t.id] ?: return@mapNotNull null
            // 试操后相对运动矢量 = 目标真速度 − 试操后本船速度(A.823 Appendix 1 §17)。
            val relVel = Geometry.targetVelocity(t)?.let { it - newOwnVel }
            val relSpeed = relVel?.norm() ?: 0.0
            val relCourse = relVel
                ?.takeIf { relSpeed >= CpaTcpaCalculator.REL_SPEED_EPSILON_KN }
                ?.let { Geometry.bearingOf(it) }
            TrialComparisonRow(
                targetId = t.id,
                source = t.source,
                liveCpaNm = t.cpaNm,
                liveTcpaSec = t.tcpaSec,
                liveDangerous = t.dangerous,
                trialCpaNm = o.trialCpaNm,
                trialTcpaSec = o.trialTcpaSec,
                trialDangerous = o.dangerous,
                trialRelCourseDeg = relCourse,
                trialRelSpeedKn = relSpeed,
            )
        }
    }
}
