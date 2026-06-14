package com.shipradar.sim.nmea

/**
 * 水速 / 对地速度 / 流。生成 **VHW / VBW / VDR**(IEC 61162-1 ED6 §8.3.118/113/116)。
 *
 * @property waterSpeedKn 对水航速(VHW,节)。
 * @property headingTrueDeg VHW 真航向(度);null=空字段。
 * @property headingMagDeg VHW 磁航向(度);null=空字段。
 * @property longWaterKn VBW 纵向对水速度(节,−=后退);null 则不发 VBW。
 * @property transWaterKn VBW 横向对水速度(节,−=左舷;§8.3.113 注1)。
 * @property longGroundKn VBW 纵向对地速度(节)。
 * @property transGroundKn VBW 横向对地速度(节,−=左舷)。
 * @property currentSetDeg VDR 流向(流所朝向的方向,真,度);null 则不发 VDR。
 * @property currentDriftKn VDR 流速(节)。
 * @property talker talker 标识,计程仪默认 "VW"(对水)。
 *
 * TODO(待核 ED6 §8.3.113):VBW 尾部"船尾横向对水/对地速度"两组字段(字段7–10)未建模;
 * 本生成器只发前 6 个字段(纵/横对水+状态、纵/横对地+状态)。如认证需要船尾速度,补字段并复核状态位约定。
 */
data class WaterSpeedSensor(
    val waterSpeedKn: Double,
    val headingTrueDeg: Double? = null,
    val headingMagDeg: Double? = null,
    val longWaterKn: Double? = null,
    val transWaterKn: Double? = null,
    val longGroundKn: Double? = null,
    val transGroundKn: Double? = null,
    val currentSetDeg: Double? = null,
    val currentDriftKn: Double? = null,
    val talker: String = "VW",
) : NmeaSource {

    override fun toSentences(): List<String> = buildList {
        add(vhw())
        if (longWaterKn != null || longGroundKn != null) add(vbw())
        if (currentSetDeg != null && currentDriftKn != null) add(vdr())
    }

    /** VHW — 对水航速与航向(§8.3.118)。字段:真航向 T、磁航向 M、对水航速节 N、对水航速 km/h K。 */
    fun vhw(): String {
        val ht = if (headingTrueDeg == null) "" else Nmea.deg360(headingTrueDeg)
        val hm = if (headingMagDeg == null) "" else Nmea.deg360(headingMagDeg)
        val body = "VHW,$ht,T,$hm,M,${Nmea.fixed(waterSpeedKn)},N,${Nmea.fixed(Nmea.knToKmh(waterSpeedKn))},K"
        return Nmea.sentence(talker, body)
    }

    /**
     * VBW — 对地/对水双速(§8.3.113,前 6 字段)。
     * 字段:纵向对水、横向对水、对水状态 A、纵向对地、横向对地、对地状态 A。状态字段非空(注2)。
     */
    fun vbw(): String {
        val lw = Nmea.opt(longWaterKn); val tw = Nmea.opt(transWaterKn)
        val waterStatus = if (longWaterKn != null) 'A' else 'V'
        val lg = Nmea.opt(longGroundKn); val tg = Nmea.opt(transGroundKn)
        val groundStatus = if (longGroundKn != null) 'A' else 'V'
        return Nmea.sentence(talker, "VBW,$lw,$tw,$waterStatus,$lg,$tg,$groundStatus")
    }

    /** VDR — 流向流速 set & drift(§8.3.116)。字段:流向真 T、流向磁 M(空)、流速节 N。 */
    fun vdr(): String =
        Nmea.sentence(talker, "VDR,${Nmea.deg360(currentSetDeg ?: 0.0)},T,,M,${Nmea.fixed(currentDriftKn ?: 0.0)},N")
}
