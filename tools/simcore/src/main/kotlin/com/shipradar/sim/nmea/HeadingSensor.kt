package com.shipradar.sim.nmea

/**
 * 罗经 / 航向传感器。生成 **HDT / THS / HDG / ROT**(IEC 61162-1 ED6 §8.3.52/101/51/83)。
 *
 * THS 在 ED6 取代了已弃用的 HDT;两者都输出,供新旧监听设备兼容。
 *
 * @property trueHeadingDeg 真航向(度)。
 * @property thsMode THS 模式指示(§8.3.101 注1):A 自主 / E 估算 / M 手输 / S 模拟 / V 无效;非空。
 * @property magneticSensorDeg HDG 磁传感器读数(度);null 则不发 HDG。
 * @property deviationDeg 自差,正=东 负=西(HDG;§8.3.51 注1);null=空字段。
 * @property variationDeg 磁差,正=东 负=西(HDG;§8.3.51 注2);null=空字段。
 * @property rateOfTurnDegMin 转向率(度/分),正=船首右转(§8.3.83);null 则不发 ROT。
 * @property rotValid ROT 状态:true→'A'(有效),false→'V'。
 * @property headingTalker HDT/THS talker,真航向陀螺默认 "HE"。
 * @property magTalker HDG talker,磁罗经默认 "HC"。
 * @property rotTalker ROT talker,转向率指示器默认 "TI"。
 */
data class HeadingSensor(
    val trueHeadingDeg: Double,
    val thsMode: Char = 'A',
    val magneticSensorDeg: Double? = null,
    val deviationDeg: Double? = null,
    val variationDeg: Double? = null,
    val rateOfTurnDegMin: Double? = null,
    val rotValid: Boolean = true,
    val headingTalker: String = "HE",
    val magTalker: String = "HC",
    val rotTalker: String = "TI",
) : NmeaSource {

    override fun toSentences(): List<String> = buildList {
        add(hdt())
        add(ths())
        if (magneticSensorDeg != null) add(hdg())
        if (rateOfTurnDegMin != null) add(rot())
    }

    /** HDT — 真航向(§8.3.52)。字段:航向、T。 */
    fun hdt(): String = Nmea.sentence(headingTalker, "HDT,${Nmea.deg360(trueHeadingDeg)},T")

    /** THS — 真航向与状态(§8.3.101)。字段:航向、模式指示。 */
    fun ths(): String = Nmea.sentence(headingTalker, "THS,${Nmea.deg360(trueHeadingDeg)},$thsMode")

    /** HDG — 磁航向、自差、磁差(§8.3.51)。字段:磁传感器读数、自差、E/W、磁差、E/W。 */
    fun hdg(): String {
        val mag = Nmea.deg360(magneticSensorDeg ?: 0.0)
        val (dev, devHemi) = Nmea.signed(deviationDeg, 'E', 'W')
        val (varc, varHemi) = Nmea.signed(variationDeg, 'E', 'W')
        return Nmea.sentence(magTalker, "HDG,$mag,$dev,$devHemi,$varc,$varHemi")
    }

    /** ROT — 转向率(§8.3.83)。字段:转向率(度/分,+=右转)、状态。 */
    fun rot(): String =
        Nmea.sentence(rotTalker, "ROT,${Nmea.fixed(rateOfTurnDegMin ?: 0.0)},${if (rotValid) 'A' else 'V'}")
}
