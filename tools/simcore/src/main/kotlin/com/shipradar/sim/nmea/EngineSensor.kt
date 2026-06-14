package com.shipradar.sim.nmea

/**
 * 一个 XDR 换能器测量量(IEC 61162-1 ED6 §8.3.127 的 "type-data-units-ID" 四元组)。
 *
 * 常用类型/单位(§8.3.127 表):
 *  - 'C' 温度,单位 'C'(摄氏)/ 'K'(开尔文),ID 如 "Engine#0"/"Air#0";
 *  - 'P' 压力,单位 'B'(巴)/ 'P'(帕),ID 如 "Baro#0";"-"=真空;
 *  - 'A' 角位移,单位 'D'(度);
 *  - 'T' 转速计,单位 'R'(RPM);'U' 电压 'V';'N' 力 'N';'F' 频率 'H' 等。
 *
 * @property type 换能器类型字母。
 * @property value 测量值。
 * @property unit 单位字母。
 * @property id 实例/上下文标识(自由文本,如 "Engine#0")。
 * @property decimals 数值小数位。
 */
data class Transducer(
    val type: Char,
    val value: Double,
    val unit: Char,
    val id: String,
    val decimals: Int = 1,
)

/**
 * 机舱传感器。生成 **RPM**(主机/轴转速,§8.3.84)与 **XDR**(通用换能器测量,§8.3.127)。
 *
 * @property source RPM 源:'S' 轴(shaft) / 'E' 主机(engine)(§8.3.84)。
 * @property number 轴/机编号(奇=右舷,偶=左舷,自中心编号;单机/中心可用 0)。
 * @property rpm 转速(转/分)。
 * @property propellerPitchPct 螺距,占满量程百分比 −100..100;null=空字段。
 * @property rpmValid RPM 状态:true→'A',false→'V'。
 * @property rpmTalker RPM talker,机舱默认 "ER"(engine room)。
 * @property transducers 该传感器要发的 XDR 测量量集合;空则不发 XDR。
 * @property xdrTalker XDR talker,默认同 [rpmTalker]。
 */
data class EngineSensor(
    val rpm: Double,
    val source: Char = 'S',
    val number: Int = 1,
    val propellerPitchPct: Double? = null,
    val rpmValid: Boolean = true,
    val rpmTalker: String = "ER",
    val transducers: List<Transducer> = emptyList(),
    val xdrTalker: String = "ER",
) : NmeaSource {

    override fun toSentences(): List<String> = buildList {
        add(rpm())
        if (transducers.isNotEmpty()) add(xdr())
    }

    /** RPM — 转速(§8.3.84)。字段:源 S/E、编号、转速、螺距%、状态。 */
    fun rpm(): String =
        Nmea.sentence(rpmTalker, "RPM,$source,$number,${Nmea.fixed(rpm)},${Nmea.opt(propellerPitchPct)},${if (rpmValid) 'A' else 'V'}")

    /** XDR — 换能器测量(§8.3.127)。每个换能器一组 "类型,数值,单位,ID"。 */
    fun xdr(): String {
        val body = buildString {
            append("XDR")
            for (t in transducers) {
                append(',').append(t.type).append(',').append(Nmea.fixed(t.value, t.decimals))
                    .append(',').append(t.unit).append(',').append(t.id)
            }
        }
        return Nmea.sentence(xdrTalker, body)
    }
}
