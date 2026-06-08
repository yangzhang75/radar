package com.shipradar.comms.halo.image

import com.shipradar.contract.SampleEncoding
import com.shipradar.util.Angles
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class SpokeParserTest {

    // ---- 测试夹具：按协议位布局构造辐条字节（小端、位域），用于往返断言与构造畸形包 ----

    private fun packSamples(samples: IntArray): ByteArray {
        val out = ByteArray((samples.size + 1) / 2)
        for (i in samples.indices) {
            val v = samples[i] and 0x0F
            val k = i ushr 1
            out[k] = (out[k].toInt() or if (i and 1 == 0) v else (v shl 4)).toByte()
        }
        return out
    }

    private fun leBytes(word: Long): ByteArray = ByteArray(4) { ((word ushr (it * 8)) and 0xFF).toByte() }

    /** 构造一个完整辐条（24 字节头 + 4-bit 数据）。spokeLength 默认 = 24 + 数据字节数。 */
    private fun buildSpoke(
        samples: IntArray,
        sequenceNumber: Int = 0,
        sampleEncoding: Int = 0,
        nOfSamples: Int = samples.size,
        bitsPerSample: Int = 4,
        rangeCellSizeMm: Int = 0,
        spokeAzimuth: Int = 0,
        bearingZeroError: Int = 0,
        spokeCompass: Int = 0,
        trueNorth: Int = 0,
        compassInvalid: Int = 0,
        rangeCellsDiv2: Int = 0,
        spokeLengthOverride: Int? = null,
    ): ByteArray {
        val data = packSamples(samples)
        val spokeLength = spokeLengthOverride ?: (SpokeParser.HEADER_BYTES + data.size)
        val w0 = spokeLength.toLong() or (sequenceNumber.toLong() shl 16) or (sampleEncoding.toLong() shl 28)
        val w1 = nOfSamples.toLong() or (bitsPerSample.toLong() shl 12) or (rangeCellSizeMm.toLong() shl 16)
        val w2 = spokeAzimuth.toLong() or (bearingZeroError.toLong() shl 14) or
            (spokeCompass.toLong() shl 16) or (trueNorth.toLong() shl 30) or (compassInvalid.toLong() shl 31)
        val w3 = rangeCellsDiv2.toLong()
        return leBytes(w0) + leBytes(w1) + leBytes(w2) + leBytes(w3) +
            leBytes(0) + leBytes(0) + data
    }

    // ---- 文档典型值：全字段断言 ----

    @Test fun parses_doc_typical_spoke_all_fields() {
        // 文档典型：spokeLength=536, nOfSamples=1024, bitsPerSample=4 -> 512 数据字节。
        val samples = IntArray(1024) { it % 16 }
        val pkt = buildSpoke(
            samples = samples,
            sequenceNumber = 1234,
            sampleEncoding = 0,            // Amplitude
            nOfSamples = 1024,
            bitsPerSample = 4,
            rangeCellSizeMm = 50000,
            spokeAzimuth = 1024,           // 90 度
            bearingZeroError = 1,
            spokeCompass = 2048,           // 180 度
            trueNorth = 1,
            compassInvalid = 0,
            rangeCellsDiv2 = 285,
        )
        assertEquals(536, pkt.size, "典型包应为 536 字节")

        val r = SpokeParser.parse(pkt)
        assertEquals(1, r.size)
        val s = r[0]
        assertEquals(Angles.rawAzimuthToDeg(1024), s.azimuthDeg, 1e-9)
        assertEquals(90.0, s.azimuthDeg, 1e-9)
        assertEquals(Angles.rawAzimuthToDeg(2048), s.headingDeg!!, 1e-9)
        assertEquals(180.0, s.headingDeg!!, 1e-9)
        assertTrue(s.trueNorth)
        assertEquals(50000, s.rangeCellSizeMm)
        assertEquals(285, s.rangeCellsDiv2)
        assertEquals(SampleEncoding.AMPLITUDE, s.encoding)
        assertEquals(1234, s.sequenceNumber)
        assertTrue(s.bearingZeroError)
        assertEquals(1024, s.samples.size)
        assertContentEquals(ByteArray(1024) { (it % 16).toByte() }, s.samples)
    }

    // ---- 4-bit 解包：低索引 = 低半字节 ----

    @Test fun unpacks_4bit_low_index_is_low_nibble() {
        // 两个字节 -> 4 个采样。byte0=0x21 -> sample0=1, sample1=2; byte1=0xF8 -> sample2=8, sample3=15
        val samples = intArrayOf(1, 2, 8, 15)
        val pkt = buildSpoke(samples = samples, nOfSamples = 4)
        val s = SpokeParser.parse(pkt).single()
        assertContentEquals(byteArrayOf(1, 2, 8, 15), s.samples)
    }

    // ---- 编码映射 ----

    @Test fun maps_doppler_encoding() {
        val s = SpokeParser.parse(buildSpoke(intArrayOf(15, 14), nOfSamples = 2, sampleEncoding = 1)).single()
        assertEquals(SampleEncoding.DOPPLER, s.encoding)
    }

    @Test fun reserved_encoding_defaults_to_amplitude() {
        val s = SpokeParser.parse(buildSpoke(intArrayOf(0, 0), nOfSamples = 2, sampleEncoding = 2)).single()
        assertEquals(SampleEncoding.AMPLITUDE, s.encoding)
    }

    // ---- 多辐条 ----

    @Test fun parses_multiple_spokes_in_one_packet() {
        val a = buildSpoke(intArrayOf(1, 2), nOfSamples = 2, spokeAzimuth = 0, sequenceNumber = 10)
        val b = buildSpoke(intArrayOf(3, 4, 5, 6), nOfSamples = 4, spokeAzimuth = 2048, sequenceNumber = 11)
        val r = SpokeParser.parse(a + b)
        assertEquals(2, r.size)
        assertEquals(10, r[0].sequenceNumber)
        assertEquals(0.0, r[0].azimuthDeg, 1e-9)
        assertEquals(11, r[1].sequenceNumber)
        assertEquals(180.0, r[1].azimuthDeg, 1e-9)
        assertContentEquals(byteArrayOf(3, 4, 5, 6), r[1].samples)
    }

    // ---- 边界：spokeAzimuth > 4095 -> 钳到 4095 ----

    @Test fun azimuth_over_4095_clamped() {
        // 13-bit 字段可存 5000 (>4095)；协议要求映射到 4095。
        val s = SpokeParser.parse(buildSpoke(intArrayOf(0, 0), nOfSamples = 2, spokeAzimuth = 5000)).single()
        assertEquals(Angles.rawAzimuthToDeg(4095), s.azimuthDeg, 1e-9)
        assertEquals(Angles.rawAzimuthToDeg(5000), s.azimuthDeg, 1e-9) // Angles 内部已钳
    }

    // ---- 边界：compassInvalid -> headingDeg null ----

    @Test fun compass_invalid_yields_null_heading() {
        val s = SpokeParser.parse(
            buildSpoke(intArrayOf(0, 0), nOfSamples = 2, spokeCompass = 2048, compassInvalid = 1, trueNorth = 1)
        ).single()
        assertNull(s.headingDeg)
        // trueNorth 位仍按原样填充（headingDeg 为 null 时无意义，但不丢信息）。
        assertTrue(s.trueNorth)
    }

    // ---- 鲁棒性：截断/畸形包不崩溃 ----

    @Test fun truncated_packet_shorter_than_header_is_dropped() {
        val r = SpokeParser.parseDetailed(ByteArray(10))
        assertTrue(r.spokes.isEmpty())
        assertEquals(0, r.skipped) // 不足一个头：正常包尾，不计为畸形
    }

    @Test fun spoke_length_exceeding_packet_is_dropped_and_counted() {
        // 头部声明 spokeLength=536，但实际只给 100 字节 -> 截断。
        val pkt = buildSpoke(IntArray(1024) { 0 }, nOfSamples = 1024, spokeLengthOverride = 536).copyOf(100)
        val r = SpokeParser.parseDetailed(pkt)
        assertTrue(r.spokes.isEmpty())
        assertEquals(1, r.skipped)
    }

    @Test fun illegal_spoke_length_below_header_is_dropped() {
        val pkt = buildSpoke(intArrayOf(1, 2), nOfSamples = 2, spokeLengthOverride = 8)
        val r = SpokeParser.parseDetailed(pkt)
        assertTrue(r.spokes.isEmpty())
        assertEquals(1, r.skipped)
    }

    @Test fun good_spoke_then_truncated_tail_keeps_good_one() {
        val good = buildSpoke(intArrayOf(7, 7), nOfSamples = 2, sequenceNumber = 5)
        val tail = buildSpoke(IntArray(1024) { 0 }, nOfSamples = 1024, spokeLengthOverride = 536).copyOf(40)
        val r = SpokeParser.parseDetailed(good + tail)
        assertEquals(1, r.spokes.size)
        assertEquals(5, r.spokes[0].sequenceNumber)
        assertEquals(1, r.skipped)
    }

    @Test fun unsupported_bits_per_sample_skips_but_continues() {
        // 第一个辐条 bitsPerSample=8（未规定）应跳过；第二个 4-bit 正常解析。
        val odd = buildSpoke(intArrayOf(0, 0, 0, 0), nOfSamples = 4, bitsPerSample = 8)
        val ok = buildSpoke(intArrayOf(9, 9), nOfSamples = 2, sequenceNumber = 77)
        val r = SpokeParser.parseDetailed(odd + ok)
        assertEquals(1, r.spokes.size)
        assertEquals(77, r.spokes[0].sequenceNumber)
        assertEquals(1, r.skipped)
    }

    @Test fun empty_packet_yields_nothing() {
        assertTrue(SpokeParser.parse(ByteArray(0)).isEmpty())
    }

    // ---- over-scan：解析阶段保留全部采样（含 >569 的过扫描区） ----

    @Test fun overscan_samples_are_all_retained() {
        // over-scan=1.8：0~569 量程内，其余过扫描；解析层全保留，裁剪交给渲染层。
        val n = 1024
        val s = SpokeParser.parse(buildSpoke(IntArray(n) { 1 }, nOfSamples = n)).single()
        assertEquals(n, s.samples.size)
    }
}
