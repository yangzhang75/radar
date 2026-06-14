package com.shipradar.app.trial

import com.shipradar.contract.OwnShipData
import com.shipradar.contract.TargetSource
import com.shipradar.contract.TargetStatus
import com.shipradar.contract.TrackedTarget
import com.shipradar.uicore.target.DangerClassifier
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * 试操船应用层逻辑单测(W6-A)。验证试操后 CPA/TCPA 变化方向正确,以及相对矢量解算。
 *
 * 几何均在本机平面内手算可复核(本船航速 10 kn,航向单位向量见 ui-core Geometry 约定:
 * 北顺时针,(x=E, y=N) = (sin, cos))。CPA/TCPA 的权威值由 ui-core 认证算法给出,这里只断方向。
 */
class TrialManeuverTest {

    /** 本船:航向/COG 000°,航速 10 kn(正北航行)。 */
    private val ownShip = OwnShipData(
        headingDeg = 0.0, headingTrue = true, cogDeg = 0.0, sogKn = 10.0,
    )

    /** 把目标先按真实态富集(CPA/TCPA/dangerous),模拟生产环境送进面板的已富集目标。 */
    private fun enriched(vararg t: TrackedTarget): List<TrackedTarget> =
        DangerClassifier.evaluateAll(ownShip, t.toList())

    @Test
    fun `head-on target — turning away increases CPA and clears danger`() {
        // 正前方 3 NM,对遇(航向 180°,10 kn):相对速度 20 kn 直接撞上,TCPA=9 min(<12 min 默认门限),
        // 当前 CPA≈0 → 危险。
        val headOn = TrackedTarget(
            id = "TT01", source = TargetSource.RADAR_TT, rangeNm = 3.0, bearingDeg = 0.0,
            trueBearing = true, courseDeg = 180.0, speedKn = 10.0, status = TargetStatus.TRACKED,
        )
        val targets = enriched(headOn)
        assertTrue(targets[0].dangerous, "对遇目标当前应判危险")

        // 右转 90° 到航向 090°,保持 10 kn,无延迟。
        val rows = TrialManeuverEvaluator.evaluate(
            ownShip, targets, TrialManeuverParams(trialCourseDeg = 90.0, trialSpeedKn = 10.0),
        )
        val row = rows.single()

        assertTrue(row.liveDangerous, "试操前危险")
        assertTrue(
            row.trialCpaNm > row.liveCpaNm!!,
            "右转后 CPA 应增大:trial=${row.trialCpaNm} live=${row.liveCpaNm}",
        )
        assertTrue(row.trialCpaNm > 2.0, "几何上 CPA 应≈2.12 NM,实得 ${row.trialCpaNm}")
        assertEquals(TrialComparisonRow.Trend.SAFER, row.cpaTrend)
        assertTrue(!row.trialDangerous, "右转后应不再危险")
        assertNotNull(row.trialTcpaSec, "仍有相对运动,TCPA 应有值")
        assertTrue(row.trialTcpaSec!! > 0, "CPA 尚未通过,TCPA 应为正")
    }

    @Test
    fun `abeam stationary target — turning toward it reduces CPA into danger`() {
        // 右舷正横 3 NM 静止目标:当前正横通过,CPA=3 NM(>2 NM 门限)→ 安全。
        val abeam = TrackedTarget(
            id = "AIS7", source = TargetSource.AIS_ACTIVE, rangeNm = 3.0, bearingDeg = 90.0,
            trueBearing = true, courseDeg = 0.0, speedKn = 0.0, status = TargetStatus.TRACKED,
        )
        val targets = enriched(abeam)
        assertTrue(!targets[0].dangerous, "正横静止目标当前应安全")

        // 右转朝向它并加速(航向 090°,20 kn):径直驶向,CPA→0、TCPA=9 min(<12 min)→ 危险。
        val rows = TrialManeuverEvaluator.evaluate(
            ownShip, targets, TrialManeuverParams(trialCourseDeg = 90.0, trialSpeedKn = 20.0),
        )
        val row = rows.single()

        assertTrue(!row.liveDangerous, "试操前安全")
        assertTrue(
            row.trialCpaNm < row.liveCpaNm!!,
            "朝向目标后 CPA 应减小:trial=${row.trialCpaNm} live=${row.liveCpaNm}",
        )
        assertTrue(row.trialCpaNm < 0.5, "几何上 CPA 应≈0,实得 ${row.trialCpaNm}")
        assertEquals(TrialComparisonRow.Trend.WORSE, row.cpaTrend)
        assertTrue(row.trialDangerous, "朝向静止目标后应判危险")
    }

    @Test
    fun `relative-motion vector reported for post-maneuver solution`() {
        // 静止目标 + 试操航向 090°/10 kn:相对速度 = 0 − 本船(10kn 向东) = 10 kn 向西(270°)。
        val stationary = TrackedTarget(
            id = "T", source = TargetSource.RADAR_TT, rangeNm = 6.0, bearingDeg = 45.0,
            trueBearing = true, courseDeg = 0.0, speedKn = 0.0, status = TargetStatus.TRACKED,
        )
        val rows = TrialManeuverEvaluator.evaluate(
            ownShip, enriched(stationary),
            TrialManeuverParams(trialCourseDeg = 90.0, trialSpeedKn = 10.0),
        )
        val row = rows.single()
        assertEquals(10.0, row.trialRelSpeedKn, 1e-6)
        assertNotNull(row.trialRelCourseDeg)
        assertEquals(270.0, row.trialRelCourseDeg!!, 1e-6)
    }

    @Test
    fun `delay shifts the trial TCPA frame-of-reference (measured from now)`() {
        val headOn = TrackedTarget(
            id = "TT01", source = TargetSource.RADAR_TT, rangeNm = 6.0, bearingDeg = 0.0,
            trueBearing = true, courseDeg = 180.0, speedKn = 10.0, status = TargetStatus.TRACKED,
        )
        val targets = enriched(headOn)
        val delayed = TrialManeuverEvaluator.evaluate(
            ownShip, targets,
            TrialManeuverParams(trialCourseDeg = 90.0, trialSpeedKn = 10.0, delayMin = 5.0),
        ).single()
        // TCPA 以"现在"为基准,必含延迟段(本船 5 min 内保持原态),故 ≥ 300 s。
        assertNotNull(delayed.trialTcpaSec)
        assertTrue(delayed.trialTcpaSec!! >= 300.0, "含 5 min 延迟,TCPA 应 ≥300 s,实得 ${delayed.trialTcpaSec}")
    }

    @Test
    fun `unresolvable targets are dropped`() {
        // 缺航向/航速 → 无法解算 → 应被剔除(同 ui-core 模拟器语义)。
        val noMotion = TrackedTarget(
            id = "X", source = TargetSource.RADAR_TT, rangeNm = 6.0, bearingDeg = 0.0,
            trueBearing = true, courseDeg = null, speedKn = null, status = TargetStatus.ACQUIRING,
        )
        val rows = TrialManeuverEvaluator.evaluate(
            ownShip, listOf(noMotion), TrialManeuverParams(90.0, 10.0),
        )
        assertTrue(rows.isEmpty())
    }
}
