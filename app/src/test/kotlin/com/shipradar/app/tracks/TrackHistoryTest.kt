package com.shipradar.app.tracks

import com.shipradar.contract.OwnShipData
import com.shipradar.contract.TargetSource
import com.shipradar.contract.TargetStatus
import com.shipradar.contract.TrackedTarget
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * W7-C — past-tracks sampling/pruning (IEC 62388 §11.2: equally time-spaced, variable plot time).
 */
class TrackHistoryTest {

    private fun target(
        id: String,
        bearingDeg: Double,
        rangeNm: Double,
        trueBearing: Boolean = true,
    ) = TrackedTarget(
        id = id,
        source = TargetSource.RADAR_TT,
        rangeNm = rangeNm,
        bearingDeg = bearingDeg,
        trueBearing = trueBearing,
        status = TargetStatus.TRACKED,
    )

    private val ship = OwnShipData(headingDeg = 90.0)

    // MIN_1 = 60 s total, 6 marks ⇒ 10 s interval.
    private fun history(length: TrackLength = TrackLength.MIN_1) = TrackHistory(TracksConfig(length))

    @Test
    fun `samples closer than the interval are dropped (equal spacing)`() {
        val h = history()
        h.sample(listOf(target("A", 0.0, 5.0)), ship, 0L)
        h.sample(listOf(target("A", 0.0, 5.0)), ship, 5_000L)  // <10 s — ignored
        assertEquals(1, h.pointsFor("A").size)
        h.sample(listOf(target("A", 0.0, 5.0)), ship, 10_000L) // ==10 s — recorded
        assertEquals(2, h.pointsFor("A").size)
    }

    @Test
    fun `marks older than the plot time are trimmed`() {
        val h = history(TrackLength.MIN_1) // 60 s
        var t = 0L
        repeat(8) { h.sample(listOf(target("A", 0.0, 5.0)), ship, t); t += 10_000L } // 0..70 s
        val pts = h.pointsFor("A")
        // newest is 70 s; anything older than 70-60 = 10 s must be gone.
        assertTrue(pts.all { 70_000L - it.timestampMs <= 60_000L }, "no mark older than plot time")
        assertTrue(pts.first().timestampMs >= 10_000L, "oldest mark within window")
    }

    @Test
    fun `capacity per target is never exceeded over a long run`() {
        val h = history(TrackLength.MIN_1)
        val cap = TracksConfig(TrackLength.MIN_1).capacityPerTarget
        var t = 0L
        repeat(50) {
            h.sample(listOf(target("A", 0.0, 5.0)), ship, t)
            assertTrue(h.pointsFor("A").size <= cap, "size ${h.pointsFor("A").size} ≤ cap $cap")
            t += 10_000L
        }
    }

    @Test
    fun `OFF records nothing`() {
        val h = history(TrackLength.OFF)
        h.sample(listOf(target("A", 0.0, 5.0)), ship, 0L)
        h.sample(listOf(target("A", 0.0, 5.0)), ship, 60_000L)
        assertTrue(h.snapshot().isEmpty())
    }

    @Test
    fun `a target that disappears ages out and is forgotten`() {
        val h = history(TrackLength.MIN_1)
        h.sample(listOf(target("A", 0.0, 5.0)), ship, 0L)
        assertEquals(1, h.snapshot().size)
        // stop reporting A; advance past the plot time and prune.
        h.sample(emptyList(), ship, 70_000L)
        assertTrue(h.snapshot().isEmpty(), "lost target's history fully aged out")
    }

    @Test
    fun `relative bearing is stored north-referenced using heading, true bearing stored as-is`() {
        val h = history()
        // relative bearing 10° with heading 90° ⇒ true 100°.
        h.sample(listOf(target("REL", 10.0, 3.0, trueBearing = false)), ship, 0L)
        assertEquals(100.0, h.pointsFor("REL").last().trueBearingDeg, 1e-9)
        // true bearing kept; wrap handled (350+? here just direct).
        h.sample(listOf(target("TRU", 200.0, 3.0, trueBearing = true)), ship, 0L)
        assertEquals(200.0, h.pointsFor("TRU").last().trueBearingDeg, 1e-9)
    }

    @Test
    fun `relative bearing normalisation wraps past 360`() {
        val h = history()
        // relative 300° + heading 90° = 390° ⇒ 30°.
        h.sample(listOf(target("W", 300.0, 3.0, trueBearing = false)), ship, 0L)
        assertEquals(30.0, h.pointsFor("W").last().trueBearingDeg, 1e-9)
    }

    @Test
    fun `multiple targets keep independent histories`() {
        val h = history()
        h.sample(listOf(target("A", 0.0, 5.0), target("B", 90.0, 2.0)), ship, 0L)
        h.sample(listOf(target("A", 0.0, 5.0), target("B", 90.0, 2.0)), ship, 10_000L)
        assertEquals(2, h.pointsFor("A").size)
        assertEquals(2, h.pointsFor("B").size)
        assertEquals(setOf("A", "B"), h.snapshot().keys)
    }

    @Test
    fun `config derives equal sampling interval from length and marks`() {
        assertEquals(10_000L, TracksConfig(TrackLength.MIN_1).sampleIntervalMs)
        assertEquals(30_000L, TracksConfig(TrackLength.MIN_3).sampleIntervalMs)
        assertEquals(60_000L, TracksConfig(TrackLength.MIN_6).sampleIntervalMs)
        assertEquals(0L, TracksConfig(TrackLength.OFF).sampleIntervalMs)
    }
}
