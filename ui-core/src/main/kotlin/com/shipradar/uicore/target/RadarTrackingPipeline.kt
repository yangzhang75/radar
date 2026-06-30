package com.shipradar.uicore.target

import com.shipradar.contract.EchoSpoke
import com.shipradar.contract.OwnShipData
import com.shipradar.contract.TrackedTarget

/**
 * Accumulates incoming [EchoSpoke]s into full antenna revolutions. A scan is considered complete when the
 * azimuth **wraps** (jumps backward past the 360°/0° seam) — radar spokes arrive in increasing azimuth and
 * roll over once per revolution. Buffers below [minSpokesPerScan] are not emitted (guards against a wrap
 * detected mid-warm-up or a noisy first spoke).
 */
class ScanAggregator(private val minSpokesPerScan: Int = 8) {
    private val buffer = ArrayList<EchoSpoke>()
    private var lastAz = Double.NaN

    /** Feed one spoke; returns the just-completed scan when this spoke begins a new revolution, else null. */
    fun onSpoke(spoke: EchoSpoke): List<EchoSpoke>? {
        val az = spoke.azimuthDeg
        var completed: List<EchoSpoke>? = null
        // a backward jump of more than 180° means we crossed the 360→0 seam → previous buffer is one scan.
        if (!lastAz.isNaN() && az + 180.0 < lastAz && buffer.size >= minSpokesPerScan) {
            completed = ArrayList(buffer)
            buffer.clear()
        }
        buffer.add(spoke)
        lastAz = az
        return completed
    }

    fun reset() {
        buffer.clear()
        lastAz = Double.NaN
    }
}

/**
 * Step 3 façade — the end-to-end **radar-video → tracks** pipeline driver.
 *
 * Wires [ScanAggregator] → [PlotExtractor] → [TrackManager] so a caller (the comms engine) can simply push
 * spokes as they arrive and receive an updated [TrackedTarget] snapshot once per completed revolution. This
 * is what makes radar targets *grow from the echo image* in the live/sim data path, rather than arriving
 * pre-tracked over HALO target packets or TTM sentences.
 *
 * Stateful but pure (no I/O); deterministic for a given (spoke, time) stream — testable without a radar.
 */
class RadarTrackingPipeline(
    private val extractorConfig: PlotExtractionConfig = PlotExtractionConfig(),
    trackerConfig: TrackerConfig = TrackerConfig(),
    /** Fallback revolution period used for the first scan (s), before two scan times are known. */
    private val nominalScanSeconds: Double = 2.5,
) {
    private val aggregator = ScanAggregator()
    private val tracker = TrackManager(trackerConfig)
    private var lastScanTimeMs: Long = -1L

    /** Last extracted plots (for diagnostics / auto-acquisition feed); replaced each completed scan. */
    var lastPlots: List<RadarPlot> = emptyList()
        private set

    /**
     * Push one spoke. Returns the updated track snapshot when [spoke] completes a revolution, else null.
     * [nowMs] is the spoke arrival time; [ownShip] (optional) yields true-motion course/speed.
     */
    fun onSpoke(spoke: EchoSpoke, nowMs: Long, ownShip: OwnShipData? = null): List<TrackedTarget>? {
        val scan = aggregator.onSpoke(spoke) ?: return null
        val dt = if (lastScanTimeMs < 0) nominalScanSeconds else (nowMs - lastScanTimeMs) / 1000.0
        lastScanTimeMs = nowMs
        val plots = PlotExtractor.extract(scan, extractorConfig)
        lastPlots = plots
        // guard against a non-positive dt (clock jitter / same-ms): fall back to nominal period.
        return tracker.update(plots, if (dt > 0.0) dt else nominalScanSeconds, ownShip)
    }

    fun reset() {
        aggregator.reset()
        tracker.reset()
        lastScanTimeMs = -1L
        lastPlots = emptyList()
    }
}
