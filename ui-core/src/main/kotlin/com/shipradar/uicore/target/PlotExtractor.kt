package com.shipradar.uicore.target

import com.shipradar.contract.EchoSpoke
import kotlin.math.roundToInt

/**
 * Step 1 of the radar tracking pipeline — **plot extraction** (radar video → plots).
 *
 * Turns one antenna revolution of raw echo spokes ([EchoSpoke], amplitude 0..15 per range cell) into a
 * list of [RadarPlot] candidates that [AutoAcquisition] and the tracker (step 2) consume. This is the
 * function that makes targets *grow out of the radar image* instead of arriving pre-tracked over AIS/TTM.
 *
 * Pipeline (all pure, unit-testable on synthetic spokes):
 *  1. **Detection (CFAR).** Per spoke, a 1-D cell-averaging CFAR runs along range: a cell is a *hit* when
 *     its amplitude exceeds `cfarFactor ×` the mean of nearby training cells (guard cells excluded), and
 *     also clears an absolute floor [PlotExtractionConfig.minAmplitude]. CFAR adapts the threshold to local
 *     clutter/noise so detection holds across sea/rain background — the standard radar approach (the IMO
 *     A.823 / IEC 62388 ARPA spec mandates acquisition in clutter but leaves the detector to the maker).
 *  2. **Clustering.** Hits adjacent in azimuth (neighbouring spokes, with wrap across the 360°/0° seam)
 *     **and** range (within a few cells) are merged with union-find into one cluster — a single physical
 *     target paints several adjacent cells.
 *  3. **Centroid + gating.** Each cluster collapses to an amplitude-weighted centroid (range, bearing).
 *     Clusters smaller than [PlotExtractionConfig.minCellsPerPlot] are rejected as speckle; clusters larger
 *     than [PlotExtractionConfig.maxCellsPerPlot] are rejected as land/rain masses (not point targets).
 *
 * Bearings are returned **true** (azimuth-relative-to-bow + heading) when the scan carries a heading,
 * else relative-to-bow; range is nautical miles.
 */
data class PlotExtractionConfig(
    /** Absolute amplitude floor (0..15); a cell below this is never a hit regardless of CFAR. */
    val minAmplitude: Int = 6,
    /** Guard cells either side of the cell-under-test, excluded from the noise estimate. */
    val cfarGuardCells: Int = 2,
    /** Training cells either side (beyond the guard band) averaged for the local noise estimate. */
    val cfarTrainingCells: Int = 8,
    /** Detection threshold multiplier applied to the local training-cell mean. */
    val cfarFactor: Double = 1.6,
    /** Reject clusters with fewer cells than this (speckle / single-cell noise). */
    val minCellsPerPlot: Int = 2,
    /** Reject clusters larger than this (coastline / heavy rain — not a discrete target). */
    val maxCellsPerPlot: Int = 5000,
    /** Azimuth adjacency: spokes within this many index steps are considered neighbours. */
    val mergeAzimuthGapSpokes: Int = 1,
    /** Range adjacency: hits within this many range cells are considered neighbours. */
    val mergeRangeGapCells: Int = 1,
) {
    init {
        require(minAmplitude in 0..15) { "minAmplitude must be 0..15" }
        require(cfarGuardCells >= 0 && cfarTrainingCells >= 1) { "invalid CFAR cell counts" }
        require(cfarFactor > 0.0) { "cfarFactor must be > 0" }
        require(maxCellsPerPlot >= minCellsPerPlot) { "maxCellsPerPlot must be >= minCellsPerPlot" }
    }
}

object PlotExtractor {

    private const val METERS_PER_NM = 1852.0

    /** One above-threshold cell, kept until clustered. */
    private data class Hit(val spokeIdx: Int, val cell: Int, val amp: Int, val rangeM: Double, val bearingTrue: Double)

    /**
     * Extract plots from one scan (a list of spokes, ideally one full antenna revolution in azimuth order).
     * Returns plots ordered strongest-first (descending peak amplitude), ids `P1, P2, …`.
     */
    fun extract(scan: List<EchoSpoke>, config: PlotExtractionConfig = PlotExtractionConfig()): List<RadarPlot> {
        if (scan.isEmpty()) return emptyList()

        // 1) per-spoke CFAR detection → flat hit list, with a per-spoke offset index for union-find.
        val hits = ArrayList<Hit>()
        val spokeHitRanges = ArrayList<IntArray>(scan.size) // cell indices that hit, per spoke (for adjacency)
        for ((si, spoke) in scan.withIndex()) {
            val cells = detect(spoke.samples, config)
            spokeHitRanges.add(cells)
            if (cells.isEmpty()) continue
            val n = spoke.samples.size
            val mPerCell = if (n > 0) spoke.rangeMetersFull / n else 0.0
            val bearingTrue = spoke.azimuthDeg + (spoke.headingDeg ?: 0.0)
            for (c in cells) {
                hits.add(
                    Hit(
                        spokeIdx = si,
                        cell = c,
                        amp = spoke.samples[c].toInt() and 0xFF,
                        rangeM = (c + 0.5) * mPerCell,
                        bearingTrue = bearingTrue,
                    ),
                )
            }
        }
        if (hits.isEmpty()) return emptyList()

        // 2) union-find over hits: connect hits that are adjacent in azimuth AND range.
        //    Index hits by (spokeIdx -> list of hit indices) so we only compare neighbouring spokes.
        val parent = IntArray(hits.size) { it }
        fun find(x: Int): Int { var r = x; while (parent[r] != r) { parent[r] = parent[parent[r]]; r = parent[r] }; return r }
        fun union(a: Int, b: Int) { val ra = find(a); val rb = find(b); if (ra != rb) parent[ra] = rb }

        val bySpoke = HashMap<Int, MutableList<Int>>()
        for (i in hits.indices) bySpoke.getOrPut(hits[i].spokeIdx) { ArrayList() }.add(i)

        val lastSpoke = scan.size - 1
        for (i in hits.indices) {
            val h = hits[i]
            // candidate neighbour spokes: same spoke and the next `mergeAzimuthGapSpokes`, plus wrap seam.
            for (d in 0..config.mergeAzimuthGapSpokes) {
                val neighbourSpokes = ArrayList<Int>(2)
                neighbourSpokes.add(h.spokeIdx + d)
                // wrap: the last spoke is azimuth-adjacent to the first (full circle).
                if (h.spokeIdx + d > lastSpoke) neighbourSpokes.add((h.spokeIdx + d) - scan.size)
                for (ns in neighbourSpokes) {
                    val list = bySpoke[ns] ?: continue
                    for (j in list) {
                        if (j <= i && d == 0) continue // avoid double-compare within same spoke
                        if (kotlin.math.abs(hits[j].cell - h.cell) <= config.mergeRangeGapCells) union(i, j)
                    }
                }
            }
        }

        // 3) collapse clusters → amplitude-weighted centroid; gate by cell count.
        val clusters = HashMap<Int, MutableList<Hit>>()
        for (i in hits.indices) clusters.getOrPut(find(i)) { ArrayList() }.add(hits[i])

        val plots = ArrayList<Pair<Double, RadarPlot>>() // (peakAmp, plot) for ordering
        for ((_, cells) in clusters) {
            if (cells.size < config.minCellsPerPlot || cells.size > config.maxCellsPerPlot) continue
            var wSum = 0.0
            var rSum = 0.0
            var bSinSum = 0.0
            var bCosSum = 0.0
            var peak = 0
            for (h in cells) {
                val w = h.amp.toDouble()
                wSum += w
                rSum += w * h.rangeM
                // average bearings as unit vectors to handle the 360/0 wrap correctly.
                val rad = Math.toRadians(h.bearingTrue)
                bSinSum += w * kotlin.math.sin(rad)
                bCosSum += w * kotlin.math.cos(rad)
                if (h.amp > peak) peak = h.amp
            }
            if (wSum <= 0.0) continue
            val rangeM = rSum / wSum
            val bearing = Geometry.normalizeDeg(Math.toDegrees(kotlin.math.atan2(bSinSum, bCosSum)))
            plots.add(
                peak.toDouble() to RadarPlot(
                    id = "",
                    rangeNm = rangeM / METERS_PER_NM,
                    trueBearingDeg = bearing,
                    amplitudePeak = peak.toDouble(),
                    cellCount = cells.size,
                ),
            )
        }

        // strongest first, then assign stable ids P1..Pn.
        plots.sortWith(compareByDescending<Pair<Double, RadarPlot>> { it.first }.thenBy { it.second.rangeNm })
        return plots.mapIndexed { idx, (_, p) -> p.copy(id = "P${idx + 1}") }
    }

    /**
     * 1-D cell-averaging CFAR along one spoke's range samples. Returns the indices of detected cells.
     * For each cell-under-test the noise estimate is the mean of the training cells on both sides
     * (skipping the guard band); the cell is a hit when it clears both `cfarFactor × noiseMean` and the
     * absolute floor. Cells near the ends use whatever training cells are available.
     */
    internal fun detect(samples: ByteArray, config: PlotExtractionConfig): IntArray {
        val n = samples.size
        if (n == 0) return IntArray(0)
        val out = ArrayList<Int>()
        val guard = config.cfarGuardCells
        val train = config.cfarTrainingCells
        for (i in 0 until n) {
            val cut = samples[i].toInt() and 0xFF
            if (cut < config.minAmplitude) continue
            var sum = 0.0
            var cnt = 0
            // left training band
            var k = i - guard - 1
            var taken = 0
            while (k >= 0 && taken < train) { sum += samples[k].toInt() and 0xFF; cnt++; taken++; k-- }
            // right training band
            k = i + guard + 1
            taken = 0
            while (k < n && taken < train) { sum += samples[k].toInt() and 0xFF; cnt++; taken++; k++ }
            val noiseMean = if (cnt > 0) sum / cnt else 0.0
            val threshold = config.cfarFactor * noiseMean
            if (cut.toDouble() >= threshold) out.add(i)
        }
        return out.toIntArray()
    }
}
