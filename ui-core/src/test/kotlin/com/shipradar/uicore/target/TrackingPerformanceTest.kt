package com.shipradar.uicore.target

import com.shipradar.contract.EchoSpoke
import com.shipradar.contract.SampleEncoding
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * Item 2 — throughput guard: one full antenna revolution of plot-extraction + tracking must complete well
 * inside the revolution period, or the pipeline can't keep up with the radar in real time.
 *
 * A full HALO scan here is 2048 spokes × 1024 range cells (~2M samples). At ~24 rpm the revolution period
 * is 2.5 s; we require the pure pipeline (extract + track) to finish in **< 1500 ms** — generous enough to
 * avoid CI flakiness while still failing on any pathological slowdown (real-time headroom on real hardware
 * is far larger since this excludes the GPU render path).
 */
class TrackingPerformanceTest {

    private val SPOKES = 2048
    private val SAMPLES = 1024

    /** Build one realistic revolution: low noise floor + a handful of point targets at fixed bearings. */
    private fun fullScan(): List<EchoSpoke> {
        val targets = intArrayOf(30, 90, 150, 210, 300) // azimuths (deg) with a strong echo
        return (0 until SPOKES).map { i ->
            val azDeg = 360.0 * i / SPOKES
            val s = ByteArray(SAMPLES) { 1 } // uniform low noise
            for (t in targets) {
                if (kotlin.math.abs(azDeg - t) < 1.0) {
                    for (c in 400..403) s[c] = 15 // a compact target blob
                }
            }
            EchoSpoke(
                azimuthDeg = azDeg, headingDeg = 0.0, trueNorth = true,
                rangeCellSizeMm = 2000, rangeCellsDiv2 = SAMPLES / 2,
                samples = s, encoding = SampleEncoding.AMPLITUDE,
                sequenceNumber = i and 0xFFF, bearingZeroError = false,
            )
        }
    }

    @Test
    fun `one full scan extracts and tracks within the revolution budget`() {
        val scan = fullScan()
        val tracker = TrackManager()
        // warm-up (JIT) — not measured.
        repeat(2) { tracker.update(PlotExtractor.extract(scan), 2.5) }

        val start = System.nanoTime()
        val plots = PlotExtractor.extract(scan)
        val tracks = tracker.update(plots, 2.5)
        val elapsedMs = (System.nanoTime() - start) / 1_000_000.0

        assertTrue(plots.isNotEmpty(), "should extract the planted targets from a real-size scan")
        assertTrue(tracks.isNotEmpty(), "tracker should report targets")
        assertTrue(elapsedMs < 1500.0, "full-scan extract+track took ${elapsedMs}ms (budget 1500ms / 2.5s period)")
    }
}
