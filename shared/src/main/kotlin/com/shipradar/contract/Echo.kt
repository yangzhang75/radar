package com.shipradar.contract

/**
 * One parsed radar echo spoke in polar form. Produced by comms (HALO image channel, 236.6.7.8:6678),
 * consumed by the PPI renderer. Raw 0..4095 / 4-bit packing never crosses this boundary.
 *
 * Source of truth: 雷达天线端协议文档-HALO.docx §辐条(Spoke)分配.
 */
data class EchoSpoke(
    /** Azimuth relative to ship's bow, 0..360. = 360 * spokeAzimuth / 4096. */
    val azimuthDeg: Double,
    /** Heading at sample time (spokeCompass). null when compassInvalid. */
    val headingDeg: Double?,
    /** Heading reference: true north (true) vs magnetic (false). Meaningless when headingDeg == null. */
    val trueNorth: Boolean,
    /** Distance per range cell, millimetres (rangeCellSize_mm). */
    val rangeCellSizeMm: Int,
    /** range-cells / 2 (rangeCellsDiv2). */
    val rangeCellsDiv2: Int,
    /** One byte per sample, value 0..15 (0 = no signal, 15 = strongest). Already unpacked from 4-bit. */
    val samples: ByteArray,
    /** AMPLITUDE or DOPPLER. Drives Doppler colouring (T2.2). */
    val encoding: SampleEncoding,
    /** sequenceNumber 0..4095, wraps. */
    val sequenceNumber: Int,
    /** bearingZeroError: antenna 0-position fault. */
    val bearingZeroError: Boolean,
) {
    /** Full range covered by this spoke, metres: (rangeCellSize_mm * 2 * rangeCellsDiv2) / nOfSamples / 1000. */
    val rangeMetersFull: Double get() = (rangeCellSizeMm.toLong() * 2 * rangeCellsDiv2) / 1000.0

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is EchoSpoke) return false
        return azimuthDeg == other.azimuthDeg && headingDeg == other.headingDeg &&
            trueNorth == other.trueNorth && rangeCellSizeMm == other.rangeCellSizeMm &&
            rangeCellsDiv2 == other.rangeCellsDiv2 && samples.contentEquals(other.samples) &&
            encoding == other.encoding && sequenceNumber == other.sequenceNumber &&
            bearingZeroError == other.bearingZeroError
    }

    override fun hashCode(): Int {
        var r = azimuthDeg.hashCode()
        r = 31 * r + (headingDeg?.hashCode() ?: 0)
        r = 31 * r + samples.contentHashCode()
        r = 31 * r + encoding.hashCode()
        r = 31 * r + sequenceNumber
        return r
    }
}

/** sampleEncoding (2 bits). DOPPLER: sample 15 = approaching, 14 = receding. */
enum class SampleEncoding { AMPLITUDE, DOPPLER }
