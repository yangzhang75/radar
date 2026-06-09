package com.shipradar.app.ppi

import com.shipradar.contract.EchoSpoke
import com.shipradar.contract.SampleEncoding
import com.shipradar.uicore.ppi.RangeModel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.delay
import kotlin.math.abs
import kotlin.math.roundToInt

/**
 * Synthetic [EchoSpoke] source for previewing [PpiView] without a radar or the comms stack.
 *
 * Deterministic (no RNG) so previews/screenshots are reproducible. The real picture comes from the
 * HALO image channel via T1.2; for an end-to-end fake feed the project also has the `tools:halofeed`
 * multicast generator (T0.3) — this in-process source is just for the IDE preview and the demo
 * Activity.
 *
 * Scene: a "coastline" arc, two point targets, and one Doppler-tagged moving target, on a low-level
 * noise floor — enough to exercise colour ramp, range rings, bearing scale and heading line.
 */
object FakeSpokes {

    const val SPOKES_PER_REV = 2048
    const val SAMPLES_PER_SPOKE = 1024

    /** One full revolution of spokes (snapshot for `@Preview` / [PpiView.renderSnapshot]). */
    fun oneRevolution(): List<EchoSpoke> =
        (0 until SPOKES_PER_REV).map { makeSpoke(it, sequenceNumber = it) }

    /**
     * A continuously rotating sweep, paced to [rpm] (one spoke every scanPeriod/SPOKES). Cancellable
     * via the collecting coroutine; never completes on its own.
     */
    fun continuousSweep(rpm: Double = 24.0): Flow<EchoSpoke> = flow {
        val perSpokeMs = (RangeModel.scanPeriodMs(rpm) / SPOKES_PER_REV).roundToInt().coerceAtLeast(1).toLong()
        var seq = 0
        var spoke = 0
        while (true) {
            emit(makeSpoke(spoke, sequenceNumber = seq))
            spoke = (spoke + 1) % SPOKES_PER_REV
            seq = (seq + 1) and 0x0FFF // sequenceNumber wraps 0..4095
            delay(perSpokeMs)
        }
    }

    private fun makeSpoke(spokeIndex: Int, sequenceNumber: Int): EchoSpoke {
        val azimuthDeg = 360.0 * spokeIndex / SPOKES_PER_REV
        val n = SAMPLES_PER_SPOKE
        val samples = ByteArray(n)

        // sample index for a given fraction of the selected range (inverse of sampleIndexToRangeFraction)
        fun idxAtFraction(f: Double): Int = (f * n / RangeModel.OVER_SCAN).roundToInt().coerceIn(0, n - 1)

        // low noise floor
        for (i in 0 until n) samples[i] = ((i * 7 + spokeIndex) % 3).toByte() // 0..2

        // coastline arc: azimuth 30°..85°, range ~0.65..0.72 of scale, strong
        if (azimuthDeg in 30.0..85.0) {
            for (i in idxAtFraction(0.65)..idxAtFraction(0.72)) samples[i] = 14
        }
        // point target A at bearing ~135°, range ~0.40
        if (abs(azimuthDeg - 135.0) < 1.5) {
            for (i in idxAtFraction(0.39)..idxAtFraction(0.41)) samples[i] = 15
        }
        // point target B at bearing ~300°, range ~0.85
        if (abs(azimuthDeg - 300.0) < 1.0) {
            for (i in idxAtFraction(0.84)..idxAtFraction(0.86)) samples[i] = 12
        }

        // Doppler approaching target near bearing 210°, range ~0.55 (sample 15 + DOPPLER encoding)
        val doppler = abs(azimuthDeg - 210.0) < 1.0
        if (doppler) {
            for (i in idxAtFraction(0.54)..idxAtFraction(0.56)) samples[i] = 15 // approaching
        }

        return EchoSpoke(
            azimuthDeg = azimuthDeg,
            headingDeg = 0.0,
            trueNorth = true,
            rangeCellSizeMm = 1500,   // illustrative; renderer uses samples.size, not this
            rangeCellsDiv2 = n,
            samples = samples,
            encoding = if (doppler) SampleEncoding.DOPPLER else SampleEncoding.AMPLITUDE,
            sequenceNumber = sequenceNumber,
            bearingZeroError = false,
        )
    }
}
