package com.shipradar.sim

import com.shipradar.constants.Endpoint
import com.shipradar.constants.Iec450Groups

/**
 * 把一条 NMEA-0183 / !AIVDM 语句封装成 **IEC 61162-450 UdPbC** 数据报,并按内容选传输组(§6.2.2 Table 4):
 *  - AIS(!AI…)→ TGTD(目标数据)
 *  - 航向/姿态(HDT/HDG/THS/ROT)→ SATD(高更新率)
 *  - 其它导航 → NAVD
 *
 * 帧格式:`"UdPbC" + 0x00 + "\s:<src>*hh\" + <原始语句> + CRLF`。`src` = 发送方 ID(talker+4 位数,如 SI0001)。
 * 纯函数,供模拟器组播输出 + 单测;与雷达侧 Iec450FrameParser/DemoFeed.frame450 同格式。
 */
object Iec450Frame {

    private fun xor(t: String): Int = t.fold(0) { a, c -> a xor c.code } and 0xFF

    /** 封一条语句(可带或不带尾部 CRLF)成 450 UdPbC 报文字节。 */
    fun wrap(sentence: String, src: String = "s:SI0001"): ByteArray {
        val body = sentence.trimEnd('\r', '\n')
        val tag = "\\$src*${"%02X".format(xor(src))}\\"
        val line = tag + body + "\r\n"
        return "UdPbC".toByteArray(Charsets.ISO_8859_1) + byteArrayOf(0) + line.toByteArray(Charsets.ISO_8859_1)
    }

    /** 按语句类型选 61162-450 传输组端点。 */
    fun endpointFor(sentence: String): Endpoint {
        val s = sentence.trimStart()
        val type = if (s.length >= 6 && (s[0] == '$' || s[0] == '!')) s.substring(3, 6) else ""
        return when {
            s.startsWith("!AI") -> Iec450Groups.TGTD
            type in HEADING_TYPES -> Iec450Groups.SATD
            else -> Iec450Groups.NAVD
        }
    }

    private val HEADING_TYPES = setOf("HDT", "HDG", "THS", "ROT")
}
