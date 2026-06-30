package com.shipradar.uicore.target

import com.shipradar.contract.EchoSpoke
import com.shipradar.contract.SampleEncoding
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Step-1 plot-extraction tests — synthetic spokes only (no real radar needed). Validates CFAR detection,
 * azimuth+range clustering with 360/0 wrap, centroid range/bearing, and speckle/blob gating.
 */
class PlotExtractorTest {

    // 512 samples, rangeCellSizeMm=2000, rangeCellsDiv2=256 ⇒ rangeMetersFull = 2*N m ⇒ 2 m per cell.
    private val N = 512
    private fun mPerCell() = 2.0

    private fun samples(base: Int = 1, blobs: Map<Int, Int> = emptyMap()): ByteArray {
        val a = ByteArray(N) { base.toByte() }
        for ((i, v) in blobs) a[i] = v.toByte()
        return a
    }

    private fun spoke(azDeg: Double, samples: ByteArray, headingDeg: Double? = 0.0) = EchoSpoke(
        azimuthDeg = azDeg,
        headingDeg = headingDeg,
        trueNorth = true,
        rangeCellSizeMm = 2000,
        rangeCellsDiv2 = N / 2,
        samples = samples,
        encoding = SampleEncoding.AMPLITUDE,
        sequenceNumber = 0,
        bearingZeroError = false,
    )

    /** A compact target: three adjacent spokes, each painting cells 100/101/102 strongly. */
    private fun targetSpokes(azStart: Double, headingDeg: Double? = 0.0): List<EchoSpoke> =
        (0..2).map { k ->
            spoke(azStart + 0.5 * k, samples(blobs = mapOf(100 to 14, 101 to 15, 102 to 14)), headingDeg)
        }

    /**
     * A realistic full 0..359° sweep (one spoke per integer azimuth, in order), with target blobs injected
     * at the given azimuths. Using an ordered sweep means list-index adjacency == azimuth adjacency, the
     * way real spokes arrive — targets at different azimuths never falsely merge.
     */
    private fun sweepWith(blobsByAz: Map<Int, Map<Int, Int>>, base: Int = 1): List<EchoSpoke> =
        (0 until 360).map { az -> spoke(az.toDouble(), samples(base = base, blobs = blobsByAz[az] ?: emptyMap())) }

    @Test
    fun `empty scan yields no plots`() {
        assertTrue(PlotExtractor.extract(emptyList()).isEmpty())
    }

    @Test
    fun `flat low-amplitude noise yields no plots`() {
        val scan = (0 until 360).map { spoke(it.toDouble(), samples(base = 2)) }
        assertTrue(PlotExtractor.extract(scan).isEmpty(), "uniform low noise must not detect")
    }

    @Test
    fun `single compact target is detected as exactly one plot at the right range and bearing`() {
        val scan = targetSpokes(azStart = 30.0)
        val plots = PlotExtractor.extract(scan)
        assertEquals(1, plots.size, "one target → one plot")
        val p = plots.first()
        // centroid cell ≈ 101.5 → range ≈ 203 m ≈ 0.110 NM
        val expectedNm = 101.5 * mPerCell() / 1852.0
        assertTrue(kotlin.math.abs(p.rangeNm - expectedNm) < 0.02, "range ${p.rangeNm} ≈ $expectedNm")
        // azimuths 30.0/30.5/31.0 with heading 0 → bearing ≈ 30.5
        assertTrue(kotlin.math.abs(p.trueBearingDeg - 30.5) < 0.6, "bearing ${p.trueBearingDeg} ≈ 30.5")
        assertEquals(15.0, p.amplitudePeak)
        assertTrue((p.cellCount ?: 0) >= 6, "≈3 spokes × 3 cells")
    }

    @Test
    fun `heading offsets relative azimuth into a true bearing`() {
        val scan = targetSpokes(azStart = 30.0, headingDeg = 100.0)
        val p = PlotExtractor.extract(scan).single()
        // true bearing = azimuth(≈30.5) + heading(100) = 130.5
        assertTrue(kotlin.math.abs(p.trueBearingDeg - 130.5) < 0.6, "bearing ${p.trueBearingDeg} ≈ 130.5")
    }

    @Test
    fun `single-cell speckle is rejected by minCellsPerPlot`() {
        // one isolated hit in one spoke only
        val scan = listOf(spoke(45.0, samples(blobs = mapOf(200 to 15)))) +
            (0 until 50).map { spoke(it.toDouble(), samples(base = 1)) }
        val plots = PlotExtractor.extract(scan, PlotExtractionConfig(minCellsPerPlot = 2))
        assertTrue(plots.none { kotlin.math.abs(it.trueBearingDeg - 45.0) < 1.0 }, "isolated single cell must be rejected")
    }

    @Test
    fun `two separated targets yield two plots`() {
        val blob = mapOf(100 to 14, 101 to 15, 102 to 14)
        val scan = sweepWith(
            mapOf(
                30 to blob, 31 to blob, 32 to blob,
                120 to mapOf(300 to 14, 301 to 15), 121 to mapOf(300 to 14, 301 to 15), 122 to mapOf(300 to 14, 301 to 15),
            ),
        )
        val plots = PlotExtractor.extract(scan)
        assertEquals(2, plots.size)
        assertTrue(plots.any { kotlin.math.abs(it.trueBearingDeg - 31.0) < 1.0 })
        assertTrue(plots.any { kotlin.math.abs(it.trueBearingDeg - 121.0) < 1.0 })
    }

    @Test
    fun `target straddling the 360 to 0 seam merges into one plot`() {
        // spokes at 359.0, 359.5 (end of list) and 0.0, 0.5 (start) — must connect across the wrap.
        val scan = listOf(
            spoke(0.0, samples(blobs = mapOf(101 to 15, 102 to 14))),
            spoke(0.5, samples(blobs = mapOf(101 to 15, 102 to 14))),
        ) + (1 until 359).map { spoke(it.toDouble(), samples(base = 1)) } + listOf(
            spoke(359.0, samples(blobs = mapOf(101 to 15, 102 to 14))),
            spoke(359.5, samples(blobs = mapOf(101 to 15, 102 to 14))),
        )
        val plots = PlotExtractor.extract(scan)
        // all four spokes are one physical target across the seam → exactly one plot near bearing ~0
        val near = plots.filter { it.trueBearingDeg < 1.0 || it.trueBearingDeg > 359.0 }
        assertEquals(1, near.size, "seam-straddling target must merge to one plot, got $plots")
    }

    @Test
    fun `CFAR suppresses a uniformly bright background but keeps a contrasting target`() {
        // whole spoke bright & uniform (e.g. saturated rain) → no contrast → no detection
        val bright = (0 until 90).map { spoke(it.toDouble(), samples(base = 12)) }
        assertTrue(PlotExtractor.extract(bright).isEmpty(), "uniform bright field must be suppressed by CFAR")

        // a target standing out above a moderate background is still detected
        val withTarget = (0..2).map { k ->
            spoke(50.0 + 0.5 * k, samples(base = 4, blobs = mapOf(150 to 15, 151 to 15, 152 to 14)))
        }
        assertEquals(1, PlotExtractor.extract(withTarget).size)
    }

    @Test
    fun `plots are ordered strongest-first with stable ids`() {
        val weak = mapOf(200 to 8, 201 to 9)
        val strong = mapOf(200 to 15, 201 to 15)
        val scan = sweepWith(
            mapOf(10 to weak, 11 to weak, 12 to weak, 80 to strong, 81 to strong, 82 to strong),
        )
        val plots = PlotExtractor.extract(scan)
        assertEquals(2, plots.size)
        assertEquals("P1", plots[0].id)
        assertTrue(plots[0].amplitudePeak!! >= plots[1].amplitudePeak!!, "P1 must be the strongest")
        assertTrue(kotlin.math.abs(plots[0].trueBearingDeg - 81.0) < 1.0, "strongest is the bright target")
    }
}
