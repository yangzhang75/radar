package com.shipradar.halofeed

import com.shipradar.contract.SampleEncoding
import com.shipradar.util.Angles

/**
 * 4-bit sample packing per 雷达天线端协议文档-HALO.docx §辐条(Spoke)分配:
 * "采样数据，4-bit格式，以低索引位存储最低有效位...字节填充。采样值为0相当于无信号，
 *  采样值为15相当于...最强目标信号。"
 *
 * So each byte holds two samples: the lower-indexed sample in the low nibble (bits 0..3),
 * the higher-indexed sample in the high nibble (bits 4..7).
 */
object Samples {
    /** Pack an array of 0..15 sample levels into ceil(n/2) bytes (low index = low nibble). */
    fun packNibbles(levels: ByteArray): ByteArray {
        val out = ByteArray((levels.size + 1) / 2)
        for (i in levels.indices) {
            val v = levels[i].toInt() and 0x0F
            val byteIdx = i / 2
            out[byteIdx] = if (i % 2 == 0) {
                ((out[byteIdx].toInt() and 0xF0) or v).toByte()
            } else {
                ((out[byteIdx].toInt() and 0x0F) or (v shl 4)).toByte()
            }
        }
        return out
    }

    /** Inverse of [packNibbles]; returns [nOfSamples] levels (0..15). Used by self-test (not the T1.2 parser). */
    fun unpackNibbles(data: ByteArray, nOfSamples: Int): ByteArray {
        val out = ByteArray(nOfSamples)
        for (i in 0 until nOfSamples) {
            val byte = data[i / 2].toInt() and 0xFF
            out[i] = (if (i % 2 == 0) byte and 0x0F else (byte ushr 4) and 0x0F).toByte()
        }
        return out
    }
}

/**
 * Generates recognizable test imagery so a PPI render can be eyeballed. Per azimuth it returns
 * an array of [nOfSamples] sample levels (0..15), combining:
 *   - concentric range rings (every [ringSpacingSamples] cells, low level) — radial scale check;
 *   - a bright heading marker along azimuth 0 — confirms bow/north alignment & scan start;
 *   - fixed-bearing target blocks — stationary blobs at known bearing/range for geometry checks.
 *
 * In DOPPLER mode the target blocks alternate approaching (15) / receding (14) so T2.2 colouring
 * has something to paint; in AMPLITUDE mode they are full-strength (15).
 */
class Scene(
    private val nOfSamples: Int = 1024,
    private val ringLevel: Int = 5,
    private val ringSpacingSamples: Int = 128,
    private val headingMarkerLevel: Int = 12,
) {
    /** A stationary target: a block at [bearingDeg] ± [halfWidthDeg], spanning sample [near, far]. */
    data class Target(val bearingDeg: Double, val halfWidthDeg: Double, val near: Int, val far: Int)

    private val targets = listOf(
        Target(bearingDeg = 45.0, halfWidthDeg = 3.0, near = 180, far = 230),
        Target(bearingDeg = 135.0, halfWidthDeg = 6.0, near = 360, far = 400),
        Target(bearingDeg = 270.0, halfWidthDeg = 2.0, near = 520, far = 560),
    )

    /** Build the sample levels for one spoke at raw [spokeAzimuth] (0..4095). */
    fun samplesFor(spokeAzimuth: Int, encoding: SampleEncoding): ByteArray {
        val levels = ByteArray(nOfSamples)

        // Concentric range rings (skip ring at cell 0).
        if (ringSpacingSamples > 0) {
            var s = ringSpacingSamples
            while (s < nOfSamples) {
                levels[s] = ringLevel.toByte()
                s += ringSpacingSamples
            }
        }

        // Heading marker: a bright radial line on the first few spokes of each scan.
        if (spokeAzimuth < 8) {
            for (s in 0 until nOfSamples) levels[s] = maxOf(levels[s].toInt(), headingMarkerLevel).toByte()
        }

        // Fixed-bearing target blocks.
        val azDeg = Angles.rawAzimuthToDeg(spokeAzimuth)
        targets.forEachIndexed { idx, t ->
            if (angularDistanceDeg(azDeg, t.bearingDeg) <= t.halfWidthDeg) {
                val level = when (encoding) {
                    SampleEncoding.AMPLITUDE -> 15
                    // Doppler: alternate approaching(15)/receding(14) per target.
                    SampleEncoding.DOPPLER -> if (idx % 2 == 0) 15 else 14
                }
                for (s in t.near..minOf(t.far, nOfSamples - 1)) levels[s] = level.toByte()
            }
        }
        return levels
    }

    private fun angularDistanceDeg(a: Double, b: Double): Double {
        val d = Math.abs(a - b) % 360.0
        return if (d > 180.0) 360.0 - d else d
    }
}
