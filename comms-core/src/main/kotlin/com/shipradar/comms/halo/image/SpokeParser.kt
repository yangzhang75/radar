package com.shipradar.comms.halo.image

import com.shipradar.contract.EchoSpoke
import com.shipradar.contract.SampleEncoding
import com.shipradar.util.Angles

/**
 * T1.2 / 合规追溯 HALO-01 — HALO 雷达图像 (Spoke) 解析器.
 *
 * 纯函数式：输入一个 UDP 包的原始字节（可能含多个辐条），输出 [EchoSpoke] 列表。无 socket、无 Android。
 * 上游：通讯模块从组播 236.6.7.8:6678 ([com.shipradar.constants.HaloEndpoints.IMAGE]) 收到的负载。
 * 下游：PPI 渲染。
 *
 * 协议来源（已交叉核对，二者一致）：
 *  - 雷达天线端协议文档-HALO.docx §辐条(Spoke)分配
 *  - Navico SDK 4.0.16  NRPClient/NavRadarSpoke.h  (struct t9174SpokeHeader / enum eSampleEncoding)
 *
 * 辐条头 24 字节、小端、`uint32_t` 位域。位域在每个 32-bit 小端字内 **从最低有效位开始** 排布
 * （C/Navico 在小端平台的标准行为）。字段顺序/位宽：
 * ```
 *  word0: spokeLength_bytes:12 | 预留:4 | sequenceNumber:12 | sampleEncoding:2 | 预留:2
 *  word1: nOfSamples:12 | bitsPerSample:4 | rangeCellSize_mm:16
 *  word2: spokeAzimuth:13 | 预留:1 | bearingZeroError:1 | 预留:1 | spokeCompass:14 | trueNorth:1 | compassInvalid:1
 *  word3: rangeCellsDiv2:16 | 预留:16
 *  word4: 预留:16 | 预留:16
 *  word5: 预留:16 | 预留:16
 * ```
 * 数据区紧随头部，长度 `spokeLength_bytes - 24` 字节。4-bit 采样：每字节装 2 个采样，
 * **低索引存最低有效半字节**（doc：“以低索引位存储最低有效位…”），即 sample[2k]=低 nibble、sample[2k+1]=高 nibble。
 * 解成 0..15 的 byte 放入 [EchoSpoke.samples]。
 *
 * over-scan=1.8：采样 0~569 在量程内，其余为过扫描数据。**解析阶段全部保留**，
 * 是否裁剪交给渲染层（量程/前向搜索模式相关），本类不做取舍。
 *
 * 鲁棒性（VPN 下会丢包/截断，绝不抛崩溃）：包不足/字段越界 → 跳过并计数。详见 [parse]。
 */
object SpokeParser {

    /** 辐条头固定长度（字节）。 */
    const val HEADER_BYTES = 24

    /** 文档/SDK 仅定义 4-bit 采样的字节装填规则；其它位宽未规定，遇到则跳过（见 [Result.skipped]）。 */
    private const val SUPPORTED_BITS_PER_SAMPLE = 4

    /**
     * 解析结果：成功解出的辐条 + 被丢弃的辐条数（截断/长度非法/位宽不支持）。
     * 计数用于上层统计丢包率（合规：链路质量监测）。
     */
    data class Result(val spokes: List<EchoSpoke>, val skipped: Int)

    /** 主入口：返回解析出的辐条列表（丢弃静默忽略）。需要丢弃计数时用 [parseDetailed]。 */
    fun parse(packet: ByteArray): List<EchoSpoke> = parseDetailed(packet).spokes

    /**
     * 解析一个 UDP 包中的全部辐条。
     *
     * 容错策略：
     *  - 剩余字节不足以容纳一个 24 字节头 → 结束（正常的包尾）。
     *  - spokeLength_bytes < 24 或 超出包剩余长度 → 长度字段不可信，无法确定下一辐条起点，
     *    **停止解析该包余下部分** 并 skipped+1（截断包的典型表现）。
     *  - bitsPerSample 非 4 → 该辐条跳过、skipped+1，但 spokeLength 可信，故继续扫描后续辐条。
     */
    fun parseDetailed(packet: ByteArray): Result {
        val spokes = ArrayList<EchoSpoke>()
        var skipped = 0
        var off = 0
        val n = packet.size

        while (off + HEADER_BYTES <= n) {
            val w0 = u32le(packet, off)
            val w1 = u32le(packet, off + 4)
            val w2 = u32le(packet, off + 8)
            val w3 = u32le(packet, off + 12)

            val spokeLength = bits(w0, 0, 12)
            if (spokeLength < HEADER_BYTES || off + spokeLength > n) {
                // 长度非法或越界：无法信任以定位下一辐条，放弃本包剩余部分。
                skipped++
                break
            }

            val sequenceNumber = bits(w0, 16, 12)
            val sampleEncodingRaw = bits(w0, 28, 2)

            val nOfSamples = bits(w1, 0, 12)
            val bitsPerSample = bits(w1, 12, 4)
            val rangeCellSizeMm = bits(w1, 16, 16)

            val spokeAzimuth = bits(w2, 0, 13)
            val bearingZeroError = bits(w2, 14, 1) == 1
            val spokeCompass = bits(w2, 16, 14)
            val trueNorth = bits(w2, 30, 1) == 1
            val compassInvalid = bits(w2, 31, 1) == 1

            val rangeCellsDiv2 = bits(w3, 0, 16)

            if (bitsPerSample != SUPPORTED_BITS_PER_SAMPLE) {
                // 位宽未规定，无法可靠解包；长度可信故跳到下一辐条继续。
                skipped++
                off += spokeLength
                continue
            }

            val dataBytes = spokeLength - HEADER_BYTES
            val samples = unpack4bit(packet, off + HEADER_BYTES, dataBytes, nOfSamples)

            spokes += EchoSpoke(
                // spokeAzimuth 0..4095 -> 0..360；>4095 由 Angles 钳到 4095（doc 要求）。
                azimuthDeg = Angles.rawAzimuthToDeg(spokeAzimuth),
                // compassInvalid==1 表示无船首传感器，spokeCompass/trueNorth 无意义 -> null。
                headingDeg = if (compassInvalid) null else Angles.rawAzimuthToDeg(spokeCompass),
                trueNorth = trueNorth,
                rangeCellSizeMm = rangeCellSizeMm,
                rangeCellsDiv2 = rangeCellsDiv2,
                samples = samples,
                encoding = toEncoding(sampleEncodingRaw),
                sequenceNumber = sequenceNumber,
                bearingZeroError = bearingZeroError,
            )
            off += spokeLength
        }
        return Result(spokes, skipped)
    }

    /**
     * sampleEncoding 位 -> 枚举。SDK eSampleEncoding：0=Amplitude，1=Doppler，2/3=保留。
     * 保留值无渲染语义，按幅度处理（不触发多普勒着色）——防御性默认，非协议字段臆造。
     */
    private fun toEncoding(raw: Int): SampleEncoding =
        if (raw == 1) SampleEncoding.DOPPLER else SampleEncoding.AMPLITUDE

    /**
     * 4-bit 解包。每字节装 2 个采样，低索引=低半字节。
     * 采样数以 nOfSamples 为准，但绝不读越数据区（dataBytes*2 个半字节为上限），
     * 二者不一致时取较小值，保证不越界。
     */
    private fun unpack4bit(buf: ByteArray, dataOff: Int, dataBytes: Int, nOfSamples: Int): ByteArray {
        val count = minOf(nOfSamples, dataBytes * 2)
        val out = ByteArray(count)
        for (i in 0 until count) {
            val b = buf[dataOff + (i ushr 1)].toInt() and 0xFF
            out[i] = (if (i and 1 == 0) b and 0x0F else (b ushr 4) and 0x0F).toByte()
        }
        return out
    }

    /** 读小端 uint32（用 Long 承载以免符号问题）。 */
    private fun u32le(b: ByteArray, off: Int): Long =
        (b[off].toLong() and 0xFF) or
            ((b[off + 1].toLong() and 0xFF) shl 8) or
            ((b[off + 2].toLong() and 0xFF) shl 16) or
            ((b[off + 3].toLong() and 0xFF) shl 24)

    /** 从 32-bit 字中取 [shift, shift+width) 位（width<=31）。 */
    private fun bits(word: Long, shift: Int, width: Int): Int =
        ((word ushr shift) and ((1L shl width) - 1)).toInt()
}
