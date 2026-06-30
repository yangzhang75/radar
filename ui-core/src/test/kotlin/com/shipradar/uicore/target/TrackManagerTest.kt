package com.shipradar.uicore.target

import com.shipradar.contract.OwnShipData
import com.shipradar.contract.TargetSource
import com.shipradar.contract.TargetStatus
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Step-2 tracker tests — synthetic plot streams only. Validates lifecycle (acquire→confirm→coast→drop),
 * α-β velocity estimation (course/speed), association/gating, id stability, and true-motion output.
 */
class TrackManagerTest {

    private val DT = 3.0 // seconds per antenna revolution
    private val dtH = DT / 3600.0

    private fun plot(rangeNm: Double, bearingDeg: Double, amp: Double = 12.0) =
        RadarPlot(id = "P", rangeNm = rangeNm, trueBearingDeg = bearingDeg, amplitudePeak = amp, cellCount = 4)

    /** Smallest angular distance between two bearings (handles 0/360 wrap). */
    private fun bearingErr(a: Double, b: Double): Double {
        val d = abs(((a - b) % 360 + 540) % 360 - 180)
        return d
    }

    @Test
    fun `a new plot starts as ACQUIRING then confirms after confirmHits scans`() {
        val tm = TrackManager(TrackerConfig(confirmHits = 3))
        var out = tm.update(listOf(plot(2.0, 45.0)), DT)
        assertEquals(1, out.size)
        assertEquals(TargetStatus.ACQUIRING, out.first().status, "1st hit → tentative")

        out = tm.update(listOf(plot(2.0, 45.0)), DT)
        assertEquals(TargetStatus.ACQUIRING, out.first().status, "2nd hit → still tentative")

        out = tm.update(listOf(plot(2.0, 45.0)), DT)
        assertEquals(TargetStatus.TRACKED, out.first().status, "3rd hit → confirmed")
        assertEquals(TargetSource.RADAR_TT, out.first().source)
    }

    @Test
    fun `alpha-beta filter recovers a constant relative course and speed`() {
        val tm = TrackManager()
        val speed = 20.0 // kn, heading due north (away), bearing 0
        var range = 2.0
        var last = tm.update(listOf(plot(range, 0.0)), DT).first()
        repeat(25) {
            range += speed * dtH
            last = tm.update(listOf(plot(range, 0.0)), DT).first()
        }
        assertEquals(TargetStatus.TRACKED, last.status)
        assertNotNull(last.speedKn)
        assertTrue(abs(last.speedKn!! - speed) < 3.0, "speed ${last.speedKn} ≈ $speed")
        assertTrue(bearingErr(last.courseDeg!!, 0.0) < 8.0, "course ${last.courseDeg} ≈ 0 (north)")
    }

    @Test
    fun `two targets are tracked as two stable tracks with persistent ids`() {
        val tm = TrackManager(TrackerConfig(confirmHits = 2))
        var out = tm.update(listOf(plot(2.0, 30.0), plot(4.0, 200.0)), DT)
        out = tm.update(listOf(plot(2.0, 30.0), plot(4.0, 200.0)), DT)
        assertEquals(2, out.size)
        val idsScan2 = out.map { it.id }.toSet()
        // feed a 3rd scan; ids must persist (same physical tracks updated, not re-created).
        val out3 = tm.update(listOf(plot(2.0, 30.0), plot(4.0, 200.0)), DT)
        assertEquals(idsScan2, out3.map { it.id }.toSet(), "track ids must be stable across scans")
        assertTrue(out3.all { it.status == TargetStatus.TRACKED })
    }

    @Test
    fun `a confirmed track coasts through misses then drops after maxCoastScans`() {
        val tm = TrackManager(TrackerConfig(confirmHits = 2, maxCoastScans = 3))
        // confirm
        tm.update(listOf(plot(3.0, 90.0)), DT)
        tm.update(listOf(plot(3.0, 90.0)), DT)
        assertEquals(1, tm.trackCount)
        // miss 3 scans → still coasting (alive)
        repeat(3) { tm.update(emptyList(), DT) }
        assertEquals(1, tm.trackCount, "coasting track must survive ≤ maxCoastScans misses")
        // 4th miss → dropped
        val out = tm.update(emptyList(), DT)
        assertEquals(0, tm.trackCount, "track must drop after maxCoastScans exceeded")
        assertTrue(out.isEmpty())
    }

    @Test
    fun `a plot outside the gate spawns a new track rather than hijacking an existing one`() {
        val tm = TrackManager(TrackerConfig(gateNm = 0.5, confirmHits = 2))
        tm.update(listOf(plot(2.0, 0.0)), DT)
        tm.update(listOf(plot(2.0, 0.0)), DT) // confirmed at (0,2)
        // next scan: plot far away (range 6 at bearing 0 → 4 NM jump, well beyond 0.5 gate)
        val out = tm.update(listOf(plot(6.0, 0.0)), DT)
        assertEquals(2, tm.trackCount, "far plot must not be associated to the near track")
        assertTrue(out.any { it.status == TargetStatus.ACQUIRING }, "the far plot is a fresh tentative track")
    }

    @Test
    fun `true course and speed include own-ship velocity when provided`() {
        // own ship steaming north at 10 kn; target painted at a fixed relative position (stationary buoy
        // relative to the seabed would move backward relative to own ship). Use a target whose RELATIVE
        // velocity is zero (it tracks with own ship → e.g. directly ahead, constant range/bearing):
        // then its TRUE velocity == own-ship velocity (north, 10 kn).
        val own = OwnShipData(headingDeg = 0.0, cogDeg = 0.0, sogKn = 10.0)
        val tm = TrackManager()
        var last = tm.update(listOf(plot(3.0, 0.0)), DT, own).first()
        repeat(10) { last = tm.update(listOf(plot(3.0, 0.0)), DT, own).first() }
        // relative velocity ≈ 0 → true velocity ≈ own velocity = 10 kn due north
        assertNotNull(last.speedKn)
        assertTrue(abs(last.speedKn!! - 10.0) < 2.0, "true speed ${last.speedKn} ≈ own 10 kn")
        assertTrue(bearingErr(last.courseDeg!!, 0.0) < 12.0, "true course ${last.courseDeg} ≈ north")
    }

    @Test
    fun `acquiring track reports no course or speed until a velocity estimate exists`() {
        val tm = TrackManager(TrackerConfig(confirmHits = 3))
        val out = tm.update(listOf(plot(2.0, 10.0)), DT).first()
        assertEquals(TargetStatus.ACQUIRING, out.status)
        assertNull(out.speedKn, "no speed from a single plot")
        assertNull(out.courseDeg)
    }

    @Test
    fun `a target that reappears soon after loss keeps its track id`() {
        val tm = TrackManager(TrackerConfig(confirmHits = 2, maxCoastScans = 2, reacquireScans = 3, reacquireGateNm = 1.0))
        // confirm a (stationary) target at bearing 90, range 3 → id assigned, status TRACKED.
        tm.update(listOf(plot(3.0, 90.0)), DT)
        val confirmed = tm.update(listOf(plot(3.0, 90.0)), DT).first()
        assertEquals(TargetStatus.TRACKED, confirmed.status)
        val originalId = confirmed.id
        // disappears: 3 empty scans → dropped into re-acquisition memory.
        repeat(3) { tm.update(emptyList(), DT) }
        assertEquals(0, tm.trackCount); assertEquals(1, tm.lostCount)
        // reappears near its last position → must revive the SAME id, immediately TRACKED.
        val revived = tm.update(listOf(plot(3.0, 90.0)), DT)
        assertEquals(1, revived.size)
        assertEquals(originalId, revived.first().id, "reappearing target must keep its id")
        assertEquals(TargetStatus.TRACKED, revived.first().status)
        assertEquals(0, tm.lostCount, "memory consumed on re-acquisition")
    }

    @Test
    fun `a target reappearing after the memory window gets a fresh id`() {
        val tm = TrackManager(TrackerConfig(confirmHits = 2, maxCoastScans = 1, reacquireScans = 2, reacquireGateNm = 1.0))
        tm.update(listOf(plot(3.0, 90.0)), DT)
        val originalId = tm.update(listOf(plot(3.0, 90.0)), DT).first().id
        // long absence: drop (2 empties) then exceed the 2-scan memory window.
        repeat(6) { tm.update(emptyList(), DT) }
        assertEquals(0, tm.lostCount, "memory must expire")
        val fresh = tm.update(listOf(plot(3.0, 90.0)), DT).first()
        assertTrue(fresh.id != originalId, "after the window, a reappearing target is a new track")
    }

    @Test
    fun `reset clears all tracks`() {
        val tm = TrackManager()
        tm.update(listOf(plot(2.0, 0.0), plot(3.0, 90.0)), DT)
        assertTrue(tm.trackCount > 0)
        tm.reset()
        assertEquals(0, tm.trackCount)
    }
}
