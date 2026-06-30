package com.shipradar.uicore.target

import com.shipradar.contract.OwnShipData
import com.shipradar.contract.TargetStatus
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.sin
import kotlin.test.Test
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * Certification-aligned tracking-accuracy tests — IMO **A.823(19) Appendix 1** / **IEC 62388 §11**.
 *
 * A.823 §3.4 + Appendix 1 require an ARPA, after **1 minute of steady-state tracking**, to present
 * relative motion trend to (95% probability) about **±11° course, ±1.5 kn (or ±10%) speed, ±1.0 NM CPA**;
 * after 3 minutes the bounds tighten. These tests drive the [TrackManager] with a deterministic kinematic
 * target and assert it meets the 1-minute and 3-minute bounds — locking the filter tuning to the standard.
 *
 * Antenna revolution is taken as 2.5 s (≈24 rpm), so 1 min = 24 scans, 3 min = 72 scans.
 */
class TrackingAccuracyTest {

    private val SCAN = 2.5 // s per revolution (~24 rpm)
    private val scansPerMin = (60.0 / SCAN).toInt() // 24

    /** NE-plane point (NM, x=East y=North) → a RadarPlot (true bearing). */
    private fun plotAt(x: Double, y: Double) = RadarPlot(
        id = "P",
        rangeNm = hypot(x, y),
        trueBearingDeg = Geometry.normalizeDeg(Math.toDegrees(atan2(x, y))),
        amplitudePeak = 12.0,
        cellCount = 4,
    )

    /** Smallest absolute angular error between two bearings (deg). */
    private fun courseErr(a: Double, b: Double) = abs(((a - b) % 360 + 540) % 360 - 180)

    /**
     * Run a steady-course target for [scans] revolutions and return the final track. Own ship is
     * stationary so the estimated (relative) velocity equals the target's true velocity.
     */
    private fun runSteady(
        startX: Double, startY: Double,
        courseDeg: Double, speedKn: Double,
        scans: Int,
    ): com.shipradar.contract.TrackedTarget {
        val tm = TrackManager()
        val own = OwnShipData(headingDeg = 0.0, cogDeg = 0.0, sogKn = 0.0)
        val vx = speedKn * sin(Math.toRadians(courseDeg)) // NM/h East
        val vy = speedKn * cos(Math.toRadians(courseDeg)) // NM/h North
        var x = startX
        var y = startY
        var last: com.shipradar.contract.TrackedTarget? = null
        repeat(scans) {
            last = tm.update(listOf(plotAt(x, y)), SCAN, own).firstOrNull()
            x += vx * (SCAN / 3600.0)
            y += vy * (SCAN / 3600.0)
        }
        return last!!
    }

    @Test
    fun `steady target meets A823 one-minute course and speed accuracy`() {
        val trueCourse = 270.0 // due west
        val trueSpeed = 15.0
        val t = runSteady(startX = 3.0, startY = 3.0, courseDeg = trueCourse, speedKn = trueSpeed, scans = scansPerMin)
        assertTrue(t.status == TargetStatus.TRACKED, "must be a confirmed track within 1 min")
        assertNotNull(t.speedKn); assertNotNull(t.courseDeg)
        // A.823 Appendix 1, 1-min steady state: course ±11°, speed ±1.5 kn (or ±10%).
        assertTrue(courseErr(t.courseDeg!!, trueCourse) <= 11.0, "1-min course err ${t.courseDeg} vs $trueCourse")
        val speedTol = maxOf(1.5, 0.10 * trueSpeed)
        assertTrue(abs(t.speedKn!! - trueSpeed) <= speedTol, "1-min speed ${t.speedKn} vs $trueSpeed (±$speedTol)")
    }

    @Test
    fun `steady target meets tighter three-minute accuracy`() {
        val trueCourse = 200.0
        val trueSpeed = 20.0
        val t = runSteady(startX = -2.0, startY = 5.0, courseDeg = trueCourse, speedKn = trueSpeed, scans = 3 * scansPerMin)
        // After 3 min the estimate should be well inside the 1-min bounds (A.823 tightens at 3 min).
        assertTrue(courseErr(t.courseDeg!!, trueCourse) <= 5.0, "3-min course err ${t.courseDeg} vs $trueCourse")
        assertTrue(abs(t.speedKn!! - trueSpeed) <= 1.0, "3-min speed ${t.speedKn} vs $trueSpeed")
    }

    @Test
    fun `crossing target CPA estimate is within A823 one-minute bound`() {
        // target crossing ahead; own ship stationary so relative motion == target motion.
        // start 6 NM north, moving due west at 12 kn → passes to the west, CPA ≈ horizontal offset.
        val tm = TrackManager()
        val own = OwnShipData(headingDeg = 0.0, cogDeg = 0.0, sogKn = 0.0)
        var x = 1.0; var y = 6.0
        val speed = 12.0; val course = 270.0
        val vx = speed * sin(Math.toRadians(course)); val vy = speed * cos(Math.toRadians(course))
        var last: com.shipradar.contract.TrackedTarget? = null
        repeat(scansPerMin) {
            last = tm.update(listOf(plotAt(x, y)), SCAN, own).firstOrNull()
            x += vx * (SCAN / 3600.0); y += vy * (SCAN / 3600.0)
        }
        val t = last!!
        // feed the track through the same CPA calculator the app uses.
        val sol = CpaTcpaCalculator.compute(own, t)
        assertNotNull(sol, "CPA must be computable from the tracked estimate")
        // true CPA for this geometry: target line y=6 moving west past x=0 → CPA = 6 NM? No: own at origin,
        // target crosses the y-axis at x=0,y=6 → closest approach is 6 NM (passes 6 NM ahead). ±1.0 NM (A.823).
        assertTrue(abs(sol.cpaNm - 6.0) <= 1.0, "1-min CPA ${sol.cpaNm} vs ~6.0 NM (±1.0)")
    }
}
