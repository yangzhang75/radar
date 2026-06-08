package com.shipradar.halofeed

import com.shipradar.contract.SampleEncoding

/**
 * One HALO spoke header (24 bytes), packed per 雷达天线端协议文档-HALO.docx §辐条(Spoke)分配.
 *
 * The doc declares the header as a run of C bit-fields over six `uint32_t` storage units, and
 * §概观 states "本文数据结构定义为字节对齐和小端法" (byte-aligned, little-endian). We therefore
 * follow the standard little-endian bit-field layout used by GCC/Clang on LE targets: within each
 * 32-bit word, fields are allocated from the least-significant bit in declaration order, and each
 * word is then emitted little-endian (least-significant byte first).
 *
 *   word0:  spokeLength_bytes:12 | _:4 | sequenceNumber:12 | sampleEncoding:2 | _:2
 *   word1:  nOfSamples:12 | bitsPerSample:4 | rangeCellSize_mm:16
 *   word2:  spokeAzimuth:13 | _:1 | bearingZeroError:1 | _:1 | spokeCompass:14 | trueNorth:1 | compassInvalid:1
 *   word3:  rangeCellsDiv2:16 | _:16
 *   word4:  _:32   (reserved: two 16-bit fields)
 *   word5:  _:32   (reserved: two 16-bit fields)
 *
 * All multi-field words above sum to exactly 32 bits, so there is no implementation-defined
 * straddling — the layout is unambiguous regardless of compiler. fromBytes/toBytes are exact
 * inverses (see SpokeHeaderTest), which is what T1.2's parser must agree with.
 */
data class SpokeHeader(
    /** Total spoke length in bytes (header + data). Typically 536 = 24 + 1024×4-bit. 12-bit field. */
    val spokeLengthBytes: Int,
    /** sequenceNumber 0..4095, increments and wraps. 12-bit field. */
    val sequenceNumber: Int,
    /** Sample encoding scheme. 2-bit field (see [encodingCode]). */
    val sampleEncoding: SampleEncoding,
    /** Number of samples in the spoke. Typically 1024. 12-bit field. */
    val nOfSamples: Int,
    /** Bits used to store one sample. Typically 4 (16 colours). 4-bit field. */
    val bitsPerSample: Int,
    /** Distance per range cell, millimetres, 0..65535. 16-bit field. */
    val rangeCellSizeMm: Int,
    /** Azimuth relative to bow, 0..4095 = 0..360°. 13-bit field. */
    val spokeAzimuth: Int,
    /** bearingZeroError: antenna 0-position fault. 1-bit field. */
    val bearingZeroError: Boolean,
    /** Heading sensor value at sample time, 0..4095 = 0..360°. 14-bit field. */
    val spokeCompass: Int,
    /** Heading reference: true north (true) vs magnetic (false). 1-bit field. */
    val trueNorth: Boolean,
    /** compassInvalid: no heading sensor connected (spokeCompass/trueNorth meaningless). 1-bit field. */
    val compassInvalid: Boolean,
    /** range-cells / 2 in this spoke. 16-bit field. */
    val rangeCellsDiv2: Int,
) {
    init {
        requireField("spokeLengthBytes", spokeLengthBytes, 12)
        requireField("sequenceNumber", sequenceNumber, 12)
        requireField("nOfSamples", nOfSamples, 12)
        requireField("bitsPerSample", bitsPerSample, 4)
        requireField("rangeCellSizeMm", rangeCellSizeMm, 16)
        requireField("spokeAzimuth", spokeAzimuth, 13)
        requireField("spokeCompass", spokeCompass, 14)
        requireField("rangeCellsDiv2", rangeCellsDiv2, 16)
    }

    /** Serialize to exactly [HEADER_BYTES] bytes. */
    fun toBytes(): ByteArray {
        val words = LongArray(6)
        words[0] = field(spokeLengthBytes, 0, 12) or
            field(sequenceNumber, 16, 12) or
            field(encodingCode(sampleEncoding), 28, 2)
        words[1] = field(nOfSamples, 0, 12) or
            field(bitsPerSample, 12, 4) or
            field(rangeCellSizeMm, 16, 16)
        words[2] = field(spokeAzimuth, 0, 13) or
            field(if (bearingZeroError) 1 else 0, 14, 1) or
            field(spokeCompass, 16, 14) or
            field(if (trueNorth) 1 else 0, 30, 1) or
            field(if (compassInvalid) 1 else 0, 31, 1)
        words[3] = field(rangeCellsDiv2, 0, 16)
        // words[4], words[5] reserved = 0

        val out = ByteArray(HEADER_BYTES)
        for (w in 0 until 6) putUInt32Le(out, w * 4, words[w])
        return out
    }

    companion object {
        const val HEADER_BYTES = 24

        /**
         * sampleEncoding 2-bit wire value.
         * TODO(待张建): the doc names SmpEncode_Amplitude / SmpEncode_Doppler but gives no numeric
         * enum values. We assume AMPLITUDE=0 (the natural "plain amplitude" default) and DOPPLER=1.
         * Confirm against the radar/SDK before trusting Doppler-mode colouring on real hardware.
         */
        fun encodingCode(e: SampleEncoding): Int = when (e) {
            SampleEncoding.AMPLITUDE -> 0
            SampleEncoding.DOPPLER -> 1
        }

        fun encodingFromCode(code: Int): SampleEncoding = when (code) {
            0 -> SampleEncoding.AMPLITUDE
            1 -> SampleEncoding.DOPPLER
            else -> SampleEncoding.AMPLITUDE // reserved codes 2,3 unspecified -> treat as amplitude
        }

        /** Parse a header from [HEADER_BYTES] bytes starting at [offset]. Inverse of [toBytes]. */
        fun fromBytes(b: ByteArray, offset: Int = 0): SpokeHeader {
            require(b.size - offset >= HEADER_BYTES) { "need $HEADER_BYTES bytes, have ${b.size - offset}" }
            val w0 = getUInt32Le(b, offset)
            val w1 = getUInt32Le(b, offset + 4)
            val w2 = getUInt32Le(b, offset + 8)
            val w3 = getUInt32Le(b, offset + 12)
            return SpokeHeader(
                spokeLengthBytes = extract(w0, 0, 12),
                sequenceNumber = extract(w0, 16, 12),
                sampleEncoding = encodingFromCode(extract(w0, 28, 2)),
                nOfSamples = extract(w1, 0, 12),
                bitsPerSample = extract(w1, 12, 4),
                rangeCellSizeMm = extract(w1, 16, 16),
                spokeAzimuth = extract(w2, 0, 13),
                bearingZeroError = extract(w2, 14, 1) != 0,
                spokeCompass = extract(w2, 16, 14),
                trueNorth = extract(w2, 30, 1) != 0,
                compassInvalid = extract(w2, 31, 1) != 0,
                rangeCellsDiv2 = extract(w3, 0, 16),
            )
        }

        private fun requireField(name: String, value: Int, bits: Int) {
            val max = (1 shl bits) - 1
            require(value in 0..max) { "$name=$value out of range for $bits-bit field (0..$max)" }
        }

        /** Place [value] into bits [pos, pos+bits) of a 32-bit word. */
        private fun field(value: Int, pos: Int, bits: Int): Long {
            val mask = (1L shl bits) - 1
            return (value.toLong() and mask) shl pos
        }

        private fun extract(word: Long, pos: Int, bits: Int): Int {
            val mask = (1L shl bits) - 1
            return ((word ushr pos) and mask).toInt()
        }

        private fun putUInt32Le(out: ByteArray, off: Int, w: Long) {
            out[off] = (w and 0xFF).toByte()
            out[off + 1] = ((w ushr 8) and 0xFF).toByte()
            out[off + 2] = ((w ushr 16) and 0xFF).toByte()
            out[off + 3] = ((w ushr 24) and 0xFF).toByte()
        }

        private fun getUInt32Le(b: ByteArray, off: Int): Long =
            (b[off].toLong() and 0xFF) or
                ((b[off + 1].toLong() and 0xFF) shl 8) or
                ((b[off + 2].toLong() and 0xFF) shl 16) or
                ((b[off + 3].toLong() and 0xFF) shl 24)
    }
}
