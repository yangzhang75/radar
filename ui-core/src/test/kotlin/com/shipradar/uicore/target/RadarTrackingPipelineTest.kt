package com.shipradar.uicore.target

import com.shipradar.contract.EchoSpoke
import com.shipradar.contract.SampleEncoding
import com.shipradar.contract.TargetStatus
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Step-3 tests: scan aggregation by azimuth wrap, and the end-to-end spoke→track pipeline. */
class RadarTrackingPipelineTest {

    private val N = 512
    private fun samples(base: Int = 1, blobs: Map<Int, Int> = emptyMap()): ByteArray {
        val a = ByteArray(N) { base.toByte() }
        for ((i, v) in blobs) a[i] = v.toByte()
        return a
    }

    private fun spoke(azDeg: Double, blobs: Map<Int, Int> = emptyMap()) = EchoSpoke(
        azimuthDeg = azDeg, headingDeg = 0.0, trueNorth = true,
        rangeCellSizeMm = 2000, rangeCellsDiv2 = N / 2,
        samples = samples(blobs = blobs), encoding = SampleEncoding.AMPLITUDE,
        sequenceNumber = 0, bearingZeroError = false,
    )

    // --- ScanAggregator ---

    @Test
    fun `aggregator emits a scan only when azimuth wraps past zero`() {
        val agg = ScanAggregator(minSpokesPerScan = 8)
        // first revolution: 0..350 step 10 → 36 spokes, none completes
        for (az in 0 until 360 step 10) assertNull(agg.onSpoke(spoke(az.toDouble())), "no scan mid-revolution at $az")
        // next spoke at az 0 wraps → previous revolution completes
        val scan = agg.onSpoke(spoke(0.0))
        assertTrue(scan != null && scan.size == 36, "completed scan should hold the 36 buffered spokes")
    }

    @Test
    fun `aggregator does not emit a too-small buffer`() {
        val agg = ScanAggregator(minSpokesPerScan = 8)
        agg.onSpoke(spoke(350.0))
        // immediate wrap with only 1 buffered spoke → below minSpokesPerScan, no emit
        assertNull(agg.onSpoke(spoke(1.0)))
    }

    // --- end-to-end pipeline ---

    /** One revolution (0..359°) with a target blob painted around [azCenter]. */
    private fun revolution(azCenter: Int, cell: Int): List<EchoSpoke> =
        (0 until 360).map { az ->
            if (abs(az - azCenter) <= 1) spoke(az.toDouble(), mapOf(cell to 14, cell + 1 to 15, cell + 2 to 14))
            else spoke(az.toDouble())
        }

    @Test
    fun `pipeline grows a tracked target from echo spokes over several revolutions`() {
        val pipe = RadarTrackingPipeline(trackerConfig = TrackerConfig(confirmHits = 3))
        var now = 0L
        var lastSnapshot: List<com.shipradar.contract.TrackedTarget>? = null
        repeat(5) { rev ->
            val spokes = revolution(azCenter = 90, cell = 150)
            for ((i, s) in spokes.withIndex()) {
                // the wrap that completes revolution N happens on the first spoke of revolution N+1
                val out = pipe.onSpoke(s, nowMs = now + i.toLong())
                if (out != null) lastSnapshot = out
            }
            now += 3000L // ~3 s per revolution
        }
        // after ≥3 completed revolutions the target must be confirmed and tracked
        assertTrue(lastSnapshot != null && lastSnapshot!!.isNotEmpty(), "pipeline must produce a track")
        val t = lastSnapshot!!.first()
        assertEquals(TargetStatus.TRACKED, t.status)
        assertTrue(abs(t.bearingDeg - 90.0) < 2.0, "tracked bearing ${t.bearingDeg} ≈ 90")
        assertEquals(com.shipradar.contract.TargetSource.RADAR_TT, t.source)
        // plots were extracted on the last completed scan
        assertTrue(pipe.lastPlots.isNotEmpty(), "lastPlots exposed for diagnostics/acquisition")
    }

    @Test
    fun `empty revolutions produce no tracks`() {
        val pipe = RadarTrackingPipeline()
        var now = 0L
        var snapshots = 0
        repeat(3) {
            val spokes = (0 until 360).map { spoke(it.toDouble()) } // pure noise floor
            for ((i, s) in spokes.withIndex()) {
                val out = pipe.onSpoke(s, now + i.toLong())
                if (out != null) { snapshots++; assertTrue(out.isEmpty(), "no targets from empty echo") }
            }
            now += 3000L
        }
        assertTrue(snapshots >= 2, "scans should still complete each revolution")
    }
}
