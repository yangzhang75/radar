package com.shipradar.sim.nmea

import java.util.Locale
import kotlin.math.abs
import kotlin.math.floor

/**
 * 传感器 NMEA-0183 / **IEC 61162-1 ED6 (2023 FDIS)** 语句生成核心(纯 JVM,无 Android)。
 *
 * 供模拟器 GUI(`tools:simgui`)与 app 离线回放共用。各传感器是一个可设字段的数据类,实现
 * [NmeaSource.toSentences],输出合法 NMEA 句(`$` 起始、talker+formatter、逗号字段、`*HH` 校验和、CRLF)。
 *
 * 设计约束:
 *  - **纯函数**,无副作用、无 I/O,完全可单测。
 *  - 所有数字格式化一律用 [Locale.ROOT],避免欧洲区域把小数点输出成逗号而破坏 NMEA 字段分隔。
 *  - 字段格式按 ED6 §8.3.x;不确定处在各传感器 KDoc 标 `TODO(待核 ED6 §x)`,不臆造。
 *
 * 注:`!` 起始的封装句(AIS VDM/VDO)由 W8-B(`sim/ais`)负责,本核心只产出 `$` 起始的常规句。
 */
object Nmea {

    /**
     * NMEA-0183 校验和:对 [content](即 `$` 与 `*` 之间的所有字符)逐字节 XOR,返回两位大写十六进制。
     * 依据 IEC 61162-1 ED6 §7.1.5(校验和字段)。
     */
    fun checksum(content: String): String {
        var x = 0
        for (c in content) x = x xor c.code
        return "%02X".format(Locale.ROOT, x)
    }

    /**
     * 组装一条完整语句:`$` + [talker] + [body] + `*` + 校验和 + `\r\n`。
     * [talker] 为两字符 talker 标识(如 GP/HE/WI),[body] 为 `FORMATTER,field,field...`(不含 `$`/talker/`*`)。
     * 校验和覆盖 `talker+body`(即 `$` 与 `*` 之间的全部字符),依据 §7.1。
     */
    fun sentence(talker: String, body: String): String {
        val content = talker + body
        return "\$$content*${checksum(content)}\r\n"
    }

    // --- 字段格式化辅助(ED6 §7.1.3 数值字段 / §8 各句字段) ----------------------------------

    /** 纬度字段 `llll.ll,a`:度分 ddmm.mmmm + 半球 N/S。正值=北(§8.3.42/43)。 */
    fun lat(latitude: Double): String {
        val deg = floor(abs(latitude)).toInt()
        val min = (abs(latitude) - deg) * 60.0
        return "%02d%07.4f,%s".format(Locale.ROOT, deg, min, if (latitude >= 0) "N" else "S")
    }

    /** 经度字段 `yyyyy.yy,a`:度分 dddmm.mmmm + 半球 E/W。正值=东(§8.3.42/43)。 */
    fun lon(longitude: Double): String {
        val deg = floor(abs(longitude)).toInt()
        val min = (abs(longitude) - deg) * 60.0
        return "%03d%07.4f,%s".format(Locale.ROOT, deg, min, if (longitude >= 0) "E" else "W")
    }

    /** UTC 时间字段 `hhmmss.ss`(§8.3.130 ZDA 示例)。 */
    fun hms(t: UtcTime): String =
        "%02d%02d%05.2f".format(Locale.ROOT, t.hour, t.minute, t.second)

    /** 日期字段 `ddmmyy`(RMC 用,§8.3.81)。 */
    fun ddmmyy(t: UtcTime): String =
        "%02d%02d%02d".format(Locale.ROOT, t.day, t.month, t.year % 100)

    /** 把角度规整到 [0,360),保留一位小数(航向/航迹/风向 0–359.9°)。 */
    fun deg360(angle: Double): String =
        "%.1f".format(Locale.ROOT, ((angle % 360.0) + 360.0) % 360.0)

    /** 固定小数位格式化(Locale.ROOT)。 */
    fun fixed(value: Double, decimals: Int = 1): String =
        "%.${decimals}f".format(Locale.ROOT, value)

    /** 可空数值字段:null → 空字段;否则固定小数位。 */
    fun opt(value: Double?, decimals: Int = 1): String =
        if (value == null) "" else fixed(value, decimals)

    /**
     * 带符号量 → `(绝对值, 半球字母)`。例如磁差 +东/−西、偏差 +东/−西。
     * null → 一对空串(两个字段都为空)。
     */
    fun signed(value: Double?, positive: Char, negative: Char, decimals: Int = 1): Pair<String, String> =
        if (value == null) "" to ""
        else fixed(abs(value), decimals) to (if (value >= 0) positive else negative).toString()

    /** 节 → 千米/时。 */
    fun knToKmh(kn: Double): Double = kn * 1.852

    /** 节 → 米/秒。 */
    fun knToMs(kn: Double): Double = kn * 0.514444

    /** 米 → 英尺。 */
    fun mToFeet(m: Double): Double = m / 0.3048

    /** 米 → 英寻(fathom)。 */
    fun mToFathom(m: Double): Double = m / 1.8288
}

/** 一个可生成 NMEA 语句的传感器。GUI 设好字段后调用 [toSentences] 取该传感器本周期应发的所有句。 */
interface NmeaSource {
    fun toSentences(): List<String>
}

/**
 * UTC 时刻(供需要时间/日期的句使用:GGA/RMC/GLL/ZDA)。
 * @property second 含小数秒(hhmmss.ss)。
 */
data class UtcTime(
    val hour: Int,
    val minute: Int,
    val second: Double,
    val day: Int = 1,
    val month: Int = 1,
    val year: Int = 2024,
)
