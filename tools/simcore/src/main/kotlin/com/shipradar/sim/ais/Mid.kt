package com.shipradar.sim.ais

/**
 * W8-B — Maritime Identification Digits (MID) → country (flag state).
 *
 * The first three digits of a ship-station MMSI are the MID, assigned by the ITU and published in the
 * ITU "Table of Maritime Identification Digits" (ITU-R M.585 / the ITU MARS database). This is a
 * **common-use subset** covering the major maritime nations and the principal flags of convenience.
 * Names are in Chinese (project convention).
 *
 * TODO(待标准: 完整 ITU MID 表) — not every MID is listed; unknown MIDs return null.
 */
object Mid {

    /** MID (3-digit) → country name. Several countries hold multiple MIDs. */
    val table: Map<Int, String> = buildMap {
        // 中国 / 港澳台
        for (m in listOf(412, 413, 414)) put(m, "中国")
        put(477, "中国香港"); put(453, "中国澳门"); put(416, "中国台湾")
        // 美国
        for (m in listOf(338, 366, 367, 368, 369, 303)) put(m, "美国")
        // 英国
        for (m in 232..235) put(m, "英国")
        // 日本 / 韩国
        put(431, "日本"); put(432, "日本")
        put(440, "韩国"); put(441, "韩国")
        // 新加坡
        for (m in 563..566) put(m, "新加坡")
        // 巴拿马(方便旗)
        for (m in listOf(351, 352, 353, 354, 355, 356, 357, 370, 371, 372, 373, 374)) put(m, "巴拿马")
        // 利比里亚 / 马绍尔群岛 / 马耳他 / 巴哈马 / 塞浦路斯(方便旗)
        put(636, "利比里亚"); put(637, "利比里亚")
        put(538, "马绍尔群岛")
        for (m in listOf(215, 229, 248, 249, 256)) put(m, "马耳他")
        for (m in listOf(308, 309, 311)) put(m, "巴哈马")
        for (m in listOf(209, 210, 212)) put(m, "塞浦路斯")
        // 欧洲主要海运国
        for (m in 244..246) put(m, "荷兰")
        put(211, "德国"); put(218, "德国")
        for (m in 226..228) put(m, "法国")
        put(247, "意大利")
        put(224, "西班牙"); put(225, "西班牙")
        for (m in 257..259) put(m, "挪威"); put(331, "挪威")
        put(265, "瑞典"); put(266, "瑞典")
        put(219, "丹麦"); put(220, "丹麦")
        put(230, "芬兰")
        for (m in 236..238) put(m, "希腊") // 237/239/240/241 亦希腊
        for (m in 239..241) put(m, "希腊")
        for (m in 273..274) put(m, "俄罗斯")
        // 亚太其它
        put(525, "印度尼西亚")
        put(533, "马来西亚")
        put(567, "泰国")
        for (m in 574..574) put(m, "越南")
        put(419, "印度")
        for (m in 503..503) put(m, "澳大利亚")
        put(512, "新西兰")
        put(548, "菲律宾")
        // 美洲其它
        for (m in 316..316) put(m, "加拿大")
        put(710, "巴西")
        put(345, "墨西哥")
        // 中东
        put(470, "阿联酋")
        put(403, "沙特阿拉伯")
    }

    /**
     * Country (flag state) for a 9-digit ship-station [mmsi], from its leading MID, or null if unknown.
     * Non-ship MMSI formats (e.g. coast stations `00MID…`, group `0MID…`) are not resolved here.
     */
    fun countryOf(mmsi: Int): String? {
        if (mmsi < 100_000_000 || mmsi > 999_999_999) return null // not a 9-digit ship station MMSI
        return table[mmsi / 1_000_000]
    }
}

/** Top-level convenience: [Mid.countryOf]. */
fun countryOf(mmsi: Int): String? = Mid.countryOf(mmsi)
