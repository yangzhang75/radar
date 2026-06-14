package com.shipradar.sim.nmea

/**
 * 测深仪。生成 **DPT / DBT**(IEC 61162-1 ED6 §8.3.28/25)。
 *
 * @property depthBelowTransducerM 换能器下方水深(米)。
 * @property transducerOffsetM DPT 换能器偏移(米):正=换能器到水线的距离;负=换能器到龙骨的距离(§8.3.28)。
 * @property maxRangeScaleM DPT 当前量程档(米,ED6 字段3);null=空字段。
 * @property talker talker 标识,测深仪默认 "SD"。
 */
data class DepthSensor(
    val depthBelowTransducerM: Double,
    val transducerOffsetM: Double = 0.0,
    val maxRangeScaleM: Double? = null,
    val talker: String = "SD",
) : NmeaSource {

    override fun toSentences(): List<String> = listOf(dpt(), dbt())

    /** DPT — 水深(§8.3.28)。字段:换能器下方水深 m、换能器偏移 m、量程档 m。 */
    fun dpt(): String =
        Nmea.sentence(talker, "DPT,${Nmea.fixed(depthBelowTransducerM)},${Nmea.fixed(transducerOffsetM)},${Nmea.opt(maxRangeScaleM)}")

    /** DBT — 换能器下方水深(§8.3.25)。字段:英尺 f、米 M、英寻 F(同一深度三单位)。 */
    fun dbt(): String {
        val m = depthBelowTransducerM
        val body = "DBT,${Nmea.fixed(Nmea.mToFeet(m))},f,${Nmea.fixed(m)},M,${Nmea.fixed(Nmea.mToFathom(m))},F"
        return Nmea.sentence(talker, body)
    }
}
